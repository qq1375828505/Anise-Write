package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository
import com.ai.assistance.quro.core.novel.graph.GraphStore
import com.ai.assistance.quro.core.novel.graph.hook.NovelHook
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * novel_materials_analyze：AI 资料自动分析（P1-3）——「读一遍书，自动建档」。
 *
 * 移植自 novelIDE `lib/data/services/ai_analysis_service.dart`（语义对齐、独立实现）：
 * 原文分 6 次调用逐类提取；本实现**合并为 1 次调用**（一次输出 6 类 JSON 对象），
 * 原因是基座工具有 60 秒硬超时（`QuroTool.TOOL_EXEC_TIMEOUT_MS`），
 * 6 次串行 LLM 调用必然腰斩；单次调用同时显著降低费用与时延。
 *
 * 六类（与参考实现一致）：人物 / 世界观设定 / 地点 / 势力 / 道具 / 伏笔。
 * 其中前五类落资料库（`NovelAssetRepository`），伏笔落图谱台账（`GraphStore`）。
 *
 * 去重与合并策略（参考实现只做「同名跳过」，此处升级为**只补空字段**）：
 * - 同名条目已存在 → 仅把**新提取到的非空字段**填进空位；已有非空内容一律不动
 *   （用户手写/此前建档的内容优先，自动分析绝不覆盖）；
 * - 不存在 → 新建；dry_run=true 时只报告名单、不写盘。
 */

// ─────────────────────────── 纯函数支撑层（工具与单测共用，不碰 Context） ───────────────────────────

internal object NovelMaterialAnalysisSupport {

    /** 输入正文上限（超出截断，参考实现同为 15000 字，此处放宽到 20000）。 */
    const val MAX_INPUT_CHARS = 20000

    /** 每类默认最多提取条数。 */
    const val MAX_PER_CATEGORY = 12

    /** 分析类别（key 即模型输出 JSON 里的数组键）。 */
    enum class Category(val key: String, val displayName: String, val assetType: AssetType?) {
        CHARACTER("characters", "人物", AssetType.CHARACTER),
        SETTING("settings", "世界观设定", AssetType.SETTING),
        LOCATION("locations", "地点", AssetType.LOCATION),
        FACTION("factions", "势力", AssetType.FACTION),
        ITEM("items", "道具", AssetType.ITEM),
        HOOK("hooks", "伏笔", null),
    }

    /** 八类共用可写字段 + 别名映射目标之外的受限集合（与 NovelAssetToolExecutor 同口径）。 */
    private val COMMON_FIELDS = setOf("name", "description", "tags")

    /** 合并时禁止由分析结果改动的托管字段。 */
    private val IMMUTABLE_FIELDS = setOf("id", "workId", "createdAt", "updatedAt", "type")

    /**
     * 组装系统提示词（单次调用版，字段说明照搬参考实现的 6 段 prompt）。
     * 输出必须是纯 JSON 对象；禁止围栏/注释/尾随逗号。
     */
    fun buildSystemPrompt(maxPerCategory: Int = MAX_PER_CATEGORY): String = buildString {
        appendLine("你是一位网文资料分析专家。请通读用户给出的小说正文，一次性提取六类资料，")
        appendLine("只输出一个严格 JSON 对象（不要解释、不要 Markdown 围栏、不要注释与尾随逗号）。")
        appendLine()
        appendLine("输出格式：")
        appendLine("{")
        appendLine("  \"characters\": [{\"name\":\"\",\"role\":\"\",\"description\":\"\",\"appearance\":\"\",\"personality\":\"\",\"background\":\"\"}],")
        appendLine("  \"settings\": [{\"name\":\"\",\"category\":\"世界观｜战力体系｜修炼等级｜社会制度｜科技水平｜其他\",\"description\":\"\"}],")
        appendLine("  \"locations\": [{\"name\":\"\",\"category\":\"城市｜宗门｜秘境｜国家｜山脉｜海域｜其他\",\"description\":\"\",\"features\":\"\",\"rules\":\"\"}],")
        appendLine("  \"factions\": [{\"name\":\"\",\"category\":\"正道｜魔道｜中立｜国家｜门派｜家族｜其他\",\"description\":\"\",\"leader\":\"\",\"strength\":\"\"}],")
        appendLine("  \"items\": [{\"name\":\"\",\"category\":\"武器｜法宝｜丹药｜功法｜阵法｜其他\",\"description\":\"\",\"grade\":\"\",\"owner\":\"\",\"isKeyItem\":false}],")
        appendLine("  \"hooks\": [{\"title\":\"\",\"description\":\"\"}]")
        appendLine("}")
        appendLine()
        appendLine("规则：")
        appendLine("1. 只提取正文中出现过具体名字的人物 / 地点 / 势力 / 道具；没有名字的一律不收；")
        appendLine("2. description 100 字内；features / rules 50 字内；只写正文能支撑的信息，严禁编造；")
        appendLine("3. role 用「主角 / 配角 / 反派 / 路人」等；strength、grade 用原文的品阶或等级表述；")
        appendLine("4. hooks（伏笔）指作者埋下、尚未完全揭示的线索或悬念——只提取真正的伏笔，不要罗列普通情节；")
        appendLine("5. 每类最多 $maxPerCategory 条，按对剧情的重要程度排序；某类没有就返回空数组 []；")
        appendLine("6. 所有键名必须与上面格式完全一致；isKeyItem 用布尔值 true / false。")
    }

    /** 从模型输出里提取 JSON 对象（剥围栏 + 取首尾花括号片段）；失败返回 null。 */
    fun extractAnalysisJson(raw: String): JSONObject? {
        val t = NovelWriteSupport.stripCodeFences(raw)
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(t.substring(start, end + 1)) }.getOrNull()
    }

    /**
     * 条目 → 资产字段（白名单过滤 + 别名映射）。
     * 别名：`powerLevel` → `grade`、`isKeyItem` → `isKey`（布尔，兼容 true / "true" / "是"）。
     * 空串与 null 一律不进结果（避免把空值写进资料）。
     */
    fun assetFieldsFor(type: AssetType, entry: JSONObject): JSONObject {
        val allowed = COMMON_FIELDS + type.fields
        val out = JSONObject()
        entry.keys().forEach { k ->
            val alias = when (k) {
                "powerLevel" -> "grade"
                "isKeyItem" -> "isKey"
                else -> k
            }
            if (alias !in allowed) return@forEach
            val v = entry.get(k)
            if (alias == "isKey") {
                out.put("isKey", truthy(v))
            } else {
                val s = v?.toString()?.trim().orEmpty()
                if (s.isNotEmpty() && s != "null") out.put(alias, s)
            }
        }
        return out
    }

    /**
     * 只补空合并：已有非空值不动，只把新来的非空值填进空位。
     * 「空」判据：缺失 / 空串 / "null" / 空数组 / 布尔 false（isKey 占位）。
     *
     * @return 合并后的完整 JSON 与「是否真的改了」；
     *   改没改用于统计「合并」与「跳过」——无变化时调用方不必落盘。
     */
    fun mergeFillEmpty(existing: JSONObject, incoming: JSONObject): Pair<JSONObject, Boolean> {
        val out = JSONObject(existing.toString())
        var changed = false
        incoming.keys().forEach { k ->
            if (k in IMMUTABLE_FIELDS || k == "tags") return@forEach
            val cur = out.opt(k)
            val curBlank = cur == null || cur.toString().isBlank() || cur.toString() == "null" ||
                (cur is JSONArray && cur.length() == 0) ||
                (cur is Boolean && !cur)
            val inc = incoming.get(k)
            val incBlank = inc == null || inc.toString().isBlank() || inc.toString() == "null"
            if (curBlank && !incBlank) {
                out.put(k, inc)
                changed = true
            }
        }
        return out to changed
    }

    /** 伏笔条目 → (title, description)；title 缺失时回退 name；都没有返回 null。 */
    fun hookPair(entry: JSONObject): Pair<String, String>? {
        val title = entry.optString("title", "").trim().ifBlank { entry.optString("name", "").trim() }
        if (title.isEmpty()) return null
        return title to entry.optString("description", "").trim()
    }

    /** 兼容布尔/字符串的「真值」判定（端侧模型常把 true 写成 "是" / "1"）。 */
    private fun truthy(v: Any?): Boolean = when (v) {
        is Boolean -> v
        null -> false
        else -> v.toString().trim().lowercase() in setOf("true", "1", "yes", "是")
    }
}

// ─────────────────────────── 工具壳 ───────────────────────────

/**
 * novel_materials_analyze：把一段正文一次性提取为 6 类资料并自动建档。
 *
 * - 写操作：新增 / 合并资料与伏笔（合并只补空字段，绝不覆盖已有内容）；
 * - `dry_run=true` 时只报告将建档的名单，不写盘；
 * - 未配置模型 / 模型输出不可解析 → 明确报错（含原始片段），不静默兜底。
 */
class NovelMaterialsAnalyzeTool : QuroTool {
    override val name = "novel_materials_analyze"
    override val description =
        "AI 资料自动分析（读一遍书，自动建档）：把一段正文或某一章一次性提取为 6 类资料——" +
            "人物 / 世界观设定 / 地点 / 势力 / 道具 / 伏笔——并写入资料库与伏笔台账；" +
            "同名条目按「只补空字段、不覆盖已有内容」合并。dry_run=true 只预览名单不写入。" +
            "何时用：用户粘贴设定稿 / 样章说「帮我建档」「把这本书的资料提取出来」时。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "text": { "type": "string", "description": "要分析的正文本（与 chapter_id 二选一；超 20000 字自动截断）" },
        "chapter_id": { "type": "string", "description": "分析某章已存正文（需同时给 work_id；与 text 二选一）" },
        "max_per_category": { "type": "integer", "description": "每类最多提取条数，默认 12，取值 1-30" },
        "dry_run": { "type": "boolean", "description": "true=只报告将要建档的名单、不写盘（默认 false）" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"text\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId }
            ?: return gError("work_not_found", "作品不存在：$workId", "用 novel_list_works 复查作品 id")

        // ── 1. 取正文（text 优先，其次某章已存正文） ──
        val inlineText = args.optString("text", "").trim()
        val chapterId = args.optString("chapter_id", "").trim()
        val raw = when {
            inlineText.isNotBlank() -> inlineText
            chapterId.isNotBlank() -> {
                val content = runCatching { repo.readChapterContent(workId, chapterId) }.getOrNull().orEmpty()
                if (content.isBlank()) {
                    return gError("chapter_empty", "章节正文为空或章节不存在：$chapterId", "先用 novel_get_chapter 确认章节与正文")
                }
                content
            }
            else -> return gError(
                GErr.INVALID_ARGS,
                "需要 text 或 chapter_id",
                "二选一：粘贴要分析的正文，或指定某章 id（如 {\"work_id\":\"…\",\"chapter_id\":\"ch_047\"}）",
            )
        }
        val capped = if (raw.length > NovelMaterialAnalysisSupport.MAX_INPUT_CHARS) {
            raw.take(NovelMaterialAnalysisSupport.MAX_INPUT_CHARS)
        } else {
            raw
        }
        val maxPer = args.optInt("max_per_category", NovelMaterialAnalysisSupport.MAX_PER_CATEGORY)
            .coerceIn(1, 30)
        val dryRun = args.optBoolean("dry_run", false)

        // ── 2. 单次 LLM 调用（60s 工具超时内必须只调一次） ──
        val llm = NovelWriteSupport.callLlm(
            context,
            NovelMaterialAnalysisSupport.buildSystemPrompt(maxPer),
            capped,
            temperature = 0.3f,
        )
        val outText = when (llm) {
            is LlmOutcome.Failed -> return gError(
                "llm_failed",
                llm.message,
                "确认「设置 → 模型配置」的 API Key 与模型可用；再重试本工具",
            )
            is LlmOutcome.Ok -> llm.text
        }
        val json = NovelMaterialAnalysisSupport.extractAnalysisJson(outText)
            ?: return gError(
                "parse_failed",
                "模型输出不是可解析的 JSON 对象。原始输出前 200 字：${outText.take(200)}",
                "可重试一次；端侧小模型解析不稳时，建议换更强模型做建档",
            )

        // ── 3. 去重合并 / 建档 ──
        val assetRepo = NovelAssetRepository.fromContext(context)
        val store = GraphStore.fromContext(context)
        val hooksInWork = store.listHooks(workId)
        val lines = ArrayList<String>()
        var totalAdded = 0
        var totalMerged = 0
        var totalSkipped = 0

        for (cat in NovelMaterialAnalysisSupport.Category.entries) {
            val arr = json.optJSONArray(cat.key) ?: JSONArray()
            var added = 0
            var merged = 0
            var skipped = 0
            val names = ArrayList<String>()
            for (i in 0 until minOf(arr.length(), maxPer)) {
                val entry = arr.optJSONObject(i) ?: continue
                val type = cat.assetType
                if (type != null) {
                    val fields = NovelMaterialAnalysisSupport.assetFieldsFor(type, entry)
                    val nm = fields.optString("name", "").trim()
                    if (nm.isEmpty()) {
                        skipped++
                        continue
                    }
                    val existing = assetRepo.findByName(workId, type, nm).firstOrNull()
                    if (existing == null) {
                        if (!dryRun) runCatching { assetRepo.add(type, workId, fields) }
                        added++
                        names.add(nm)
                    } else {
                        val (mergedJson, changed) = NovelMaterialAnalysisSupport.mergeFillEmpty(existing.toJson(), fields)
                        if (changed) {
                            if (!dryRun) runCatching { assetRepo.update(type, workId, existing.id, mergedJson) }
                            merged++
                            names.add("$nm（补字段）")
                        } else {
                            skipped++
                        }
                    }
                } else {
                    // 伏笔：同名（title 精确）判定；已存在的只补空描述
                    val pair = NovelMaterialAnalysisSupport.hookPair(entry)
                    if (pair == null) {
                        skipped++
                        continue
                    }
                    val title = pair.first
                    val desc = pair.second
                    val existing = hooksInWork.firstOrNull { it.title.trim() == title }
                    if (existing == null) {
                        if (!dryRun) {
                            runCatching {
                                store.addHook(NovelHook(id = "", workId = workId, title = title, description = desc))
                            }
                        }
                        added++
                        names.add(title)
                    } else if (existing.description.isBlank() && desc.isNotBlank()) {
                        if (!dryRun) runCatching { store.updateHook(workId, existing.id) { it.copy(description = desc) } }
                        merged++
                        names.add("$title（补描述）")
                    } else {
                        skipped++
                    }
                }
            }
            totalAdded += added
            totalMerged += merged
            totalSkipped += skipped
            lines.add(
                "· ${cat.displayName}：新增 $added / 合并 $merged / 跳过 $skipped" +
                    if (names.isNotEmpty()) "　${names.joinToString("、")}" else ""
            )
        }

        return buildString {
            appendLine(
                (if (dryRun) "【试运行 · 未写入】" else "") +
                    "资料分析完成：作品《${work.title}》"
            )
            appendLine(
                "文本 ${capped.length} 字" +
                    if (raw.length > capped.length) "（原文 ${raw.length} 字，已按 ${NovelMaterialAnalysisSupport.MAX_INPUT_CHARS} 字截断）" else ""
            )
            lines.forEach { appendLine(it) }
            append("合计：新增 $totalAdded / 合并 $totalMerged / 跳过 $totalSkipped")
            if (dryRun) append("；确认无误后以 dry_run=false 重跑即写入")
        }
    }
}
