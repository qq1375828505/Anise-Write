package com.ai.assistance.quro.core.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [QuroTaskWeightRouter] 的行为契约测试（P1-5 重/轻任务自动路由）。
 *
 * 钉死的语义：
 * - 明确轻（polish / 单章 review）→ 轻量组；
 * - 明确重（outline / consistency / 多章 review）→ 默认组；
 * - 未点名（foreshadow / character）→ 保守向重；
 * - 未知子代理 → 不干预（null，回落 same_as_me，绝不瞎猜）。
 */
class QuroTaskWeightRouterTest {

    private fun range(from: Int, to: Int): JSONObject =
        JSONObject().put("from", from).put("to", to)

    // ------------------------------------------------------------------ 明确轻

    @Test
    fun `polish 是轻任务 - 走轻量组`() {
        assertEquals(QuroTaskWeightRouter.Weight.LIGHT, QuroTaskWeightRouter.classify("polish"))
        assertEquals(QuroModelIntent.LIGHT_GROUP, QuroTaskWeightRouter.inferIntent("polish"))
    }

    @Test
    fun `review 单章 - chapter_id 在场`() {
        val w = QuroTaskWeightRouter.classify("review", chapterRange = null, chapterId = "ch_047")
        assertEquals(QuroTaskWeightRouter.Weight.LIGHT, w)
        assertEquals(QuroModelIntent.LIGHT_GROUP, QuroTaskWeightRouter.inferIntent("review", chapterId = "ch_047"))
    }

    @Test
    fun `review 单章 - range from 等于 to`() {
        assertEquals(
            QuroTaskWeightRouter.Weight.LIGHT,
            QuroTaskWeightRouter.classify("review", chapterRange = range(88, 88)),
        )
    }

    // ------------------------------------------------------------------ 明确重

    @Test
    fun `review 多章 - 走默认组`() {
        assertEquals(
            QuroTaskWeightRouter.Weight.HEAVY,
            QuroTaskWeightRouter.classify("review", chapterRange = range(81, 100)),
        )
        assertEquals(
            QuroModelIntent.DEFAULT_GROUP,
            QuroTaskWeightRouter.inferIntent("review", chapterRange = range(81, 100)),
        )
    }

    @Test
    fun `review 范围不明 - 保守向重`() {
        assertEquals(QuroTaskWeightRouter.Weight.HEAVY, QuroTaskWeightRouter.classify("review"))
    }

    @Test
    fun `outline consistency 是重任务`() {
        assertEquals(QuroTaskWeightRouter.Weight.HEAVY, QuroTaskWeightRouter.classify("outline"))
        assertEquals(QuroTaskWeightRouter.Weight.HEAVY, QuroTaskWeightRouter.classify("consistency"))
        assertEquals(QuroModelIntent.DEFAULT_GROUP, QuroTaskWeightRouter.inferIntent("outline"))
        assertEquals(QuroModelIntent.DEFAULT_GROUP, QuroTaskWeightRouter.inferIntent("consistency"))
    }

    @Test
    fun `foreshadow 与 character - 未点名者保守向重`() {
        assertEquals(QuroTaskWeightRouter.Weight.HEAVY, QuroTaskWeightRouter.classify("foreshadow"))
        assertEquals(QuroTaskWeightRouter.Weight.HEAVY, QuroTaskWeightRouter.classify("character"))
    }

    // ------------------------------------------------------------------ 不猜

    @Test
    fun `未知子代理 - 不干预`() {
        assertNull(QuroTaskWeightRouter.classify("mystery_agent"))
        assertNull(QuroTaskWeightRouter.inferIntent("mystery_agent"))
        assertNull(QuroTaskWeightRouter.classify(""))
    }

    @Test
    fun `range 不完整时回退到 chapter_id 判据`() {
        val half = JSONObject().put("from", 5) // 缺 to
        assertEquals(
            QuroTaskWeightRouter.Weight.LIGHT,
            QuroTaskWeightRouter.classify("review", chapterRange = half, chapterId = "ch_005"),
        )
        assertEquals(
            QuroTaskWeightRouter.Weight.HEAVY,
            QuroTaskWeightRouter.classify("review", chapterRange = half, chapterId = ""),
        )
    }
}
