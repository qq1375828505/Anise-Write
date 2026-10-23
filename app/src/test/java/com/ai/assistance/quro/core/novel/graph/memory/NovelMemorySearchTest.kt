package com.ai.assistance.quro.core.novel.graph.memory

import com.ai.assistance.quro.core.novel.graph.NovelMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelMemorySearch] 的行为契约测试（P1-4 图谱轨 RRF 混合检索）。
 *
 * 钉死的语义（对齐 novelIDE `memory_repository.dart` 的移植目标）：
 * - 关键词路按 RRF 倒数排名（K=60）：标题命中排在正文命中之前；
 * - 覆盖加成 0.6：命中词元越多得分越高；
 * - 评分模式乘数：关键词优先 1.3、语义优先 0.8；
 * - 标签权重 > 0 时标签命中直接加权；
 * - 图谱邻域：沿边走一跳，邻居获得「边权 × 边权乘数 ÷（K + 种子排名）」；
 * - 阈值过滤与 `*` 全量短路。
 */
class NovelMemorySearchTest {

    private fun mem(id: String, title: String = "", content: String = "", tags: List<String> = emptyList()) =
        NovelMemory(id = id, workId = "w1", content = content, title = title, tags = tags)

    private fun link(source: String, target: String, weight: Double = 1.0) =
        NovelMemoryLink(id = 1L, sourceId = source, targetId = target, weight = weight)

    // ------------------------------------------------------------------ 词元

    @Test
    fun `切词 - 含竖线按或切`() {
        assertEquals(listOf("玉佩", "当铺"), NovelMemorySearch.splitKeywords("玉佩|当铺"))
    }

    @Test
    fun `切词 - 空白分词`() {
        assertEquals(listOf("玉佩", "当铺"), NovelMemorySearch.splitKeywords("  玉佩   当铺 "))
    }

    @Test
    fun `词元保留 - 太短或太长都丢`() {
        assertFalse(NovelMemorySearch.shouldKeepToken("玉"))
        assertTrue(NovelMemorySearch.shouldKeepToken("玉佩"))
        assertFalse(NovelMemorySearch.shouldKeepToken("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(NovelMemorySearch.shouldKeepToken("……"))
    }

    @Test
    fun `通配符匹配`() {
        assertTrue(NovelMemorySearch.textMatchesToken("玉佩抵押给了当铺", "玉*当"))
        assertFalse(NovelMemorySearch.textMatchesToken("玉佩抵押给了当铺", "玉*银"))
    }

    // ------------------------------------------------------------------ 向量

    @Test
    fun `余弦相似度`() {
        assertEquals(1.0, NovelMemorySearch.cosineSimilarity(listOf(1.0, 0.0), listOf(1.0, 0.0)), 1e-9)
        assertEquals(0.0, NovelMemorySearch.cosineSimilarity(listOf(1.0, 0.0), listOf(0.0, 1.0)), 1e-9)
        assertEquals(0.0, NovelMemorySearch.cosineSimilarity(listOf(1.0), listOf(1.0, 2.0)), 1e-9)
    }

    // ------------------------------------------------------------------ RRF 排名

    @Test
    fun `标题命中排在正文命中之前`() {
        val titleHit = mem("a", title = "玉佩抵押")
        val contentHit = mem("b", content = "她把玉佩抵押了")
        val hits = NovelMemorySearch.search("玉佩", listOf(contentHit, titleHit))
        assertEquals(2, hits.size)
        assertEquals("a", hits[0].memory.id)
        assertEquals(1, hits[0].keywordRank)
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test
    fun `覆盖加成 - 命中词元更多得分更高`() {
        val both = mem("a", content = "玉佩 抵押")
        val one = mem("b", content = "玉佩")
        val hits = NovelMemorySearch.search("玉佩 抵押", listOf(one, both))
        assertEquals("a", hits[0].memory.id)
        assertTrue(hits[0].matchedTokens > hits[1].matchedTokens)
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test
    fun `评分模式 - 关键词优先放大 1_3 倍`() {
        val m = mem("a", title = "玉佩")
        val balanced = NovelMemorySearch.search("玉佩", listOf(m)).first().score
        val keywordFirst = NovelMemorySearch.search(
            "玉佩", listOf(m),
            config = NovelMemorySearch.Config(scoreMode = NovelMemorySearch.ScoreMode.KEYWORD_FIRST),
        ).first().score
        assertEquals(1.3, keywordFirst / balanced, 1e-9)
    }

    // ------------------------------------------------------------------ 标签路

    @Test
    fun `标签权重 - 命中标签按次数加权`() {
        val m = mem("a", content = "与关键词无关的内容")
        val hits = NovelMemorySearch.search(
            "林晚",
            listOf(m),
            config = NovelMemorySearch.Config(tagWeight = 5.0),
            tagsOf = { listOf("林晚", "玉佩") },
        )
        assertEquals(1, hits.size)
        assertEquals(5.0, hits[0].score, 1e-9)
    }

    @Test
    fun `标签权重为零 - 标签不参与`() {
        val m = mem("a", content = "与关键词无关的内容")
        val hits = NovelMemorySearch.search("林晚", listOf(m), tagsOf = { listOf("林晚") })
        assertTrue(hits.isEmpty())
    }

    // ------------------------------------------------------------------ 图谱邻域

    @Test
    fun `图谱邻域 - 沿边带出一跳邻居`() {
        val seed = mem("a", title = "玉佩抵押")
        val neighbor = mem("b", content = "中元节赎回")
        val hits = NovelMemorySearch.search(
            "玉佩",
            listOf(seed, neighbor),
            linksOf = { id -> if (id == "a") listOf(link("a", "b")) else emptyList() },
        )
        assertEquals(2, hits.size)
        val viaLink = hits.first { it.memory.id == "b" }
        assertTrue(viaLink.viaLink)
        // 1/(60+1) × 0.4 × 1.0 = 0.006557…
        assertEquals(1.0 / 61.0 * 0.4, viaLink.score, 1e-9)
    }

    @Test
    fun `图谱邻域 - 边权为零则整路跳过`() {
        val seed = mem("a", title = "玉佩抵押")
        val neighbor = mem("b", content = "中元节赎回")
        val hits = NovelMemorySearch.search(
            "玉佩",
            listOf(seed, neighbor),
            config = NovelMemorySearch.Config(edgeWeight = 0.0),
            linksOf = { listOf(link("a", "b")) },
        )
        assertEquals(1, hits.size)
    }

    // ------------------------------------------------------------------ 阈值与短路

    @Test
    fun `阈值过滤`() {
        val seed = mem("a", title = "玉佩抵押")
        val neighbor = mem("b", content = "中元节赎回")
        val hits = NovelMemorySearch.search(
            "玉佩",
            listOf(seed, neighbor),
            linksOf = { listOf(link("a", "b")) },
            threshold = 0.1,
        )
        assertEquals(1, hits.size)
        assertEquals("a", hits[0].memory.id)
    }

    @Test
    fun `星号查询 - 全量返回且得分为零`() {
        val hits = NovelMemorySearch.search("*", listOf(mem("a"), mem("b")))
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.score == 0.0 })
    }

    @Test
    fun `空查询 - 返回全量且得分为零`() {
        val hits = NovelMemorySearch.search("   ", listOf(mem("a")))
        assertEquals(1, hits.size)
        assertEquals(0.0, hits[0].score, 1e-9)
    }

    @Test
    fun `无候选或查询无有效词元 - 返回空`() {
        assertTrue(NovelMemorySearch.search("玉佩", emptyList()).isEmpty())
        assertTrue(NovelMemorySearch.search("……", listOf(mem("a"))).isEmpty())
    }
}
