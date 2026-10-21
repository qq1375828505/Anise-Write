package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository
import com.ai.assistance.quro.core.novel.graph.ChapterRef
import com.ai.assistance.quro.core.novel.graph.GraphStore
import com.ai.assistance.quro.core.novel.graph.NovelMemory
import com.ai.assistance.quro.core.novel.graph.NovelRelationship
import com.ai.assistance.quro.core.novel.graph.analysis.NovelAnalyzer
import com.ai.assistance.quro.core.novel.graph.hook.NovelHook
import com.ai.assistance.quro.core.novel.graph.hook.NovelHookPriority
import com.ai.assistance.quro.core.novel.graph.hook.NovelHookStatus
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * PenAi 网文写作域 · 图谱域工具族（Phase 3c）。
 *
 * 覆盖规划书 §6.1 图谱/分析/记忆/检索四行：伏笔台账（含生命周期）、人物关系 CRUD
 * 与关系图渲染数据、写作记忆双件套（save/query/总览）、前文检索、一致性检查。
 * 全部数据操作经由 [GraphStore]（`filesDir/novel/graph/`）与 [NovelRepository]，
 * 本文件不直接碰任何文件与索引格式；一致性规则引擎在 [NovelAnalyzer]（纯 JVM 可测）。
 *
 * ## 错误码（与结构域/资料域同口径）
 * | code | 含义 |
 * |---|---|
 * | `invalid_arguments` | 参数缺失/空白/不是合法 JSON/枚举值不合法 |
 * | `work_not_found` | 作品不存在（hint 列出现有作品） |
 * | `chapter_not_found` | 章节不存在或不属于该作品 |
 * | `hook_not_found` / `relationship_not_found` / `memory_not_found` | 图谱条目不存在 |
 *
 * ## 数据与视图分离
 * `novel_relationship_graph` 只产出**渲染数据**（nodes/edges，强度→边宽、颜色→边色），
 * Compose Canvas 关系图（Phase 6）消费同一份数据结构，不在此处做任何绘制。
 */

/** 图谱域工具错误码（对外稳定契约，模型可据此前瞻分流）。 */
internal object GErr {
    const val INVALID_ARGS = "invalid_arguments"
    const val WORK_NOT_FOUND = "work_not_found"
    const val CHAPTER_NOT_FOUND = "chapter_not_found"
    const val HOOK_NOT_FOUND = "hook_not_found"
    const val REL_NOT_FOUND = "relationship_not_found"
    const val MEMORY_NOT_FOUND = "memory_not_found"
}

internal fun gError(code: String, message: String, hint: String? = null): String = JSONObject().apply {
    put("ok", false)
    put("code", code)
    put("error", message)
    if (hint != null) put("hint", hint)
}.toString()

private fun gArgs(arguments: String): JSONObject? = runCatching { JSONObject(arguments) }.getOrNull()

/** 作品不存在错误（hint 附现有作品，一次重发即可修正）。 */
internal fun gWorkNotFound(repo: NovelRepository, workId: String): String {
    val works = repo.loadWorks()
    val avail = if (works.isEmpty()) "当前没有任何作品，可先用 novel_create_work 创建。"
    else "现有作品：" + works.joinToString("；") { "《${it.title}》(${it.id})" }
    return gError(GErr.WORK_NOT_FOUND, "作品不存在：$workId", "先用 novel_list_works 确认 work_id。$avail")
}

/** 章节不存在错误。 */
internal fun gChapterNotFound(workTitle: String, chapterId: String, hint: String): String =
    gError(GErr.CHAPTER_NOT_FOUND, "章节不存在：$chapterId（作品《$workTitle》）", hint)

internal fun gOutlineHint(workId: String): String = "用 novel_get_outline(work_id=$workId) 查看章节 id。"

/** 章节快照：按 id 找章节 → ChapterRef（找不到返回 null，由调用方报错）。 */
private fun gChapterRefOf(chapters: List<com.ai.assistance.quro.core.novel.NovelChapter>, chapterId: String): ChapterRef? =
    chapters.firstOrNull { it.id == chapterId }?.let { ChapterRef(it.id, it.title, it.order) }

private fun hookJson(h: NovelHook, currentMaxOrder: Int): JSONObject = JSONObject().apply {
    put("id", h.id)
    put("title", h.title)
    put("description", h.description)
    put("status", h.status.key)
    put("statusLabel", h.status.label)
    put("priority", h.priority.key)
    put("priorityLabel", h.priority.label)
    h.plantedAt?.let { put("plantedAt", it.toJson()) }
    h.resolvedAt?.let { put("resolvedAt", it.toJson()) }
    put("characterNames", JSONArray().also { a -> h.characterNames.forEach { n -> a.put(n) } })
    val idle = h.idleChapters(currentMaxOrder)
    if (idle != null) put("idleChapters", idle)
    put("createdAt", h.createdAt)
    put("updatedAt", h.updatedAt)
}

/**
 * novel_hooks_status：伏笔台账（状态报表 + 生命周期操作）。
 *
 * 规划书 §6.1 伏笔行只有这一个工具，故它身兼两职：
 * - **默认（action=status）**：状态报表——已埋/已回收/已废弃三态计数 + 闲置章数 + 逐条台账；
 * - **action=create/close/abandon/reopen/delete**：生命周期操作
 *   （状态机见 [NovelHook]：CLOSED 是终态，reopen 需先 abandon；
 *   close 必须带 resolved_chapter_id 记录回收位置）。
 */
class NovelHooksStatusTool : QuroTool {
    override val name = "novel_hooks_status"
    override val description =
        "伏笔台账：查看已埋/已回收/已废弃三态 + 闲置章数统计（默认）；或管理伏笔生命周期" +
            "（action=create 埋伏笔 / close 回收（须带 resolved_chapter_id）/ abandon 废弃 / reopen 重启 / delete 删除）。" +
            "何时用：写章前看还有哪些伏笔没收；埋新伏笔；读者问「那个坑填了没」。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "action": {
          "type": "string",
          "description": "操作：status（默认，只读报表）/ create / close / abandon / reopen / delete",
          "enum": ["status", "create", "close", "abandon", "reopen", "delete"]
        },
        "hook_id": { "type": "string", "description": "伏笔 id（close/abandon/reopen/delete 必填）" },
        "title": { "type": "string", "description": "伏笔名（create 必填），如「老者留下的玉佩」" },
        "description": { "type": "string", "description": "伏笔内容/埋伏意图（create 可选）" },
        "priority": { "type": "string", "description": "优先级（create 可选，默认 normal）", "enum": ["low", "normal", "high"] },
        "planted_chapter_id": { "type": "string", "description": "埋设章节 id（create 可选，缺省不锚定章节）" },
        "resolved_chapter_id": { "type": "string", "description": "回收章节 id（close 必填）" },
        "character_names": { "type": "array", "items": { "type": "string" }, "description": "关联人物名（create 可选）" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"action\":\"status\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val store = GraphStore.fromContext(context)
        val action = args.optString("action", "status").trim().lowercase()
        val maxOrder = work.chapters.maxOfOrNull { it.order } ?: 0

        return when (action) {
            "status" -> {
                val hooks = store.listHooks(workId)
                val counts = JSONObject().apply {
                    NovelHookStatus.entries.forEach { s -> put(s.key, hooks.count { it.status == s }) }
                }
                val shown = hooks.take(30)
                val arr = JSONArray().also { a -> shown.forEach { a.put(hookJson(it, maxOrder)) } }
                val idleOpen = hooks.filter { it.status == NovelHookStatus.OPEN && (it.idleChapters(maxOrder) ?: 0) >= 3 }
                JSONObject().apply {
                    put("ok", true)
                    put("total", hooks.size)
                    put("counts", counts)
                    put("hooks", arr)
                    if (hooks.size > shown.size) put("truncated", true)
                    put(
                        "message",
                        buildString {
                            append("伏笔台账：已埋 ${counts.optInt("open")}、已回收 ${counts.optInt("closed")}、已废弃 ${counts.optInt("abandoned")}。")
                            if (idleOpen.isNotEmpty()) {
                                append("⏰ ${idleOpen.size} 条伏笔闲置 ≥3 章（按优先级）：")
                                append(idleOpen.sortedByDescending { it.priority.level }.joinToString("；") { "「${it.title}」" })
                            }
                            if (hooks.isEmpty()) append("（本作还没有伏笔，action=create 埋第一条）")
                        },
                    )
                }.toString()
            }
            "create" -> {
                val title = args.optString("title", "").trim()
                if (title.isBlank()) return gError(GErr.INVALID_ARGS, "create 需要 title（伏笔名）", "形如 {\"work_id\":\"…\",\"action\":\"create\",\"title\":\"老者留下的玉佩\"}")
                val priority = NovelHookPriority.fromKey(args.optString("priority", "normal"))
                val planted = args.optString("planted_chapter_id", "").trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { cid -> gChapterRefOf(work.chapters, cid) ?: return gChapterNotFound(work.title, cid, gOutlineHint(workId)) }
                val names = args.optJSONArray("character_names")?.let { a ->
                    (0 until a.length()).map { a.optString(it, "").trim() }.filter { it.isNotBlank() }
                } ?: emptyList()
                val hook = store.addHook(
                    NovelHook(
                        id = "", workId = workId, title = title,
                        description = args.optString("description", "").trim(),
                        status = NovelHookStatus.OPEN, priority = priority,
                        plantedAt = planted, characterNames = names,
                    ),
                )
                JSONObject().apply {
                    put("ok", true)
                    put("hook", hookJson(hook, maxOrder))
                    put("message", "已埋伏笔「${hook.title}」（${hook.priority.label}优先级）${if (planted != null) "，锚定「${planted.title}」" else ""}。")
                }.toString()
            }
            "close" -> {
                val hookId = args.optString("hook_id", "").trim()
                if (hookId.isBlank()) return gError(GErr.INVALID_ARGS, "close 需要 hook_id", "先用 action=status 查伏笔 id")
                val resolved = args.optString("resolved_chapter_id", "").trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { cid -> gChapterRefOf(work.chapters, cid) ?: return gChapterNotFound(work.title, cid, gOutlineHint(workId)) }
                    ?: return gError(GErr.INVALID_ARGS, "close 必须带 resolved_chapter_id（回收章节）", "回收位置是后续「伏笔回收查证」的锚点，不能省略")
                val updated = store.updateHook(workId, hookId) { it.copy(status = NovelHookStatus.CLOSED, resolvedAt = resolved) }
                    ?: return gError(GErr.HOOK_NOT_FOUND, "伏笔不存在：$hookId", "用 action=status 查现有伏笔 id")
                JSONObject().apply {
                    put("ok", true)
                    put("hook", hookJson(updated, maxOrder))
                    put("message", "伏笔「${updated.title}」已在「${resolved.title}」回收 ✔")
                }.toString()
            }
            "abandon" -> hookTransition(store, work, args, NovelHookStatus.ABANDONED, maxOrder, "已废弃")
            "reopen" -> hookTransition(store, work, args, NovelHookStatus.OPEN, maxOrder, "已重新埋回 OPEN")
            "delete" -> {
                val hookId = args.optString("hook_id", "").trim()
                if (hookId.isBlank()) return gError(GErr.INVALID_ARGS, "delete 需要 hook_id", "先用 action=status 查伏笔 id")
                val removed = store.deleteHook(workId, hookId)
                if (!removed) return gError(GErr.HOOK_NOT_FOUND, "伏笔不存在：$hookId", "用 action=status 查现有伏笔 id")
                JSONObject().apply { put("ok", true); put("message", "伏笔 $hookId 已删除。") }.toString()
            }
            else -> gError(GErr.INVALID_ARGS, "未知 action：$action", "合法值：status/create/close/abandon/reopen/delete")
        }
    }

    private fun hookTransition(
        store: GraphStore,
        work: com.ai.assistance.quro.core.novel.NovelWork,
        args: JSONObject,
        target: NovelHookStatus,
        maxOrder: Int,
        label: String,
    ): String {
        val hookId = args.optString("hook_id", "").trim()
        if (hookId.isBlank()) return gError(GErr.INVALID_ARGS, "需要 hook_id", "先用 action=status 查伏笔 id")
        val updated = store.updateHook(work.id, hookId) { it.copy(status = target) }
            ?: return gError(GErr.HOOK_NOT_FOUND, "伏笔不存在：$hookId", "用 action=status 查现有伏笔 id")
        return JSONObject().apply {
            put("ok", true)
            put("hook", hookJson(updated, maxOrder))
            put("message", "伏笔「${updated.title}」$label。")
        }.toString()
    }
}

/**
 * novel_relationships：人物关系 CRUD（方向 from→to，人物名为键，强度 1-5）。
 *
 * action=list（默认）/ get / add / update / delete。类型预设：亲人/恋人/师徒/盟友/
 * 竞争/仇敌/上下级/其他（自由文本）。允许引用未建档人物（一致性检查会给 info 提示）。
 */
class NovelRelationshipsTool : QuroTool {
    override val name = "novel_relationships"
    override val description =
        "人物关系增删改查：from→to 方向关系（类型：亲人/恋人/师徒/盟友/竞争/仇敌/上下级/其他；强度 1-5；可选自定义颜色）。" +
            "action=list（默认）/ get / add / update / delete。" +
            "何时用：读者问「A 和 B 什么关系」；写作中人物关系发生变化（结盟/反目）时更新。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "action": {
          "type": "string",
          "description": "操作：list（默认）/ get / add / update / delete",
          "enum": ["list", "get", "add", "update", "delete"]
        },
        "rel_id": { "type": "string", "description": "关系 id（get/update/delete 必填；list 可按人名过滤后获取）" },
        "from_name": { "type": "string", "description": "人物 A 名（add 必填；update 可改）", "example": "林晚" },
        "to_name": { "type": "string", "description": "人物 B 名（add 必填；update 可改）", "example": "沈砚" },
        "type": { "type": "string", "description": "关系类型（add 可选默认「其他」；update 可改）", "example": "恋人" },
        "strength": { "type": "integer", "description": "强度 1-5（add 可选默认 3；update 可改）：1 点头之交 … 5 生死羁绊" },
        "color": { "type": "string", "description": "关系图边色 #RRGGBB（可选，空=主题默认色）", "example": "#E06C75" },
        "description": { "type": "string", "description": "关系说明（可选）" },
        "name": { "type": "string", "description": "list 时按人名过滤（匹配 from 或 to，可选）" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"action\":\"add\",\"from_name\":\"林晚\",\"to_name\":\"沈砚\",\"type\":\"恋人\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val store = GraphStore.fromContext(context)
        val action = args.optString("action", "list").trim().lowercase()

        fun relJson(r: NovelRelationship): JSONObject = JSONObject().apply {
            put("id", r.id); put("fromName", r.fromName); put("toName", r.toName)
            put("type", r.type); put("strength", r.strength); put("color", r.color)
            put("description", r.description); put("updatedAt", r.updatedAt)
        }

        return when (action) {
            "list" -> {
                val nameFilter = args.optString("name", "").trim()
                val all = store.listRelationships(workId)
                val filtered = if (nameFilter.isBlank()) all else all.filter { it.fromName == nameFilter || it.toName == nameFilter }
                val arr = JSONArray().also { a -> filtered.forEach { a.put(relJson(it)) } }
                JSONObject().apply {
                    put("ok", true)
                    put("total", filtered.size)
                    put("relationships", arr)
                    put("message", if (filtered.isEmpty()) "还没有关系记录（action=add 建立第一条）。" else "共 ${filtered.size} 条关系。")
                }.toString()
            }
            "get" -> {
                val relId = args.optString("rel_id", "").trim()
                if (relId.isBlank()) return gError(GErr.INVALID_ARGS, "get 需要 rel_id", "先用 action=list 查关系 id")
                val rel = store.listRelationships(workId).firstOrNull { it.id == relId }
                    ?: return gError(GErr.REL_NOT_FOUND, "关系不存在：$relId", "用 action=list 查现有关系 id")
                JSONObject().apply { put("ok", true); put("relationship", relJson(rel)) }.toString()
            }
            "add" -> {
                val from = args.optString("from_name", "").trim()
                val to = args.optString("to_name", "").trim()
                if (from.isBlank() || to.isBlank()) return gError(GErr.INVALID_ARGS, "add 需要 from_name 与 to_name", "形如 {\"work_id\":\"…\",\"action\":\"add\",\"from_name\":\"林晚\",\"to_name\":\"沈砚\",\"type\":\"恋人\"}")
                if (from == to) return gError(GErr.INVALID_ARGS, "from_name 与 to_name 不能相同", "自环关系请直接写进人物卡描述")
                val strength = args.optInt("strength", 3).coerceIn(1, 5)
                val rel = store.addRelationship(
                    NovelRelationship(
                        id = "", workId = workId, fromName = from, toName = to,
                        type = args.optString("type", "其他").trim().ifBlank { "其他" },
                        strength = strength,
                        color = args.optString("color", "").trim(),
                        description = args.optString("description", "").trim(),
                    ),
                )
                JSONObject().apply {
                    put("ok", true)
                    put("relationship", relJson(rel))
                    put("message", "已建立关系：${rel.fromName} —${rel.type}(强度${rel.strength})— ${rel.toName}。")
                }.toString()
            }
            "update" -> {
                val relId = args.optString("rel_id", "").trim()
                if (relId.isBlank()) return gError(GErr.INVALID_ARGS, "update 需要 rel_id", "先用 action=list 查关系 id")
                if (!args.has("type") && !args.has("strength") && !args.has("color") && !args.has("description") && !args.has("from_name") && !args.has("to_name")) {
                    return gError(GErr.INVALID_ARGS, "update 至少要改一个字段（type/strength/color/description/from_name/to_name）", "例：{\"work_id\":\"…\",\"action\":\"update\",\"rel_id\":\"…\",\"type\":\"仇敌\",\"strength\":5}")
                }
                val strengthArg = if (args.has("strength")) args.optInt("strength", 3).coerceIn(1, 5) else null
                val updated = store.updateRelationship(workId, relId) { old ->
                    old.copy(
                        fromName = args.optString("from_name", old.fromName).trim().ifBlank { old.fromName },
                        toName = args.optString("to_name", old.toName).trim().ifBlank { old.toName },
                        type = if (args.has("type")) args.optString("type", old.type).trim().ifBlank { old.type } else old.type,
                        strength = strengthArg ?: old.strength,
                        color = if (args.has("color")) args.optString("color", old.color).trim() else old.color,
                        description = if (args.has("description")) args.optString("description", old.description) else old.description,
                    )
                } ?: return gError(GErr.REL_NOT_FOUND, "关系不存在：$relId", "用 action=list 查现有关系 id")
                JSONObject().apply {
                    put("ok", true)
                    put("relationship", relJson(updated))
                    put("message", "已更新关系：${updated.fromName} —${updated.type}(强度${updated.strength})— ${updated.toName}。")
                }.toString()
            }
            "delete" -> {
                val relId = args.optString("rel_id", "").trim()
                if (relId.isBlank()) return gError(GErr.INVALID_ARGS, "delete 需要 rel_id", "先用 action=list 查关系 id")
                val removed = store.deleteRelationship(workId, relId)
                if (!removed) return gError(GErr.REL_NOT_FOUND, "关系不存在：$relId", "用 action=list 查现有关系 id")
                JSONObject().apply { put("ok", true); put("message", "关系 $relId 已删除。") }.toString()
            }
            else -> gError(GErr.INVALID_ARGS, "未知 action：$action", "合法值：list/get/add/update/delete")
        }
    }
}

/**
 * novel_relationship_graph：关系图渲染数据（数据与视图分离，Phase 6 关系图 UI 消费）。
 *
 * 节点 = 资料库已建档人物 ∪ 关系端点（去重），带度数；边 = 关系列表（强度→边宽、颜色→边色）。
 */
class NovelRelationshipGraphTool : QuroTool {
    override val name = "novel_relationship_graph"
    override val readOnly = true
    override val description =
        "生成人物关系图渲染数据：nodes（人物名+度数+是否已建档）+ edges（关系/类型/强度/颜色）。" +
            "强度 1-5 映射边宽，color 映射边色（空=主题默认）。何时用：AI 需要通览全作人物网；UI 关系图页取数。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val store = GraphStore.fromContext(context)
        val rels = store.listRelationships(workId)
        // 节点：已建档人物 ∪ 关系端点（去重，保序：建档在前）
        val characterNames = runCatching {
            NovelAssetRepository.fromContext(context).list(workId, AssetType.CHARACTER).map { it.name }
        }.getOrDefault(emptyList())
        val nodeNames = LinkedHashSet<String>()
        characterNames.forEach { nodeNames.add(it) }
        rels.forEach { nodeNames.add(it.fromName); nodeNames.add(it.toName) }
        nodeNames.remove("")
        val degree = HashMap<String, Int>()
        rels.forEach { r ->
            degree[r.fromName] = (degree[r.fromName] ?: 0) + 1
            degree[r.toName] = (degree[r.toName] ?: 0) + 1
        }
        val nodes = JSONArray().also { a ->
            nodeNames.forEach { n ->
                a.put(JSONObject().apply {
                    put("name", n)
                    put("degree", degree[n] ?: 0)
                    put("isCharacterAsset", n in characterNames)
                })
            }
        }
        val edges = JSONArray().also { a ->
            rels.take(100).forEach { r ->
                a.put(JSONObject().apply {
                    put("from", r.fromName); put("to", r.toName); put("type", r.type)
                    put("strength", r.strength); put("color", r.color)
                    if (r.description.isNotBlank()) put("description", r.description)
                })
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("nodeCount", nodeNames.size)
            put("edgeCount", rels.size)
            put("nodes", nodes)
            put("edges", edges)
            if (rels.size > 100) put("edgesTruncated", true)
            put("message", "关系图数据：${nodeNames.size} 节点 / ${rels.size} 边（强度 1-5→边宽，color→边色）。")
        }.toString()
    }
}

/**
 * novel_memory_save：保存一条写作记忆（跨章节要记住的事实/决定/时间线事件）。
 */
class NovelMemorySaveTool : QuroTool {
    override val name = "novel_memory_save"
    override val description =
        "保存写作记忆：把「必须跨章节记住」的事实/决定/时间线事件写进记忆库（带标签与可选章节锚点）。" +
            "何时用：写完一章发现产生了新的关键设定/人物决定/时间点；用户说「记住：…」。" +
            "查询用 novel_memory_query，总览用 novel_memory。不要存正文片段（那是 novel_query_previous 的活）。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "content": { "type": "string", "description": "记忆内容（一句话到三句话，写「可检索的事实」而非感想）", "example": "林晚在第三章把玉佩抵押给了当铺，赎回期限是中元节" },
        "kind": { "type": "string", "description": "记忆类型（可选，默认 fact）", "enum": ["fact", "decision", "timeline", "setting"] },
        "tags": { "type": "array", "items": { "type": "string" }, "description": "标签（可选，建议带人物名）", "example": ["林晚", "玉佩"] },
        "chapter_id": { "type": "string", "description": "锚定章节 id（可选：这条记忆来自/关于哪一章）" }
      },
      "required": ["work_id", "content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"content\":\"…\",\"tags\":[\"林晚\"]}")
        val workId = args.optString("work_id", "").trim()
        val content = args.optString("content", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        if (content.isBlank()) return gError(GErr.INVALID_ARGS, "content 不能为空", "写「可检索的事实」，例：「林晚把玉佩抵押给了当铺」")
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val anchor = args.optString("chapter_id", "").trim()
            .takeIf { it.isNotBlank() }
            ?.let { cid -> gChapterRefOf(work.chapters, cid) ?: return gChapterNotFound(work.title, cid, gOutlineHint(workId)) }
        val tags = args.optJSONArray("tags")?.let { a ->
            (0 until a.length()).map { a.optString(it, "").trim() }.filter { it.isNotBlank() }
        } ?: emptyList()
        val store = GraphStore.fromContext(context)
        val saved = store.addMemory(
            NovelMemory(
                id = "", workId = workId, content = content,
                kind = args.optString("kind", "fact").trim().ifBlank { "fact" },
                tags = tags, chapterRef = anchor,
            ),
        )
        return JSONObject().apply {
            put("ok", true)
            put("memory", JSONObject().apply {
                put("id", saved.id)
                put("kind", saved.kind)
                put("tags", JSONArray().also { a -> saved.tags.forEach { a.put(it) } })
                saved.chapterRef?.let { put("chapterRef", it.toJson()) }
                put("createdAt", saved.createdAt)
            })
            put("message", "已记住（${saved.kind}）：" + saved.content.take(50) + if (saved.content.length > 50) "…" else "")
        }.toString()
    }
}

/**
 * novel_memory_query：查询写作记忆（关键词包含 / 标签精确 / 类型过滤，按时间倒序）。
 */
class NovelMemoryQueryTool : QuroTool {
    override val name = "novel_memory_query"
    override val readOnly = true
    override val description =
        "查询写作记忆：按关键词（content 包含匹配）和/或标签（精确匹配）过滤，按时间倒序返回。" +
            "何时用：写新章前回想「玉佩那条线到哪了」；核对某人物之前的决定。总览用 novel_memory。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "keyword": { "type": "string", "description": "关键词（content 包含匹配，可选）", "example": "玉佩" },
        "tag": { "type": "string", "description": "标签精确匹配（可选）", "example": "林晚" },
        "kind": { "type": "string", "description": "类型过滤（可选）", "enum": ["fact", "decision", "timeline", "setting"] },
        "limit": { "type": "integer", "description": "最多返回条数，默认 10，上限 50" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"keyword\":\"玉佩\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val keyword = args.optString("keyword", "").trim()
        val tag = args.optString("tag", "").trim()
        val kind = args.optString("kind", "").trim()
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val all = GraphStore.fromContext(context).listMemories(workId)
        val hits = all.asSequence()
            .filter { keyword.isBlank() || it.content.contains(keyword, ignoreCase = true) }
            .filter { tag.isBlank() || it.tags.contains(tag) }
            .filter { kind.isBlank() || it.kind == kind }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
        val arr = JSONArray().also { a ->
            hits.forEach { m ->
                a.put(JSONObject().apply {
                    put("id", m.id); put("content", m.content); put("kind", m.kind)
                    put("tags", JSONArray().also { t -> m.tags.forEach { t.put(it) } })
                    m.chapterRef?.let { put("chapterRef", it.toJson()) }
                    put("createdAt", m.createdAt)
                })
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", hits.size)
            put("memories", arr)
            put(
                "message",
                if (hits.isEmpty()) "没有命中的记忆（条件：keyword=$keyword tag=$tag kind=$kind）。"
                else "命中 ${hits.size} 条（按时间倒序）。"
            )
        }.toString()
    }
}

/**
 * novel_memory：记忆库总览（条数 / 类型分布 / 标签 TOP / 最近条目）。
 */
class NovelMemoryOverviewTool : QuroTool {
    override val name = "novel_memory"
    override val readOnly = true
    override val description =
        "写作记忆库总览：总条数、类型分布（fact/decision/timeline/setting）、高频标签 TOP5、最近 5 条。" +
            "何时用：开写前快速回忆「这本书我都记了些什么」；判断记忆库是否该整理。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val all = GraphStore.fromContext(context).listMemories(workId)
        val kinds = JSONObject().apply {
            listOf("fact", "decision", "timeline", "setting").forEach { k -> put(k, all.count { it.kind == k }) }
        }
        val tagTop = all.asSequence().flatMap { it.tags.asSequence() }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
        val recent = all.sortedByDescending { it.createdAt }.take(5)
        val recentArr = JSONArray().also { a ->
            recent.forEach { m -> a.put(m.content.take(60) + if (m.content.length > 60) "…" else "") }
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", all.size)
            put("kinds", kinds)
            put("topTags", JSONArray().also { a -> tagTop.forEach { a.put("${it.key}(${it.value})") } })
            put("recent", recentArr)
            put("message", if (all.isEmpty()) "记忆库还是空的（novel_memory_save 存第一条）。" else "共 ${all.size} 条记忆。")
        }.toString()
    }
}

/**
 * novel_query_previous：前文检索（在已写章节正文中搜关键词，返回带上下文的片段）。
 *
 * 规划书 §6.1 检索行：控制上下文成本——只返回命中片段（±60 字），不回灌整章正文。
 * 每章每关键词只取第一个命中；全作命中总量由 max_hits 封顶。
 */
class NovelQueryPreviousTool : QuroTool {
    override val name = "novel_query_previous"
    override val readOnly = true
    override val description =
        "前文检索：在全部已写章节正文里搜关键词，返回「哪一章、第几段附近、上下文片段」。" +
            "何时用：写章前核对人物上次出场/道具下落/时间线；用户问「前文哪里提过 X」。结果只给片段不回灌整章，控制上下文成本。" +
            "多关键词用 keywords 数组（任一命中即算）。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "keywords": {
          "type": "array",
          "items": { "type": "string" },
          "description": "关键词列表（1-5 个，任一命中即返回；如 [\"玉佩\",\"当铺\"]）"
        },
        "max_hits": { "type": "integer", "description": "最多返回命中数，默认 8，上限 20" },
        "snippet_chars": { "type": "integer", "description": "每条片段的最大字符数，默认 160，上限 400" }
      },
      "required": ["work_id", "keywords"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"keywords\":[\"玉佩\"]}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val keywords = args.optJSONArray("keywords")?.let { a ->
            (0 until a.length()).map { a.optString(it, "").trim() }.filter { it.isNotBlank() }.distinct()
        } ?: emptyList()
        if (keywords.isEmpty()) return gError(GErr.INVALID_ARGS, "keywords 不能为空（1-5 个）", "例：{\"work_id\":\"…\",\"keywords\":[\"玉佩\"]}")
        if (keywords.size > 5) return gError(GErr.INVALID_ARGS, "keywords 最多 5 个（收到 ${keywords.size} 个）", "拆成多次查询，或挑最关键的关键词")
        val maxHits = args.optInt("max_hits", 8).coerceIn(1, 20)
        val snippetChars = args.optInt("snippet_chars", 160).coerceIn(40, 400)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val chapters = work.chapters.sortedWith(compareBy({ it.order }, { it.createdAt }))
        val hits = JSONArray()
        var hitCount = 0
        for (c in chapters) {
            if (hitCount >= maxHits) break
            val content = repo.readChapterContent(workId, c.id)
            if (content.isBlank()) continue
            var foundForChapter = false
            for (kw in keywords) {
                if (hitCount >= maxHits) break
                val idx = content.indexOf(kw, ignoreCase = true)
                if (idx < 0) continue
                val start = (idx - 60).coerceAtLeast(0)
                val end = (idx + kw.length + 60).coerceAtMost(content.length)
                var snippet = content.substring(start, end).replace(Regex("\\s+"), " ").trim()
                if (snippet.length > snippetChars) snippet = snippet.take(snippetChars) + "…"
                hits.put(JSONObject().apply {
                    put("chapterId", c.id)
                    put("chapterTitle", c.title)
                    put("order", c.order)
                    put("keyword", kw)
                    put("snippet", (if (start > 0) "…" else "") + snippet + (if (end < content.length) "…" else ""))
                })
                hitCount++
                foundForChapter = true
            }
            if (foundForChapter && content.length > 200_000) {
                // 超长章节每关键词只报首个命中（防止单章刷屏），继续下一章
                continue
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", hitCount)
            put("hits", hits)
            put(
                "message",
                if (hitCount == 0) "已写章节中没有命中 ${keywords.joinToString("/")}（含未写正文的章节不算）。"
                else "前文命中 $hitCount 处（每条含章节定位与 ±60 字上下文）。"
            )
        }.toString()
    }
}

/**
 * novel_consistency_check：一致性检查（确定性规则引擎，见 [NovelAnalyzer] 规则表）。
 */
class NovelConsistencyCheckTool : QuroTool {
    override val name = "novel_consistency_check"
    override val readOnly = true
    override val description =
        "全书一致性检查（规则版）：伏笔锚定章节被删 / 伏笔闲置过久（按优先级）/ 章节标完稿但字数为 0 / " +
            "关系引用未建档人物 / 记忆锚定章节被删。输出分级（error/warn/info）问题清单与下一步建议。" +
            "何时用：每写完一卷、或用户说「检查一下有没有前后矛盾」。语义级矛盾（人物性格漂移等）不在此工具范围。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "idle_hook_threshold": { "type": "integer", "description": "伏笔闲置告警阈值（章），默认 10" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val threshold = args.optInt("idle_hook_threshold", NovelAnalyzer.DEFAULT_IDLE_HOOK_THRESHOLD).coerceIn(1, 200)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val store = GraphStore.fromContext(context)
        val characterNames = runCatching {
            NovelAssetRepository.fromContext(context).list(workId, AssetType.CHARACTER).map { it.name }.toSet()
        }.getOrDefault(emptySet())
        val report = NovelAnalyzer.analyze(
            work = work,
            hooks = store.listHooks(workId),
            relationships = store.listRelationships(workId),
            memories = store.listMemories(workId),
            characterNames = characterNames,
            idleHookThreshold = threshold,
        )
        val issuesArr = JSONArray().also { a ->
            report.issues.forEach { i ->
                a.put(JSONObject().apply {
                    put("severity", i.severity.key)
                    put("code", i.code)
                    put("message", i.message)
                })
            }
        }
        val summary = buildString {
            append("一致性检查完成：")
            if (report.issues.isEmpty()) {
                append("✅ 未发现问题（伏笔/关系/记忆/章节状态四域均正常）。")
            } else {
                append("${report.errorCount} error、${report.warnCount} warn、${report.infoCount} info。")
                append("建议先处理 error/warn：")
                report.issues.filter { it.severity != NovelAnalyzer.Severity.INFO }
                    .take(3)
                    .forEach { append("\n· [${it.severity.key}] ${it.message}") }
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("issueCount", report.issues.size)
            put("errorCount", report.errorCount)
            put("warnCount", report.warnCount)
            put("infoCount", report.infoCount)
            put("issues", issuesArr)
            put("idleHookThreshold", report.idleHookThreshold)
            put("message", summary)
        }.toString()
    }
}

/** 图谱域工具全集：注册表（NovelToolSet）与名字集合共用同一真相源。 */
val NOVEL_GRAPH_TOOLS: List<QuroTool> = listOf(
    NovelHooksStatusTool(),
    NovelRelationshipsTool(),
    NovelRelationshipGraphTool(),
    NovelMemorySaveTool(),
    NovelMemoryQueryTool(),
    NovelMemoryOverviewTool(),
    NovelQueryPreviousTool(),
    NovelConsistencyCheckTool(),
)
