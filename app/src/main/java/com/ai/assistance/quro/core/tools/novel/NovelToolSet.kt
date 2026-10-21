package com.ai.assistance.quro.core.tools.novel

import com.ai.assistance.quro.core.tools.QuroToolRegistry

/**
 * 网文写作域工具集（PenAi）。
 *
 * 挂载方式与基座其它域保持一致（参照 core/cluster/ClusterTools.kt 的 ClusterToolSet）：
 * 在基座统一注册处（core/tools/QuroBuiltInTools.kt 的 buildQuroRegistry）加一行
 * `NovelToolSet.registerAll(r)` 即可。
 *
 * 工具类全部为无参构造，实现体在同包各 *Tools.kt 内：
 *   - NovelWriteTools.kt     （Phase 2）
 *   - NovelStructureTools.kt （Phase 3a）
 *   - NovelAssetTools.kt     （Phase 3b）
 */
object NovelToolSet {

    fun registerAll(r: QuroToolRegistry) {
        // Phase 2 · 文风 / 写章 / 续写 / 润色
        r.register(NovelWriteTool())
        r.register(NovelContinueTool())
        r.register(NovelPolishTool())
        // Phase 3a · 结构域
        r.register(NovelListWorksTool())
        r.register(NovelCreateWorkTool())
        r.register(NovelDeleteWorkTool())
        r.register(NovelGetOutlineTool())
        r.register(NovelSaveOutlineTool())
        r.register(NovelCreateVolumeTool())
        r.register(NovelDeleteVolumeTool())
        r.register(NovelCreateChapterTool())
        r.register(NovelDeleteChapterTool())
        r.register(NovelChapterStatsTool())
        r.register(NovelGetChapterTool())
        r.register(NovelUpdateWorkTool())
        r.register(NovelChapterStatusTool())
        // Phase 3b · 资料域
        r.register(NovelMaterialsGetTool())
        r.register(NovelMaterialsListTool())
        r.register(NovelMaterialsSearchTool())
        r.register(NovelMaterialsAddTool())
        r.register(NovelMaterialsUpdateTool())
        r.register(NovelMaterialsDeleteTool())
    }
}
