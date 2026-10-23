package com.ai.assistance.quro.core.novel.graph.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelTextMemory] 与 [NovelTextMemoryCache] 的行为契约测试（P1-4 文本轨）。
 *
 * 钉死的语义（对齐 novelIDE `novel_memory.dart` 的移植目标）：
 * - 十节齐全，节标题与节序固定；
 * - 第三节只取最近 5 章；
 * - 伏笔闲置超过 10 章打 ⚠️ 标记；
 * - 单条摘要按 120 字截断；
 * - 缓存 5 分钟 TTL、命中不重建、过期重建、可失效。
 */
class NovelTextMemoryTest {

    private fun chapter(order: Int, title: String, wordCount: Int = 0, summary: String = "") =
        NovelTextMemory.ChapterLine(order = order, title = title, wordCount = wordCount, status = "draft", summary = summary)

    private fun sampleInput(chapterCount: Int = 7): NovelTextMemory.Input = NovelTextMemory.Input(
        work = NovelTextMemory.WorkInfo(
            id = "w1",
            title = "玉佩记",
            genre = "古代言情",
            totalWordCount = 12000,
            chapterCount = chapterCount,
            description = "林晚的玉佩之旅",
        ),
        volumes = listOf(
            NovelTextMemory.VolumeBlock(
                title = "第一卷",
                chapters = (1..chapterCount).map { chapter(it, "第${it}章") },
            ),
        ),
        characters = listOf(
            NovelTextMemory.CharacterLine(name = "林晚", role = "主角", description = "玉佩持有者", personality = "倔强"),
        ),
        settings = listOf(NovelTextMemory.AssetLine(name = "修炼体系", category = "力量", description = "九境")),
        locations = listOf(NovelTextMemory.AssetLine(name = "临安城", description = "故事主城")),
        factions = listOf(NovelTextMemory.AssetLine(name = "青云宗", extras = listOf("首领" to "玄真"))),
        items = listOf(NovelTextMemory.AssetLine(name = "玉佩", key = true, extras = listOf("持有者" to "林晚"))),
        hooks = listOf(
            NovelTextMemory.HookLine(title = "玉佩的来历", description = "未解之谜", resolved = false, idleChapters = 12),
            NovelTextMemory.HookLine(title = "当铺线索", resolved = true),
        ),
        references = listOf(NovelTextMemory.ReferenceLine(title = "唐史", source = "书")),
        updatedAt = 1000L,
    )

    // ------------------------------------------------------------------ 十节

    @Test
    fun `十节齐全 - 每个节标题都出现且顺序固定`() {
        val text = NovelTextMemory.build(sampleInput())
        var cursor = -1
        NovelTextMemory.SECTION_TITLES.forEach { title ->
            val idx = text.indexOf("═══ $title ═══")
            assertTrue("缺少节：$title", idx > 0)
            assertTrue("节顺序不对：$title", idx > cursor)
            cursor = idx
        }
        assertEquals(10, NovelTextMemory.SECTION_TITLES.size)
    }

    @Test
    fun `头部与页脚 - 自动生成声明与更新时间`() {
        val text = NovelTextMemory.build(sampleInput())
        assertTrue(text.contains("小说记忆文件"))
        assertTrue(text.contains("请勿手动编辑"))
        assertTrue(text.contains("更新时间:"))
    }

    // ------------------------------------------------------------------ 第三节

    @Test
    fun `最近章节 - 只取最近 5 章`() {
        val text = NovelTextMemory.build(sampleInput(chapterCount = 7))
        val section = text.substringAfter("═══ ${NovelTextMemory.SECTION_TITLES[2]} ═══")
            .substringBefore("═══ ${NovelTextMemory.SECTION_TITLES[3]} ═══")
        assertTrue(section.contains("第7章"))
        assertTrue(section.contains("第3章"))
        assertTrue(!section.contains("第2章"))
        assertTrue(!section.contains("第1章"))
    }

    @Test
    fun `无摘要 - 占位显示`() {
        val text = NovelTextMemory.build(sampleInput(chapterCount = 1))
        assertTrue(text.contains("（无摘要）"))
    }

    // ------------------------------------------------------------------ 第九节

    @Test
    fun `伏笔 - 闲置超阈值打警告 已回收单独成组`() {
        val text = NovelTextMemory.build(sampleInput())
        assertTrue(text.contains("⚠️闲置12章"))
        assertTrue(text.contains("【未回收伏笔】(1条)"))
        assertTrue(text.contains("【已回收伏笔】(1条)"))
    }

    // ------------------------------------------------------------------ 截断

    @Test
    fun `长文本按 120 字截断`() {
        val long = "字".repeat(400)
        val input = sampleInput().copy(
            characters = listOf(NovelTextMemory.CharacterLine(name = "林晚", description = long)),
        )
        val text = NovelTextMemory.build(input)
        assertTrue(text.contains("字".repeat(NovelTextMemory.EXCERPT_LIMIT) + "…"))
        assertTrue(!text.contains("字".repeat(NovelTextMemory.EXCERPT_LIMIT + 1)))
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    fun `缓存 - TTL 内命中不重建 过期后重建`() {
        var now = 0L
        val cache = NovelTextMemoryCache { now }
        var builds = 0
        val first = cache.get("w1") { builds++; "v$builds" }
        val second = cache.get("w1") { builds++; "v$builds" }
        assertEquals("v1", first)
        assertEquals("v1", second)
        assertEquals(1, builds)

        now = NovelTextMemory.CACHE_TTL_MS - 1
        assertEquals("v1", cache.get("w1") { builds++; "v$builds" })
        assertEquals(1, builds)

        now = NovelTextMemory.CACHE_TTL_MS
        assertEquals("v2", cache.get("w1") { builds++; "v$builds" })
        assertEquals(2, builds)
    }

    @Test
    fun `缓存 - 按作品隔离且可单独失效`() {
        var builds = 0
        val cache = NovelTextMemoryCache { 0L }
        cache.get("w1") { builds++; "a$builds" }
        cache.get("w2") { builds++; "b$builds" }
        assertEquals(2, builds)
        cache.invalidate("w1")
        assertNull(cache.peek("w1"))
        assertTrue(cache.isFresh("w2"))
        cache.invalidate()
        assertNull(cache.peek("w2"))
    }
}
