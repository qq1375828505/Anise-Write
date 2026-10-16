package com.ai.assistance.quro.core.novel

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * PenAi 小说数据层（Phase 1）：作品 / 卷 / 章节的 JSON 文件持久化。
 *
 * ## 目录布局（`filesDir/novel/`，与基座 filesDir JSON 风格一致，不引入 Room）
 * ```
 * filesDir/novel/
 * ├── works.json                  # 元数据索引：全部作品 → 卷 → 章节（不含正文）
 * │                                #   结构 {"version":1,"works":[{...,"volumes":[],"chapters":[]}]}
 * └── chapters/
 *     └── {workId}/               # 每作品一个子目录（删作品即整目录删除 = 级联删除）
 *         └── {chapterId}.md      # 章节正文（UTF-8 纯文本/Markdown）
 * ```
 *
 * ## 设计约定
 * - 正文存 `.md` 文件、元数据存 JSON（规划书 §6.4）；杀进程重进后从磁盘完整恢复。
 * - 所有写操作先进 [atomicWrite] 落盘再更新内存索引，崩溃不留半截文件。
 * - 所有公开方法线程安全（内部同一把锁），UI 与后续 AI 工具可并发调用。
 * - Phase 1 不做：8 类资料 / 伏笔 / 关系图 / 记忆（对应目录 materials/outline/memory
 *   在 Phase 3+ 落地，版本号 version 字段为那时的迁移预留）。
 * - 富文本编辑器（:novel-editor，Phase 7）落盘后同样是一个 `.md` 文件，与本层无耦合。
 */
class NovelRepository(private val rootDir: File) {

    private val lock = Any()

    private val indexFile: File get() = File(rootDir, INDEX_NAME)
    private val chaptersRoot: File get() = File(rootDir, CHAPTERS_DIR_NAME)

    // ─────────────────────────── 读 ───────────────────────────

    /** 加载全部作品（含卷/章节元数据）。索引缺失或整体损坏时返回空列表。 */
    fun loadWorks(): List<NovelWork> = synchronized(lock) {
        runCatching {
            if (!indexFile.exists()) return emptyList()
            val text = indexFile.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            val arr = JSONObject(text).optJSONArray("works") ?: return emptyList()
            val works = mutableListOf<NovelWork>()
            for (i in 0 until arr.length()) {
                runCatching { arr.getJSONObject(i) }
                    .getOrNull()
                    ?.let { runCatching { NovelWork.fromJson(it) }.getOrNull() }
                    ?.let { works.add(it) }
            }
            works.sortedWith(compareBy({ it.createdAt }, { it.title }))
        }.getOrElse { e ->
            // 索引整体不可解析时不闪退：知识库/会话持久化同款策略——记日志 + 空列表，
            // 用户可在工作台重建结构（正文 .md 文件不受影响，不删用户数据）。
            Log.w(TAG, "loadWorks: 索引损坏，返回空列表", e)
            emptyList()
        }
    }

    /** 读取章节正文；未写过的章节返回空串（文件不存在是正常态，不是错误）。 */
    fun readChapterContent(workId: String, chapterId: String): String = synchronized(lock) {
        val f = chapterFile(workId, chapterId)
        if (!f.exists()) "" else runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
    }

    // ─────────────────────────── 作品 ───────────────────────────

    /**
     * 新建作品（自动带一个默认卷，默认卷名由 UI 层传入以支持本地化）。
     * @param defaultVolumeTitle 默认卷标题（如「第一卷」）
     */
    fun createWork(title: String, genre: String, defaultVolumeTitle: String): NovelWork =
        synchronized(lock) {
            require(title.isNotBlank()) { "作品名不能为空" }
            val now = System.currentTimeMillis()
            val work = NovelWork(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                genre = genre.trim(),
                createdAt = now,
                updatedAt = now,
            )
            val volume = NovelVolume(
                id = UUID.randomUUID().toString(),
                workId = work.id,
                title = defaultVolumeTitle.trim().ifBlank { "第一卷" },
                order = 0,
                createdAt = now,
                updatedAt = now,
            )
            saveIndex(loadRawWorks() + work.copy(volumes = listOf(volume)))
            work.copy(volumes = listOf(volume))
        }

    /** 删除作品：级联删除其下全部卷、章节元数据与 `chapters/{workId}/` 正文文件。 */
    fun deleteWork(workId: String): Boolean = synchronized(lock) {
        val works = loadRawWorks()
        val target = works.firstOrNull { it.id == workId } ?: return false
        chaptersRoot.resolve(workId).deleteRecursively()
        saveIndex(works.filterNot { it.id == workId })
        Log.i(TAG, "deleteWork: ${target.title}($workId) 级联删除 ${target.chapters.size} 章")
        true
    }

    // ─────────────────────────── 卷 ───────────────────────────

    /** 在作品下新建卷（order 自动排到末尾）。 */
    fun createVolume(workId: String, title: String): NovelVolume = synchronized(lock) {
        require(title.isNotBlank()) { "卷名不能为空" }
        val works = loadRawWorks()
        val idx = works.indexOfFirst { it.id == workId }
        if (idx < 0) throw IllegalArgumentException("作品不存在: $workId")
        val now = System.currentTimeMillis()
        val volume = NovelVolume(
            id = UUID.randomUUID().toString(),
            workId = workId,
            title = title.trim(),
            order = (works[idx].volumes.maxOfOrNull { it.order } ?: -1) + 1,
            createdAt = now,
            updatedAt = now,
        )
        works[idx] = works[idx].copy(
            volumes = works[idx].volumes + volume,
            updatedAt = now,
        )
        saveIndex(works)
        volume
    }

    /** 删除卷：级联删除其下全部章节元数据与正文文件。 */
    fun deleteVolume(workId: String, volumeId: String): Boolean = synchronized(lock) {
        val works = loadRawWorks()
        val idx = works.indexOfFirst { it.id == workId }
        if (idx < 0) return false
        val work = works[idx]
        val doomed = work.chapters.filter { it.volumeId == volumeId }
        if (work.volumes.none { it.id == volumeId }) return false
        doomed.forEach { chapterFile(workId, it.id).delete() }
        val now = System.currentTimeMillis()
        works[idx] = work.copy(
            volumes = work.volumes.filterNot { it.id == volumeId },
            chapters = work.chapters.filterNot { it.volumeId == volumeId },
            updatedAt = now,
        )
        saveIndex(works)
        Log.i(TAG, "deleteVolume: $volumeId 级联删除 ${doomed.size} 章")
        true
    }

    // ─────────────────────────── 章节 ───────────────────────────

    /** 在卷下新建章节（order 自动排到末尾，初值 status=UNWRITTEN）。 */
    fun createChapter(workId: String, volumeId: String, title: String): NovelChapter = synchronized(lock) {
        require(title.isNotBlank()) { "章节名不能为空" }
        val works = loadRawWorks()
        val idx = works.indexOfFirst { it.id == workId }
        if (idx < 0) throw IllegalArgumentException("作品不存在: $workId")
        val work = works[idx]
        if (work.volumes.none { it.id == volumeId }) throw IllegalArgumentException("卷不存在: $volumeId")
        val now = System.currentTimeMillis()
        val chapter = NovelChapter(
            id = UUID.randomUUID().toString(),
            volumeId = volumeId,
            title = title.trim(),
            order = (work.chapters.filter { it.volumeId == volumeId }.maxOfOrNull { it.order } ?: -1) + 1,
            status = NovelChapterStatus.UNWRITTEN,
            wordCount = 0,
            createdAt = now,
            updatedAt = now,
        )
        works[idx] = work.copy(chapters = work.chapters + chapter, updatedAt = now)
        saveIndex(works)
        chapter
    }

    /** 删除章节：元数据 + 正文 `.md` 文件一并删除。 */
    fun deleteChapter(workId: String, chapterId: String): Boolean = synchronized(lock) {
        val works = loadRawWorks()
        val idx = works.indexOfFirst { it.id == workId }
        if (idx < 0) return false
        val work = works[idx]
        if (work.chapters.none { it.id == chapterId }) return false
        chapterFile(workId, chapterId).delete()
        val now = System.currentTimeMillis()
        works[idx] = work.copy(chapters = work.chapters.filterNot { it.id == chapterId }, updatedAt = now)
        saveIndex(works)
        true
    }

    /**
     * 保存章节正文（原子写 `.md` 文件），并回写元数据：字数、updatedAt。
     *
     * 状态机流转（Phase 1 数据层唯一自动流转，只升不降）：
     * UNWRITTEN + 正文非空 → DRAFT；POLISHING / COMPLETED 保持原状。
     *
     * @return 更新后的章节元数据
     */
    fun writeChapterContent(workId: String, chapterId: String, content: String): NovelChapter =
        synchronized(lock) {
            val works = loadRawWorks()
            val idx = works.indexOfFirst { it.id == workId }
            if (idx < 0) throw IllegalArgumentException("作品不存在: $workId")
            val work = works[idx]
            val cidx = work.chapters.indexOfFirst { it.id == chapterId }
            if (cidx < 0) throw IllegalArgumentException("章节不存在: $chapterId")
            val old = work.chapters[cidx]
            // 先落正文（用户资产优先），再更新索引
            val f = chapterFile(workId, chapterId)
            f.parentFile?.mkdirs()
            atomicWrite(f, content)
            val now = System.currentTimeMillis()
            val status = if (old.status == NovelChapterStatus.UNWRITTEN && content.isNotBlank()) {
                NovelChapterStatus.DRAFT
            } else {
                old.status
            }
            val updated = old.copy(
                status = status,
                wordCount = countWords(content),
                updatedAt = now,
            )
            val chapters = work.chapters.toMutableList().also { it[cidx] = updated }
            works[idx] = work.copy(chapters = chapters, updatedAt = now)
            saveIndex(works)
            updated
        }

    // ─────────────────────────── 内部 ───────────────────────────

    private fun chapterFile(workId: String, chapterId: String): File =
        chaptersRoot.resolve(workId).resolve("$chapterId.md")

    private fun loadRawWorks(): MutableList<NovelWork> = loadWorks().toMutableList()

    private fun saveIndex(works: List<NovelWork>) {
        val arr = JSONArray()
        works.forEach { arr.put(it.toJson()) }
        val root = JSONObject().apply {
            put("version", INDEX_VERSION)
            put("works", arr)
        }
        rootDir.mkdirs()
        atomicWrite(indexFile, root.toString())
    }

    /** 原子写：先写临时文件再 rename，避免进程被杀时留下半截文件（用户正文资产优先）。 */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // rename 失败（极少见：目标被占用/跨卷）：退回直接覆盖，保证写入不丢
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "NovelRepo"
        private const val INDEX_NAME = "works.json"
        private const val CHAPTERS_DIR_NAME = "chapters"

        /** 索引格式版本：Phase 3+ 增加资料/伏笔/关系图时递增并做迁移。 */
        const val INDEX_VERSION = 1

        /** 基座约定：数据落 `filesDir/novel/`（同 quro_conversations.json 的 filesDir JSON 风格）。 */
        fun fromContext(context: Context): NovelRepository =
            NovelRepository(File(context.filesDir, "novel"))
    }
}
