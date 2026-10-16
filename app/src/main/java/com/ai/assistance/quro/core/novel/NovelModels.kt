package com.ai.assistance.quro.core.novel

import org.json.JSONArray
import org.json.JSONObject

/**
 * 网文写作域数据模型（PenAi Phase 1）。
 *
 * 模型直译 novelIDE / AIGC-NetNov 的实体设计，序列化沿用基座自研 JSON 文件风格
 * （org.json，不引入 Room 与任何第三方序列化库，见规划书 §6.4）。
 *
 * 层级：作品（NovelWork）→ 卷（NovelVolume）→ 章节（NovelChapter）。
 * 章节正文**不在**本模型内——正文存 `.md` 文件，元数据（含字数）走 JSON 索引，
 * 详见 [NovelRepository] 的 KDoc 目录布局说明。
 */

/**
 * 章节状态机：unwritten → draft → polishing → completed。
 *
 * Phase 1 只建枚举与落库，流转交互（状态按钮 / 批量流转）留待后续阶段。
 * 数据层唯一自动流转：[NovelRepository.writeChapterContent] 在正文首次非空保存时
 * 把 UNWRITTEN 提升为 DRAFT（有正文的章节不可能是"未写"），且**只升不降**。
 */
enum class NovelChapterStatus(val key: String) {
    UNWRITTEN("unwritten"),
    DRAFT("draft"),
    POLISHING("polishing"),
    COMPLETED("completed"),
    ;

    companion object {
        /** 旧数据 / 损坏数据无匹配 key 时回落 UNWRITTEN（新章节初值）。 */
        fun fromKey(key: String?): NovelChapterStatus =
            entries.firstOrNull { it.key == key } ?: UNWRITTEN
    }
}

/** 卷：作品的分卷（Phase 1 冻结决策：无树形目录，章节按卷分组扁平展示）。 */
data class NovelVolume(
    val id: String,
    val workId: String,
    val title: String,
    val order: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("title", title)
        put("order", order)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelVolume = NovelVolume(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            title = o.optString("title", ""),
            order = o.optInt("order", 0),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 章节元数据（正文在 `chapters/{workId}/{chapterId}.md`）。 */
data class NovelChapter(
    val id: String,
    val volumeId: String,
    val title: String,
    val order: Int,
    val status: NovelChapterStatus = NovelChapterStatus.UNWRITTEN,
    val wordCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("volumeId", volumeId)
        put("title", title)
        put("order", order)
        put("status", status.key)
        put("wordCount", wordCount)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelChapter = NovelChapter(
            id = o.optString("id", ""),
            volumeId = o.optString("volumeId", ""),
            title = o.optString("title", ""),
            order = o.optInt("order", 0),
            status = NovelChapterStatus.fromKey(o.optString("status", NovelChapterStatus.UNWRITTEN.key)),
            wordCount = o.optInt("wordCount", 0),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 作品：网文的最小管理单元（Phase 1 表单字段 = 作品名 + 类型）。 */
data class NovelWork(
    val id: String,
    val title: String,
    val genre: String,
    val createdAt: Long,
    val updatedAt: Long,
    val volumes: List<NovelVolume> = emptyList(),
    val chapters: List<NovelChapter> = emptyList(),
) {
    /** 卷内章节按 order 升序（稳定：同 order 按创建时间）。 */
    fun chaptersOfVolume(volumeId: String): List<NovelChapter> =
        chapters.filter { it.volumeId == volumeId }.sortedWith(compareBy({ it.order }, { it.createdAt }))

    /** 总字数（各章节 wordCount 之和，元数据聚合，不读正文文件）。 */
    fun totalWordCount(): Int = chapters.sumOf { it.wordCount }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("genre", genre)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("volumes", JSONArray().also { arr -> volumes.forEach { v -> arr.put(v.toJson()) } })
        put("chapters", JSONArray().also { arr -> chapters.forEach { c -> arr.put(c.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): NovelWork {
            val volumesArr = o.optJSONArray("volumes") ?: JSONArray()
            val volumes = mutableListOf<NovelVolume>()
            for (i in 0 until volumesArr.length()) {
                runCatching { volumesArr.getJSONObject(i) }.getOrNull()?.let { volumes.add(NovelVolume.fromJson(it)) }
            }
            val chaptersArr = o.optJSONArray("chapters") ?: JSONArray()
            val chapters = mutableListOf<NovelChapter>()
            for (i in 0 until chaptersArr.length()) {
                runCatching { chaptersArr.getJSONObject(i) }.getOrNull()?.let { chapters.add(NovelChapter.fromJson(it)) }
            }
            return NovelWork(
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                genre = o.optString("genre", ""),
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L),
                volumes = volumes,
                chapters = chapters,
            )
        }
    }
}

/**
 * 中文网文字数统计：CJK 字符（含全角标点）每字计 1，连续拉丁字母/数字串计 1 词，
 * 空白不计。对齐主流中文写作工具的「字数」口径（Word 的中文字数 + 英文单词数）。
 */
fun countWords(text: String): Int {
    var count = 0
    var inLatinWord = false
    for (ch in text) {
        when {
            ch.isWhitespace() -> inLatinWord = false
            ch.code < 0x2E80 -> {
                // 拉丁字母 / 数字 / 半角符号：连续串只计 1 词
                if (!inLatinWord) {
                    count++
                    inLatinWord = true
                }
            }
            else -> count++ // CJK 字符与全角标点
        }
    }
    return count
}
