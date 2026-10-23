package com.ai.assistance.quro.core.tools.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 子代理五级模糊匹配单测（P0-2）。
 *
 * 覆盖 [matchSubagent] 的五级优先级与 [parseSubagentMeta] 的 frontmatter 解析，
 * 均为纯函数，不碰 [android.content.Context]。
 *
 * 存在理由：委派工具此前只有精确枚举，「帮我润色这段」这类自然表达直接失配报错；
 * 五级匹配按 精确ID→中文名→互相包含→触发词→描述 的优先级落位，
 * 且**顺序即优先级**——本测试逐级钉死，防止未来调整顺序时静默换档。
 */
class SubagentFuzzyMatchTest {

    // 与 assets 真实 frontmatter 同构的元数据（节选字段，语义不变）
    private val OUTLINE = SubagentMeta(
        "outline", "大纲代理",
        "把创作需求展开为卷/章/节三级细纲。读取需求与既有大纲，产出带爽点标注、反转节点、伏笔挂载点的结构化细纲；不写正文、不改已有大纲文件。",
        listOf("大纲", "细纲", "分卷", "章节规划", "黄金三章", "剧情规划", "下一章写什么", "outline"),
    )
    private val POLISH = SubagentMeta(
        "polish", "润色代理",
        "去AI味、句式节奏、文风统一的精修代理。读指定文本与流派文风令牌，产出改写建议。",
        listOf("润色", "精修", "改稿", "去AI味", "文风", "句式", "润一下", "改得自然", "polish"),
    )
    private val REVIEW = SubagentMeta(
        "review", "审读代理",
        "逻辑漏洞、人设崩塌、节奏问题的审读代理。读单章或局部正文与参照材料，产出问题清单。",
        listOf("审读", "审一下", "审查", "挑错", "看看有什么问题", "质量检查", "发布前检查", "review"),
    )

    private val metas = listOf(OUTLINE, REVIEW, POLISH) // 故意乱序：匹配结果应与列表顺序无关（除多候选）

    // ---------- 第 1 级：精确 ID ----------

    @Test
    fun `level1 exact id`() {
        assertEquals(OUTLINE, matchSubagent("outline", metas))
        assertEquals(POLISH, matchSubagent("polish", metas))
    }

    @Test
    fun `level1 id is case insensitive`() {
        assertEquals(POLISH, matchSubagent("POLISH", metas))
        assertEquals(OUTLINE, matchSubagent(" Outline ", metas))
    }

    // ---------- 第 2 级：中文名精确 ----------

    @Test
    fun `level2 exact chinese title`() {
        assertEquals(POLISH, matchSubagent("润色代理", metas))
        assertEquals(REVIEW, matchSubagent("审读代理", metas))
    }

    // ---------- 第 3 级：互相包含 ----------

    @Test
    fun `level3 title containment both directions`() {
        // title 包含 input
        assertEquals(OUTLINE, matchSubagent("大纲", metas))
        // input 包含 title
        assertEquals(POLISH, matchSubagent("调用润色代理处理一下", metas))
    }

    @Test
    fun `level3 id containment`() {
        assertEquals(POLISH, matchSubagent("polish代理", metas))
    }

    // ---------- 第 4 级：触发词映射 ----------

    @Test
    fun `level4 trigger words the P0-2 headline case`() {
        // 指标用例：此前精确枚举会把这句直接判为「subagent 不合法」
        assertEquals(POLISH, matchSubagent("帮我润色这段", metas))
    }

    @Test
    fun `level4 trigger ascii is case insensitive (reference bug fixed)`() {
        // 参考实现里 'AI味' 因未小写永不命中；本实现两侧统一小写
        assertEquals(POLISH, matchSubagent("帮我做下去ai味处理", metas))
        assertEquals(POLISH, matchSubagent("去AI味", metas))
    }

    @Test
    fun `level4 multi word trigger`() {
        assertEquals(OUTLINE, matchSubagent("下一章写什么好", metas))
    }

    // ---------- 第 5 级：描述包含 ----------

    @Test
    fun `level5 description containment`() {
        // 该片段不在任何 trigger / title 中，只能靠描述命中
        assertEquals(OUTLINE, matchSubagent("产出带爽点标注", metas))
    }

    // ---------- 顺序即优先级 ----------

    @Test
    fun `level order wins - exact id beats trigger`() {
        // "review" 既是 REVIEW 的 id，又可能是别的词；精确 ID 必须第一优先
        assertEquals(REVIEW, matchSubagent("review", metas))
        // "润色" 命中 POLISH 的 trigger 与 title 包含两个口径，结果一致
        assertEquals(POLISH, matchSubagent("润色", metas))
    }

    @Test
    fun `multiple candidates - first in list wins deterministically`() {
        val a = SubagentMeta("a", "甲代理", "", listOf("共同"))
        val b = SubagentMeta("b", "乙代理", "", listOf("共同"))
        assertEquals(a, matchSubagent("共同", listOf(a, b)))
        assertEquals(b, matchSubagent("共同", listOf(b, a)))
    }

    // ---------- 未命中与边界 ----------

    @Test
    fun `no match returns null`() {
        assertNull(matchSubagent("给我讲个笑话", metas))
        assertNull(matchSubagent("", metas))
        assertNull(matchSubagent("   ", metas))
    }

    @Test
    fun `empty title does not false positive`() {
        val noTitle = SubagentMeta("c", "", "描述", emptyList())
        assertNull(matchSubagent("随便什么句子", listOf(noTitle)))
    }

    // ---------- frontmatter 解析 ----------

    @Test
    fun `parse frontmatter fields`() {
        val raw = """
            |---
            |name: polish
            |title: 润色代理
            |description: 去AI味、句式节奏、文风统一的精修代理。
            |trigger: 润色,精修, 改稿 ,去AI味
            |version: 1.0.0
            |zero-write: true
            |---
            |
            |# 正文
        """.trimMargin()
        val meta = parseSubagentMeta(raw)!!
        assertEquals("polish", meta.id)
        assertEquals("润色代理", meta.title)
        assertEquals("去AI味、句式节奏、文风统一的精修代理。", meta.description)
        // trigger 按逗号切分并去空白
        assertEquals(listOf("润色", "精修", "改稿", "去AI味"), meta.triggers)
    }

    @Test
    fun `parse skips non frontmatter and missing zero-write`() {
        assertNull(parseSubagentMeta("没有 frontmatter 的正文"))
        assertNull(parseSubagentMeta("---\nname: x\ntitle: X\n---\n正文"))
        assertEquals("", parseSubagentMeta("---\nname: x\nzero-write: true\n---\n正文")!!.title) // 缺 title 时宽松解析为「」，是否可用由 matcher/白名单把关
    }
}
