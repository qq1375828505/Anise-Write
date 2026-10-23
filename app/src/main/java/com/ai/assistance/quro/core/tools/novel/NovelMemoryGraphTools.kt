package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.CharacterAsset
import com.ai.assistance.quro.core.novel.asset.FactionAsset
import com.ai.assistance.quro.core.novel.asset.ItemAsset
import com.ai.assistance.quro.core.novel.asset.LocationAsset
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository
import com.ai.assistance.quro.core.novel.asset.ReferenceAsset
import com.ai.assistance.quro.core.novel.asset.SettingAsset
import com.ai.assistance.quro.core.novel.graph.GraphStore
import com.ai.assistance.quro.core.novel.graph.hook.NovelHookStatus
import com.ai.assistance.quro.core.novel.graph.memory.NovelMemoryLink
import com.ai.assistance.quro.core.novel.graph.memory.NovelTextMemory
import com.ai.assistance.quro.core.novel.graph.memory.NovelTextMemoryCache
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONObject

/**
 * PenAi 写作域 · 记忆双轨工具族（P1-4）。
 *
 * | 工具 | 轨 | 职责 |
 * |---|---|---|
 * | `novel_memory_text` | 文本轨 | 取十节快照（5 分钟 TTL 缓存，可强制刷新） |
 * | `novel_memory_link` | 图谱轨 | 在两段记忆之间建边（相关/因果/伏笔/回收/同一人） |
 * | `novel_memory_graph` | 图谱轨 | 导出记忆图谱（节点 + 边 + 五表计数），供前端渲染与自查 |
 *
 * 图谱轨的增删查（`novel_memory_save` / `novel_memory_query` / `novel_memory`）在
 * NovelGraphTools.kt；本文件只管「文本轨快照」与「图谱的边与视图」。
 */

/**
 * 文本轨快照的取数层：把 Phase 1 结构域、3b 资料域、3c 图谱域的数据装配成
 * [NovelTextMemory.Input]（纯数据），构建交给 [NovelTextMemory]（纯函数，可 JVM 单测）。
 */
internal object NovelTextMemoryProvider {

    /** 章节状态 key 的中文化（快照要给人读；Phase 1 枚举只有 key）。 */
    private fun statusLabel(key: String): String = when (key) {
        "unwritten" -> "未写"
        "draft" -> "草稿"
        "polishing" -> "打磨中"
        "completed" -> "已写完"
        else -> key
    }

    fun input(context: Context, workId: String): NovelTextMemory.Input? {
        val work = NovelRepository.fromContext(context).loadWorks().firstOrNull { it.id == workId }
            ?: return null
        val assets = NovelAssetRepository.fromContext(context)
        val hooks = GraphStore.fromContext(context).listHooks(workId)

        val volumes = work.volumes.sortedWith(compareBy({ it.order }, { it.createdAt })).map { vol ->
            NovelTextMemory.VolumeBlock(
                title = vol.title,
                chapters = work.chaptersOfVolume(vol.id).map { ch ->
                    NovelTextMemory.ChapterLine(
                        order = ch.order,
                        title = ch.title,
                        wordCount = ch.wordCount,
                        status = statusLabel(ch.status.key),
                    )
                },
            )
        }
        val knownVolumeIds = work.volumes.map { it.id }.toSet()
        val loose = work.chapters
            .filter { !knownVolumeIds.contains(it.volumeId) }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))
            .map { ch ->
                NovelTextMemory.ChapterLine(
                    order = ch.order,
                    title = ch.title,
                    wordCount = ch.wordCount,
                    status = statusLabel(ch.status.key),
                )
            }

        val maxOrder = work.chapters.maxOfOrNull { it.order } ?: 0
        val characters = assets.list(workId, AssetType.CHARACTER).filterIsInstance<CharacterAsset>()
            .map { c ->
                NovelTextMemory.CharacterLine(
                    name = c.name,
                    role = c.role,
                    description = c.description,
                    personality = c.personality,
                    background = c.background,
                    tags = c.tags,
                )
            }
        val settings = assets.list(workId, AssetType.SETTING).filterIsInstance<SettingAsset>()
            .map { s ->
                NovelTextMemory.AssetLine(
                    name = s.name,
                    category = s.category,
                    description = s.description,
                    extras = listOf("正文" to s.content) + s.constraints.map { "约束" to it },
                )
            }
        val locations = assets.list(workId, AssetType.LOCATION).filterIsInstance<LocationAsset>()
            .map { l ->
                NovelTextMemory.AssetLine(
                    name = l.name,
                    category = l.category,
                    description = l.description,
                    extras = listOf("规则" to l.rules, "特征" to l.features),
                )
            }
        val factions = assets.list(workId, AssetType.FACTION).filterIsInstance<FactionAsset>()
            .map { f ->
                NovelTextMemory.AssetLine(
                    name = f.name,
                    category = f.category,
                    description = f.description,
                    extras = listOf(
                        "首领" to f.leader,
                        "地盘" to f.territory,
                        "成员" to f.members.joinToString("、"),
                    ),
                )
            }
        val items = assets.list(workId, AssetType.ITEM).filterIsInstance<ItemAsset>()
            .map { i ->
                NovelTextMemory.AssetLine(
                    name = i.name,
                    category = i.category,
                    description = i.description,
                    extras = listOf("品阶" to i.grade, "持有者" to i.owner, "效果" to i.effect),
                    key = i.isKey,
                )
            }
        val references = assets.list(workId, AssetType.REFERENCE).filterIsInstance<ReferenceAsset>()
            .map { r -> NovelTextMemory.ReferenceLine(title = r.name, source = r.source) }

        val hookLines = hooks.map { h ->
            val planted = h.plantedAt
            val idle = if (planted != null && maxOrder > 0) (maxOrder - planted.order).coerceAtLeast(0) else 0
            NovelTextMemory.HookLine(
                title = h.title,
                description = h.description,
                resolved = h.status == NovelHookStatus.CLOSED,
                idleChapters = idle,
            )
        }

        return NovelTextMemory.Input(
            work = NovelTextMemory.WorkInfo(
                id = work.id,
                title = work.title,
                genre = work.genre,
                totalWordCount = work.totalWordCount(),
                chapterCount = work.chapters.size,
                description = "",
            ),
            volumes = volumes,
            looseChapters = loose,
            characters = characters,
            settings = settings,
            locations = locations,
            factions = factions,
            items = items,
            hooks = hookLines,
            references = references,
            updatedAt = System.currentTimeMillis(),
        )
    }
}

/**
 * novel_memory_text：取「十节记忆快照」（P1-4 文本轨）。
 *
 * 快照把作品当前状态压成一页文本（作品信息 / 卷章结构 / 最近章节 / 角色 / 设定 / 地点 /
 * 势力 / 道具 / 伏笔 / 参考资料），供写章前一次性回灌上下文；**5 分钟 TTL 缓存**，
 * 期间重复取用零成本。
 */
class NovelMemoryTextTool : QuroTool {
    override val name = "novel_memory_text"
    override val readOnly = true
    override val description =
        "取十节记忆快照（文本轨）：作品信息 / 卷章结构 / 最近章节 / 角色 / 设定 / 地点 / 势力 / 道具 / 伏笔 / 参考资料，" +
        "一次调用拿到全书现状（结果有 5 分钟缓存）。" +
            "何时用：写新章前一次性对齐世界观与进度；用户问「这本书现在什么情况」。" +
            "细粒度检索用 novel_memory_query，图谱视图用 novel_memory_graph。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "force_refresh": { "type": "boolean", "description": "强制重建快照（跳过 5 分钟缓存），默认 false" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId }
            ?: return gWorkNotFound(repo, workId)
        val forceRefresh = args.optBoolean("force_refresh", false)
        val cache = NovelTextMemoryCache.shared
        if (forceRefresh) cache.invalidate(workId)
        val wasFresh = cache.isFresh(workId)
        val snapshot = cache.get(workId) {
            val input = NovelTextMemoryProvider.input(context, workId)
                ?: NovelTextMemory.Input(work = NovelTextMemory.WorkInfo(id = workId, title = work.title))
            NovelTextMemory.build(input)
        }
        val truncated = snapshot.length > TEXT_LIMIT
        return JSONObject().apply {
            put("ok", true)
            put("work_id", workId)
            put("sections", org.json.JSONArray().also { a -> NovelTextMemory.SECTION_TITLES.forEach { a.put(it) } })
            put("cached", wasFresh && !forceRefresh)
            put("ttlSeconds", NovelTextMemory.CACHE_TTL_MS / 1000)
            put("length", snapshot.length)
            put("text", if (truncated) snapshot.take(TEXT_LIMIT) else snapshot)
            if (truncated) put("textTruncated", true)
            put(
                "message",
                "十节记忆快照（${snapshot.length} 字符，${if (wasFresh && !forceRefresh) "命中缓存" else "已重建"}；" +
                    "缓存 ${NovelTextMemory.CACHE_TTL_MS / 60000} 分钟）。",
            )
        }.toString()
    }

    private companion object {
        /** 回执正文上限（快照正常千余字符；异常膨胀时截断，避免撑爆调用方上下文）。 */
        const val TEXT_LIMIT = 8000
    }
}

/**
 * novel_memory_link：在两段记忆之间建边（图谱轨）。
 */
class NovelMemoryLinkTool : QuroTool {
    override val name = "novel_memory_link"
    override val description =
        "连接两段记忆（建一条图谱边）：把「玉佩抵押」和「中元节赎回」这类有关系的记忆连起来，" +
        "之后检索命中一端会顺着边带出另一端。" +
            "何时用：发现两条记忆存在因果/伏笔/同一人关系；用户说「把这两条记一起」。" +
            "记忆 id 用 novel_memory_query 或 novel_memory 获取。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "source_id": { "type": "string", "description": "起点记忆 id" },
        "target_id": { "type": "string", "description": "终点记忆 id" },
        "type": { "type": "string", "description": "关系类型（可选，默认 related）", "enum": ["related", "cause", "foreshadow", "resolve", "same_person"] },
        "weight": { "type": "number", "description": "边强度 0.0~1.0（可选，默认 1.0）" },
        "description": { "type": "string", "description": "边的说明（可选）" }
      },
      "required": ["work_id", "source_id", "target_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"source_id\":\"…\",\"target_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        val sourceId = args.optString("source_id", "").trim()
        val targetId = args.optString("target_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        if (sourceId.isBlank() || targetId.isBlank()) {
            return gError(GErr.INVALID_ARGS, "source_id / target_id 不能为空", "用 novel_memory_query 取记忆 id")
        }
        if (sourceId == targetId) {
            return gError(GErr.INVALID_ARGS, "不能把一段记忆连到自己", "两端需是不同记忆")
        }
        val store = GraphStore.fromContext(context).memoryStore
        val link = store.addLink(
            workId = workId,
            sourceId = sourceId,
            targetId = targetId,
            type = args.optString("type", NovelMemoryLink.TYPE_RELATED).trim().ifBlank { NovelMemoryLink.TYPE_RELATED },
            weight = args.optDouble("weight", 1.0).let { if (it.isNaN()) 1.0 else it },
            description = args.optString("description", "").trim(),
        ) ?: return gError(
            GErr.INVALID_ARGS,
            "建边失败：两端记忆必须都已存在且不同",
            "确认 source_id / target_id 来自 novel_memory_query 的结果",
        )
        return JSONObject().apply {
            put("ok", true)
            put("link", link.toJson())
            put("message", "已连接两段记忆（${link.type}，强度 ${link.weight}）。")
        }.toString()
    }
}

/**
 * novel_memory_graph：记忆图谱视图（节点 + 边 + 五表计数）。
 */
class NovelMemoryGraphTool : QuroTool {
    override val name = "novel_memory_graph"
    override val readOnly = true
    override val description =
        "记忆图谱视图：返回全部记忆节点与它们之间的边（含五张表的计数：节点/标签/关系/边/属性）。" +
            "何时用：判断记忆库的结构是否健康（有没有孤立节点、标签是否散乱）；给用户展示记忆网络。" +
            "条目检索用 novel_memory_query，整书快照用 novel_memory_text。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "node_limit": { "type": "integer", "description": "最多返回节点数，默认 200" },
        "edge_limit": { "type": "integer", "description": "最多返回边数，默认 400" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = gArgs(arguments)
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val store = GraphStore.fromContext(context).memoryStore
        val graph = store.graphJson(
            workId = workId,
            nodeLimit = args.optInt("node_limit", 200).coerceIn(1, 1000),
            edgeLimit = args.optInt("edge_limit", 400).coerceIn(1, 2000),
        )
        val nodes = graph.optJSONArray("nodes")?.length() ?: 0
        val edges = graph.optJSONArray("edges")?.length() ?: 0
        graph.put("ok", true)
        graph.put(
            "message",
            if (nodes == 0) "记忆图谱还是空的（novel_memory_save 存第一条）。"
            else "记忆图谱：$nodes 节点 / $edges 边（标签 ${graph.optInt("tagCount")} 个 / 属性 ${graph.optInt("propertyCount")} 条）。",
        )
        return graph.toString()
    }
}
