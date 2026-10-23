package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroToolCall
import org.json.JSONObject

/**
 * XML 工具调用解析器（P0-1 降级层②「正则解析层」）。
 *
 * 移植自 novelIDE `lib/data/services/chat/xml_tool_call_parser.dart`（语义对齐、独立实现，
 * 正则改 Kotlin [Regex]）。用于**不支持原生 function calling 的模型**（端侧小模型、
 * MiMo 类中转）以正文文本发起工具调用的降级通道。
 *
 * 支持的格式（novelIDE 协议，与 `<tool_call>` JSON 族互补）：
 * ```xml
 * <tool name="get_characters"></tool>
 * <tool name="add_character">
 *   <param name="name">张三</param>
 *   <param name="role">主角</param>
 * </tool>
 * ```
 * 参数值支持 CDATA 包裹与 XML 实体（`&lt;` / `&amp;` 等）反转义。
 */

/** 一次 XML 工具调用的解析结果。 */
data class QuroXmlToolCall(
    val name: String,
    /** 参数名 → 参数值（已做 XML 反转义）。 */
    val arguments: Map<String, String>,
    val rawXml: String,
    val startOffset: Int,
    val endOffset: Int,
)

object QuroXmlToolCallParser {

    private val TOOL_RE = Regex(
        """<tool\s+name="([^"]+)">(.*?)</tool>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PARAM_RE = Regex(
        """<param\s+name="([^"]+)">(.*?)</param>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 解析文本中的全部 XML 工具调用；无匹配返回空列表。 */
    fun parse(text: String): List<QuroXmlToolCall> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<QuroXmlToolCall>()
        for (m in TOOL_RE.findAll(text)) {
            val inner = m.groupValues[2]
            val args = LinkedHashMap<String, String>()
            for (pm in PARAM_RE.findAll(inner)) {
                args[pm.groupValues[1]] = unescapeXml(pm.groupValues[2])
            }
            out.add(
                QuroXmlToolCall(
                    name = m.groupValues[1],
                    arguments = args,
                    rawXml = m.value,
                    startOffset = m.range.first,
                    endOffset = m.range.last + 1,
                )
            )
        }
        return out
    }

    /** 文本中是否存在 XML 工具调用。 */
    fun hasToolCalls(text: String): Boolean = TOOL_RE.containsMatchIn(text)

    /** 从文本中剥离全部 XML 工具调用标签（供展示/回传的可见副本），并收敛多余空行。 */
    fun stripToolCalls(text: String): String =
        text.replace(TOOL_RE, "").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** CDATA 与常见 XML 实体反转义（移植版行为；先剥 CDATA 再替换实体）。 */
    fun unescapeXml(input: String): String {
        var r = input.trim()
        if (r.startsWith("<![CDATA[") && r.endsWith("]]>")) r = r.substring(9, r.length - 3)
        if (r.endsWith("]]>")) r = r.substring(0, r.length - 3)
        if (r.startsWith("<![CDATA[")) r = r.substring(9)
        return r
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}

/**
 * 文本态工具调用「升格桥」——P0-1 降级层③（循环层回传）的入口。
 *
 * 把 [QuroLlmResult.Text]（正文里夹着工具调用文本）升格为 [QuroLlmResult.ToolCalls]，
 * 复用主循环/子代理循环里**完整的工具执行链**（执行、结果信封、检查点、UI 工具卡、
 * 下一轮上下文回传）——绝不把「模型声明要调工具」当成最终答复交出去。
 *
 * 两级解析（顺序即优先级）：
 * 1. [QuroXmlToolCallParser]：novelIDE XML 协议（`<tool name="…"><param …>`）；
 * 2. [QuroToolCallRepair]：既有宽松标签族（`<tool_call>{…}</tool_call>` / `[TOOL_CALLS]` /
 *    函数式调用 / 裸键名 / 单引号等畸形形态），并利用 `knownNames` 做工具名纠错。
 *
 * 纪律：
 * - **只升格解析出调用的情况**——无调用原样返回（含「提到标签但没真调用」）；
 * - 非 Text 结果（ToolCalls / Error）原样传递，绝不做二次处理；
 * - 可见正文用各解析器自带的剥离函数清洗（`<tool …>` / `<tool_call>` 原文绝不上屏）。
 */
object QuroTextToolCallBridge {

    /** 升格一次结果。[knownNames] 供层 2 的工具名纠错使用；缺省空集（仅关纠错，不影响解析）。 */
    fun upgrade(raw: QuroLlmResult, knownNames: Collection<String> = emptyList()): QuroLlmResult {
        if (raw !is QuroLlmResult.Text) return raw
        val text = raw.content
        if (text.isBlank()) return raw

        // ① novelIDE XML 协议
        val xmlCalls = QuroXmlToolCallParser.parse(text)
        if (xmlCalls.isNotEmpty()) {
            val calls = xmlCalls.map { xc ->
                QuroToolCall(id = "", name = xc.name, arguments = argsJson(xc.arguments))
            }
            return QuroLlmResult.ToolCalls(
                calls = calls,
                reasoning = raw.reasoning,
                content = QuroXmlToolCallParser.stripToolCalls(text).takeIf { it.isNotBlank() },
                meta = raw.meta,
            )
        }

        // ② 宽松标签族（复用既有修复解析器）
        val outcome = QuroToolCallRepair.extract(text, knownNames)
        if (outcome.calls.isNotEmpty()) {
            return QuroLlmResult.ToolCalls(
                calls = outcome.calls,
                reasoning = raw.reasoning,
                content = QuroToolCallRepair.sanitizeVisible(text).takeIf { it.isNotBlank() },
                meta = raw.meta,
            )
        }

        // 没解析出调用：原样返回（「想调但没解析出来」由既有诊断通道处理，不在此处制造行为变化）。
        return raw
    }

    /** 参数表 → JSON 字符串（[QuroToolCall.arguments] 契约）。 */
    private fun argsJson(args: Map<String, String>): String {
        val o = JSONObject()
        args.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }
}
