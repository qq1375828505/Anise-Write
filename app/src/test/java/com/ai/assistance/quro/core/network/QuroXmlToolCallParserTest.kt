package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroXmlToolCallParser] + [QuroTextToolCallBridge] 的行为契约测试（P0-1 层②③）。
 *
 * 断言的输入取自 novelIDE 参考协议与真实端侧模型常见形态：
 * XML 标签族（`<tool name=…><param …>`）与宽松 JSON 标签族（`<tool_call>{…}</tool_call>`）。
 * 任何一条失败 = 对应那类「不支持 function calling」的模型会退回
 * 「调用被当普通文本吞掉、用户以为 AI 不肯动手」的旧状态。
 */
class QuroXmlToolCallParserTest {

    // ------------------------------------------------------------------ 解析：XML 协议

    @Test
    fun `解析无参数工具`() {
        val calls = QuroXmlToolCallParser.parse(
            "好的，我来查一下\n<tool name=\"get_characters\"></tool>"
        )
        assertEquals(1, calls.size)
        assertEquals("get_characters", calls[0].name)
        assertTrue(calls[0].arguments.isEmpty())
    }

    @Test
    fun `解析带参数工具`() {
        val calls = QuroXmlToolCallParser.parse(
            """
            |<tool name="add_character">
            |  <param name="name">张三</param>
            |  <param name="role">主角</param>
            |</tool>
            """.trimMargin()
        )
        assertEquals(1, calls.size)
        assertEquals("张三", calls[0].arguments["name"])
        assertEquals("主角", calls[0].arguments["role"])
    }

    @Test
    fun `解析多个工具`() {
        val calls = QuroXmlToolCallParser.parse(
            """<tool name="a"></tool>中间文本<tool name="b"><param name="x">1</param></tool>"""
        )
        assertEquals(2, calls.size)
        assertEquals("a", calls[0].name)
        assertEquals("b", calls[1].name)
    }

    @Test
    fun `CDATA 与实体反转义`() {
        val calls = QuroXmlToolCallParser.parse(
            """<tool name="t"><param name="a"><![CDATA[<raw&val>]]></param><param name="b">&lt;tag&gt; &amp; &quot;q&quot;</param></tool>"""
        )
        assertEquals("<raw&val>", calls[0].arguments["a"])
        assertEquals("<tag> & \"q\"", calls[0].arguments["b"])
    }

    @Test
    fun `无匹配与非工具文本`() {
        assertTrue(QuroXmlToolCallParser.parse("普通一段话，没有调用").isEmpty())
        assertTrue(QuroXmlToolCallParser.parse("<tool></tool>").isEmpty()) // 无 name 属性
        assertTrue(QuroXmlToolCallParser.parse("<tool name=x></tool>").isEmpty()) // 引号缺失
    }

    @Test
    fun `hasToolCalls 与 stripToolCalls`() {
        val text = "先查一下\n<tool name=\"get_current_time\"></tool>\n\n\n结束"
        assertTrue(QuroXmlToolCallParser.hasToolCalls(text))
        assertFalse(QuroXmlToolCallParser.hasToolCalls("没有调用"))
        assertEquals("先查一下\n\n结束", QuroXmlToolCallParser.stripToolCalls(text))
    }

    // ------------------------------------------------------------------ 升格桥：层③

    @Test
    fun `XML 文本升格为 ToolCalls 且保留前缀正文`() {
        val raw = QuroLlmResult.Text("先查一下\n<tool name=\"get_current_time\"></tool>")
        val up = QuroTextToolCallBridge.upgrade(raw)
        assertTrue(up is QuroLlmResult.ToolCalls)
        val tc = up as QuroLlmResult.ToolCalls
        assertEquals(1, tc.calls.size)
        assertEquals("get_current_time", tc.calls[0].name)
        assertEquals("先查一下", tc.content)
    }

    @Test
    fun `XML 升格参数序列化为合法 JSON`() {
        val raw = QuroLlmResult.Text(
            """<tool name="add_character"><param name="name">张三</param><param name="role">主角</param></tool>"""
        )
        val tc = QuroTextToolCallBridge.upgrade(raw) as QuroLlmResult.ToolCalls
        val args = JSONObject(tc.calls[0].arguments)
        assertEquals("张三", args.getString("name"))
        assertEquals("主角", args.getString("role"))
        // 纯调用文本：可见正文应为空（剥离干净），调用方按「无前缀」处理
        assertTrue(tc.content == null)
    }

    @Test
    fun `宽松标签族升格（复用 QuroToolCallRepair）`() {
        val raw = QuroLlmResult.Text(
            """<tool_call>{"name":"get_current_time","arguments":{}}</tool_call>"""
        )
        val up = QuroTextToolCallBridge.upgrade(raw)
        assertTrue(up is QuroLlmResult.ToolCalls)
        assertEquals("get_current_time", (up as QuroLlmResult.ToolCalls).calls[0].name)
    }

    @Test
    fun `工具名纠错经 knownNames 生效`() {
        val raw = QuroLlmResult.Text(
            """<tool_call>{"name":"get_current_times","arguments":{}}</tool_call>"""
        )
        val up = QuroTextToolCallBridge.upgrade(raw, setOf("get_current_time", "read_file"))
        assertTrue(up is QuroLlmResult.ToolCalls)
        assertEquals("get_current_time", (up as QuroLlmResult.ToolCalls).calls[0].name)
    }

    @Test
    fun `普通文本原样返回`() {
        val raw = QuroLlmResult.Text("你好呀，今天想写点什么？")
        assertSame(raw, QuroTextToolCallBridge.upgrade(raw))
    }

    @Test
    fun `空正文原样返回`() {
        val raw = QuroLlmResult.Text("")
        assertSame(raw, QuroTextToolCallBridge.upgrade(raw))
    }

    @Test
    fun `只有标签词没有真调用 - 原样返回`() {
        val raw = QuroLlmResult.Text("刚才我提到了 <tool_call> 这个标签，但没有真的调用它。")
        assertSame(raw, QuroTextToolCallBridge.upgrade(raw))
    }

    @Test
    fun `ToolCalls 与 Error 原样传递`() {
        val tcs = QuroLlmResult.ToolCalls(listOf(QuroToolCall(id = "c1", name = "x", arguments = "{}")))
        assertSame(tcs, QuroTextToolCallBridge.upgrade(tcs))
        val err = QuroLlmResult.Error("boom")
        assertSame(err, QuroTextToolCallBridge.upgrade(err))
    }
}
