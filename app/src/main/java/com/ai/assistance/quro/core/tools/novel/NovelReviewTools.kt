package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * PenAi 网文写作域 · 审查域工具族（Phase 4a）：去 AI 味 / 标题候选 / 爽点报告 / 注水报告。
 *
 * 对应规划书 §6.1「分析」行（映射 novelIDE 四类审查规则）。设计取舍：
 * - **确定性规则优先**（词表命中 / 统计指标），可测、零 token、秒回；
 * - **语义级审查**（情节矛盾、人物弧光、爽点是否真爽）不在本文件——那属于
 *   `novel_delegate_subagent` 的 review/consistency 子代理（LLM 判断），
 *   各工具的 message 会明确指出这一分工，不越界冒充。
 */

/** AI 高频套话词表（命中即提示；按小说语境人工取舍，工具只报不改）。 */
private val HUMANIZE_CLICHES = listOf(
    "嘴角勾起", "眼底闪过", "眼中闪过", "一抹", "空气中弥漫", "浑身上下", "浑身一震",
    "深吸一口气", "心中一动", "目光一凝", "不由得", "顿了顿", "似笑非笑",
    "瞬间", "仿佛", "似乎", "宛如", "犹如", "顷刻间", "刹那间",
    "无可挑剔", "无与伦比", "刻骨铭心", "历历在目", "一言难尽",
)

/** 公式化过渡词（密度过高 = AI 腔）。 */
private val HUMANIZE_TRANSITIONS = listOf("然而", "紧接着", "与此同时", "只见", "显然", "事实上", "值得注意的是", "可以说")

/** 爽点信号词（节奏仪表盘用；是否真爽由 review 子代理语义判断）。 */
private val SHUANGDIAN_LEXICON = listOf(
    "打脸", "突破", "暴涨", "晋升", "反转", "震惊", "慑服", "认怂", "跪", "一鸣惊人",
    "扮猪吃虎", "扬眉吐气", "当众", "翻身", "碾压", "秒杀", "身份揭露", "真实身份",
    "奖励", "系统提示", "升职", "翻盘", "逆天", "屠杀", "一拳", "秒了",
)

/** 悬念/钩子词（章尾 100 字内命中 = 有钩）。 */
private val CLIFFHANGER_LEXICON = listOf("突然", "却", "岂料", "谁知", "下一刻", "轰", "？", "！", "瞳孔", "骤变", "炸开")

/** 注水套话词表。 */
private val WATER_FILLER = listOf(
    "显然", "事实上", "不得不说", "值得注意的是", "总的来说", "可以预见",
    "无论如何", "毫无疑问", "可以说", "众所周知",
)

/** 章节文本统计（爽点/注水报告共用）。 */
private data class ChapterStats(
    val title: String,
    val order: Int,
    val chars: Int,
    val dialogueLines: Int,
    val totalLines: Int,
    val clicheHits: Map<String, Int>,
    val shuangdianHits: Map<String, Int>,
    val waterHits: Map<String, Int>,
    val duplicateSentences: List<String>,
    val repeatedParagraphStarts: Map<String, Int>,
    val hasCliffhanger: Boolean,
    val transitionHits: Map<String, Int>,
)

/** 读章节并做全部统计（正文为空返回 null）。 */
private fun analyzeChapter(context: Context, workId: String, title: String, order: Int, chapterId: String): ChapterStats? {
    val repo = NovelRepository.fromContext(context)
    val content = repo.readChapterContent(workId, chapterId)
    if (content.isBlank()) return null

    fun countIn(list: List<String>): Map<String, Int> = buildMap {
        list.forEach { w ->
            var idx = content.indexOf(w)
            var n = 0
            while (idx >= 0) {
                n++
                idx = content.indexOf(w, idx + w.length)
            }
            if (n > 0) put(w, n)
        }
    }

    val paragraphs = content.split('\n').map { it.trim() }.filter { it.isNotBlank() }
    val dialogueLines = paragraphs.count { it.startsWith('「') || it.startsWith('"') || it.startsWith('“') }
    val sentences = content.split('。', '！', '？').map { it.trim() }.filter { it.length > 10 }
    val seen = HashMap<String, Int>()
    sentences.forEach { seen[it] = (seen[it] ?: 0) + 1 }
    val duplicates = seen.filter { it.value >= 2 }.keys.take(5)
    val starts = paragraphs.filter { it.length >= 6 }.groupingBy { it.take(6) }.eachCount()
    val repeatedStarts = starts.filter { it.value >= 3 }
    val tail = content.takeLast(100)
    val lengths = paragraphs.map { it.length }
    val mean = if (lengths.isEmpty()) 0.0 else lengths.average()
    val std = if (lengths.size < 2) 0.0 else sqrt(lengths.sumOf { (it - mean) * (it - mean) } / lengths.size)

    return ChapterStats(
        title = title, order = order, chars = content.length,
        dialogueLines = dialogueLines, totalLines = paragraphs.size,
        clicheHits = countIn(HUMANIZE_CLICHES),
        shuangdianHits = countIn(SHUANGDIAN_LEXICON),
        waterHits = countIn(WATER_FILLER),
        duplicateSentences = duplicates,
        repeatedParagraphStarts = repeatedStarts,
        hasCliffhanger = CLIFFHANGER_LEXICON.any { tail.contains(it) },
        transitionHits = countIn(HUMANIZE_TRANSITIONS),
    )
}

/** 命中表 → "词×n" 紧凑串（空则 "无"）。 */
private fun hitsToText(hits: Map<String, Int>): String =
    if (hits.isEmpty()) "无" else hits.entries.sortedByDescending { it.value }.joinToString("、") { "${it.key}×${it.value}" }

/**
 * novel_humanize：去 AI 味体检（规则版，只报不改）。
 */
class NovelHumanizeTool : QuroTool {
    override val name = "novel_humanize"
    override val readOnly = true
    override val description =
        "去 AI 味体检（规则版）：套话词表命中、公式化过渡词密度、段落长度均匀度、重复段首。" +
            "输入 text（任意文本）或 chapter_id（查全作章节），二选一。" +
            "只报问题不改文；改写走 novel_polish 或 polish 子代理（语义级改写不在此工具范围）。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "text": { "type": "string", "description": "要体检的文本（与 chapter_id 二选一）" },
        "chapter_id": { "type": "string", "description": "章节 id（查该章已存正文；与 text 二选一）" },
        "work_id": { "type": "string", "description": "作品 id（用 chapter_id 时必填）" }
      },
      "required": []
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"text\":\"…\"} 或 {\"work_id\":\"…\",\"chapter_id\":\"…\"}")
        val text = args.optString("text", "").trim()
        val chapterId = args.optString("chapter_id", "").trim()
        val stats = if (text.isNotBlank()) {
            analyzeInline(text)
        } else if (chapterId.isNotBlank()) {
            val workId = args.optString("work_id", "").trim()
            if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "chapter_id 需要搭配 work_id", "形如 {\"work_id\":\"…\",\"chapter_id\":\"…\"}")
            val repo = NovelRepository.fromContext(context)
            val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
            val c = work.chapters.firstOrNull { it.id == chapterId }
                ?: return gChapterNotFound(work.title, chapterId, gOutlineHint(workId))
            analyzeChapter(context, workId, c.title, c.order, chapterId)
        } else {
            return gError(GErr.INVALID_ARGS, "text 与 chapter_id 至少传一个", "体检指定文本传 text；体检已存章节传 work_id+chapter_id")
        } ?: return gError(GErr.INVALID_ARGS, "该章节还没有正文", "用 novel_write 写完再体检")

        val clicheTotal = stats.clicheHits.values.sum()
        val transitionTotal = stats.transitionHits.values.sum()
        val densityPer1000 = if (stats.chars == 0) 0.0 else transitionTotal * 1000.0 / stats.chars
        val findings = JSONArray()
        if (clicheTotal > 0) {
            findings.put(JSONObject().apply {
                put("type", "cliche"); put("count", clicheTotal)
                put("detail", hitsToText(stats.clicheHits))
                put("suggestion", "套话换成具体动作/感官细节，或直接删除")
            })
        }
        if (densityPer1000 > 3.0) {
            findings.put(JSONObject().apply {
                put("type", "formulaic_transition"); put("count", transitionTotal)
                put("detail", "过渡词密度 ${"%.1f".format(densityPer1000)}/千字（阈值 3.0）：" + hitsToText(stats.transitionHits))
                put("suggestion", "删掉一半过渡词，让动作顺序自己带节奏")
            })
        }
        if (stats.repeatedParagraphStarts.isNotEmpty()) {
            findings.put(JSONObject().apply {
                put("type", "repeated_paragraph_start"); put("count", stats.repeatedParagraphStarts.size)
                put("detail", hitsToText(stats.repeatedParagraphStarts))
                put("suggestion", "段首句式打散：换主语/换句式/合并段落")
            })
        }
        if (stats.duplicateSentences.isNotEmpty()) {
            findings.put(JSONObject().apply {
                put("type", "duplicate_sentence"); put("count", stats.duplicateSentences.size)
                put("detail", stats.duplicateSentences.joinToString("；") { it.take(30) })
                put("suggestion", "重复句删一处或改写其一")
            })
        }
        val aiScore = (clicheTotal * 2 + transitionTotal + findings.length() * 3).coerceAtMost(100)
        return JSONObject().apply {
            put("ok", true)
            put("aiScore", aiScore)
            put("findings", findings)
            put(
                "message",
                if (findings.length() == 0) "✅ 未检出明显 AI 腔（词表/密度/段式均正常）。"
                else "检出 ${findings.length()} 类 AI 腔信号（aiScore=$aiScore，越高越像 AI）。改写建议见 findings.suggestion；改文用 novel_polish。",
            )
        }.toString()
    }

    /** 对临时文本做体检（无章节上下文）。 */
    private fun analyzeInline(text: String): ChapterStats {
        val paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        val sentences = text.split('。', '！', '？').map { it.trim() }.filter { it.length > 10 }
        val seen = HashMap<String, Int>()
        sentences.forEach { seen[it] = (seen[it] ?: 0) + 1 }

        fun countIn(list: List<String>): Map<String, Int> = buildMap {
            list.forEach { w ->
                var idx = text.indexOf(w); var n = 0
                while (idx >= 0) { n++; idx = text.indexOf(w, idx + w.length) }
                if (n > 0) put(w, n)
            }
        }
        val lengths = paragraphs.map { it.length }
        val mean = if (lengths.isEmpty()) 0.0 else lengths.average()
        val std = if (lengths.size < 2) 0.0 else sqrt(lengths.sumOf { (it - mean) * (it - mean) } / lengths.size)
        return ChapterStats(
            title = "(临时文本)", order = 0, chars = text.length,
            dialogueLines = paragraphs.count { it.startsWith('「') || it.startsWith('"') || it.startsWith('“') },
            totalLines = paragraphs.size,
            clicheHits = countIn(HUMANIZE_CLICHES),
            shuangdianHits = countIn(SHUANGDIAN_LEXICON),
            waterHits = countIn(WATER_FILLER),
            duplicateSentences = seen.filter { it.value >= 2 }.keys.take(5),
            repeatedParagraphStarts = paragraphs.filter { it.length >= 6 }
                .groupingBy { it.take(6) }.eachCount().filter { it.value >= 3 },
            hasCliffhanger = CLIFFHANGER_LEXICON.any { text.takeLast(100).contains(it) },
            transitionHits = countIn(HUMANIZE_TRANSITIONS),
        )
    }
}

/**
 * novel_title_candidates：标题候选（LLM 生成，流派/大纲/人物入题）。
 */
class NovelTitleCandidatesTool : QuroTool {
    override val name = "novel_title_candidates"
    override val readOnly = true
    override val description =
        "生成书名/章节标题候选（LLM）：结合作品流派、大纲结构、主要人物与用户关键词，一次给 N 个候选及一句话理由。" +
            "何时用：开新书起名；用户说「帮我想几个书名/章节标题」。需要已配置模型 API Key。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（提供流派与人物上下文）" },
        "count": { "type": "integer", "description": "候选数量，默认 8，上限 15" },
        "keywords": { "type": "array", "items": { "type": "string" }, "description": "用户强调的关键词/意象（可选）" },
        "for_chapter": { "type": "string", "description": "章节 id（可选：起章节标题而非书名）" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"count\":8}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val count = args.optInt("count", 8).coerceIn(3, 15)
        val keywords = args.optJSONArray("keywords")?.let { a ->
            (0 until a.length()).map { a.optString(it, "").trim() }.filter { it.isNotBlank() }
        } ?: emptyList()
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val charNames = runCatching {
            com.ai.assistance.quro.core.novel.asset.NovelAssetRepository.fromContext(context)
                .list(workId, com.ai.assistance.quro.core.novel.asset.AssetType.CHARACTER).take(6).map { it.name }
        }.getOrDefault(emptyList())

        val isChapter = args.optString("for_chapter", "").isNotBlank()
        val chapter = if (isChapter) work.chapters.firstOrNull { it.id == args.optString("for_chapter") }
            ?: return gChapterNotFound(work.title, args.optString("for_chapter"), gOutlineHint(workId)) else null
        val system = "你是番茄小说命名高手。书名要：题材词前置、冲突/反差/金手指外显、10 字内、" +
            "符合流派调性。章节标题要：短、有钩子、剧透一半。只输出候选清单，每行一条：`候选 — 一句话理由`。"
        val user = buildString {
            append("作品：《${work.title}》（流派：${work.genre.ifBlank { "未定" }}）\n")
            if (charNames.isNotEmpty()) append("主要人物：${charNames.joinToString("、")}\n")
            if (isChapter && chapter != null) {
                append("章节：《${chapter.title}》（第${chapter.order + 1}章，${chapter.wordCount} 字），为其起新标题。\n")
            } else {
                val volTitles = work.volumes.sortedBy { it.order }.take(3).joinToString("、") { it.title }
                if (volTitles.isNotBlank()) append("分卷：$volTitles\n")
            }
            if (keywords.isNotEmpty()) append("必须融入的关键词/意象：${keywords.joinToString("、")}\n")
            append("给出 $count 个${if (isChapter) "章节标题" else "书名"}候选。")
        }
        return when (val r = NovelWriteSupport.callLlm(context, system, user, temperature = 0.9f)) {
            is LlmOutcome.Ok -> JSONObject().apply {
                put("ok", true)
                put("candidates", JSONArray().also { a ->
                    r.text.lines().map { it.trim() }.filter { it.isNotBlank() }.take(count).forEach { a.put(it) }
                })
                put("message", "已生成候选（LLM 原样返回，逐行解析）。挑中后用 novel_update_work 改名。")
            }.toString()
            is LlmOutcome.Failed -> gError("llm_error", r.message, "检查「设置 → 模型配置」的 API Key；或人工起名后用 novel_update_work 落名")
        }
    }
}

/**
 * novel_shuangdian_report：爽点节奏仪表盘（规则版）。
 */
class NovelShuangdianReportTool : QuroTool {
    override val name = "novel_shuangdian_report"
    override val readOnly = true
    override val description =
        "爽点节奏报告（规则版）：逐章统计爽点信号词密度、对话占比、章尾悬念钩。" +
            "这是节奏仪表盘（零 token 秒回）；「这段爽不爽」的语义判断交给 review 子代理。" +
            "何时用：连写几章后自查节奏；用户说「最近是不是不够爽」。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "last_n": { "type": "integer", "description": "统计最近 N 个已写章节，默认 5，上限 20" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val lastN = args.optInt("last_n", 5).coerceIn(1, 20)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val chapters = work.chapters.filter { it.wordCount > 0 }
            .sortedWith(compareByDescending<com.ai.assistance.quro.core.novel.NovelChapter> { it.order })
            .take(lastN)
        if (chapters.isEmpty()) return gError(GErr.INVALID_ARGS, "该作品还没有已写章节", "先 novel_write 写出正文再出报告")
        val arr = JSONArray()
        var denseChapters = 0
        var hookChapters = 0
        chapters.reversed().forEach { c ->
            val s = analyzeChapter(context, workId, c.title, c.order, c.id)
            val hits = s?.shuangdianHits?.values?.sum() ?: 0
            val density = if (s == null || s.chars == 0) 0.0 else hits * 1000.0 / s.chars
            val dialogueRatio = if (s == null || s.totalLines == 0) 0.0 else s.dialogueLines.toDouble() / s.totalLines
            if (density >= 2.0) denseChapters++
            if (s?.hasCliffhanger == true) hookChapters++
            arr.put(JSONObject().apply {
                put("chapter", "第${c.order + 1}章「${c.title}」")
                put("chars", s?.chars ?: 0)
                put("shuangdianHits", hits)
                put("densityPer1000", "%.1f".format(density))
                put("topHits", s?.let { hitsToText(it.shuangdianHits) } ?: "无")
                put("dialogueRatio", "%.0f%%".format(dialogueRatio * 100))
                put("endsWithHook", s?.hasCliffhanger ?: false)
            })
        }
        val total = chapters.size
        return JSONObject().apply {
            put("ok", true)
            put("chapters", arr)
            put(
                "message",
                buildString {
                    append("节奏仪表盘（最近 $total 章）：$denseChapters 章爽点密度达标（≥2/千字），$hookChapters 章有章尾钩。")
                    if (denseChapters == 0) append(" ⚠️ 连续无达标爽点——建议下一章安排一次打脸/突破/反转。")
                    if (hookChapters < total / 2) append(" ⚠️ 章尾钩不足一半——每章结尾留半句悬念是番茄基操。")
                    append(" 语义级「爽不爽」判断请派 review 子代理。")
                },
            )
        }.toString()
    }
}

/**
 * novel_water_report：注水检测（规则版）。
 */
class NovelWaterReportTool : QuroTool {
    override val name = "novel_water_report"
    override val readOnly = true
    override val description =
        "注水检测报告（规则版）：重复句、重复段首、注水套话密度、章长异常。" +
            "零 token 秒回；「这段是不是废话」的语义判断交给 review 子代理。" +
            "何时用：日更前自查；用户说「最近是不是在灌水」。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "last_n": { "type": "integer", "description": "检测最近 N 个已写章节，默认 5，上限 20" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val lastN = args.optInt("last_n", 5).coerceIn(1, 20)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val written = work.chapters.filter { it.wordCount > 0 }
            .sortedWith(compareByDescending<com.ai.assistance.quro.core.novel.NovelChapter> { it.order })
        if (written.isEmpty()) return gError(GErr.INVALID_ARGS, "该作品还没有已写章节", "先写正文再检测")
        val meanChars = written.map { it.wordCount }.average()
        val target = written.take(lastN)
        val arr = JSONArray()
        var waterFlags = 0
        target.forEach { c ->
            val s = analyzeChapter(context, workId, c.title, c.order, c.id)
            val signals = mutableListOf<String>()
            if (s != null) {
                if (s.duplicateSentences.isNotEmpty()) signals.add("重复句×${s.duplicateSentences.size}")
                if (s.repeatedParagraphStarts.isNotEmpty()) signals.add("重复段首×${s.repeatedParagraphStarts.size}")
                val fillerTotal = s.waterHits.values.sum()
                if (s.chars > 0 && fillerTotal * 1000.0 / s.chars > 2.0) signals.add("套话密度${"%.1f".format(fillerTotal * 1000.0 / s.chars)}/千字")
                if (meanChars > 0 && s.chars > meanChars * 1.6) signals.add("章长异常（均值 ${meanChars.toInt()} 字）")
                if (s.totalLines > 0 && s.dialogueLines.toDouble() / s.totalLines < 0.15) signals.add("对话占比<15%")
            }
            if (signals.isNotEmpty()) waterFlags++
            arr.put(JSONObject().apply {
                put("chapter", "第${c.order + 1}章「${c.title}」")
                put("chars", s?.chars ?: 0)
                if (signals.isEmpty()) put("verdict", "OK")
                else put("signals", JSONArray().also { a -> signals.forEach { a.put(it) } })
                if (s?.duplicateSentences?.isNotEmpty() == true) put("duplicates", JSONArray().also { a -> s.duplicateSentences.forEach { a.put(it.take(30)) } })
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("chapters", arr)
            put(
                "message",
                buildString {
                    append("注水检测（最近 ${target.size} 章）：$waterFlags 章有注水信号。")
                    if (waterFlags > target.size / 2) append(" ⚠️ 过半章节有信号——考虑用 novel_polish 精修或删并场景。")
                    append(" 语义级「是不是废话」请派 review 子代理。")
                },
            )
        }.toString()
    }
}
