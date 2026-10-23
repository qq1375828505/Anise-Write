package com.ai.assistance.quro.core.tools.novel

import android.content.Context

/**
 * 子代理模糊匹配（五级）——P0-2。
 *
 * 移植自 novelIDE `agent_tool_executors.dart` 的 `fuzzyMatchAgent`（语义对齐、独立实现）：
 *   1. **精确 ID**（大小写不敏感）；
 *   2. **中文名**（title）精确；
 *   3. **互相包含**（title / id 双向 contains）；
 *   4. **关键词映射**（各子代理 frontmatter `trigger` 触发词表；两侧统一小写比较，
 *      修正参考实现里「'AI味' 因大小写永不命中」的缺陷）；
 *   5. **描述包含**（`description.contains(input)`）。
 *
 * 元数据来源 = `assets/prompts/subagents/*.md` 的 YAML frontmatter（唯一真相源；
 * 加载时校验 `zero-write: true`，与提示词加载同口径，自动跳过
 * DELEGATION/CONVERGENCE/ORCHESTRATION 等无标记文件）。
 * 匹配顺序 = 文件名字典序，多候选取第一个（确定性）。
 * 全部未中 → null：调用方报错并附清单，不兜底（工程禁令）。
 */

/** 子代理元数据目录（assets 内）。 */
internal const val SUBAGENT_META_DIR = "prompts/subagents"

/** 子代理元数据（frontmatter 的匹配相关字段子集）。 */
internal data class SubagentMeta(
    val id: String,
    val title: String,
    val description: String,
    val triggers: List<String>,
)

/**
 * 解析子代理 frontmatter（纯函数，可单测）。
 * 非 frontmatter 开头 / 缺 `zero-write: true` / 缺 `name` → null（跳过该文件）。
 */
internal fun parseSubagentMeta(raw: String): SubagentMeta? {
    if (!raw.startsWith("---")) return null
    val end = raw.indexOf("\n---", 3)
    if (end < 0) return null
    val fields = HashMap<String, String>()
    for (line in raw.substring(3, end).lines()) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val key = line.substring(0, idx).trim()
        if (key.isEmpty() || key.startsWith("#")) continue
        fields[key] = line.substring(idx + 1).trim()
    }
    if (fields["zero-write"] != "true") return null
    val id = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
    return SubagentMeta(
        id = id,
        title = fields["title"].orEmpty(),
        description = fields["description"].orEmpty(),
        triggers = fields["trigger"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )
}

/** 从 assets 扫描全部子代理元数据（按文件名字典序，确定性）。IO 失败 → 空表（匹配落空由调用方报错）。 */
internal fun loadSubagentMetas(context: Context): List<SubagentMeta> = runCatching {
    context.assets.list(SUBAGENT_META_DIR).orEmpty()
        .filter { it.endsWith(".md") }
        .sorted()
        .mapNotNull { name ->
            context.assets.open("$SUBAGENT_META_DIR/$name").bufferedReader().use { it.readText() }
                .let(::parseSubagentMeta)
        }
}.getOrElse { emptyList() }

/** 五级模糊匹配（纯函数，可单测）。顺序即优先级，命中即返回。 */
internal fun matchSubagent(input: String, metas: List<SubagentMeta>): SubagentMeta? {
    val q = input.trim()
    if (q.isEmpty()) return null
    val ql = q.lowercase()

    // 1. 精确 ID（大小写不敏感）
    metas.firstOrNull { it.id.equals(q, ignoreCase = true) }?.let { return it }
    // 2. 中文名精确
    metas.firstOrNull { it.title == q }?.let { return it }
    // 3. 互相包含（title / id 双向；title 为空串时跳过，防 `q.contains("")` 恒真）
    metas.firstOrNull { m ->
        (m.title.isNotBlank() && (m.title.contains(q) || q.contains(m.title))) ||
            m.id.contains(ql) || ql.contains(m.id.lowercase())
    }?.let { return it }
    // 4. 触发词映射（frontmatter trigger[]，两侧统一小写）
    for (m in metas) {
        for (kw in m.triggers) {
            if (ql.contains(kw.lowercase())) return m
        }
    }
    // 5. 描述包含
    return metas.firstOrNull { it.description.contains(q) }
}
