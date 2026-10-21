package com.ai.assistance.quro.core.novel.graph

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.novel.graph.hook.NovelHook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * PenAi 图谱域数据层（Phase 3c）：人物关系 + 写作记忆的 JSON 文件持久化。
 *
 * ## 目录布局（`filesDir/novel/graph/`，与 Phase 1/3b 同风格）
 * ```
 * filesDir/novel/graph/
 * └── {workId}/
 *     ├── relationships.json   # {"version":1,"items":[NovelRelationship...]}
 *     └── memories.json        # {"version":1,"items":[NovelMemory...]}
 * ```
 *
 * ## 设计约定
 * - 图谱域条目是**轻量小对象**（单条 < 1KB），量级为全书几十~几百条，与资料域
 *   「一记录一文件」不同，采用**每作品单文件**（同 quro_conversations.json 风格），
 *   读改写全量重落盘即可，原子写防半截文件。
 * - 关系以**人物名**为键（不是 id）：人物名在小说语境里是天然主键（改名 = 剧情事件），
 *   且允许引用尚未建档的人物（一致性检查会提示 info）。
 * - 所有公开方法线程安全（内部同一把锁）。
 * - 伏笔（NovelHook）同落本仓（`hooks.json`）：Phase 3c 早期已交付模型（graph/hook/NovelHook.kt），
 *   持久化与台账管理统一走本仓，`novel_hooks_status` 的状态报表与生命周期操作共用。
 */
class GraphStore(private val rootDir: File) {

    private val lock = Any()

    // ─────────────────────────── 伏笔表 ───────────────────────────

    fun listHooks(workId: String): List<NovelHook> = synchronized(lock) {
        readItems(hookFile(workId)) { NovelHook.fromJson(it) }
    }

    fun addHook(hook: NovelHook): NovelHook = synchronized(lock) {
        val items = readItems(hookFile(hook.workId)) { NovelHook.fromJson(it) }.toMutableList()
        val now = System.currentTimeMillis()
        val stored = hook.copy(
            id = if (hook.id.isBlank()) UUID.randomUUID().toString() else hook.id,
            createdAt = if (hook.createdAt == 0L) now else hook.createdAt,
            updatedAt = now,
        )
        items.add(stored)
        writeItems(hookFile(hook.workId), items) { it.toJson() }
        stored
    }

    fun updateHook(workId: String, hookId: String, transform: (NovelHook) -> NovelHook): NovelHook? =
        synchronized(lock) {
            val items = readItems(hookFile(workId)) { NovelHook.fromJson(it) }.toMutableList()
            val idx = items.indexOfFirst { it.id == hookId }
            if (idx < 0) return null
            val now = System.currentTimeMillis()
            val updated = transform(items[idx]).copy(id = hookId, workId = workId, updatedAt = now)
            items[idx] = updated
            writeItems(hookFile(workId), items) { it.toJson() }
            updated
        }

    fun deleteHook(workId: String, hookId: String): Boolean = synchronized(lock) {
        val items = readItems(hookFile(workId)) { NovelHook.fromJson(it) }.toMutableList()
        val removed = items.removeIf { it.id == hookId }
        if (removed) writeItems(hookFile(workId), items) { it.toJson() }
        removed
    }

    // ─────────────────────────── 关系表 ───────────────────────────

    fun listRelationships(workId: String): List<NovelRelationship> = synchronized(lock) {
        readItems(relFile(workId)) { NovelRelationship.fromJson(it) }
    }

    fun addRelationship(rel: NovelRelationship): NovelRelationship = synchronized(lock) {
        val items = readItems(relFile(rel.workId)) { NovelRelationship.fromJson(it) }.toMutableList()
        val now = System.currentTimeMillis()
        val stored = rel.copy(id = if (rel.id.isBlank()) UUID.randomUUID().toString() else rel.id, createdAt = now, updatedAt = now)
        items.add(stored)
        writeItems(relFile(rel.workId), items) { it.toJson() }
        stored
    }

    fun updateRelationship(workId: String, relId: String, transform: (NovelRelationship) -> NovelRelationship): NovelRelationship? =
        synchronized(lock) {
            val items = readItems(relFile(workId)) { NovelRelationship.fromJson(it) }.toMutableList()
            val idx = items.indexOfFirst { it.id == relId }
            if (idx < 0) return null
            val now = System.currentTimeMillis()
            val updated = transform(items[idx]).copy(id = relId, workId = workId, updatedAt = now)
            items[idx] = updated
            writeItems(relFile(workId), items) { it.toJson() }
            updated
        }

    fun deleteRelationship(workId: String, relId: String): Boolean = synchronized(lock) {
        val items = readItems(relFile(workId)) { NovelRelationship.fromJson(it) }.toMutableList()
        val removed = items.removeIf { it.id == relId }
        if (removed) writeItems(relFile(workId), items) { it.toJson() }
        removed
    }

    // ─────────────────────────── 记忆表 ───────────────────────────

    fun listMemories(workId: String): List<NovelMemory> = synchronized(lock) {
        readItems(memFile(workId)) { NovelMemory.fromJson(it) }
    }

    fun addMemory(memory: NovelMemory): NovelMemory = synchronized(lock) {
        val items = readItems(memFile(memory.workId)) { NovelMemory.fromJson(it) }.toMutableList()
        val now = System.currentTimeMillis()
        val stored = memory.copy(id = if (memory.id.isBlank()) UUID.randomUUID().toString() else memory.id, createdAt = now, updatedAt = now)
        items.add(stored)
        writeItems(memFile(memory.workId), items) { it.toJson() }
        stored
    }

    fun deleteMemory(workId: String, memoryId: String): Boolean = synchronized(lock) {
        val items = readItems(memFile(workId)) { NovelMemory.fromJson(it) }.toMutableList()
        val removed = items.removeIf { it.id == memoryId }
        if (removed) writeItems(memFile(workId), items) { it.toJson() }
        removed
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private fun relFile(workId: String): File = rootDir.resolve(workId).resolve("relationships.json")
    private fun memFile(workId: String): File = rootDir.resolve(workId).resolve("memories.json")
    private fun hookFile(workId: String): File = rootDir.resolve(workId).resolve("hooks.json")

    /** 读条目数组；文件缺失/损坏返回空列表（记日志，不假数据兜底——图谱可重建，用户资产在章节正文里）。 */
    private fun <T> readItems(file: File, parse: (JSONObject) -> T): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parse(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse { e ->
            Log.w(TAG, "readItems: ${file.name} 损坏，返回空列表", e)
            emptyList()
        }
    }

    private fun <T> writeItems(file: File, items: List<T>, toJson: (T) -> JSONObject) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        val root = JSONObject().apply {
            put("version", VERSION)
            put("items", arr)
        }
        file.parentFile?.mkdirs()
        atomicWrite(file, root.toString())
    }

    /** 原子写：先写临时文件再 rename（同 Phase 1「崩溃不留半截文件」）。 */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "GraphStore"
        private const val VERSION = 1

        /** 基座约定：图谱域数据落 `filesDir/novel/graph/`。 */
        fun fromContext(context: Context): GraphStore =
            GraphStore(File(context.filesDir, "novel/graph"))
    }
}

/**
 * 人物关系（Phase 3c）：规划的「关系三表」在工具层的落地取最小闭环——
 * 关系表（方向：from→to，人物名为键）+ 参与者隐含在端点里；事件表待 Phase 6 图谱 UI 落地时再加。
 *
 * @param strength 强度 1-5（1=点头之交 … 5=生死羁绊），映射 Phase 6 关系图的边宽
 * @param color 自定义颜色（#RRGGBB，可空串=用默认主题色），映射 Phase 6 关系图的边色
 */
data class NovelRelationship(
    val id: String,
    val workId: String,
    val fromName: String,
    val toName: String,
    /** 关系类型：亲人/恋人/师徒/盟友/竞争/仇敌/上下级/其他（自由文本，预设写在工具 schema 描述里）。 */
    val type: String,
    val strength: Int = 3,
    val color: String = "",
    val description: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("fromName", fromName)
        put("toName", toName)
        put("type", type)
        put("strength", strength)
        put("color", color)
        put("description", description)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelRelationship = NovelRelationship(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            fromName = o.optString("fromName", ""),
            toName = o.optString("toName", ""),
            type = o.optString("type", "其他"),
            strength = o.optInt("strength", 3).coerceIn(1, 5),
            color = o.optString("color", ""),
            description = o.optString("description", ""),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/**
 * 写作记忆（Phase 3c）：跨章节需要记住的事实/决定/时间线事件，供写章时回灌上下文。
 * 规划的「双轨记忆」在数据层的最小闭环 = 单表 + 标签 + 章节锚点快照（ChapterRef）；
 * 「近期章节快照」文本轨由 [novel_query_previous] 按需即时检索正文承担，不重复存储。
 *
 * @param kind fact=事实 / decision=决定 / timeline=时间线 / setting=设定补充（自由文本）
 * @param chapterRef 锚定章节快照（可空：全书级记忆无锚点）
 */
data class NovelMemory(
    val id: String,
    val workId: String,
    val content: String,
    val kind: String = "fact",
    val tags: List<String> = emptyList(),
    val chapterRef: ChapterRef? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("content", content)
        put("kind", kind)
        put("tags", JSONArray().also { arr -> tags.forEach { arr.put(it) } })
        chapterRef?.let { put("chapterRef", it.toJson()) }
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): NovelMemory = NovelMemory(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            content = o.optString("content", ""),
            kind = o.optString("kind", "fact"),
            tags = o.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
            } ?: emptyList(),
            chapterRef = o.optJSONObject("chapterRef")?.let { ChapterRef.fromJson(it) },
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}
