package com.ai.assistance.quro.core.model

import org.json.JSONObject

/**
 * 重/轻任务自动路由（P1-5）——novelIDE `model_router` 策略的移植与升级。
 *
 * 背景：模型组给委派方留了意图三选一（`same_as_me` / `default_model` / `sub_model`），
 * 但「模型有时候就是不说」——未给 `model_group` 时旧行为一律回落父会话模型，
 * 于是「重任务本该走强模型、轻任务本该走便宜模型」的策略落不了地。
 * 本路由在**委派方未显式表达意图**时，按任务的客观重量补一个意图：
 *
 * - 重任务 → [QuroModelIntent.DEFAULT_GROUP]（默认组，通常最强）；
 * - 轻任务 → [QuroModelIntent.LIGHT_GROUP]（轻量组，通常更快更便宜）；
 * - 判不准 → null（调用方保持 `same_as_me`，**不猜**）。
 *
 * 判定表（保守原则：**拿不准向重靠**——质量损失比成本损失更贵）：
 *
 * | 子代理 | 重量 | 依据 |
 * |---|---|---|
 * | polish | 轻 | SPEC §1「润色/标题候选：快、便宜的小模型即可」；DELEGATION §5「轻任务（polish 单章）可端侧」 |
 * | review（单章） | 轻 | 同上（review 单章） |
 * | review（多章/全书/未明） | 重 | SPEC §1「一致性审查/全文审读：强模型 + 长上下文」 |
 * | outline | 重 | 「细纲生成：强模型、长上下文」 |
 * | consistency | 重 | 「一致性审查：强模型 + 长上下文」 |
 * | foreshadow / character | 重 | 未点名的生成/审计类，保守向重 |
 * | 未知子代理 | 不干预（null） | 不猜 |
 *
 * 优先级不变：**用户钉住 > 显式意图 > 本路由** —— 本路由只填补「什么都没说」的空白。
 * 目标组不可用时由 [QuroModelGroupRouter.resolveForCall] 静默回落父会话模型（非钉住场景不吵闹）。
 */
object QuroTaskWeightRouter {

    /** 任务重量。 */
    enum class Weight { LIGHT, HEAVY }

    /**
     * 按委派参数判定任务重量；判不准返回 null（不干预）。
     *
     * @param subagent 已解析的子代理 id（outline / character / foreshadow / polish / review / consistency）
     * @param chapterRange 章节范围（可空；`{from,to}`）
     * @param chapterId 单章 id（可空串）
     */
    fun classify(
        subagent: String,
        chapterRange: JSONObject? = null,
        chapterId: String = "",
    ): Weight? = when (subagent) {
        // 明确轻：润色 / 单章审读
        "polish" -> Weight.LIGHT
        "review" -> if (isSingleChapter(chapterRange, chapterId)) Weight.LIGHT else Weight.HEAVY
        // 明确重：细纲 / 一致性 / 全文审读；未点名者保守向重（伏笔审计、人物生成推导）
        "outline", "consistency", "foreshadow", "character" -> Weight.HEAVY
        // 未知：不猜
        else -> null
    }

    /** 重量 → 委派意图；判不准 → null（调用方回落 `same_as_me`）。 */
    fun inferIntent(
        subagent: String,
        chapterRange: JSONObject? = null,
        chapterId: String = "",
    ): QuroModelIntent? = when (classify(subagent, chapterRange, chapterId)) {
        Weight.LIGHT -> QuroModelIntent.LIGHT_GROUP
        Weight.HEAVY -> QuroModelIntent.DEFAULT_GROUP
        null -> null
    }

    /** 单章判据：range 完整时以 `from == to` 为准；range 不完整/缺省时看有没有 chapter_id。 */
    private fun isSingleChapter(chapterRange: JSONObject?, chapterId: String): Boolean {
        if (chapterRange != null && chapterRange.has("from") && chapterRange.has("to")) {
            return chapterRange.optInt("from", -1) == chapterRange.optInt("to", -2)
        }
        return chapterId.isNotBlank()
    }
}
