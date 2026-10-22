package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroModelGroupRepository
import com.ai.assistance.quro.core.model.QuroModelGroupRouter
import com.ai.assistance.quro.core.model.QuroModelIntent
import com.ai.assistance.quro.core.model.QuroModelTarget
import com.ai.assistance.quro.core.model.QuroMultiProviderRepository
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONObject
import java.util.UUID

/**
 * PenAi 网文写作域 · 委派工具（Phase 4a / DELEGATION.md §4）。
 *
 * novel_delegate_subagent：把创作任务派发给 6 个子代理（outline/character/foreshadow/
 * polish/review/consistency）。实现要点（对齐 `assets/prompts/subagents/DELEGATION.md`）：
 * - **主循环内同步执行**：一次工具调用完成，不创建独立 subStore（基座 Issue #15）；
 * - **信封契约**：请求/响应均为 §2 纯 JSON 信封；delegation_id = `dlg_` + 16 hex，
 *   响应必须回显（LLM 漏回显时由本工具补齐）；
 * - **零写权限断言**：子代理提示词 frontmatter 必须含 `zero-write: true` 才允许执行，
 *   缺失/为 false 一律拒绝（硬约束，加载器与调度器共担）；
 * - **路由**：按 §2.3 把工具参数映射为各代理专有 targets，正文的 system prompt 取自
 *   `assets/prompts/subagents/<subagent>.md`（frontmatter 剥离），user 消息 = 请求信封 JSON；
 * - **每轮一次 LLM**：`NovelWriteSupport.callLlm(temperature=0.4)`，round 1-3，
 *   round≥2 必须携带 previous_result（组装为 previous_round 透传）；
 * - **失败可见**：任何失败路径返回错误 + 可用子代理清单，不返回空结果冒充成功（工程禁令：无兜底）。
 */

/** 子代理提示词资产目录。 */
private const val SUBAGENT_ASSET_DIR = "prompts/subagents"

/** 六个子代理（id → 中文名），与 assets/prompts/subagents/ 的 6 个提示词文件一一对应。 */
private val SUBAGENT_TITLES = mapOf(
    "outline" to "大纲代理",
    "character" to "人物代理",
    "foreshadow" to "伏笔代理",
    "polish" to "润色代理",
    "review" to "审读代理",
    "consistency" to "一致性代理",
)

/** 可用子代理清单（附在所有错误里，失败必须可见、可自纠）。 */
private fun gSubagentListHint(): String =
    "可用子代理：" + SUBAGENT_TITLES.entries.joinToString("；") { "${it.key}（${it.value}）" }

/**
 * 读子代理提示词：返回 (frontmatter, systemPrompt 正文)。
 * 文件缺失 / assets IO 失败 → null（调用方报错，不兜底）。
 */
private fun gLoadSubagentPrompt(context: Context, subagent: String): Pair<String, String>? = runCatching {
    val raw = context.assets.open("$SUBAGENT_ASSET_DIR/$subagent.md").bufferedReader().use { it.readText() }
    if (!raw.startsWith("---")) return@runCatching "" to raw.trim()
    val end = raw.indexOf("\n---", 3)
    if (end < 0) return@runCatching "" to raw.trim()
    raw.substring(3, end).trim() to raw.substring(end + 4).trim()
}.getOrNull()

/** 零写权限断言：frontmatter 必须显式声明 zero-write: true。 */
private fun gAssertZeroWrite(frontmatter: String): Boolean =
    Regex("zero-write\\s*:\\s*true").containsMatchIn(frontmatter)

/**
 * 从 LLM 原始输出提取响应信封 JSON（取首个 '{' 到末个 '}' 的片段解析）。
 * 解析失败返回 null（调用方原样透传，不伪造信封）。
 */
private fun gExtractResponseJson(text: String): JSONObject? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
}

/** 生成 delegation_id：`dlg_` + 16 位 hex。 */
private fun gNewDelegationId(): String = "dlg_" + UUID.randomUUID().toString().replace("-", "").take(16)

/**
 * 组装 §2.3 各代理专有 targets；枚举值不合法或必填缺失返回 null（调用方报错）。
 * 工具参数 → targets 映射：
 * - outline：scope（whole/volume/chapters，缺省按是否给 chapter_range 推断）、volume_index、from/to；
 * - character：mode 必填（create/enrich/derive_relationships）+ character_names[]；
 * - foreshadow：mode 必填（plan/audit/both）+ chapter_range{from,to}；
 * - polish：mode 必填（suggest/rewrite）；
 * - review：无专有字段（范围由 context.chapter 决定）；
 * - consistency：scope{from_chapter,to_chapter} 必填（由 chapter_range 映射）。
 */
private fun gBuildTargets(subagent: String, args: JSONObject): JSONObject? {
    val mode = args.optString("mode", "").trim()
    val range = args.optJSONObject("chapter_range")
    return when (subagent) {
        "outline" -> JSONObject().apply {
            val scope = args.optString("scope", "").trim()
            put("scope", when (scope) {
                "whole", "volume", "chapters" -> scope
                "" -> if (range != null) "chapters" else "whole"
                else -> return null
            })
            val vi = args.optInt("volume_index", -1)
            if (vi >= 0) put("volume_index", vi)
            if (range != null) {
                if (range.has("from")) put("from", range.optInt("from"))
                if (range.has("to")) put("to", range.optInt("to"))
            }
        }
        "character" -> JSONObject().apply {
            if (mode !in listOf("create", "enrich", "derive_relationships")) return null
            put("mode", mode)
            args.optJSONArray("character_names")?.let { put("character_names", it) }
        }
        "foreshadow" -> JSONObject().apply {
            if (mode !in listOf("plan", "audit", "both")) return null
            put("mode", mode)
            if (range != null) put("chapter_range", JSONObject().apply {
                put("from", range.optInt("from"))
                put("to", range.optInt("to"))
            })
        }
        "polish" -> JSONObject().apply {
            if (mode !in listOf("suggest", "rewrite")) return null
            put("mode", mode)
        }
        "review" -> JSONObject()
        "consistency" -> JSONObject().apply {
            if (range == null || !range.has("from") || !range.has("to")) return null
            put("scope", JSONObject().apply {
                put("from_chapter", range.optInt("from"))
                put("to_chapter", range.optInt("to"))
            })
        }
        else -> null
    }
}

/**
 * novel_delegate_subagent：派发一个创作子代理（大纲/人物/伏笔/润色/审读/一致性）。
 *
 * 子代理零写权限：本工具 readOnly = true，子代理只产出建议态结论（响应信封），
 * 写操作由主 AI 经 novel_write_chapter / novel_materials_add 等显式另发工具调用完成。
 * 失败可见：所有错误路径都返回错误详情 + 可用子代理清单。
 */
class NovelDelegateSubagentTool : QuroTool {
    override val name = "novel_delegate_subagent"
    override val readOnly = true
    override val description =
        "派发一个创作子代理（outline 大纲 / character 人物 / foreshadow 伏笔 / polish 润色 / review 审读 / consistency 一致性）。" +
            "子代理零写权限，结果以建议态 JSON 信封返回，写操作由主 AI 执行。" +
            "何时用：七阶段工作流的「分析/检查」步骤（细纲展开、人物弧光、伏笔审计、单章润色、审读、全书一致性扫描）；" +
            "或用户点名「让大纲代理/审读代理看看」。round 1-3：需要在上轮结论上迭代时 round+1 并带上轮响应 JSON（previous_result）。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "subagent": { "type": "string", "enum": ["outline", "character", "foreshadow", "polish", "review", "consistency"], "description": "目标子代理" },
        "task": { "type": "string", "description": "自然语言任务（主 AI 转写，须自足可执行）" },
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "chapter_id": { "type": "string", "description": "单章任务必填（polish/review 单章；自动预取该章正文进 context.chapter）" },
        "chapter_range": { "type": "object", "properties": { "from": { "type": "integer" }, "to": { "type": "integer" } }, "description": "章节范围（consistency 必填；foreshadow/outline 的 from/to）" },
        "mode": { "type": "string", "description": "子代理专有模式：character=create/enrich/derive_relationships，foreshadow=plan/audit/both，polish=suggest/rewrite（这三个子代理必填）" },
        "scope": { "type": "string", "enum": ["whole", "volume", "chapters"], "description": "outline 专有：任务范围（缺省按是否给 chapter_range 推断）" },
        "volume_index": { "type": "integer", "description": "outline 专有：scope=volume 时的卷序号" },
        "character_names": { "type": "array", "items": { "type": "string" }, "description": "character 专有：目标人物名" },
        "round": { "type": "integer", "minimum": 1, "maximum": 3, "description": "委派轮次，默认 1" },
        "previous_result": { "type": "string", "description": "round≥2 必填：上一轮子代理响应 JSON 原文" }
      },
      "required": ["subagent", "task", "work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"subagent\":\"outline\",\"task\":\"…\",\"work_id\":\"…\"}。${gSubagentListHint()}")

        // ---- 1. 基础校验（全部失败可见，附子代理清单） ----
        val subagent = args.optString("subagent", "").trim()
        if (subagent !in SUBAGENT_TITLES) {
            return gError(
                GErr.INVALID_ARGS,
                "subagent 不合法：「$subagent」",
                "取值须为 outline/character/foreshadow/polish/review/consistency。${gSubagentListHint()}",
            )
        }
        val task = args.optString("task", "").trim()
        if (task.isBlank()) return gError(GErr.INVALID_ARGS, "task 不能为空", "写清要子代理做什么，如「为第 81-100 章展开细纲」")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val round = args.optInt("round", 1)
        if (round < 1 || round > 3) return gError(GErr.INVALID_ARGS, "round 取值 1-3，当前：$round", "首轮委派传 1；迭代轮 round+1 并携带 previous_result")

        val previousResult = args.optString("previous_result", "").trim()
        if (round >= 2 && previousResult.isBlank()) {
            return gError(GErr.INVALID_ARGS, "round≥2 必须携带 previous_result（上一轮响应 JSON）", "把上轮 novel_delegate_subagent 的返回原文填入 previous_result")
        }

        // mode 必填与枚举校验（§2.3：character/foreshadow/polish 的 mode 必填）
        val mode = args.optString("mode", "").trim()
        val expectedModes = mapOf(
            "character" to listOf("create", "enrich", "derive_relationships"),
            "foreshadow" to listOf("plan", "audit", "both"),
            "polish" to listOf("suggest", "rewrite"),
        )
        if (subagent in expectedModes) {
            val allowed = expectedModes[subagent]!!
            if (mode !in allowed) {
                return gError(
                    GErr.INVALID_ARGS,
                    "subagent=$subagent 的 mode 必须为 ${allowed.joinToString("/")}，当前：「${mode.ifBlank { "(空)" }}」",
                    "见 DELEGATION.md §2.3。${gSubagentListHint()}",
                )
            }
        } else if (mode.isNotBlank() && subagent !in expectedModes) {
            return gError(GErr.INVALID_ARGS, "subagent=$subagent 无专有 mode，传入的「$mode」会被忽略", "outline/review/consistency 不需要 mode。${gSubagentListHint()}")
        }

        // ---- 2. 零写权限断言 + 加载提示词（缺 zero-write:true 一律拒绝执行） ----
        val (frontmatter, systemPrompt) = gLoadSubagentPrompt(context, subagent)
            ?: return gError(
                "subagent_prompt_missing",
                "子代理提示词 assets/$SUBAGENT_ASSET_DIR/$subagent.md 缺失或不可读",
                "重新安装资产或检查 APK 内 assets 完整性。${gSubagentListHint()}",
            )
        if (!gAssertZeroWrite(frontmatter)) {
            return gError(
                "zero_write_violation",
                "子代理 $subagent 的提示词 frontmatter 未声明 zero-write:true，零写权限断言失败，拒绝执行",
                "修正提示词 frontmatter（补 zero-write: true）后再试。${gSubagentListHint()}",
            )
        }

        // ---- 3. 组装请求信封（§2.1） ----
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val targets = gBuildTargets(subagent, args) ?: return gError(
            GErr.INVALID_ARGS,
            "subagent=$subagent 的 targets 组装失败（scope/mode/chapter_range 不符合 §2.3）",
            "outline.scope=whole/volume/chapters；consistency 需 chapter_range{from,to}。${gSubagentListHint()}",
        )
        val delegationId = gNewDelegationId()

        val envelope = JSONObject().apply {
            put("delegation_id", delegationId)
            put("subagent", subagent)
            put("round", round)
            put("task", task)
            put("work", JSONObject().apply {
                put("work_id", work.id)
                put("title", work.title)
                put("genre", work.genre)
            })
            put("targets", targets)

            // context：chapter_id 给了就预取该章正文（§3：预取失败不用假数据，直接报错）
            val chapterId = args.optString("chapter_id", "").trim()
            if (chapterId.isNotBlank()) {
                val ch = work.chapters.firstOrNull { it.id == chapterId }
                    ?: return gChapterNotFound(work.title, chapterId, gOutlineHint(workId))
                val content = repo.readChapterContent(workId, chapterId)
                put("context", JSONObject().apply {
                    put("chapter", JSONObject().apply {
                        put("chapter_id", ch.id)
                        put("title", ch.title)
                        put("content", content)
                        put("summary", "")
                    })
                })
            }

            put("constraints", JSONObject().apply {
                put("max_output_chars", 8000)
            })

            // previous_round：round≥2 时把上轮响应透传（delegation_id/round 回显由上轮信封携带）
            if (round >= 2) {
                val prev = runCatching { JSONObject(previousResult) }.getOrNull()
                put("previous_round", JSONObject().apply {
                    put("round", prev?.optInt("round", round - 1) ?: (round - 1))
                    put("summary", prev?.optString("summary", "") ?: "")
                    put("output", prev?.toString() ?: previousResult)
                })
            }
        }

        // ---- 4. 模型组解析：钉住的组 > 委派请求的意图 > 当前会话模型 ----
        val groupRepo = QuroModelGroupRepository(context)
        val groupSettings = groupRepo.load()
        val intent = QuroModelIntent.fromKey(args.optString("model_group", "").trim())
        val pinnedGroup = groupSettings.groupForAgent(subagent)
        val providers = QuroMultiProviderRepository(context)
        val globalCfg = QuroModelConfigRepository(context).load()
        val parentTarget = QuroModelTarget(
            baseUrl = globalCfg.baseUrl,
            apiKey = globalCfg.apiKey,
            model = globalCfg.model,
        )
        val modelTarget = QuroModelGroupRouter.resolveForCall(
            intent = intent,
            pinnedGroup = pinnedGroup,
            settings = groupSettings,
            providers = providers,
            parentTarget = parentTarget,
        )
        val modelLabel = if (modelTarget.groupName.isNotBlank()) {
            modelTarget.groupName + " · " + modelTarget.model
        } else {
            modelTarget.model
        }

        // ---- 5. 每轮调用一次 LLM：system = 子代理提示词正文，user = 请求信封 JSON ----
        return when (val r = NovelWriteSupport.callLlm(context, systemPrompt, envelope.toString(), temperature = 0.4f, target = modelTarget)) {
            is LlmOutcome.Ok -> {
                val parsed = gExtractResponseJson(r.text)
                if (parsed != null) {
                    // 契约硬约束：delegation_id/subagent/round 必须回显；LLM 漏了由本工具补齐
                    if (!parsed.has("delegation_id")) parsed.put("delegation_id", delegationId)
                    if (!parsed.optString("delegation_id", "").isBlank() && parsed.optString("delegation_id") != delegationId) {
                        parsed.put("delegation_id_echo_mismatch", true)
                        parsed.put("delegation_id", delegationId)
                    }
                    if (!parsed.has("subagent")) parsed.put("subagent", subagent)
                    if (!parsed.has("round")) parsed.put("round", round)
                    // 模型来源可观测：用了哪一档 / 是否因钉住的组不可用而回落
                    parsed.put("model_used", modelLabel)
                    if (modelTarget.pinnedGroupUnavailable) {
                        parsed.put("model_group_unavailable", true)
                        parsed.put(
                            "model_group_note",
                            "为该子代理指定的模型组不可用（组内无可用模型或 provider 已删除），已回落到当前会话模型。",
                        )
                    }
                    return parsed.toString()
                }
                // 非信封输出：原样透传、明示未解析，不伪造 payload 冒充成功（工程禁令：无兜底）
                JSONObject().apply {
                    put("ok", true)
                    put("delegation_id", delegationId)
                    put("subagent", subagent)
                    put("round", round)
                    put("status", "ok")
                    put("raw_output", r.text)
                    put("message", "子代理返回了非 JSON 信封的文本，已原样透传（未伪造 payload）；请人工并入结论。")
                }.toString()
            }
            is LlmOutcome.Failed -> gError(
                "llm_error",
                r.message,
                "检查「设置 → 模型配置」的 API Key 后重试；或放弃委派改由主 AI 直接完成该任务。${gSubagentListHint()}",
            )
        }
    }
}
