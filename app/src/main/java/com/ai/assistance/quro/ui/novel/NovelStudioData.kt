package com.ai.assistance.quro.ui.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelChapter
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.NovelVolume
import com.ai.assistance.quro.core.novel.NovelWork
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.NovelAsset
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository
import com.ai.assistance.quro.core.novel.graph.GraphStore
import com.ai.assistance.quro.core.novel.graph.NovelRelationship
import com.ai.assistance.quro.core.novel.graph.hook.NovelHook

/**
 * 写作工作台数据层：把三个仓储（作品/资料/图谱）汇成一份界面快照。
 *
 * 设计约束（承接 Phase 5 契约对齐）：
 * - **界面不直接摸仓储**，只读 [NovelStudioSnapshot]；
 * - 仓储全部由 `fromContext` 构造，根目录沿用基座 `filesDir/novel/`；
 * - 读取是同步 IO（基座 JSON 文件量级），一律在协程里调用，不阻塞主线程。
 */
data class NovelStudioSnapshot(
    val works: List<NovelWork> = emptyList(),
    val selectedWorkId: String? = null,
    val assets: List<NovelAsset> = emptyList(),
    val hooks: List<NovelHook> = emptyList(),
    val relationships: List<NovelRelationship> = emptyList(),
    val loading: Boolean = false,
) {
    /** 当前作品：优先取选中项，没有则取第一本。 */
    val selectedWork: NovelWork?
        get() = works.firstOrNull { it.id == selectedWorkId } ?: works.firstOrNull()

    val volumes: List<NovelVolume>
        get() = selectedWork?.volumes ?: emptyList()

    fun chaptersOf(volumeId: String): List<NovelChapter> =
        selectedWork?.chaptersOfVolume(volumeId) ?: emptyList()

    val chapterCount: Int get() = selectedWork?.chapters?.size ?: 0

    val totalWords: Int get() = selectedWork?.totalWordCount() ?: 0

    fun assetCount(type: AssetType): Int = assets.count { it.type == type }

    fun assetsOf(type: AssetType): List<NovelAsset> = assets.filter { it.type == type }

    val openHookCount: Int get() = hooks.count { it.status.key != "closed" }

    val closedHookCount: Int get() = hooks.count { it.status.key == "closed" }

    /** 关系图节点：按人物名去重。 */
    val graphNodes: List<String>
        get() = relationships.flatMap { listOf(it.fromName, it.toName) }
            .filter { it.isNotBlank() }
            .distinct()

    /** 近 7 天有更新的章节字数（近似「本周字数」）。 */
    fun recentWords(now: Long = System.currentTimeMillis()): Int {
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        return selectedWork?.chapters?.filter { it.updatedAt >= weekAgo }?.sumOf { it.wordCount } ?: 0
    }
}

/** 仓储封装：读 + 最小写操作（新建作品/卷/章、写正文）。 */
class NovelStudioData(context: Context) {

    private val repo = NovelRepository.fromContext(context)
    private val assetRepo = NovelAssetRepository.fromContext(context)
    private val graph = GraphStore.fromContext(context)

    /** 读取全量快照。 */
    fun load(selectedWorkId: String? = null): NovelStudioSnapshot {
        val works = repo.loadWorks()
        val work = works.firstOrNull { it.id == selectedWorkId } ?: works.firstOrNull()
        return NovelStudioSnapshot(
            works = works,
            selectedWorkId = work?.id,
            assets = work?.let { assetRepo.listAll(it.id) } ?: emptyList(),
            hooks = work?.let { graph.listHooks(it.id) } ?: emptyList(),
            relationships = work?.let { graph.listRelationships(it.id) } ?: emptyList(),
            loading = false,
        )
    }

    fun createWork(title: String, genre: String = ""): NovelWork =
        repo.createWork(title, genre, "第一卷")

    fun createVolume(workId: String, title: String): NovelVolume =
        repo.createVolume(workId, title)

    fun createChapter(workId: String, volumeId: String, title: String): NovelChapter =
        repo.createChapter(workId, volumeId, title)

    fun deleteChapter(workId: String, chapterId: String): Boolean =
        repo.deleteChapter(workId, chapterId)

    fun readChapterContent(workId: String, chapterId: String): String =
        repo.readChapterContent(workId, chapterId)

    fun writeChapterContent(workId: String, chapterId: String, content: String): NovelChapter =
        repo.writeChapterContent(workId, chapterId, content)

    fun setChapterStatus(workId: String, chapterId: String, status: com.ai.assistance.quro.core.novel.NovelChapterStatus): NovelChapter =
        repo.setChapterStatus(workId, chapterId, status)
}
