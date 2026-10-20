package com.ai.assistance.quro.core.novel.graph.hook

import com.ai.assistance.quro.core.novel.graph.ChapterRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * PenAi 伏笔（Phase 3c · 图谱域）。
 *
 * 对应规划书 §6.1 伏笔域（novel_hooks_*）与 §6.4 数据层的伏笔跟踪：一条伏笔记录
 * 「在哪一章埋、打算在哪收、关联哪些人物、优先级多高、现在是埋着/收了/废了」。
 *
 * 状态机（[NovelHookStatus]）：
 * ```
 * add ──► OPEN(已埋) ──close(回收章节)──► CLOSED(已回收)
 *            │  │                             │
 *            │  └──abandon(废弃)──► ABANDONED(已废弃) ──reopen──┐
 *            └────────────────────reopen(重启)─────────────────┤
 * ```
 * CLOSED 是终态（回收章节已记录，重新打开等于篡改已发生的事实，只能废弃后重埋）；
 * OPEN ↔ ABANDONED 可逆。
 */
data class NovelHook(
    val id: String,
    val workId: String,
    /** 伏笔名称（一句话，如「老者留下的玉佩」）。 */
    val title: String,
    /** 伏笔内容/埋伏意图（可选长文本）。 */
    val description: String = "",
    val status: NovelHookStatus = NovelHookStatus.OPEN,
    val priority: NovelHookPriority = NovelHookPriority.NORMAL,
    /** 埋设章节快照（章节被删后仍可读，一致性检查会告警）。 */
    val plantedAt: ChapterRef? = null,
    /** 回收章节快照（仅 CLOSED 有值）。 */
    val resolvedAt: ChapterRef? = null,
    /** 关联人物名列表（图谱域以人物名为键，见 RelationshipRepository KDoc）。 */
    val characterNames: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 闲置章数：OPEN 状态下距埋设章已过的章数（无埋设锚点或已回收为 null）。 */
    fun idleChapters(currentOrder: Int): Int? {
        if (status != NovelHookStatus.OPEN) return null
        val planted = plantedAt ?: return null
        return (currentOrder - planted.order).coerceAtLeast(0)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("title", title)
        put("description", description)
        put("status", status.key)
        put("priority", priority.key)
        plantedAt?.let { put("plantedAt", it.toJson()) }
        resolvedAt?.let { put("resolvedAt", it.toJson()) }
        put("characterNames", JSONArray().also { arr -> characterNames.forEach { n -> arr.put(n) } })
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelHook = NovelHook(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            title = o.optString("title", ""),
            description = o.optString("description", ""),
            status = NovelHookStatus.fromKey(o.optString("status", NovelHookStatus.OPEN.key)),
            priority = NovelHookPriority.fromKey(o.optString("priority", NovelHookPriority.NORMAL.key)),
            plantedAt = o.optJSONObject("plantedAt")?.let { ChapterRef.fromJson(it) },
            resolvedAt = o.optJSONObject("resolvedAt")?.let { ChapterRef.fromJson(it) },
            characterNames = o.optJSONArray("characterNames")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
            } ?: emptyList(),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 伏笔状态：已埋 / 已回收 / 已废弃（规划书 §6.1「已埋/已回收/已废弃 + 闲置章数统计」）。 */
enum class NovelHookStatus(val key: String, val label: String) {
    OPEN("open", "已埋"),
    CLOSED("closed", "已回收"),
    ABANDONED("abandoned", "已废弃"),
    ;

    companion object {
        /** 旧数据/损坏数据无匹配 key 时回落 OPEN（新伏笔初值）。 */
        fun fromKey(key: String?): NovelHookStatus =
            entries.firstOrNull { it.key == key } ?: OPEN
    }
}

/** 伏笔优先级：决定一致性告警的排序与上下文组装的取舍顺序。 */
enum class NovelHookPriority(val key: String, val label: String, val level: Int) {
    LOW("low", "低", 1),
    NORMAL("normal", "中", 2),
    HIGH("high", "高", 3),
    ;

    companion object {
        fun fromKey(key: String?): NovelHookPriority =
            entries.firstOrNull { it.key == key } ?: NORMAL
    }
}
