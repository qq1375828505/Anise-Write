package com.ai.assistance.quro.core.novel.workflow

import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.NovelAsset
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository

/**
 * Phase 3b 资料仓储适配：把 [NovelAssetRepository] 包成核心层可用的 [MaterialSource]。
 *
 * 放在独立文件（而非 `ChapterWorkflowModels.kt`）是为了让核心模型文件保持**零 Android 依赖**——
 * [NovelAssetRepository] 内部用 `android.util.Log` 与 `android.content.Context`，
 * 而 `ChapterWorkflowModels.kt` 要能被 JVM 单测直接构造。
 *
 * 语义一一对应，不加兜底、不加缓存：仓储层已有的「索引损坏即按目录重建」等自愈策略保持原样。
 */
class RepositoryMaterialSource(
    private val repository: NovelAssetRepository,
) : MaterialSource {

    override fun list(workId: String, type: AssetType): List<NovelAsset> =
        repository.list(workId, type)

    override fun listAll(workId: String): List<NovelAsset> =
        repository.listAll(workId)

    override fun get(workId: String, type: AssetType, id: String): NovelAsset? =
        repository.get(workId, type, id)

    override fun search(workId: String, keyword: String, type: AssetType?): List<NovelAsset> =
        repository.search(workId, keyword, type)
}
