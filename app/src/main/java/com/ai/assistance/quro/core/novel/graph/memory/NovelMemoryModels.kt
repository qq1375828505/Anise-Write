package com.ai.assistance.quro.core.novel.graph.memory

import org.json.JSONObject

/**
 * 记忆图谱（P1-4 双轨记忆 · 图谱轨）的四张附属表模型。
 *
 * ## 五表对照（移植自 novelIDE database_helper 的 V7 记忆系统表）
 * | 本仓（JSON） | novelIDE（SQLite） | 职责 |
 * |---|---|---|
 * | `memories.json`（既有 [com.ai.assistance.quro.core.novel.graph.NovelMemory]） | `memories` | 记忆节点 |
 * | `memory_tags.json`（[NovelMemoryTag]） | `memory_tags` | 层级标签 |
 * | `memory_tag_relations.json`（[NovelMemoryTagRelation]） | `memory_tag_relations` | 节点与标签的多对多 |
 * | `memory_links.json`（[NovelMemoryLink]） | `memory_links` | 记忆图谱的边（source 指向 target） |
 * | `memory_properties.json`（[NovelMemoryProperty]） | `memory_properties` | 节点的键值属性 |
 *
 * ## 与 SQLite 版的差异（有意为之）
 * - 节点 id 用字符串 UUID（与全仓其它域一致），不是 SQLite 自增整数；四张附属表仍用自增整数主键，
 *   由存储层按文件内容分配（见 NovelMemoryStore 的 nextId）。
 * - 不引入 SQLite / Room：`core/novel` 域保持零 Android 依赖、可 JVM 单测（与 GraphStore 同风格）。
 * - 标签既是「表 2 的实体」也在节点 [com.ai.assistance.quro.core.novel.graph.NovelMemory.tags] 上保留
 *   名称快照：节点侧是展示与检索的快路径，关系表是结构化真源，由存储层在写入时保持同步。
 */

/** 表 2 `memory_tags`：层级标签（[parentId] 为空 = 顶层）。 */
data class NovelMemoryTag(
    val id: Long,
    val name: String,
    val parentId: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        if (parentId != null) put("parentId", parentId) else put("parentId", JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelMemoryTag = NovelMemoryTag(
            id = o.optLong("id", 0L),
            name = o.optString("name", ""),
            parentId = if (o.isNull("parentId")) null else o.optLong("parentId", 0L),
        )
    }
}

/** 表 3 `memory_tag_relations`：记忆与标签的多对多关系。 */
data class NovelMemoryTagRelation(
    val memoryId: String,
    val tagId: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("memoryId", memoryId)
        put("tagId", tagId)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelMemoryTagRelation = NovelMemoryTagRelation(
            memoryId = o.optString("memoryId", ""),
            tagId = o.optLong("tagId", 0L),
        )
    }
}

/** 表 4 `memory_links`：记忆图谱的边。 */
data class NovelMemoryLink(
    val id: Long,
    val sourceId: String,
    val targetId: String,
    /** 关系类型：related=相关 / cause=因果 / foreshadow=伏笔 / resolve=回收 / same_person=同一人（自由文本）。 */
    val type: String = TYPE_RELATED,
    /** 边强度（0.0~1.0）：参与图谱检索的邻域加权与副作用排序。 */
    val weight: Double = 1.0,
    val description: String = "",
) {
    /** 另一端 id（给定一端求对端；两端都不是则返回空串）。 */
    fun other(memoryId: String): String = when (memoryId) {
        sourceId -> targetId
        targetId -> sourceId
        else -> ""
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sourceId", sourceId)
        put("targetId", targetId)
        put("type", type)
        put("weight", weight)
        put("description", description)
    }

    companion object {
        const val TYPE_RELATED = "related"
        const val TYPE_CAUSE = "cause"
        const val TYPE_FORESHADOW = "foreshadow"
        const val TYPE_RESOLVE = "resolve"
        const val TYPE_SAME_PERSON = "same_person"

        fun fromJson(o: JSONObject): NovelMemoryLink = NovelMemoryLink(
            id = o.optLong("id", 0L),
            sourceId = o.optString("sourceId", ""),
            targetId = o.optString("targetId", ""),
            type = o.optString("type", TYPE_RELATED),
            weight = o.optDouble("weight", 1.0).let { if (it.isNaN()) 1.0 else it },
            description = o.optString("description", ""),
        )
    }
}

/** 表 5 `memory_properties`：节点的键值属性（如 人物=林晚、章节=第三章）。 */
data class NovelMemoryProperty(
    val id: Long,
    val memoryId: String,
    val key: String,
    val value: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("memoryId", memoryId)
        put("key", key)
        put("value", value)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelMemoryProperty = NovelMemoryProperty(
            id = o.optLong("id", 0L),
            memoryId = o.optString("memoryId", ""),
            key = o.optString("key", ""),
            value = o.optString("value", ""),
        )
    }
}
