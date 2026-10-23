package com.ai.assistance.quro.core.novel.graph.memory

import com.ai.assistance.quro.core.novel.graph.NovelMemory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 写作记忆图谱存储（P1-4 双轨记忆 · 图谱轨）：五张表落五个 JSON 文件。
 *
 * ## 目录布局（与 GraphStore 同仓同风格，`filesDir/novel/graph/{workId}/`）
 * ```
 * memories.json               # 表 1 记忆节点（既有文件，字段向后兼容扩展）
 * memory_tags.json            # 表 2 层级标签
 * memory_tag_relations.json   # 表 3 节点与标签的多对多
 * memory_links.json           # 表 4 图谱的边
 * memory_properties.json      # 表 5 节点键值属性
 * ```
 *
 * ## 设计约定
 * - 每作品单文件全量读改写成盘（同 GraphStore），原子写防半截文件。
 * - 不依赖 Android（无 Context 取用、无 Log）：`core` 域保持可 JVM 单测；坏文件按空表处理，
 *   记忆可由 AI 重新提炼，用户正文资产不在这里。
 * - 删除节点时级联清理它的标签关系、边、属性（对应 SQLite 版的 ON DELETE CASCADE）。
 * - [NovelMemory.tags]（标签名快照）与表 2 表 3 在写入时同步，避免两套真相漂移。
 */
class NovelMemoryStore(private val rootDir: File) {

    private val lock = Any()

    // ─────────────────────────── 表 1：记忆节点 ───────────────────────────

    fun listMemories(workId: String): List<NovelMemory> = synchronized(lock) { readMemories(workId) }

    fun getMemory(workId: String, memoryId: String): NovelMemory? =
        listMemories(workId).firstOrNull { it.id == memoryId }

    /** 新增或按 id 覆盖（id 为空则生成 UUID）；同时同步标签表与关系表。 */
    fun addMemory(memory: NovelMemory): NovelMemory = synchronized(lock) {
        val now = System.currentTimeMillis()
        val items = readMemories(workId = memory.workId).toMutableList()
        val existing = items.indexOfFirst { it.id == memory.id && memory.id.isNotBlank() }
        val stored = memory.copy(
            id = if (memory.id.isBlank()) UUID.randomUUID().toString() else memory.id,
            createdAt = when {
                memory.createdAt != 0L -> memory.createdAt
                existing >= 0 -> items[existing].createdAt
                else -> now
            },
            updatedAt = now,
            lastAccessedAt = if (memory.lastAccessedAt != 0L) memory.lastAccessedAt else now,
        )
        if (existing >= 0) items[existing] = stored else items.add(stored)
        writeMemories(stored.workId, items)
        syncTags(stored.workId, stored.id, stored.tags)
        stored
    }

    fun updateMemory(workId: String, memoryId: String, transform: (NovelMemory) -> NovelMemory): NovelMemory? =
        synchronized(lock) {
            val items = readMemories(workId).toMutableList()
            val idx = items.indexOfFirst { it.id == memoryId }
            if (idx < 0) return null
            val updated = transform(items[idx]).copy(
                id = memoryId,
                workId = workId,
                createdAt = items[idx].createdAt,
                updatedAt = System.currentTimeMillis(),
            )
            items[idx] = updated
            writeMemories(workId, items)
            syncTags(workId, memoryId, updated.tags)
            updated
        }

    /** 删除节点并级联清理标签关系、边、属性（对应 SQLite 的 ON DELETE CASCADE）。 */
    fun deleteMemory(workId: String, memoryId: String): Boolean = synchronized(lock) {
        val items = readMemories(workId).toMutableList()
        val removed = items.removeIf { it.id == memoryId }
        if (!removed) return false
        writeMemories(workId, items)
        writeTagRelations(workId, readTagRelations(workId).filterNot { it.memoryId == memoryId })
        writeProperties(workId, readProperties(workId).filterNot { it.memoryId == memoryId })
        writeLinks(workId, readLinks(workId).filterNot { it.sourceId == memoryId || it.targetId == memoryId })
        true
    }

    /** 记录访问时间（检索命中后调用，供「最近用到」排序与陈旧记忆清理参考）。 */
    fun touchAccessed(workId: String, memoryIds: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (memoryIds.isEmpty()) return
        synchronized(lock) {
            val ids = memoryIds.toSet()
            val items = readMemories(workId)
            var dirty = false
            val updated = items.map { m ->
                if (ids.contains(m.id)) {
                    dirty = true
                    m.copy(lastAccessedAt = now)
                } else m
            }
            if (dirty) writeMemories(workId, updated)
        }
    }

    // ─────────────────────────── 表 2 / 表 3：标签与关系 ───────────────────────────

    fun listTags(workId: String): List<NovelMemoryTag> = synchronized(lock) { readTags(workId) }

    fun listTagRelations(workId: String): List<NovelMemoryTagRelation> =
        synchronized(lock) { readTagRelations(workId) }

    /** 取某节点挂的标签实体（按名称字典序）。 */
    fun tagsOf(workId: String, memoryId: String): List<NovelMemoryTag> = synchronized(lock) {
        val tagIds = readTagRelations(workId).filter { it.memoryId == memoryId }.map { it.tagId }.toSet()
        readTags(workId).filter { tagIds.contains(it.id) }.sortedBy { it.name }
    }

    /** 取某节点挂的标签名（检索标签路的输入）。 */
    fun tagNamesOf(workId: String, memoryId: String): List<String> = tagsOf(workId, memoryId).map { it.name }

    /** 给节点挂标签（已有标签复用，新标签建实体）；返回挂完后的标签实体表。 */
    fun attachTags(workId: String, memoryId: String, names: List<String>): List<NovelMemoryTag> =
        synchronized(lock) {
            syncTags(workId, memoryId, names)
            tagsOf(workId, memoryId)
        }

    // ─────────────────────────── 表 4：边 ───────────────────────────

    fun listLinks(workId: String): List<NovelMemoryLink> = synchronized(lock) { readLinks(workId) }

    fun linksOf(workId: String, memoryId: String): List<NovelMemoryLink> =
        listLinks(workId).filter { it.sourceId == memoryId || it.targetId == memoryId }

    /**
     * 加一条边（自环、端点不存在、重复边一律拒绝，返回 null）。
     * 重复判定：同向同类型的边只保留一条（重复加返回既有边）。
     */
    fun addLink(
        workId: String,
        sourceId: String,
        targetId: String,
        type: String = NovelMemoryLink.TYPE_RELATED,
        weight: Double = 1.0,
        description: String = "",
    ): NovelMemoryLink? = synchronized(lock) {
        if (sourceId.isBlank() || targetId.isBlank() || sourceId == targetId) return null
        val nodes = readMemories(workId)
        if (nodes.none { it.id == sourceId } || nodes.none { it.id == targetId }) return null
        val items = readLinks(workId).toMutableList()
        val existing = items.firstOrNull {
            it.sourceId == sourceId && it.targetId == targetId && it.type == type
        }
        if (existing != null) return existing
        val stored = NovelMemoryLink(
            id = nextId(items.map { it.id }),
            sourceId = sourceId,
            targetId = targetId,
            type = type.ifBlank { NovelMemoryLink.TYPE_RELATED },
            weight = weight.coerceIn(0.0, 1.0),
            description = description,
        )
        items.add(stored)
        writeLinks(workId, items)
        stored
    }

    fun deleteLink(workId: String, linkId: Long): Boolean = synchronized(lock) {
        val items = readLinks(workId).toMutableList()
        val removed = items.removeIf { it.id == linkId }
        if (removed) writeLinks(workId, items)
        removed
    }

    // ─────────────────────────── 表 5：属性 ───────────────────────────

    fun listProperties(workId: String): List<NovelMemoryProperty> = synchronized(lock) { readProperties(workId) }

    fun propertiesOf(workId: String, memoryId: String): Map<String, String> =
        listProperties(workId).filter { it.memoryId == memoryId }.associate { it.key to it.value }

    /** 写属性（同键覆盖）。 */
    fun setProperty(workId: String, memoryId: String, key: String, value: String): NovelMemoryProperty? =
        synchronized(lock) {
            if (memoryId.isBlank() || key.isBlank()) return null
            val items = readProperties(workId).toMutableList()
            val idx = items.indexOfFirst { it.memoryId == memoryId && it.key == key }
            val stored = if (idx >= 0) {
                items[idx].copy(value = value).also { items[idx] = it }
            } else {
                NovelMemoryProperty(
                    id = nextId(items.map { it.id }),
                    memoryId = memoryId,
                    key = key,
                    value = value,
                ).also { items.add(it) }
            }
            writeProperties(workId, items)
            stored
        }

    fun deleteProperty(workId: String, memoryId: String, key: String): Boolean = synchronized(lock) {
        val items = readProperties(workId).toMutableList()
        val removed = items.removeIf { it.memoryId == memoryId && it.key == key }
        if (removed) writeProperties(workId, items)
        removed
    }

    // ─────────────────────────── 检索与图谱 ───────────────────────────

    /**
     * RRF 混合检索（关键词 + 标签 + 语义 + 图谱邻域）。
     *
     * @param kind 类型硬过滤（空 = 不过滤）
     * @param limit 截断条数
     */
    fun search(
        workId: String,
        query: String,
        kind: String = "",
        config: NovelMemorySearch.Config = NovelMemorySearch.Config(),
        limit: Int = 10,
        threshold: Double = 0.0,
    ): List<NovelMemorySearch.Hit> {
        val all = listMemories(workId)
        val candidates = if (kind.isBlank()) all else all.filter { it.kind == kind }
        return NovelMemorySearch.search(
            query = query,
            candidates = candidates,
            config = config,
            tagsOf = { id -> tagNamesOf(workId, id) },
            linksOf = { id -> linksOf(workId, id) },
            threshold = threshold,
        ).take(limit.coerceAtLeast(1))
    }

    /** 图谱导出（节点 + 边），供记忆图谱工具与界面使用。 */
    fun graphJson(workId: String, nodeLimit: Int = 200, edgeLimit: Int = 400): JSONObject = synchronized(lock) {
        val nodes = readMemories(workId)
        val links = readLinks(workId)
        val nodeArr = JSONArray().also { arr ->
            nodes.take(nodeLimit).forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("title", m.title.ifBlank { m.content.take(20) })
                    put("kind", m.kind)
                    put("importance", m.importance)
                    put("tags", JSONArray().also { t -> tagNamesOf(workId, m.id).forEach { t.put(it) } })
                })
            }
        }
        val edgeArr = JSONArray().also { arr ->
            links.take(edgeLimit).forEach { l ->
                arr.put(JSONObject().apply {
                    put("id", l.id)
                    put("source", l.sourceId)
                    put("target", l.targetId)
                    put("type", l.type)
                    put("weight", l.weight)
                })
            }
        }
        return JSONObject().apply {
            put("nodes", nodeArr)
            put("edges", edgeArr)
            put("nodeCount", nodes.size)
            put("edgeCount", links.size)
            put("tagCount", readTags(workId).size)
            put("propertyCount", readProperties(workId).size)
            if (nodes.size > nodeLimit) put("nodesTruncated", true)
            if (links.size > edgeLimit) put("edgesTruncated", true)
        }
    }

    // ─────────────────────────── 内部：标签同步 ───────────────────────────

    /** 让表 2 表 3 与给定的标签名列表一致（新名字建实体、消失的名字断关系、标签实体不删）。 */
    private fun syncTags(workId: String, memoryId: String, names: List<String>) {
        val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val tags = readTags(workId).toMutableList()
        val wantedIds = wanted.map { name ->
            val found = tags.firstOrNull { it.name == name }
            if (found != null) found.id else {
                val created = NovelMemoryTag(id = nextId(tags.map { it.id }), name = name)
                tags.add(created)
                created.id
            }
        }
        writeTags(workId, tags)
        val relations = readTagRelations(workId).filterNot { it.memoryId == memoryId }.toMutableList()
        wantedIds.forEach { tid -> relations.add(NovelMemoryTagRelation(memoryId = memoryId, tagId = tid)) }
        writeTagRelations(workId, relations)
    }

    // ─────────────────────────── 内部：文件属于哪张表 ───────────────────────────

    private fun workDir(workId: String): File = File(rootDir, workId)

    private fun memFile(workId: String): File = File(workDir(workId), "memories.json")
    private fun tagFile(workId: String): File = File(workDir(workId), "memory_tags.json")
    private fun tagRelFile(workId: String): File = File(workDir(workId), "memory_tag_relations.json")
    private fun linkFile(workId: String): File = File(workDir(workId), "memory_links.json")
    private fun propFile(workId: String): File = File(workDir(workId), "memory_properties.json")

    private fun readMemories(workId: String): List<NovelMemory> =
        readItems(memFile(workId)) { NovelMemory.fromJson(it) }

    private fun readTags(workId: String): List<NovelMemoryTag> =
        readItems(tagFile(workId)) { NovelMemoryTag.fromJson(it) }

    private fun readTagRelations(workId: String): List<NovelMemoryTagRelation> =
        readItems(tagRelFile(workId)) { NovelMemoryTagRelation.fromJson(it) }

    private fun readLinks(workId: String): List<NovelMemoryLink> =
        readItems(linkFile(workId)) { NovelMemoryLink.fromJson(it) }

    private fun readProperties(workId: String): List<NovelMemoryProperty> =
        readItems(propFile(workId)) { NovelMemoryProperty.fromJson(it) }

    private fun writeMemories(workId: String, items: List<NovelMemory>) =
        writeItems(memFile(workId), items) { it.toJson() }

    private fun writeTags(workId: String, items: List<NovelMemoryTag>) =
        writeItems(tagFile(workId), items) { it.toJson() }

    private fun writeTagRelations(workId: String, items: List<NovelMemoryTagRelation>) =
        writeItems(tagRelFile(workId), items) { it.toJson() }

    private fun writeLinks(workId: String, items: List<NovelMemoryLink>) =
        writeItems(linkFile(workId), items) { it.toJson() }

    private fun writeProperties(workId: String, items: List<NovelMemoryProperty>) =
        writeItems(propFile(workId), items) { it.toJson() }

    /** 读条目数组；文件缺失或损坏一律按空表处理（记忆可重建，不在这里造假数据）。 */
    private fun <T> readItems(file: File, parse: (JSONObject) -> T): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parse(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse { emptyList() }
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

    /** 自增整数主键：既有最大 id 加一（附属表用，与 SQLite 的 AUTOINCREMENT 等价语义）。 */
    private fun nextId(existing: List<Long>): Long =
        (existing.maxOrNull() ?: 0L) + 1L

    companion object {
        private const val VERSION = 2
    }
}
