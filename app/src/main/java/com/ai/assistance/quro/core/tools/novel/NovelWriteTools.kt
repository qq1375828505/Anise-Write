package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.novel.NovelChapter
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.NovelWork
import com.ai.assistance.quro.core.novel.countWords
import com.ai.assistance.quro.core.tools.QuroTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * PenAi 网文写作三工具（Phase 2 · AI 续写最小闭环）。
 *
 * 工具族（规划书 §6.1 章节域，Phase 2 只落地这三个）：
 *  - [NovelWriteTool]   `novel_write`   ：按细纲写新章正文，整章落盘；
 *  - [NovelContinueTool] `novel_continue`：从当前正文尾部无缝续写并追加落盘；
 *  - [NovelPolishTool]  `novel_polish`  ：按档位润色整章，落盘前自动备份原稿。
 *
 * ## 实现范式（照基座既有工具，不猜 API）
 *  - 工具契约：`QuroTool`（name/description/parametersJson/run，见 core/tools/QuroTool.kt）；
 *  - LLM 调用：`QuroLlmClient().chat(...)` + `runBlocking`，配置走 `QuroModelConfigRepository`
 *    全局主模型（范式同 core/tools/TranslateTool.kt）；
 *  - 数据读写：Phase 1 的 [NovelRepository]（`filesDir/novel/`，正文 `.md` + JSON 索引），
 *    只读引用其公开 API，不修改数据层；
 *  - 文风令牌 / 人设：随包 assets（`assets/prompts/penai-novel/`），运行时读取注入提示词。
 *
 * ## 已知约束（写工具前必读）
 *  - 基座工具执行有 60s 硬超时（QuroTool.kt TOOL_EXEC_TIMEOUT_MS）：超长章节（4000 字以上）
 *    或慢模型可能被腰斩，故默认目标字数 2000，并在报告遗留问题中登记；
 *  - 云端 LLM 客户端不支持端侧本地模型（MNN/llama.cpp 走 QuroAssistant.routeLocal），
 *    未配 API Key 时返回明确引导，与 translate 工具行为一致。
 *
 * ## 注册接线（七步法，规划书 §2.3）
 *  1. 本文件三个工具类（新文件，Phase 2 独占）；
 *  2. `QuroBuiltInTools.buildQuroRegistry()` 注册三行 + NovelPersona.ensureInstalled 一行；
 *  3. `QuroTool.coreSpecs()` 的 coreNames 加入三个工具名（必写，否则默认配置下模型不可见）；
 *  4. `QuroToolRouter.ALWAYS_ON` 加入三个工具名（常驻，免 tool_router 检索）；
 *  5. （建议）ToolCapabilityDirectory handbook / QuroToolUsageHints 补条目。
 */
internal const val NOVEL_WRITE_TOOLS_TAG = "NovelWriteTools"

// ─────────────────────────── 公共支撑 ───────────────────────────

/** LLM 调用结果（工具内部用：区分"失败"与"空文本"）。 */
internal sealed interface LlmOutcome {
    data class Ok(val text: String) : LlmOutcome
    data class Failed(val message: String) : LlmOutcome
}

/** 文风令牌装载结果。 */
internal sealed interface StyleDirective {
    /** [text] 为 null 表示 style=none，不注入。 */
    data class Ready(val text: String?) : StyleDirective
    data class Broken(val message: String) : StyleDirective
}

/** 作品/章节指认结果。 */
internal sealed interface NovelResolve {
    data class Ok(val work: NovelWork, val chapter: NovelChapter) : NovelResolve
    data class Fail(val message: String) : NovelResolve
}

/**
 * 三工具共享的纯逻辑 + IO 助手。
 *
 * 解析 / 装配类方法全部为**纯函数**（不碰 Context 与磁盘），可在 JVM 单测直接覆盖。
 */
internal object NovelWriteSupport {

    /** 文风令牌资产路径（随包 assets）。 */
    const val STYLE_ASSET_PATH = "prompts/penai-novel/style_tokens.md"

    /** light 档只注入的节号（对应 style_tokens.md 的 §1/§2/§5）。 */
    val LIGHT_SECTIONS: Set<String> = setOf("1", "2", "5")

    private val SECTION_RE = Regex("^## §(\\S+)")

    /** 章节序号指认："3" / "第3章" / "第三章" / "第 3 章" → 3；无法解析返回 null。 */
    fun parseChapterRef(arg: String): Int? {
        val s = arg.replace(" ", "").replace("　", "")
            .removePrefix("第").removeSuffix("章").removePrefix("《").removeSuffix("》")
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return if (it >= 1) it else null }
        return parseChineseNumber(s)?.takeIf { it >= 1 }
    }

    /** 中文数字解析（支持到 9999，覆盖章节序号常见区间；非法字符返回 null）。 */
    fun parseChineseNumber(s: String): Int? {
        if (s.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        var section = 0
        var number = 0
        for (ch in s) {
            when (ch) {
                in digits -> number = digits.getValue(ch)
                '十' -> {
                    section += (if (number == 0) 1 else number) * 10
                    number = 0
                }
                '百' -> {
                    section += (if (number == 0) 1 else number) * 100
                    number = 0
                }
                '千' -> {
                    section += (if (number == 0) 1 else number) * 1000
                    number = 0
                }
                else -> return null
            }
        }
        return section + number
    }

    /** 作品内章节全局顺序：卷 order → 章 order → 创建时间（"第 N 章"按此计数）。 */
    fun orderedChapters(work: NovelWork): List<NovelChapter> =
        work.volumes.sortedWith(compareBy({ it.order }, { it.createdAt }))
            .flatMap { v -> work.chaptersOfVolume(v.id) }

    /**
     * 指认作品与章节。
     *
     * 匹配优先级：精确 ID → 精确名称 → 序号（仅章节）→ 名称包含（唯一命中时）。
     * 失败时返回带候选清单的引导文案（模型应原样转告用户，不允许猜 ID 重试）。
     */
    fun resolveWorkAndChapter(
        repo: NovelRepository,
        workArg: String,
        chapterArg: String,
    ): NovelResolve {
        val works = repo.loadWorks()
        if (works.isEmpty()) {
            return NovelResolve.Fail("还没有任何作品。请先在小说工作台新建作品与章节，再让 AI 写作。")
        }
        val work = works.firstOrNull { it.id == workArg }
            ?: works.firstOrNull { it.title == workArg }
            ?: works.filter { it.title.contains(workArg, ignoreCase = true) }
                .takeIf { it.size == 1 }?.firstOrNull()
            ?: return NovelResolve.Fail(
                buildString {
                    append("作品不存在：「").append(workArg).append("」。当前作品：")
                    works.forEachIndexed { i, w -> append(i + 1).append(")《").append(w.title).append("》") }
                    append("。请用作品名或 ID 重新指认。")
                }
            )

        val ordered = orderedChapters(work)
        if (ordered.isEmpty()) {
            return NovelResolve.Fail("《${work.title}》还没有任何章节。请先在小说工作台新建章节。")
        }
        val chapter = ordered.firstOrNull { it.id == chapterArg }
            ?: ordered.firstOrNull { it.title == chapterArg }
            ?: parseChapterRef(chapterArg)?.let { n -> ordered.getOrNull(n - 1) }
            ?: ordered.filter { it.title.contains(chapterArg, ignoreCase = true) }
                .takeIf { it.size == 1 }?.firstOrNull()
            ?: return NovelResolve.Fail(
                buildString {
                    append("《").append(work.title).append("》中不存在章节：「").append(chapterArg)
                        .append("」。共 ").append(ordered.size).append(" 章：")
                    ordered.take(MAX_LISTED_CHAPTERS).forEachIndexed { i, c ->
                        append(i + 1).append(".《").append(c.title).append("》")
                    }
                    if (ordered.size > MAX_LISTED_CHAPTERS) append("……")
                    append("。请用章节名或序号重新指认。")
                }
            )
        return NovelResolve.Ok(work, chapter)
    }

    /** 章节在作品内的全局序号（1 基；用于提示词与汇报）。 */
    fun globalOrdinal(work: NovelWork, chapter: NovelChapter): Int =
        orderedChapters(work).indexOfFirst { it.id == chapter.id } + 1

    /**
     * 上一章正文结尾（衔接用）。
     *
     * @param maxChars 截断长度（从尾部取，超长前置省略号）
     * @return 无上一章 / 上一章为空时返回空串
     */
    fun previousChapterTail(
        repo: NovelRepository,
        work: NovelWork,
        chapter: NovelChapter,
        maxChars: Int,
    ): String {
        val ordered = orderedChapters(work)
        val idx = ordered.indexOfFirst { it.id == chapter.id }
        if (idx <= 0) return ""
        val text = repo.readChapterContent(work.id, ordered[idx - 1].id).trim()
        if (text.isBlank()) return ""
        return if (text.length <= maxChars) text else "……" + text.takeLast(maxChars)
    }

    /**
     * 装载文风令牌。
     *
     * @param style `standard`=全文 / `light`=§1+§2+§5 核心子集 / `none`=不注入
     */
    fun loadStyleDirective(context: Context, style: String): StyleDirective = when (style) {
        "none" -> StyleDirective.Ready(null)
        "standard" -> {
            val md = readAssetOrNull(context, STYLE_ASSET_PATH)
            if (md == null || md.isBlank()) {
                StyleDirective.Broken("文风令牌资产缺失或为空：assets/$STYLE_ASSET_PATH")
            } else {
                StyleDirective.Ready(md.trim())
            }
        }
        "light" -> {
            val md = readAssetOrNull(context, STYLE_ASSET_PATH)
            if (md == null || md.isBlank()) {
                StyleDirective.Broken("文风令牌资产缺失或为空：assets/$STYLE_ASSET_PATH")
            } else {
                val sections = extractSections(md, LIGHT_SECTIONS)
                val missing = LIGHT_SECTIONS - sections.keys
                if (missing.isNotEmpty()) {
                    StyleDirective.Broken("文风令牌资产损坏：缺少 §${missing.joinToString("、§")} 节")
                } else {
                    StyleDirective.Ready(
                        buildString {
                            appendLine("【文风令牌 · 核心子集（禁用词 / 句式节奏 / 章末钩子）】")
                            LIGHT_SECTIONS.forEach { k -> appendLine(sections.getValue(k)).appendLine() }
                        }.trim()
                    )
                }
            }
        }
        else -> StyleDirective.Broken("style 只支持 standard / light / none，收到：$style")
    }

    /**
     * 从文风令牌 Markdown 中抽取指定节（按 `## §N` 标题切块）。
     * 纯函数；节号集合外的内容忽略。供单测与 light 档使用。
     */
    fun extractSections(md: String, wanted: Set<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        val buf = StringBuilder()
        fun flush() {
            val k = key
            if (k != null && buf.isNotBlank() && k in wanted) out[k] = buf.toString().trim()
            buf.clear()
        }
        for (line in md.lines()) {
            val m = SECTION_RE.find(line)
            if (m != null) {
                flush()
                key = m.groupValues[1]
            } else if (key != null) {
                buf.appendLine(line)
            }
        }
        flush()
        return out
    }

    /** 读随包 assets 文本；失败（缺失/IO）返回 null（由调用方转成明确错误，不静默兜底）。 */
    fun readAssetOrNull(context: Context, assetPath: String): String? = runCatching {
        context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 去掉模型输出可能携带的 Markdown 代码围栏（```...```）。纯函数。 */
    fun stripCodeFences(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        val firstNewline = t.indexOf('\n')
        if (firstNewline < 0) return t.removePrefix("```").removeSuffix("```").trim()
        val body = t.substring(firstNewline + 1)
        val lastFence = body.lastIndexOf("```")
        return (if (lastFence >= 0) body.substring(0, lastFence) else body).trim()
    }

    /**
     * 续写防重检测：新增部分的开头是否与已有正文尾部重复
     * （模型常见故障——把原文最后几句重新写一遍）。纯函数。
     */
    fun startsWithOverlap(existing: String, addition: String, probe: Int = 15): Boolean {
        if (addition.length < probe) return false
        val head = addition.take(probe)
        return existing.takeLast(OVERLAP_TAIL_CHARS).contains(head)
    }

    /** 续写合并：与现有正文之间补足空行分隔。纯函数。 */
    fun mergeContinuation(existing: String, addition: String): String = when {
        existing.isBlank() -> addition
        existing.endsWith("\n\n") -> existing + addition
        existing.endsWith("\n") -> existing + "\n" + addition
        else -> "$existing\n\n$addition"
    }

    /**
     * 调云端 LLM（全局主模型配置）。
     *
     * 范式同 [com.ai.assistance.quro.core.tools.TranslateTool]：
     * `QuroModelConfigRepository.load()` → `QuroLlmClient().chat(...)`（runBlocking 包裹，
     * 工具本身是同步执行体）。temperature 按任务硬编码（写作求发散、润色求收敛），
     * maxTokens 沿用用户配置（默认 65536，保证长章不被腰斩）。
     */
    fun callLlm(
        context: Context,
        system: String,
        user: String,
        temperature: Float,
        target: QuroModelTarget? = null,
    ): LlmOutcome {
        val cfg = QuroModelConfigRepository(context).load()
        // 有指定目标（模型组解析结果）就用它，否则沿用全局模型配置
        val useTarget = target != null && target.baseUrl.isNotBlank() && target.apiKey.isNotBlank()
        if (!useTarget && cfg.apiKey.isBlank()) {
            return LlmOutcome.Failed(
                "未配置模型 API Key。请在「设置 → 模型配置」中填写 API Key 后再使用网文写作工具。"
            )
        }
        return try {
            val result = runBlocking {
                QuroLlmClient().chat(
                    baseUrl = if (useTarget) target!!.baseUrl else cfg.baseUrl,
                    apiKey = if (useTarget) target!!.apiKey else cfg.apiKey,
                    model = if (useTarget) target!!.model else cfg.model,
                    messages = listOf(
                        QuroChatMessage(role = "system", content = system),
                        QuroChatMessage(role = "user", content = user),
                    ),
                    temperature = temperature,
                    maxTokens = cfg.maxTokens,
                )
            }
            when (result) {
                is QuroLlmResult.Text -> result.content.trim().takeIf { it.isNotBlank() }
                    ?.let { LlmOutcome.Ok(it) }
                    ?: LlmOutcome.Failed("模型返回了空内容，请重试。")
                is QuroLlmResult.ToolCalls -> (result.content ?: "").trim().takeIf { it.isNotBlank() }
                    ?.let { LlmOutcome.Ok(it) }
                    ?: LlmOutcome.Failed("模型只发起了工具调用、没有返回文本，请重试。")
                is QuroLlmResult.Error -> LlmOutcome.Failed(result.message)
            }
        } catch (e: Exception) {
            Log.e(NOVEL_WRITE_TOOLS_TAG, "LLM 调用异常", e)
            LlmOutcome.Failed("模型调用异常：${e.message}")
        }
    }

    /** 汇报用的章节定位串，如 `《书名》第3章「章名」`。 */
    fun chapterLabel(work: NovelWork, chapter: NovelChapter): String =
        "《${work.title}》第${globalOrdinal(work, chapter)}章「${chapter.title}」"

    private const val MAX_LISTED_CHAPTERS = 20
    private const val OVERLAP_TAIL_CHARS = 300
}

// ─────────────────────────── novel_write ───────────────────────────

/**
 * novel_write：按细纲写新章正文并整章落盘。
 *
 * 章节已有正文时**不覆盖**，除非显式 `overwrite=true`（人格卡安全约束：不可逆操作需用户确认）。
 */
class NovelWriteTool : QuroTool {
    override val name = "novel_write"
    override val description = """按细纲写新章正文并整章落盘（网文写作·写新章）。
入参：work=作品（ID 或作品名）、chapter=章节（ID、章节名或"第几章"）、outline=本章细纲（必含场景/人物/冲突/章末钩子四要素）、style=文风档位（standard 默认 / light 核心子集 / none 不注入）、target_words=目标字数（默认 2000，±20%）、overwrite=章节已有正文时是否覆盖（默认 false，覆盖即丢旧稿，须用户明确确认）、extra_requirements=额外要求（可选）。
当用户说「写第 N 章」「按这个细纲写一章」「新写一章」时使用。
返回：落盘结果（章节定位/字数/状态流转）+ 正文全文。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "work":{"type":"string","description":"作品：ID 或作品名，如「我的第一本书」"},
            "chapter":{"type":"string","description":"章节：ID、章节名或序号，如「3」「第3章」「第三章」"},
            "outline":{"type":"string","description":"本章细纲，必含四要素：场景（时地）、人物（谁想要什么）、冲突（阻力与反转）、章末钩子（结尾留什么）"},
            "style":{"type":"string","enum":["standard","light","none"],"description":"文风令牌档位：standard=网文语言规范全文（默认）；light=只注入禁用词/句式节奏/章末钩子核心子集；none=不注入"},
            "target_words":{"type":"integer","description":"目标字数（中文计字），默认 2000，允许 200-20000"},
            "overwrite":{"type":"boolean","description":"章节已有正文时是否整章覆盖（默认 false）。覆盖后旧稿不保留，必须用户明确确认后才可传 true"},
            "extra_requirements":{"type":"string","description":"用户额外要求（可选），如「这章多写打斗」「结尾要虐」"}
        },
        "required":["work","chapter","outline"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return "novel_write 参数解析失败（需要 JSON：{\"work\":\"...\",\"chapter\":\"...\",\"outline\":\"...\"}）"
        val workArg = args.optString("work", "").trim()
        val chapterArg = args.optString("chapter", "").trim()
        val outline = args.optString("outline", "").trim()
        if (workArg.isEmpty()) return "novel_write 需要 work（作品 ID 或作品名）"
        if (chapterArg.isEmpty()) return "novel_write 需要 chapter（章节 ID、章节名或第几章）"
        if (outline.isEmpty()) {
            return "novel_write 需要 outline（本章细纲：场景/人物/冲突/章末钩子）。用户没给细纲时，先拟定细纲请用户确认。"
        }
        val style = args.optString("style", "standard").trim().ifBlank { "standard" }
        val targetWords = args.optInt("target_words", DEFAULT_TARGET_WORDS)
        if (targetWords !in 200..20_000) {
            return "novel_write 的 target_words 需在 200–20000 之间，收到：$targetWords"
        }
        val overwrite = args.optBoolean("overwrite", false)
        val extra = args.optString("extra_requirements", "").trim()

        val repo = NovelRepository.fromContext(context)
        return when (val r = NovelWriteSupport.resolveWorkAndChapter(repo, workArg, chapterArg)) {
            is NovelResolve.Fail -> r.message
            is NovelResolve.Ok ->
                writeChapter(context, repo, r.work, r.chapter, outline, style, targetWords, overwrite, extra)
        }
    }

    private fun writeChapter(
        context: Context,
        repo: NovelRepository,
        work: NovelWork,
        chapter: NovelChapter,
        outline: String,
        style: String,
        targetWords: Int,
        overwrite: Boolean,
        extra: String,
    ): String {
        val existing = repo.readChapterContent(work.id, chapter.id)
        val existingWords = countWords(existing)
        if (existing.isNotBlank() && !overwrite) {
            return "${NovelWriteSupport.chapterLabel(work, chapter)}已有正文 ${existingWords} 字。" +
                "novel_write 会整章覆盖且不保留旧稿——确认覆盖请携带 overwrite=true 重试；" +
                "若只想接着写，请改用 novel_continue。"
        }
        val directive = when (val d = NovelWriteSupport.loadStyleDirective(context, style)) {
            is StyleDirective.Broken -> return d.message
            is StyleDirective.Ready -> d.text
        }

        val prevTail = NovelWriteSupport.previousChapterTail(repo, work, chapter, PREV_TAIL_CHARS)
        val system = buildString {
            appendLine("你是资深网文作者，正在为一部中文连载网文写一章正文。")
            if (directive != null) {
                appendLine("严格遵守以下文风规范（写作时执行，不要在正文里复述规则）：")
                appendLine(directive)
            }
        }
        val user = buildString {
            appendLine("【作品】《${work.title}》" + if (work.genre.isNotBlank()) "（类型：${work.genre}）" else "")
            appendLine("【章节】第${NovelWriteSupport.globalOrdinal(work, chapter)}章「${chapter.title}」")
            appendLine("【本章细纲】")
            appendLine(outline)
            if (prevTail.isNotBlank()) {
                appendLine("【前文衔接】上一章正文结尾如下，本章从其后继续；衔接语气与未收的线索，但不得重复其内容：")
                appendLine(prevTail)
            }
            appendLine("【要求】")
            appendLine("- 按细纲写完本章正文，目标字数 ${targetWords} 字左右（±20% 可接受）。")
            appendLine("- 只输出正文本身：不要章标题、不要小标题、不要任何解释、不要 Markdown 代码围栏。")
            appendLine("- 从场景切入，直接开写。")
            if (extra.isNotBlank()) appendLine("- 用户额外要求：$extra")
        }

        return when (val outcome = NovelWriteSupport.callLlm(context, system.toString(), user.toString(), TEMPERATURE_WRITE)) {
            is LlmOutcome.Failed -> "novel_write 失败：${outcome.message}"
            is LlmOutcome.Ok -> {
                val text = NovelWriteSupport.stripCodeFences(outcome.text)
                if (text.isBlank()) {
                    return "novel_write 失败：模型未产出正文，请重试或调整细纲。"
                }
                val before = chapter.status
                val updated = repo.writeChapterContent(work.id, chapter.id, text)
                buildString {
                    append("【写入完成】").append(NovelWriteSupport.chapterLabel(work, chapter))
                        .append("｜正文 ").append(countWords(text)).append(" 字")
                    if (before != updated.status) {
                        append("｜状态：${before.key} → ${updated.status.key}")
                    }
                    if (overwrite && existingWords > 0) {
                        append("｜已覆盖旧稿（旧稿 ").append(existingWords).append(" 字，未保留）")
                    }
                    appendLine()
                    appendLine("【正文】")
                    append(text)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TARGET_WORDS = 2000
        const val PREV_TAIL_CHARS = 600
        const val TEMPERATURE_WRITE = 0.8f
    }
}

// ─────────────────────────── novel_continue ───────────────────────────

/**
 * novel_continue：从当前正文尾部无缝续写并追加落盘。
 *
 * 章节为空时引导改用 novel_write（续写必须已有断点）。
 */
class NovelContinueTool : QuroTool {
    override val name = "novel_continue"
    override val description = """从章节当前正文尾部无缝续写并追加落盘（网文写作·续写）。
入参：work=作品（ID 或作品名）、chapter=章节（ID、章节名或"第几章"）、instruction=续写指令（接下来发生什么）、target_words=本次续写目标字数（默认 1000）、style=文风档位（standard 默认 / light / none）。
当用户说「接着写」「继续写」「往下写」「没写完继续」时使用。
返回：追加结果（新增字数/全章字数）+ 本次新增的正文。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "work":{"type":"string","description":"作品：ID 或作品名"},
            "chapter":{"type":"string","description":"章节：ID、章节名或序号，如「3」「第3章」"},
            "instruction":{"type":"string","description":"续写指令：接下来要发生什么，如「主角识破埋伏，反将一军」「收束本章，留章末钩子」"},
            "target_words":{"type":"integer","description":"本次续写目标字数（中文计字），默认 1000，允许 200-20000"},
            "style":{"type":"string","enum":["standard","light","none"],"description":"文风令牌档位：standard=网文语言规范全文（默认）；light=核心子集；none=不注入"}
        },
        "required":["work","chapter","instruction"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return "novel_continue 参数解析失败（需要 JSON：{\"work\":\"...\",\"chapter\":\"...\",\"instruction\":\"...\"}）"
        val workArg = args.optString("work", "").trim()
        val chapterArg = args.optString("chapter", "").trim()
        val instruction = args.optString("instruction", "").trim()
        if (workArg.isEmpty()) return "novel_continue 需要 work（作品 ID 或作品名）"
        if (chapterArg.isEmpty()) return "novel_continue 需要 chapter（章节 ID、章节名或第几章）"
        if (instruction.isEmpty()) return "novel_continue 需要 instruction（续写指令：接下来发生什么）"
        val style = args.optString("style", "standard").trim().ifBlank { "standard" }
        val targetWords = args.optInt("target_words", DEFAULT_TARGET_WORDS)
        if (targetWords !in 200..20_000) {
            return "novel_continue 的 target_words 需在 200–20000 之间，收到：$targetWords"
        }

        val repo = NovelRepository.fromContext(context)
        return when (val r = NovelWriteSupport.resolveWorkAndChapter(repo, workArg, chapterArg)) {
            is NovelResolve.Fail -> r.message
            is NovelResolve.Ok ->
                continueChapter(context, repo, r.work, r.chapter, instruction, style, targetWords)
        }
    }

    private fun continueChapter(
        context: Context,
        repo: NovelRepository,
        work: NovelWork,
        chapter: NovelChapter,
        instruction: String,
        style: String,
        targetWords: Int,
    ): String {
        val existing = repo.readChapterContent(work.id, chapter.id)
        if (existing.isBlank()) {
            return "${NovelWriteSupport.chapterLabel(work, chapter)}当前没有正文，无从续写。" +
                "请改用 novel_write 按细纲写首版。"
        }
        val directive = when (val d = NovelWriteSupport.loadStyleDirective(context, style)) {
            is StyleDirective.Broken -> return d.message
            is StyleDirective.Ready -> d.text
        }

        val tail = existing.takeLast(TAIL_CHARS)
        val system = buildString {
            appendLine("你是资深网文作者，正在为一部中文连载网文续写一章正文。")
            if (directive != null) {
                appendLine("严格遵守以下文风规范（写作时执行，不要在正文里复述规则）：")
                appendLine(directive)
            }
        }
        val user = buildString {
            appendLine("【作品】《${work.title}》" + if (work.genre.isNotBlank()) "（类型：${work.genre}）" else "")
            appendLine("【章节】第${NovelWriteSupport.globalOrdinal(work, chapter)}章「${chapter.title}」")
            appendLine("【现有正文结尾】（断点在此，以下内容已存在，不得重复）")
            appendLine(tail)
            appendLine("【续写指令】")
            appendLine(instruction)
            appendLine("【要求】")
            appendLine("- 从断点处无缝续写，只输出新增部分，目标 ${targetWords} 字左右（±20% 可接受）。")
            appendLine("- 不得重复上文任何句子、不得总结前文、不得解释你在做什么。")
            appendLine("- 指令要求收束本章时才写章末钩子收尾，否则写到指令要求的内容即止。")
            appendLine("- 只输出正文本身：不要小标题、不要解释、不要 Markdown 代码围栏。")
        }

        return when (val outcome = NovelWriteSupport.callLlm(context, system.toString(), user.toString(), TEMPERATURE_CONTINUE)) {
            is LlmOutcome.Failed -> "novel_continue 失败：${outcome.message}"
            is LlmOutcome.Ok -> {
                val addition = NovelWriteSupport.stripCodeFences(outcome.text)
                if (addition.isBlank()) {
                    return "novel_continue 失败：模型未产出续写内容，请重试。"
                }
                if (NovelWriteSupport.startsWithOverlap(existing, addition)) {
                    return "novel_continue 失败：续写内容开头与原文重复（模型把结尾又写了一遍），请重试。"
                }
                val merged = NovelWriteSupport.mergeContinuation(existing, addition)
                repo.writeChapterContent(work.id, chapter.id, merged)
                val added = countWords(addition)
                buildString {
                    append("【续写完成】").append(NovelWriteSupport.chapterLabel(work, chapter))
                        .append("｜新增 ").append(added).append(" 字｜全章现 ").append(countWords(merged)).append(" 字")
                    appendLine()
                    appendLine("【新增正文】")
                    append(addition)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TARGET_WORDS = 1000
        const val TAIL_CHARS = 1200
        const val TEMPERATURE_CONTINUE = 0.8f
    }
}

// ─────────────────────────── novel_polish ───────────────────────────

/**
 * novel_polish：按档位润色整章正文。
 *
 * 安全设计：落盘前把原稿备份为同目录 `{chapterId}.md.polish-bak`（目录布局契约见
 * [NovelRepository] KDoc），备份失败则中止润色——保证操作可逆。
 */
class NovelPolishTool : QuroTool {
    override val name = "novel_polish"
    override val description = """润色指定章节的整章正文并落盘（网文写作·润色精修）。
入参：work=作品（ID 或作品名）、chapter=章节（ID、章节名或"第几章"）、level=润色档位（light 轻度 / standard 标准 / deep 深度）、focus=聚焦维度（可选，如「对话」「节奏」「描写」）。
当用户说「润色这章」「精修一下」「改一遍」时使用。润色以网文语言规范（去 AI 味/句式节奏/章末钩子）为目标，不改变情节、人物与设定。
返回：修改说明 + 润色后全文 + 原稿备份路径 + 字数变化。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "work":{"type":"string","description":"作品：ID 或作品名"},
            "chapter":{"type":"string","description":"章节：ID、章节名或序号，如「3」「第3章」"},
            "level":{"type":"string","enum":["light","standard","deep"],"description":"润色档位：light=只改错别字语病与明显 AI 腔词汇；standard=改 AI 腔与翻译腔、调句式节奏、补感官细节；deep=在 standard 基础上重写表达吃力的段落"},
            "focus":{"type":"string","description":"聚焦维度（可选），如「对话」「节奏」「描写」「开头」；缺省为全篇均衡润色"}
        },
        "required":["work","chapter","level"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return "novel_polish 参数解析失败（需要 JSON：{\"work\":\"...\",\"chapter\":\"...\",\"level\":\"standard\"}）"
        val workArg = args.optString("work", "").trim()
        val chapterArg = args.optString("chapter", "").trim()
        val level = args.optString("level", "standard").trim().ifBlank { "standard" }
        if (workArg.isEmpty()) return "novel_polish 需要 work（作品 ID 或作品名）"
        if (chapterArg.isEmpty()) return "novel_polish 需要 chapter（章节 ID、章节名或第几章）"
        if (level !in LEVELS) {
            return "novel_polish 的 level 只支持 ${LEVELS.joinToString(" / ")}，收到：$level"
        }
        val focus = args.optString("focus", "").trim()

        val repo = NovelRepository.fromContext(context)
        return when (val r = NovelWriteSupport.resolveWorkAndChapter(repo, workArg, chapterArg)) {
            is NovelResolve.Fail -> r.message
            is NovelResolve.Ok -> polishChapter(context, repo, r.work, r.chapter, level, focus)
        }
    }

    private fun polishChapter(
        context: Context,
        repo: NovelRepository,
        work: NovelWork,
        chapter: NovelChapter,
        level: String,
        focus: String,
    ): String {
        val original = repo.readChapterContent(work.id, chapter.id)
        if (original.isBlank()) {
            return "${NovelWriteSupport.chapterLabel(work, chapter)}当前没有正文，无可润色内容。"
        }
        val directive = when (val d = NovelWriteSupport.loadStyleDirective(context, "standard")) {
            is StyleDirective.Broken -> return d.message
            is StyleDirective.Ready -> d.text
        }

        val system = buildString {
            appendLine("你是资深网文编辑，对作者提交的章节正文做润色精修。")
            appendLine("铁律：不改变情节、人物、设定与任何信息；只改表达。")
            appendLine("润色目标（网文语言规范，执行但不要在输出里复述规则）：")
            if (directive != null) appendLine(directive)
            appendLine("输出格式（严格遵守，两个标记都要出现）：")
            appendLine("【修改说明】")
            appendLine("- 每条一行：位置线索 + 改了什么 + 为什么（不超过 10 条）")
            appendLine("【润色后正文】")
            appendLine("完整润色后全文")
        }
        val user = buildString {
            appendLine("【作品】《${work.title}》" + if (work.genre.isNotBlank()) "（类型：${work.genre}）" else "")
            appendLine("【章节】第${NovelWriteSupport.globalOrdinal(work, chapter)}章「${chapter.title}」")
            appendLine("【润色档位】${LEVEL_DESC.getValue(level)}")
            if (focus.isNotBlank()) appendLine("【聚焦维度】$focus（该维度重点改，其余保持）")
            appendLine("【待润色正文】")
            append(original)
        }

        return when (val outcome = NovelWriteSupport.callLlm(context, system.toString(), user.toString(), TEMPERATURE_POLISH)) {
            is LlmOutcome.Failed -> "novel_polish 失败：${outcome.message}"
            is LlmOutcome.Ok -> {
                val parsed = parsePolishOutput(outcome.text)
                    ?: return "novel_polish 失败：模型未按约定格式输出（缺少【润色后正文】标记），请重试。"
                val polished = NovelWriteSupport.stripCodeFences(parsed.polished)
                if (polished.isBlank()) {
                    return "novel_polish 失败：润色结果为空，请重试。"
                }
                // 可逆性保证：先备份原稿，备份失败则中止（不写半截状态）。
                val backup = backupFile(context, work.id, chapter.id)
                val backupOk = runCatching {
                    backup.parentFile?.mkdirs()
                    backup.writeText(original)
                }.isSuccess
                if (!backupOk) {
                    return "novel_polish 失败：原稿备份写入失败（${backup.absolutePath}），已中止润色，原稿未动。"
                }
                repo.writeChapterContent(work.id, chapter.id, polished)
                val beforeWords = countWords(original)
                val afterWords = countWords(polished)
                buildString {
                    append("【润色完成】").append(NovelWriteSupport.chapterLabel(work, chapter))
                        .append("｜档位：").append(level)
                        .append("｜").append(beforeWords).append(" 字 → ").append(afterWords).append(" 字")
                    appendLine()
                    append("【原稿备份】").append(backup.absolutePath)
                    appendLine()
                    appendLine("【修改说明】")
                    appendLine(parsed.changes)
                    appendLine("【润色后正文】")
                    append(polished)
                }
            }
        }
    }

    /** 解析润色输出：`【修改说明】…【润色后正文】…`。纯函数；格式不符返回 null（调用方转失败重试）。 */
    internal fun parsePolishOutput(raw: String): PolishParse? {
        val marker = "【润色后正文】"
        val idx = raw.indexOf(marker)
        if (idx < 0) return null
        val polished = raw.substring(idx + marker.length).trim()
        if (polished.isBlank()) return null
        var changes = raw.substring(0, idx).trim()
        changes = changes.removePrefix("【修改说明】").trim()
        if (changes.isBlank()) changes = "（模型未给出修改说明）"
        return PolishParse(changes, polished)
    }

    internal data class PolishParse(val changes: String, val polished: String)

    /**
     * 润色备份文件路径：`filesDir/novel/chapters/{workId}/{chapterId}.md.polish-bak`。
     *
     * 布局契约见 [NovelRepository] KDoc（正文 `chapters/{workId}/{chapterId}.md`）——
     * 备份与正文同目录，删作品/删卷的级联删除会一并清掉备份，不留孤儿文件。
     * 后续若 Phase 1 仓库层提供官方备份 API，应改调该 API（已在遗留问题登记）。
     */
    internal fun backupFile(context: Context, workId: String, chapterId: String): File =
        File(File(File(context.filesDir, "novel"), "chapters"), "$workId/$chapterId.md.polish-bak")

    private companion object {
        val LEVELS = listOf("light", "standard", "deep")
        val LEVEL_DESC: Map<String, String> = mapOf(
            "light" to "轻度：只改错别字、语病与明显 AI 腔词汇；句式与结构不动",
            "standard" to "标准：改 AI 腔与翻译腔、调句式长短节奏、补感官细节；情节与信息不变",
            "deep" to "深度：在标准基础上，重写表达吃力的段落（重设句式、替换套话、增强现场感）；仍不得改动情节与信息",
        )
        const val TEMPERATURE_POLISH = 0.4f
    }
}

// ─────────────────────────── penai-novel 人设安装 ───────────────────────────

/**
 * penai-novel 人设安装器（Phase 2 · 人格替换）。
 *
 * 激活链路（详见 `assets/prompts/penai-novel/ACTIVATION.md`）：
 * ```
 * assets/prompts/penai-novel/persona.md
 *   → NovelPersona.ensureInstalled()（buildQuroRegistry 内调用，幂等）
 *   → filesDir/quro_personas.json（QuroPersona.roleSetting）
 *   → QuroChatViewModel.buildSystemPrompt() → QuroSoulPromptEngine（注入系统提示词）
 * ```
 *
 * 行为约定：
 *  - 幂等：人格卡已存在（含用户在人格页改过的内容）则不覆盖；
 *  - 只在**首次启动**且当前没有用户显式选择的人格时激活（基座默认 Zorv AI 人格会被替换），
 *    一次性守卫 flag 落 SharedPreferences，之后永不自动改用户的激活选择；
 *  - 任何异常都不允许影响工具注册表构建（内部全量 runCatching）。
 */
internal object NovelPersona {

    /** 固定 ID：跨安装/版本保持稳定（对齐基座 ZORV_AI_ID 的用法）。 */
    const val PERSONA_ID = "persona_penai_novel"

    private const val ASSET_PATH = "prompts/penai-novel/persona.md"
    private const val PREFS = "penai_novel"
    private const val KEY_BOOTSTRAPPED = "persona_bootstrap_v1"
    private const val PERSONA_NAME = "PenAi 网文创作助手"

    fun ensureInstalled(context: Context) {
        runCatching {
            val repo = QuroPersonaRepository(context)
            val all = repo.loadAll()
            if (all.none { it.id == PERSONA_ID }) {
                val roleSetting = readPersonaAsset(context)
                if (roleSetting == null) {
                    Log.e(NOVEL_WRITE_TOOLS_TAG, "penai-novel 人设资产缺失：assets/$ASSET_PATH，跳过人格安装")
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repo.upsert(
                    QuroPersona(
                        id = PERSONA_ID,
                        name = PERSONA_NAME,
                        avatarEmoji = "✍",
                        description = "网文创作专家：懂类型、懂节奏、懂长篇工程",
                        roleSetting = roleSetting,
                        opening = "你好，我是你的网文创作搭档。今天想写哪一章？",
                        chatSetting = "专业编辑口吻，简洁直接；汇报落盘结果不超过三行。",
                        tags = listOf("网文", "创作", "编辑"),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                Log.i(NOVEL_WRITE_TOOLS_TAG, "penai-novel 人格卡已安装（$PERSONA_ID）")
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_BOOTSTRAPPED, false)) return@runCatching
            val activeId = repo.getActiveId()
            if (shouldAutoActivate(all, activeId)) {
                repo.setActiveId(PERSONA_ID)
                Log.i(NOVEL_WRITE_TOOLS_TAG, "penai-novel 人格卡已激活（替换默认人格）")
            }
            prefs.edit().putBoolean(KEY_BOOTSTRAPPED, true).apply()
        }.onFailure { e ->
            Log.e(NOVEL_WRITE_TOOLS_TAG, "penai-novel 人格安装失败（不影响工具注册）", e)
        }
    }

    /**
     * 是否自动激活 penai-novel 人格（纯函数，可单测）。
     *
     * - 人格卡未安装 → 不激活；
     * - 当前无激活人格（空/失效）→ 激活；
     * - 当前激活的是基座默认 Zorv AI 人格（用户从未显式选过）→ 激活；
     * - 用户已显式选择其他人格 → 不打扰。
     */
    fun shouldAutoActivate(existing: List<QuroPersona>, activeId: String): Boolean {
        if (existing.none { it.id == PERSONA_ID }) return false
        val active = existing.firstOrNull { it.id == activeId } ?: return true
        return active.id == QuroPersonaRepository.ZORV_AI_ID
    }

    /**
     * 读取人设资产并剥离开头说明块（取第一个 `---` 分隔线之后的内容为 roleSetting）。
     * 资产缺失或无分隔线返回 null（调用方按缺失处理，不静默兜底）。
     */
    internal fun readPersonaAsset(context: Context): String? {
        val raw = NovelWriteSupport.readAssetOrNull(context, ASSET_PATH) ?: return null
        val lines = raw.lines()
        val idx = lines.indexOfFirst { it.trim() == "---" }
        if (idx < 0) return null
        return lines.drop(idx + 1).joinToString("\n").trim().takeIf { it.isNotBlank() }
    }
}
