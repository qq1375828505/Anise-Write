package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.mcp.McpTool
import com.ai.assistance.quro.core.mcp.ParameterType
import com.ai.assistance.quro.core.mcp.ToolAnnotations
import com.ai.assistance.quro.core.mcp.ToolParameter
import com.ai.assistance.quro.core.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 适配器：把我们的原创 [QuroTool] 适配进 vendored droid-mcp 引擎（Apache-2.0）。
 * 这样「工具实现 100% 原创」与「引擎来自 Apache-2.0 公用库」干净分离。
 */
fun QuroTool.toMcpTool(engine: QuroToolEngine): McpTool {
    val self = this
    return object : McpTool {
        override val name = self.name
        override val description = self.description
        override val parameters = jsonSchemaToToolParameters(self.parametersJson)
        override val annotations = ToolAnnotations(readOnlyHint = true)

        override suspend fun execute(params: Map<String, Any>): ToolResult {
            val ctx = engine.getContext() ?: return ToolResult.error("上下文不可用")
            val argsJson = mapToJson(params)
            // 🔴 卡死防护（真机实测：run_code 跑脚本卡死半天不返回）：
            // QuroTool.run 是**普通阻塞函数**，本链路（McpTool.execute → registry → 此处）
            // 全程没有挂起点——上层的 withTimeout 对「阻塞在 C 层/JNI/无超时网络」的
            // 实现永远打不响，只能干等。改为独立守护线程执行 + 按 [QuroTool.timeoutMs]
            // 限期等待：到期本协程立刻以超时返回，执行线程遗弃为后台（其进程类副作用
            // 仍由 QuroToolProcesses 在取消时强杀；原生 Python 解释器由 PyEngine 的
            // wedge 隔离兜底，不重进）。宁可报超时让模型换策略，不让整轮对话挂死。
            val resultRef = java.util.concurrent.atomic.AtomicReference<ToolResult?>(null)
            val done = java.util.concurrent.CountDownLatch(1)
            val worker = Thread {
                try {
                    resultRef.set(
                        runCatching { self.run(ctx, argsJson) }
                            .map { ToolResult.success(mapOf("result" to it)) }
                            .getOrElse { ToolResult.error(it.message ?: "error") }
                    )
                } finally {
                    done.countDown()
                }
            }.apply {
                isDaemon = true
                name = "quro-tool-${self.name}"
                start()
            }
            return try {
                if (!done.await(self.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    ToolResult.error(
                        "工具「${self.name}」执行超过 ${self.timeoutMs / 1000}s 未返回（疑似阻塞在不可中断调用），已放弃等待。" +
                            "如需执行长时间任务，请分步操作或缩小任务粒度。"
                    )
                } else {
                    resultRef.get() ?: ToolResult.error("工具无结果")
                }
            } finally {
                worker.interrupt()
            }
        }
    }
}

/** 解析 OpenAI/JSON-Schema 参数定义 → droid-mcp ToolParameter 列表。 */
fun jsonSchemaToToolParameters(schemaJson: String): List<ToolParameter> {
    val out = mutableListOf<ToolParameter>()
    runCatching {
        val root = JSONObject(schemaJson)
        val props = root.optJSONObject("properties") ?: return emptyList()
        val required = root.optJSONArray("required")
        val reqSet = mutableSetOf<String>()
        required?.let { for (i in 0 until it.length()) reqSet.add(it.optString(i)) }
        val keys = props.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val p = props.optJSONObject(name) ?: continue
            val type = when (p.optString("type", "string")) {
                "integer" -> ParameterType.INTEGER
                "number" -> ParameterType.NUMBER
                "boolean" -> ParameterType.BOOLEAN
                "array" -> ParameterType.ARRAY
                "object" -> ParameterType.OBJECT
                else -> ParameterType.STRING
            }
            out.add(ToolParameter(name, p.optString("description", ""), type, reqSet.contains(name)))
        }
    }
    return out
}

/** Map<String, Any> → JSON 字符串（供 QuroTool.run 使用）。 */
fun mapToJson(map: Map<String, Any>): String {
    val o = JSONObject()
    map.forEach { (k, v) -> o.put(k, v) }
    return o.toString()
}

/** JSON 字符串 → Map<String, Any>（LLM 的 tool_calls.arguments → droid-mcp params）。 */
fun jsonToMap(json: String): Map<String, Any> {
    val map = LinkedHashMap<String, Any>()
    val o = JSONObject(json)
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        map[k] = o.get(k)
    }
    return map
}
