package com.ai.assistance.quro.core.novel.graph

/**
 * 章节引用：图谱域（伏笔/记忆）锚定章节用的小结构。
 *
 * 为什么不在图谱域直接存 [com.ai.assistance.quro.core.novel.NovelChapter]：
 * 章节是 Phase 1 作品契约的实体，归 `core/novel/NovelModels.kt` 所有；图谱域只读引用、
 * 不复制其模型。这里存**快照**（id + 标题 + 序号），章节被删/改名后图谱数据仍可读，
 * 一致性检查（[com.ai.assistance.quro.core.novel.graph.analysis.NovelAnalyzer]）负责
 * 发现「伏笔锚定的章节已不存在」并告警。
 */
data class ChapterRef(
    val chapterId: String,
    val title: String,
    val order: Int,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("chapterId", chapterId)
        put("title", title)
        put("order", order)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject): ChapterRef = ChapterRef(
            chapterId = o.optString("chapterId", ""),
            title = o.optString("title", ""),
            order = o.optInt("order", 0),
        )
    }
}
