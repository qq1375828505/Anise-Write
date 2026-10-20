package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelChapter
import com.ai.assistance.quro.core.novel.NovelChapterStatus
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.NovelVolume
import com.ai.assistance.quro.core.novel.NovelWork
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * PenAi 网文写作域 · 结构域工具族（Phase 3a）。
 *
 * 覆盖规划书 §6.1「作品/结构」与「章节」两行的结构侧工具：作品 CRUD、三级大纲
 * （作品→卷→章）查询与批量落地、卷/章增删、章节统计。全部数据操作经由
 * Phase 1 契约 [NovelRepository]（`filesDir/novel/` JSON 索引 + `.md` 正文），
 * 本文件不直接碰任何文件与索引格式。
 *
 * ## 安全模型（级联删除）
 * 删除作品/卷/章都会级联删除正文 `.md` 文件且**不可恢复**。三个删除工具统一使用
 * `confirm` 开关：`confirm` 不为 `true` 时不执行删除，只返回删除预览
 * （将删掉的卷数/章数/字数），由调用方（AI 或用户）显式带 `confirm=true` 重调
 * 才真正执行。预览本身也是一次存在性校验——目标不存在时先报 not_found。
 *
 * ## 错误码（不兜底，明确分流）
 * | code | 含义 |
 * |---|---|
 * | `invalid_arguments` | 参数缺失/空白/不是合法 JSON/大纲结构不合法 |
 * | `work_not_found` | 作品不存在（hint 列出现有作品） |
 * | `volume_not_found` | 卷不存在或不属于该作品（hint 列出现有卷） |
 * | `chapter_not_found` | 章节不存在或不属于该作品（hint 列出章节） |
 * | `confirm_required` | 不可逆操作缺确认，附删除预览，未执行任何写操作 |
 *
 * ## 已知边界（契约缺口，未在此实现）
 * 规划要求的作品/卷/章**更新**（改名/改类型/移动章节/状态机流转）与**章节重排**
 * 依赖 [NovelRepository] 尚未提供的写方法（`updateWork` / `updateVolume` /
 * `updateChapter` / `reorderChapters`）。按工程约定（契约文件只读、禁止兜底代码、
 * 不在工具层绕过仓库自造持久化），这些工具不在本文件桩实现，缺口与建议签名
 * 已写入 Phase 3a 工程报告交主编排层；契约补齐后按同一范式补注册即可。
 */

/** 结构域工具错误码（对外稳定契约，模型可据此前瞻分流）。 */
private object ErrCode {
    const val INVALID_ARGS = "invalid_arguments"
    const val WORK_NOT_FOUND = "work_not_found"
    const val VOLUME_NOT_FOUND = "volume_not_found"
    const val CHAPTER_NOT_FOUND = "chapter_not_found"
    const val CONFIRM_REQUIRED = "confirm_required"
}

/** 结构化错误：code 供调用方分流，hint 告诉调用方下一步该干什么（对齐基座 NOT_FOUND 修正语义）。 */
private fun novelError(
    code: String,
    message: String,
    hint: String? = null,
    preview: JSONObject? = null,
): String = JSONObject().apply {
    put("ok", false)
    put("code", code)
    put("error", message)
    if (hint != null) put("hint", hint)
    if (preview != null) put("preview", preview)
}.toString()

/** 参数解析：坏 JSON 一律 invalid_arguments，不猜、不兜底。 */
private fun parseArgs(arguments: String): JSONObject? =
    runCatching { JSONObject(arguments) }.getOrNull()

/** 卷按 order 升序（稳定：同 order 按创建时间），与 [NovelWork.chaptersOfVolume] 的章排序同口径。 */
private fun sortedVolumes(work: NovelWork): List<NovelVolume> =
    work.volumes.sortedWith(compareBy({ it.order }, { it.createdAt }))

private fun chapterJson(c: NovelChapter): JSONObject = JSONObject().apply {
    put("id", c.id)
    put("volumeId", c.volumeId)
    put("title", c.title)
    put("order", c.order)
    put("status", c.status.key)
    put("wordCount", c.wordCount)
    put("createdAt", c.createdAt)
    put("updatedAt", c.updatedAt)
}

/**
 * 卷元数据 JSON。
 * @param chapters 非 null 时附带 chapterCount/wordCount/chapters（空列表也带，表示确实无章）；
 *                 null 表示只返卷级字段（如 create_work 的默认卷预览）。
 */
private fun volumeJson(v: NovelVolume, chapters: List<NovelChapter>?): JSONObject = JSONObject().apply {
    put("id", v.id)
    put("title", v.title)
    put("order", v.order)
    put("createdAt", v.createdAt)
    put("updatedAt", v.updatedAt)
    if (chapters != null) {
        put("chapterCount", chapters.size)
        put("wordCount", chapters.sumOf { it.wordCount })
        put("chapters", JSONArray().also { arr -> chapters.forEach { arr.put(chapterJson(it)) } })
    }
}

/**
 * 作品 JSON。
 * @param includeChildren true = 三级大纲（volumes[] 各带 chapters[]）；
 *                        false + includeStats = 列表用摘要（卷数/章数/总字数）；
 *                        false + !includeStats = 仅基础字段。
 */
private fun workJson(work: NovelWork, includeStats: Boolean, includeChildren: Boolean): JSONObject =
    JSONObject().apply {
        put("id", work.id)
        put("title", work.title)
        put("genre", work.genre)
        put("createdAt", work.createdAt)
        put("updatedAt", work.updatedAt)
        if (includeChildren || includeStats) {
            put("volumeCount", work.volumes.size)
            put("chapterCount", work.chapters.size)
            put("wordCount", work.totalWordCount())
        }
        if (includeChildren) {
            put("volumes", JSONArray().also { arr ->
                sortedVolumes(work).forEach { v ->
                    arr.put(volumeJson(v, work.chaptersOfVolume(v.id)))
                }
            })
        }
    }

/** 四态章节计数（unwritten/draft/polishing/completed 恒全量返回，零值也给，schema 稳定）。 */
private fun statusCountsJson(chapters: List<NovelChapter>): JSONObject = JSONObject().apply {
    NovelChapterStatus.entries.forEach { s -> put(s.key, chapters.count { it.status == s }) }
}

/** 作品不存在错误：hint 附现有作品清单，让调用方一次重发就能修正（基座 NOT_FOUND 修正语义）。 */
private fun workNotFoundError(repo: NovelRepository, workId: String): String {
    val works = repo.loadWorks()
    val avail = if (works.isEmpty()) {
        "当前没有任何作品，可先用 novel_create_work 创建。"
    } else {
        "现有作品：" + works.joinToString("；") { "《${it.title}》(${it.id})" }
    }
    return novelError(
        ErrCode.WORK_NOT_FOUND,
        "作品不存在：$workId",
        "先用 novel_list_works 确认真实 work_id。$avail",
    )
}

/** 卷不存在错误：hint 附该作品现有卷清单。 */
private fun volumeNotFoundError(work: NovelWork, volumeId: String): String {
    val avail = if (work.volumes.isEmpty()) {
        "该作品下还没有卷。"
    } else {
        "现有卷：" + sortedVolumes(work).joinToString("；") { "「${it.title}」(${it.id})" }
    }
    return novelError(
        ErrCode.VOLUME_NOT_FOUND,
        "卷不存在：$volumeId（作品《${work.title}》）",
        "用 novel_get_outline(work_id=${work.id}) 查看卷 id。$avail",
    )
}

/** 章节不存在错误：hint 附该作品前若干章节（有界输出，全量用 novel_get_outline 看）。 */
private fun chapterNotFoundError(work: NovelWork, chapterId: String): String {
    val shown = work.chapters.take(8)
    val avail = if (shown.isEmpty()) {
        "该作品下还没有章节。"
    } else {
        val more = if (work.chapters.size > shown.size) " 等 ${work.chapters.size} 章" else ""
        "部分章节：" + shown.joinToString("；") { "「${it.title}」(${it.id})" } + more
    }
    return novelError(
        ErrCode.CHAPTER_NOT_FOUND,
        "章节不存在：$chapterId（作品《${work.title}》）",
        "用 novel_get_outline(work_id=${work.id}) 查看章节 id。$avail",
    )
}

/**
 * novel_list_works：列出全部作品。
 *
 * 只读（readOnly=true，已入并发白名单）：任何需要 work_id 的操作之前先调它。
 */
class NovelListWorksTool : QuroTool {
    override val name = "novel_list_works"
    override val readOnly = true
    override val description =
        "列出全部作品（书名/类型/卷章数/总字数）。何时用：任何需要 work_id 的操作之前、用户问「我有哪些书」。" +
            "返回 {\"ok\":true,\"total\":N,\"works\":[…]}；空库时 message 提示先建作品。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "include_stats": {
          "type": "boolean",
          "description": "是否附带卷数/章节数/总字数统计，默认 true；false 时只返回基础字段（更短）"
        }
      },
      "required": []
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "本工具参数均可选，传 {} 即可")
        val includeStats = args.optBoolean("include_stats", true)
        val works = NovelRepository.fromContext(context).loadWorks()
        val arr = JSONArray().also { a ->
            works.forEach { a.put(workJson(it, includeStats = includeStats, includeChildren = false)) }
        }
        val message = if (works.isEmpty()) {
            "还没有任何作品。用 novel_create_work 创建第一部作品。"
        } else {
            "共 ${works.size} 部作品。"
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", works.size)
            put("works", arr)
            put("message", message)
        }.toString()
    }
}

/**
 * novel_create_work：新建作品（仓库自动带一个默认卷）。
 */
class NovelCreateWorkTool : QuroTool {
    override val name = "novel_create_work"
    override val description =
        "新建一部作品，并自动创建一个默认卷（建章需要默认卷 id，见返回的 defaultVolume）。" +
            "何时用：用户说「开新书 / 新建小说 / 建个作品」。" +
            "返回新作品的完整信息；若已存在同名书，结果里 duplicateTitleWarning=true 并附提示。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "title": {
          "type": "string",
          "description": "作品名（书名），必填、非空白",
          "example": "剑起苍澜"
        },
        "genre": {
          "type": "string",
          "description": "类型/流派，选填，默认空串",
          "example": "玄幻"
        },
        "default_volume_title": {
          "type": "string",
          "description": "首个默认卷的卷名，选填，默认「第一卷」；传空白串同样回落「第一卷」",
          "example": "第一卷"
        }
      },
      "required": ["title"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"title\":\"剑起苍澜\",\"genre\":\"玄幻\"}")
        val title = args.optString("title", "").trim()
        if (title.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "title 不能为空（作品名）", "形如 {\"title\":\"剑起苍澜\"}")
        }
        val genre = args.optString("genre", "").trim()
        val defaultVolumeTitle = args.optString("default_volume_title", "第一卷")
        val repo = NovelRepository.fromContext(context)
        val duplicateTitle = repo.loadWorks().any { it.title == title }
        val work = try {
            repo.createWork(title, genre, defaultVolumeTitle)
        } catch (e: IllegalArgumentException) {
            // 仓库 require 只可能因标题空白触发（上面已预检）；到此说明契约行为变化，明确报错不兜底
            return novelError(ErrCode.INVALID_ARGS, "创建作品被仓库拒绝：${e.message}", "检查 title 是否为非空白字符串")
        }
        val defaultVolume = work.volumes.firstOrNull()
        return JSONObject().apply {
            put("ok", true)
            put("work", workJson(work, includeStats = true, includeChildren = false))
            if (defaultVolume != null) put("defaultVolume", volumeJson(defaultVolume, chapters = null))
            put("duplicateTitleWarning", duplicateTitle)
            put(
                "message",
                buildString {
                    append("已创建作品《${work.title}》")
                    if (genre.isNotBlank()) append("（类型：$genre）")
                    append("，默认卷「${defaultVolume?.title ?: "第一卷"}」已就绪")
                    if (duplicateTitle) append("；注意：已存在同名作品，后续操作请使用本结果返回的 id")
                    append("。下一步：novel_create_chapter 建章，或 novel_save_outline 批量落地大纲。")
                },
            )
        }.toString()
    }
}

/**
 * novel_delete_work：删除整部作品（级联删除全部卷/章节元数据/正文 .md，不可恢复）。
 *
 * confirm 门：confirm != true 时不执行任何写操作，只返回删除预览。
 */
class NovelDeleteWorkTool : QuroTool {
    override val name = "novel_delete_work"
    override val description =
        "删除整部作品：级联删除其下全部卷、全部章节元数据与正文 .md 文件，不可恢复。" +
            "何时用：用户明确要求删掉整本书。安全约束：confirm 不为 true 时不执行，只返回删除预览" +
            "（卷数/章数/字数）；confirm=true 才真正删除。局部删除请用 novel_delete_volume / novel_delete_chapter。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "要删除的作品 id（novel_list_works 获取）",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "confirm": {
          "type": "boolean",
          "description": "删除确认开关，默认 false。false/缺省 = 只返回删除预览、不执行；true = 执行级联删除（不可恢复）",
          "example": true
        }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\",\"confirm\":true}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val confirm = args.optBoolean("confirm", false)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return workNotFoundError(repo, workId)

        val preview = JSONObject().apply {
            put("workId", work.id)
            put("title", work.title)
            put("volumeCount", work.volumes.size)
            put("chapterCount", work.chapters.size)
            put("wordCount", work.totalWordCount())
        }
        if (!confirm) {
            return novelError(
                ErrCode.CONFIRM_REQUIRED,
                "删除作品不可恢复：将级联删除《${work.title}》及其下 ${work.volumes.size} 卷 / " +
                    "${work.chapters.size} 章 / 共 ${work.totalWordCount()} 字（含全部正文 .md 文件）。",
                "确认删除请以相同参数加 confirm=true 重新调用；只想删局部可改用 novel_delete_volume / novel_delete_chapter。",
                preview = preview,
            )
        }
        if (!repo.deleteWork(workId)) return workNotFoundError(repo, workId)
        return JSONObject().apply {
            put("ok", true)
            put("deleted", preview)
            put(
                "message",
                "已删除作品《${work.title}》及其下 ${work.volumes.size} 卷 ${work.chapters.size} 章" +
                    "（共 ${work.totalWordCount()} 字，正文文件已一并清除，不可恢复）。",
            )
        }.toString()
    }
}

/**
 * novel_get_outline：查询一部作品的三级大纲（作品→卷→章）。
 *
 * 只读（readOnly=true，已入并发白名单）。写章/改结构前看现状、回答进度问题的入口。
 */
class NovelGetOutlineTool : QuroTool {
    override val name = "novel_get_outline"
    override val readOnly = true
    override val description =
        "查询一部作品的三级大纲（作品→卷→章）：每卷顺序/章节数/字数，每章顺序/状态" +
            "（unwritten/draft/polishing/completed）/字数，并附总览统计（卷数、章数、总字数、四态分布）。" +
            "何时用：写章或改结构前看现状、用户问进度或大纲。只读，可安全并发。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "作品 id（novel_list_works 获取）",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "include_chapters": {
          "type": "boolean",
          "description": "是否展开到章节级，默认 true；false 时只返回到卷级（结构更短）"
        }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val includeChapters = args.optBoolean("include_chapters", true)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return workNotFoundError(repo, workId)
        val unwritten = work.chapters.count { it.status == NovelChapterStatus.UNWRITTEN }
        return JSONObject().apply {
            put("ok", true)
            put("work", workJson(work, includeStats = true, includeChildren = includeChapters))
            put(
                "stats",
                JSONObject().apply {
                    put("volumeCount", work.volumes.size)
                    put("chapterCount", work.chapters.size)
                    put("wordCount", work.totalWordCount())
                    put("unwrittenCount", unwritten)
                    put("statusCounts", statusCountsJson(work.chapters))
                },
            )
            put(
                "message",
                "《${work.title}》：${work.volumes.size} 卷 ${work.chapters.size} 章，共 ${work.totalWordCount()} 字" +
                    "；其中未写 $unwritten 章。",
            )
        }.toString()
    }
}

/**
 * novel_save_outline：把大纲批量落地为卷/章结构。
 *
 * 语义（刻意只增不删不改）：卷名/章名按**精确匹配**去重——已存在的跳过，缺失的按
 * 数组顺序新建在末尾。同一请求内同名卷合并、同名章只建一次。重命名/删除/调序
 * 不在本工具职责（对应更新类方法契约未提供，见文件头「已知边界」）。
 */
class NovelSaveOutlineTool : QuroTool {
    override val name = "novel_save_outline"
    override val description =
        "把 AI 生成的三级大纲批量落地为作品的卷/章结构：卷名/章名精确匹配，已存在自动跳过，" +
            "缺失的按给定顺序新建在末尾。只增不删不改——重命名、删除、调序不使用本工具。" +
            "何时用：大纲定稿后「把大纲存进作品」。返回新建/跳过的卷章清单。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "作品 id（novel_list_works 获取）",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "outline": {
          "type": "array",
          "description": "大纲数组，按卷组织；卷名/章名均精确匹配去重（已存在则跳过、绝不改动），缺失项按数组顺序新建在末尾；同一 outline 内同名卷会合并到同一卷",
          "items": {
            "type": "object",
            "properties": {
              "volume": {
                "type": "string",
                "description": "卷名，必填、非空白",
                "example": "第一卷"
              },
              "chapters": {
                "type": "array",
                "description": "章名数组，按顺序落地；缺省视为空卷",
                "items": {
                  "type": "string",
                  "description": "章名，非空白",
                  "example": "第一章 觉醒"
                }
              }
            },
            "required": ["volume"]
          }
        }
      },
      "required": ["work_id", "outline"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\",\"outline\":[{\"volume\":\"第一卷\",\"chapters\":[\"第一章 觉醒\"]}]}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val outlineArr = args.optJSONArray("outline")
            ?: return novelError(ErrCode.INVALID_ARGS, "outline 缺失或不是数组", "形如 [{\"volume\":\"第一卷\",\"chapters\":[\"第一章 觉醒\"]}]")
        if (outlineArr.length() == 0) {
            return novelError(ErrCode.INVALID_ARGS, "outline 不能为空数组", "至少要给一卷")
        }

        // 先整份校验再执行：避免「建了三卷才发现第四项是坏的」留下半截结构
        val items = mutableListOf<Pair<String, List<String>>>()
        for (i in 0 until outlineArr.length()) {
            val item = outlineArr.optJSONObject(i)
                ?: return novelError(ErrCode.INVALID_ARGS, "outline[$i] 不是对象", "每项形如 {\"volume\":\"第一卷\",\"chapters\":[...]}")
            val volumeTitle = item.optString("volume", "").trim()
            if (volumeTitle.isBlank()) {
                return novelError(ErrCode.INVALID_ARGS, "outline[$i].volume 不能为空", "卷名不能空白")
            }
            val chapterArr = item.optJSONArray("chapters") ?: JSONArray()
            val chapterTitles = mutableListOf<String>()
            for (j in 0 until chapterArr.length()) {
                val t = chapterArr.optString(j, "").trim()
                if (t.isBlank()) {
                    return novelError(ErrCode.INVALID_ARGS, "outline[$i].chapters[$j] 不能为空", "章名不能空白")
                }
                chapterTitles += t
            }
            items += volumeTitle to chapterTitles
        }

        val repo = NovelRepository.fromContext(context)
        val createdVolumes = JSONArray()
        val createdChapters = JSONArray()
        val existingVolumeTitles = mutableListOf<String>()
        var existingChapterCount = 0

        for ((volumeTitle, chapterTitles) in items) {
            // 每项都从仓库实时读：保存大纲可能跨多次调用，也可能与其它写操作交替，快照会过期
            val live = repo.loadWorks().firstOrNull { it.id == workId }
                ?: return novelError(ErrCode.WORK_NOT_FOUND, "作品不存在：$workId", "作品可能已被删除，用 novel_list_works 确认")
            var volume = live.volumes.firstOrNull { it.title == volumeTitle }
            if (volume == null) {
                volume = try {
                    repo.createVolume(workId, volumeTitle)
                } catch (e: IllegalArgumentException) {
                    return novelError(ErrCode.WORK_NOT_FOUND, "新建卷失败：${e.message}", "作品可能已被删除，用 novel_list_works 确认")
                }
                createdVolumes.put(
                    JSONObject().apply {
                        put("id", volume.id)
                        put("title", volume.title)
                        put("order", volume.order)
                    },
                )
            } else {
                existingVolumeTitles += volumeTitle
            }
            // 本调用内已建的章也计入 seen：同一 outline 里重复章名只建一次
            val seen = live.chaptersOfVolume(volume.id).map { it.title }.toMutableSet()
            for (title in chapterTitles) {
                if (title in seen) {
                    existingChapterCount++
                    continue
                }
                val chapter = try {
                    repo.createChapter(workId, volume.id, title)
                } catch (e: IllegalArgumentException) {
                    return novelError(ErrCode.VOLUME_NOT_FOUND, "新建章节失败：${e.message}", "卷可能已被删除，用 novel_get_outline 确认结构")
                }
                seen += title
                createdChapters.put(
                    JSONObject().apply {
                        put("id", chapter.id)
                        put("volumeId", volume.id)
                        put("volumeTitle", volume.title)
                        put("title", chapter.title)
                        put("order", chapter.order)
                    },
                )
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("workId", workId)
            put("createdVolumes", createdVolumes)
            put("createdChapters", createdChapters)
            put("existingVolumes", JSONArray(existingVolumeTitles))
            put("existingChapterCount", existingChapterCount)
            put(
                "message",
                buildString {
                    append("大纲已落地：新建 ${createdVolumes.length()} 卷、${createdChapters.length()} 章")
                    if (existingVolumeTitles.isNotEmpty() || existingChapterCount > 0) {
                        append("；跳过已存在的 ${existingVolumeTitles.size} 卷、$existingChapterCount 章")
                    }
                    append("。本工具只增不删不改。")
                },
            )
        }.toString()
    }
}

/**
 * novel_create_volume：在作品下新建一卷（order 自动排到末尾）。
 */
class NovelCreateVolumeTool : QuroTool {
    override val name = "novel_create_volume"
    override val description =
        "在作品下新建一卷，order 自动排在末尾。何时用：开新卷/分卷。" +
            "返回新卷信息（含 id，后续建章要用）。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "作品 id（novel_list_works 获取）",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "title": {
          "type": "string",
          "description": "卷名，必填、非空白",
          "example": "第二卷"
        }
      },
      "required": ["work_id", "title"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\",\"title\":\"第二卷\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val title = args.optString("title", "").trim()
        if (title.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "title 不能为空（卷名）", "形如 {\"work_id\":\"...\",\"title\":\"第二卷\"}")
        }
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return workNotFoundError(repo, workId)
        val volume = try {
            repo.createVolume(workId, title)
        } catch (e: IllegalArgumentException) {
            // 预检已过仍被拒：只可能是作品在两次读之间被删（TOCTOU），按不存在明确报错
            return workNotFoundError(repo, workId)
        }
        return JSONObject().apply {
            put("ok", true)
            put("volume", volumeJson(volume, chapters = null))
            put(
                "message",
                "已在《${work.title}》下新建卷「${volume.title}」（order=${volume.order}，自动排在末尾，id=${volume.id}）。",
            )
        }.toString()
    }
}

/**
 * novel_delete_volume：删除一卷（级联删除该卷下全部章节元数据与正文 .md，不可恢复）。
 *
 * confirm 门：confirm != true 时不执行任何写操作，只返回删除预览。
 */
class NovelDeleteVolumeTool : QuroTool {
    override val name = "novel_delete_volume"
    override val description =
        "删除一卷：级联删除该卷下全部章节元数据与正文 .md 文件，不可恢复。" +
            "何时用：用户明确要求删掉整卷。安全约束：confirm 不为 true 时不执行，只返回删除预览" +
            "（章数/字数）；confirm=true 才真正删除。只删一章请用 novel_delete_chapter。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "作品 id",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "volume_id": {
          "type": "string",
          "description": "要删除的卷 id（novel_get_outline 获取）",
          "example": "c2d4e6f8-0000-0000-0000-123456789abc"
        },
        "confirm": {
          "type": "boolean",
          "description": "删除确认开关，默认 false。false/缺省 = 只返回删除预览、不执行；true = 执行级联删除（不可恢复）",
          "example": true
        }
      },
      "required": ["work_id", "volume_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\",\"volume_id\":\"...\",\"confirm\":true}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val volumeId = args.optString("volume_id", "").trim()
        if (volumeId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "volume_id 不能为空", "用 novel_get_outline 查看卷 id")
        }
        val confirm = args.optBoolean("confirm", false)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return workNotFoundError(repo, workId)
        val volume = work.volumes.firstOrNull { it.id == volumeId } ?: return volumeNotFoundError(work, volumeId)
        val chapters = work.chaptersOfVolume(volume.id)

        val preview = JSONObject().apply {
            put("workId", work.id)
            put("volumeId", volume.id)
            put("title", volume.title)
            put("chapterCount", chapters.size)
            put("wordCount", chapters.sumOf { it.wordCount })
        }
        if (!confirm) {
            return novelError(
                ErrCode.CONFIRM_REQUIRED,
                "删除卷不可恢复：将删除《${work.title}》的卷「${volume.title}」及其下 ${chapters.size} 章 / " +
                    "共 ${chapters.sumOf { it.wordCount }} 字（含全部正文 .md 文件）。",
                "确认删除请以相同参数加 confirm=true 重新调用；只想删个别章用 novel_delete_chapter。",
                preview = preview,
            )
        }
        if (!repo.deleteVolume(workId, volumeId)) return volumeNotFoundError(work, volumeId)
        return JSONObject().apply {
            put("ok", true)
            put("deleted", preview)
            put(
                "message",
                "已删除《${work.title}》的卷「${volume.title}」及其下 ${chapters.size} 章" +
                    "（共 ${chapters.sumOf { it.wordCount }} 字，正文文件已一并清除，不可恢复）。",
            )
        }.toString()
    }
}

/**
 * novel_create_chapter：在卷下新建一章（order 自动排到卷内末尾，初态 unwritten、0 字）。
 */
class NovelCreateChapterTool : QuroTool {
    override val name = "novel_create_chapter"
    override val description =
        "在指定卷下新建一章，排在卷内末尾，初始状态 unwritten、0 字。" +
            "何时用：大纲落地后补章、用户说「加一章/补一章」。返回新章信息（含 id）。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "作品 id",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "volume_id": {
          "type": "string",
          "description": "目标卷 id（novel_get_outline 获取）",
          "example": "c2d4e6f8-0000-0000-0000-123456789abc"
        },
        "title": {
          "type": "string",
          "description": "章名，必填、非空白",
          "example": "第三章 暗流"
        }
      },
      "required": ["work_id", "volume_id", "title"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\",\"volume_id\":\"...\",\"title\":\"第三章 暗流\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val volumeId = args.optString("volume_id", "").trim()
        if (volumeId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "volume_id 不能为空", "用 novel_get_outline 查看卷 id")
        }
        val title = args.optString("title", "").trim()
        if (title.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "title 不能为空（章名）", "形如 {\"work_id\":\"...\",\"volume_id\":\"...\",\"title\":\"第三章 暗流\"}")
        }
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return workNotFoundError(repo, workId)
        val volume = work.volumes.firstOrNull { it.id == volumeId } ?: return volumeNotFoundError(work, volumeId)
        val chapter = try {
            repo.createChapter(workId, volumeId, title)
        } catch (e: IllegalArgumentException) {
            // 预检已过仍被拒：作品或卷在两次读之间被删（TOCTOU），重新判定谁没了
            val live = repo.loadWorks().firstOrNull { it.id == workId }
                ?: return workNotFoundError(repo, workId)
            return volumeNotFoundError(live, volumeId)
        }
        return JSONObject().apply {
            put("ok", true)
            put("chapter", chapterJson(chapter))
            put(
                "message",
                "已在《${work.title}》·「${volume.title}」新建章节「${chapter.title}」" +
                    "（order=${chapter.order}，排在卷内末尾，初态 unwritten，id=${chapter.id}）。",
            )
        }.toString()
    }
}

/**
 * novel_delete_chapter：删除一章（元数据与正文 .md 一并删除，不可恢复）。
 *
 * confirm 门：confirm != true 时不执行任何写操作，只返回删除预览。
 */
class NovelDeleteChapterTool : QuroTool {
    override val name = "novel_delete_chapter"
    override val description =
        "删除一章：章节元数据与正文 .md 文件一并删除，不可恢复。" +
            "何时用：用户明确要求删掉某一章。安全约束：confirm 不为 true 时不执行，只返回删除预览" +
            "（标题/所在卷/字数）；confirm=true 才真正删除。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "作品 id",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "chapter_id": {
          "type": "string",
          "description": "要删除的章节 id（novel_get_outline 获取）",
          "example": "d4e6f8a0-0000-0000-0000-123456789abc"
        },
        "confirm": {
          "type": "boolean",
          "description": "删除确认开关，默认 false。false/缺省 = 只返回删除预览、不执行；true = 执行删除（不可恢复）",
          "example": true
        }
      },
      "required": ["work_id", "chapter_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\",\"chapter_id\":\"...\",\"confirm\":true}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val chapterId = args.optString("chapter_id", "").trim()
        if (chapterId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "chapter_id 不能为空", "用 novel_get_outline 查看章节 id")
        }
        val confirm = args.optBoolean("confirm", false)
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return workNotFoundError(repo, workId)
        val chapter = work.chapters.firstOrNull { it.id == chapterId } ?: return chapterNotFoundError(work, chapterId)
        // 卷被外部改坏时 chapter.volumeId 可能查不到卷：展示原始 id，不编造卷名
        val volumeTitle = work.volumes.firstOrNull { it.id == chapter.volumeId }?.title ?: chapter.volumeId

        val preview = JSONObject().apply {
            put("workId", work.id)
            put("chapterId", chapter.id)
            put("title", chapter.title)
            put("volumeId", chapter.volumeId)
            put("volumeTitle", volumeTitle)
            put("wordCount", chapter.wordCount)
        }
        if (!confirm) {
            return novelError(
                ErrCode.CONFIRM_REQUIRED,
                "删除章节不可恢复：将删除《${work.title}》·「$volumeTitle」的章节「${chapter.title}」" +
                    "（${chapter.wordCount} 字，正文 .md 文件一并删除）。",
                "确认删除请以相同参数加 confirm=true 重新调用。",
                preview = preview,
            )
        }
        if (!repo.deleteChapter(workId, chapterId)) return chapterNotFoundError(work, chapterId)
        return JSONObject().apply {
            put("ok", true)
            put("deleted", preview)
            put(
                "message",
                "已删除《${work.title}》·「$volumeTitle」的章节「${chapter.title}」" +
                    "（${chapter.wordCount} 字，正文文件已一并清除，不可恢复）。",
            )
        }.toString()
    }
}

/**
 * novel_chapter_stats：章节统计（作品级或单卷）。
 *
 * 只读（readOnly=true，已入并发白名单）。回答「进度/字数」问题、AI 决定下一篇写什么
 * （unwrittenChapters 是待写清单）的入口。
 */
class NovelChapterStatsTool : QuroTool {
    override val name = "novel_chapter_stats"
    override val readOnly = true
    override val description =
        "统计一部作品（或其中一卷）的章节数据：章数、总字数、均章字数、四态分布" +
            "（unwritten/draft/polishing/completed）、分卷拆条、未写章节清单。" +
            "何时用：用户问进度/字数、AI 决定下一篇写什么。只读，可安全并发。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": {
          "type": "string",
          "description": "作品 id（novel_list_works 获取）",
          "example": "b3f1c2a4-0000-0000-0000-123456789abc"
        },
        "volume_id": {
          "type": "string",
          "description": "选填：只统计这一卷；缺省统计整个作品",
          "example": "c2d4e6f8-0000-0000-0000-123456789abc"
        }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = parseArgs(arguments)
            ?: return novelError(ErrCode.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"...\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) {
            return novelError(ErrCode.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        }
        val volumeId = args.optString("volume_id", "").trim()
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return workNotFoundError(repo, workId)
        val scopeVolume = if (volumeId.isNotEmpty()) {
            work.volumes.firstOrNull { it.id == volumeId } ?: return volumeNotFoundError(work, volumeId)
        } else {
            null
        }
        val chapters = if (scopeVolume != null) {
            work.chaptersOfVolume(scopeVolume.id)
        } else {
            work.chapters.sortedWith(compareBy({ it.order }, { it.createdAt }))
        }
        val totalWords = chapters.sumOf { it.wordCount }
        val avgWords = if (chapters.isEmpty()) 0 else totalWords / chapters.size
        val unwritten = chapters.filter { it.status == NovelChapterStatus.UNWRITTEN }
        val volumeOrder = work.volumes.associate { it.id to it.order }
        val unwrittenSorted = unwritten.sortedWith(
            compareBy({ volumeOrder[it.volumeId] ?: Int.MAX_VALUE }, { it.order }, { it.createdAt }),
        )
        val unwrittenArr = JSONArray().also { arr ->
            unwrittenSorted.forEach { c ->
                arr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("volumeId", c.volumeId)
                        put("volumeTitle", work.volumes.firstOrNull { it.id == c.volumeId }?.title ?: c.volumeId)
                        put("title", c.title)
                        put("order", c.order)
                    },
                )
            }
        }
        val scopeVolumes = if (scopeVolume != null) listOf(scopeVolume) else sortedVolumes(work)
        val volumesArr = JSONArray().also { arr ->
            scopeVolumes.forEach { v ->
                val vChapters = work.chaptersOfVolume(v.id)
                arr.put(
                    JSONObject().apply {
                        put("volumeId", v.id)
                        put("title", v.title)
                        put("order", v.order)
                        put("chapterCount", vChapters.size)
                        put("wordCount", vChapters.sumOf { it.wordCount })
                    },
                )
            }
        }
        val scopeLabel = if (scopeVolume != null) "《${work.title}》·「${scopeVolume.title}」" else "《${work.title}》"
        val counts = NovelChapterStatus.entries.associateWith { s -> chapters.count { it.status == s } }
        return JSONObject().apply {
            put("ok", true)
            put("workId", work.id)
            put("scope", if (scopeVolume != null) scopeVolume.id else "all")
            put(
                "stats",
                JSONObject().apply {
                    put("chapterCount", chapters.size)
                    put("totalWords", totalWords)
                    put("avgWords", avgWords)
                    put("statusCounts", statusCountsJson(chapters))
                    put("volumes", volumesArr)
                    put("unwrittenChapters", unwrittenArr)
                },
            )
            put(
                "message",
                "$scopeLabel：${chapters.size} 章 / 共 $totalWords 字（均 $avgWords 字/章）；" +
                    "未写 ${counts[NovelChapterStatus.UNWRITTEN]}、" +
                    "草稿 ${counts[NovelChapterStatus.DRAFT]}、" +
                    "精修 ${counts[NovelChapterStatus.POLISHING]}、" +
                    "完稿 ${counts[NovelChapterStatus.COMPLETED]}。",
            )
        }.toString()
    }
}

/**
 * 结构域工具全集：注册表（QuroBuiltInTools）与单测共用同一真相源，
 * 避免「注册了但名字写错 / 漏登 coreNames」这类静默失效。
 */
val NOVEL_STRUCTURE_TOOLS: List<QuroTool> = listOf(
    NovelListWorksTool(),
    NovelCreateWorkTool(),
    NovelDeleteWorkTool(),
    NovelGetOutlineTool(),
    NovelSaveOutlineTool(),
    NovelCreateVolumeTool(),
    NovelDeleteVolumeTool(),
    NovelCreateChapterTool(),
    NovelDeleteChapterTool(),
    NovelChapterStatsTool(),
)

/** 结构域工具名集合（coreNames / ALWAYS_ON / 并发白名单三处登记 + 单测断言用）。 */
val NOVEL_STRUCTURE_TOOL_NAMES: Set<String> = NOVEL_STRUCTURE_TOOLS.map { it.name }.toSet()
