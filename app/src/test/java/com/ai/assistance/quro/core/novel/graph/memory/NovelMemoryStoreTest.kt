package com.ai.assistance.quro.core.novel.graph.memory

import com.ai.assistance.quro.core.novel.graph.NovelMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [NovelMemoryStore] 的行为契约测试（P1-4 图谱轨五表落地）。
 *
 * 钉死的语义（对齐 novelIDE SQLite 版 V7 记忆表的移植目标）：
 * - 五张表落五个 JSON 文件，读改写成对；
 * - 节点保存时标签名同步进 memory_tags + memory_tag_relations；
 * - 删节点级联清掉它的标签关系、边、属性（等价 ON DELETE CASCADE）；
 * - 连边拒绝自环与不存在的端点，重复边幂等返回既有边；
 * - 图谱视图计数与表内容一致。
 */
class NovelMemoryStoreTest {

    private fun newStore(): NovelMemoryStore =
        NovelMemoryStore(Files.createTempDirectory("aw-memory").toFile())

    private fun memory(id: String = "", content: String = "林晚把玉佩抵押给了当铺", tags: List<String> = emptyList()) =
        NovelMemory(id = id, workId = "w1", content = content, tags = tags)

    // ------------------------------------------------------------------ 节点

    @Test
    fun `新增 - 生成 id 且可读回`() {
        val store = newStore()
        val saved = store.addMemory(memory())
        assertTrue(saved.id.isNotBlank())
        assertEquals(1, store.listMemories("w1").size)
        assertEquals(saved.id, store.getMemory("w1", saved.id)?.id)
    }

    @Test
    fun `同 id 覆盖 - 不产生重复条目`() {
        val store = newStore()
        val first = store.addMemory(memory(content = "旧内容"))
        val second = store.addMemory(first.copy(content = "新内容"))
        assertEquals(first.id, second.id)
        assertEquals(1, store.listMemories("w1").size)
        assertEquals("新内容", store.getMemory("w1", first.id)?.content)
    }

    // ------------------------------------------------------------------ 标签同步

    @Test
    fun `标签同步 - 名称进标签表并在关系表建立关联`() {
        val store = newStore()
        val saved = store.addMemory(memory(tags = listOf("林晚", "玉佩")))
        assertEquals(2, store.listTags("w1").size)
        assertEquals(listOf("林晚", "玉佩"), store.tagNamesOf("w1", saved.id).sorted())
        assertEquals(2, store.listTagRelations("w1").size)
    }

    @Test
    fun `标签复用 - 同名标签不重复建实体`() {
        val store = newStore()
        val a = store.addMemory(memory(tags = listOf("林晚")))
        val b = store.addMemory(memory(tags = listOf("林晚")))
        assertEquals(1, store.listTags("w1").size)
        assertEquals(2, store.listTagRelations("w1").size)
        assertEquals(listOf("林晚"), store.tagNamesOf("w1", a.id))
        assertEquals(listOf("林晚"), store.tagNamesOf("w1", b.id))
    }

    // ------------------------------------------------------------------ 级联删除

    @Test
    fun `删除 - 级联清理标签关系 边与属性`() {
        val store = newStore()
        val a = store.addMemory(memory(tags = listOf("林晚")))
        val b = store.addMemory(memory(content = "中元节赎回"))
        assertNotNull(store.addLink("w1", a.id, b.id))
        assertNotNull(store.setProperty("w1", a.id, "人物", "林晚"))

        assertTrue(store.deleteMemory("w1", a.id))
        assertEquals(1, store.listMemories("w1").size)
        assertTrue(store.listTagRelations("w1").none { it.memoryId == a.id })
        assertTrue(store.listLinks("w1").isEmpty())
        assertTrue(store.listProperties("w1").isEmpty())
        // 标签实体本身保留（其它记忆可能还在用）
        assertEquals(1, store.listTags("w1").size)
    }

    @Test
    fun `删除 - 不存在的 id 返回 false 且不动数据`() {
        val store = newStore()
        store.addMemory(memory())
        assertFalse(store.deleteMemory("w1", "missing"))
        assertEquals(1, store.listMemories("w1").size)
    }

    // ------------------------------------------------------------------ 边

    @Test
    fun `连边 - 自环与不存在端点被拒绝`() {
        val store = newStore()
        val a = store.addMemory(memory())
        assertNull(store.addLink("w1", a.id, a.id))
        assertNull(store.addLink("w1", a.id, "missing"))
        assertNull(store.addLink("w1", "missing", a.id))
    }

    @Test
    fun `连边 - 同向同类型幂等`() {
        val store = newStore()
        val a = store.addMemory(memory())
        val b = store.addMemory(memory(content = "中元节赎回"))
        val first = store.addLink("w1", a.id, b.id, type = NovelMemoryLink.TYPE_CAUSE)
        val again = store.addLink("w1", a.id, b.id, type = NovelMemoryLink.TYPE_CAUSE)
        assertEquals(first?.id, again?.id)
        assertEquals(1, store.listLinks("w1").size)
        // 反向或换类型算另一条边
        store.addLink("w1", b.id, a.id, type = NovelMemoryLink.TYPE_RESOLVE)
        assertEquals(2, store.listLinks("w1").size)
    }

    @Test
    fun `边的对端解析`() {
        val link = NovelMemoryLink(id = 1L, sourceId = "a", targetId = "b")
        assertEquals("b", link.other("a"))
        assertEquals("a", link.other("b"))
        assertEquals("", link.other("c"))
    }

    // ------------------------------------------------------------------ 属性

    @Test
    fun `属性 - 同键覆盖`() {
        val store = newStore()
        val a = store.addMemory(memory())
        store.setProperty("w1", a.id, "章节", "第三章")
        store.setProperty("w1", a.id, "章节", "第四章")
        assertEquals(mapOf("章节" to "第四章"), store.propertiesOf("w1", a.id))
        assertEquals(1, store.listProperties("w1").size)
    }

    // ------------------------------------------------------------------ 检索与图谱

    @Test
    fun `检索 - 关键词命中按 RRF 排序`() {
        val store = newStore()
        store.addMemory(memory(content = "林晚把玉佩抵押给了当铺"))
        store.addMemory(memory(content = "与检索无关的内容"))
        val hits = store.search("w1", "玉佩")
        assertEquals(1, hits.size)
        assertTrue(hits[0].score > 0.0)
    }

    @Test
    fun `检索 - 类型过滤`() {
        val store = newStore()
        store.addMemory(memory(content = "玉佩抵押").copy(kind = "fact"))
        store.addMemory(memory(content = "玉佩归属").copy(kind = "decision"))
        val hits = store.search("w1", "玉佩", kind = "decision")
        assertEquals(1, hits.size)
        assertEquals("decision", hits[0].memory.kind)
    }

    @Test
    fun `图谱视图 - 计数与表内容一致`() {
        val store = newStore()
        val a = store.addMemory(memory(tags = listOf("林晚")))
        val b = store.addMemory(memory(content = "中元节赎回"))
        store.addLink("w1", a.id, b.id)
        store.setProperty("w1", b.id, "期限", "中元节")
        val graph = store.graphJson("w1")
        assertEquals(2, graph.optInt("nodeCount"))
        assertEquals(1, graph.optInt("edgeCount"))
        assertEquals(1, graph.optInt("tagCount"))
        assertEquals(1, graph.optInt("propertyCount"))
        assertEquals(2, graph.optJSONArray("nodes")?.length())
        assertEquals(1, graph.optJSONArray("edges")?.length())
    }

    @Test
    fun `访问时间 - 命中后回写`() {
        val store = newStore()
        val a = store.addMemory(memory())
        store.touchAccessed("w1", listOf(a.id), now = 12345L)
        assertEquals(12345L, store.getMemory("w1", a.id)?.lastAccessedAt)
    }
}
