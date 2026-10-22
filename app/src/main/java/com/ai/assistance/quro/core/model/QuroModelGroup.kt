package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 模型组（Model Group）——把「用哪个模型」从一次会话的全局选择，变成**按任务可配置的策略**。
 *
 * 设计学法参考 OpenMinis（GPL-3.0）的模型组范式（只取方法与语义，实现独立编写；
 * 见 NOTICE 的 "DESIGN METHODOLOGY REFERENCE"）：
 * - 组 = **有序成员** + 角色 + 组级上下文上限 / 思考档；
 * - 委派方只表达**意图**（同款 / 默认组 / 轻量组），不写死模型名——因为
 *   "哪个模型强、哪个便宜" 是用户的知识，"任务有多难" 才是模型的知识；
 * - **用户钉住 > 模型判断**：子代理可钉死一组，委派请求的意图被忽略；
 * - 钉住的组不可用时**显式回落并标记**，绝不静默换模型。
 *
 * 与基座既有能力的关系：基座已有 `QuroMultiProviderRepository`（多提供商 + 优先级 +
 * 健康检查）与 `QuroFailoverManager`（provider 级故障转移），本层**不重复造**，
 * 只在其上增加「模型维度」的分组与按任务路由。
 */

/** 组内一个成员：指向基座某个 provider 下的某个模型。 */
data class QuroModelGroupMember(
    val providerId: String,
    val model: String,
    val displayName: String = model,
)

/** 组的角色。 */
enum class QuroModelGroupRole(val key: String) {
    /** 默认组：委派意图「默认组」指向它，也用于没有指定时的回落。 */
    DEFAULT("default"),

    /** 轻量组：委派意图「轻量组」指向它（通常更快更便宜）。 */
    LIGHT("light"),

    /** 普通自定义组。 */
    CUSTOM("custom"),
    ;

    companion object {
        fun fromKey(key: String?): QuroModelGroupRole =
            entries.firstOrNull { it.key == key } ?: CUSTOM
    }
}

/** 一个命名模型组。 */
data class QuroModelGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val members: List<QuroModelGroupMember> = emptyList(),
    val role: QuroModelGroupRole = QuroModelGroupRole.CUSTOM,
    /** 组级上下文上限（token）；0 = 不限（沿用模型原生窗口）。 */
    val contextLimitTokens: Int = 0,
    /** 组级默认思考档（off/low/medium/high/xhigh）；空 = 不覆盖。 */
    val defaultThinkingLevel: String = "",
) {
    val memberCount: Int get() = members.size
}

/**
 * 模型选择意图——委派方（子代理调用）能表达的只有这三种，**不表达具体模型**。
 */
enum class QuroModelIntent(val key: String) {
    /** 跟当前会话同一个模型。 */
    SAME_AS_PARENT("same_as_me"),

    /** 用用户的默认组（通常是强模型）。 */
    DEFAULT_GROUP("default_model"),

    /** 用用户的轻量组（通常更快更便宜）。 */
    LIGHT_GROUP("sub_model"),
    ;

    companion object {
        /** 解析委派请求里的意图；未给或无法识别 → 跟当前会话（最安全的方向）。 */
        fun fromKey(key: String?): QuroModelIntent =
            entries.firstOrNull { it.key == key } ?: SAME_AS_PARENT
    }
}

/** 一次调用的最终模型目标（已解析到 baseUrl/apiKey/model）。 */
data class QuroModelTarget(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val groupId: String = "",
    val groupName: String = "",
    /** 钉住/指定的组不可用，已回落到别的来源。 */
    val fellBack: Boolean = false,
    /** 具体是"钉住的组不可用"导致的回落（需要在结果里如实告诉用户）。 */
    val pinnedGroupUnavailable: Boolean = false,
)

/** 全部模型组设置 + 子代理绑定。 */
data class QuroModelGroupSettings(
    val groups: List<QuroModelGroup> = emptyList(),
    /** 子代理名 → 组 id；空串表示"跟随委派请求"。 */
    val agentBindings: Map<String, String> = emptyMap(),
) {
    fun groupById(id: String): QuroModelGroup? = groups.firstOrNull { it.id == id }

    val defaultGroup: QuroModelGroup?
        get() = groups.firstOrNull { it.role == QuroModelGroupRole.DEFAULT }

    val lightGroup: QuroModelGroup?
        get() = groups.firstOrNull { it.role == QuroModelGroupRole.LIGHT }

    fun groupForAgent(agent: String): QuroModelGroup? =
        agentBindings[agent]?.takeIf { it.isNotBlank() }?.let { groupById(it) }
}

/**
 * 模型组仓储：SharedPreferences + JSON（与基座 provider 配置同风格）。
 */
class QuroModelGroupRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_model_groups", Context.MODE_PRIVATE)

    /** 读取全部设置；首次读取时写入预置组。 */
    fun load(): QuroModelGroupSettings {
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrBlank()) {
            val preset = preset()
            save(preset)
            return preset
        }
        return runCatching { parse(JSONObject(raw)) }.getOrElse { preset() }
    }

    fun save(settings: QuroModelGroupSettings) {
        prefs.edit { putString(KEY, serialize(settings).toString()) }
    }

    // ── 便捷访问 ──────────────────────────────────────────────
    fun defaultGroup(): QuroModelGroup? = load().defaultGroup
    fun lightGroup(): QuroModelGroup? = load().lightGroup
    fun groupById(id: String): QuroModelGroup? = load().groupById(id)
    fun groupForAgent(agent: String): QuroModelGroup? = load().groupForAgent(agent)

    /** 覆盖某个子代理的绑定（空串 = 跟随请求）。 */
    fun bindAgent(agent: String, groupId: String) {
        val s = load()
        val next = s.agentBindings.toMutableMap()
        if (groupId.isBlank()) next.remove(agent) else next[agent] = groupId
        save(s.copy(agentBindings = next))
    }

    /** 新建组。 */
    fun addGroup(group: QuroModelGroup) {
        val s = load()
        save(s.copy(groups = s.groups + group))
    }

    /** 更新组。 */
    fun updateGroup(group: QuroModelGroup) {
        val s = load()
        save(s.copy(groups = s.groups.map { if (it.id == group.id) group else it }))
    }

    /** 删除组（并清掉指向它的子代理绑定）。 */
    fun removeGroup(id: String) {
        val s = load()
        save(
            s.copy(
                groups = s.groups.filterNot { it.id == id },
                agentBindings = s.agentBindings.mapValues { (_, v) -> if (v == id) "" else v }.filterValues { it.isNotBlank() },
            )
        )
    }

    /** 设默认组 / 轻量组（同角色互斥）。 */
    fun setRole(id: String, role: QuroModelGroupRole) {
        val s = load()
        val groups = s.groups.map {
            when {
                it.id == id -> it.copy(role = role)
                role != QuroModelGroupRole.CUSTOM && it.role == role -> it.copy(role = QuroModelGroupRole.CUSTOM)
                else -> it
            }
        }
        save(s.copy(groups = groups))
    }

    // ── 序列化 ────────────────────────────────────────────────
    private fun serialize(s: QuroModelGroupSettings): JSONObject {
        val groups = JSONArray()
        for (g in s.groups) {
            val members = JSONArray()
            for (m in g.members) {
                members.put(
                    JSONObject()
                        .put("provider_id", m.providerId)
                        .put("model", m.model)
                        .put("display_name", m.displayName)
                )
            }
            groups.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("role", g.role.key)
                    .put("context_limit", g.contextLimitTokens)
                    .put("thinking", g.defaultThinkingLevel)
                    .put("members", members)
            )
        }
        val bindings = JSONObject()
        s.agentBindings.forEach { (k, v) -> bindings.put(k, v) }
        return JSONObject().put("groups", groups).put("agent_bindings", bindings)
    }

    private fun parse(o: JSONObject): QuroModelGroupSettings {
        val groups = ArrayList<QuroModelGroup>()
        val arr = o.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val members = ArrayList<QuroModelGroupMember>()
            val ma = g.optJSONArray("members") ?: JSONArray()
            for (j in 0 until ma.length()) {
                val m = ma.optJSONObject(j) ?: continue
                val model = m.optString("model", "")
                if (model.isBlank()) continue
                members.add(
                    QuroModelGroupMember(
                        providerId = m.optString("provider_id", ""),
                        model = model,
                        displayName = m.optString("display_name", model),
                    )
                )
            }
            groups.add(
                QuroModelGroup(
                    id = g.optString("id", UUID.randomUUID().toString()),
                    name = g.optString("name", "未命名组"),
                    members = members,
                    role = QuroModelGroupRole.fromKey(g.optString("role", null)),
                    contextLimitTokens = g.optInt("context_limit", 0),
                    defaultThinkingLevel = g.optString("thinking", ""),
                )
            )
        }
        val bindings = HashMap<String, String>()
        o.optJSONObject("agent_bindings")?.let { b ->
            b.keys().forEach { k -> bindings[k] = b.optString(k, "") }
        }
        return QuroModelGroupSettings(groups = groups, agentBindings = bindings)
    }

    /** 预置三档：默认写作组 / 轻量组 / 审查组（成员为空，添加后生效）。 */
    private fun preset(): QuroModelGroupSettings = QuroModelGroupSettings(
        groups = listOf(
            QuroModelGroup(
                id = "preset-default",
                name = "默认写作组",
                members = emptyList(),
                role = QuroModelGroupRole.DEFAULT,
            ),
            QuroModelGroup(
                id = "preset-light",
                name = "轻量组",
                members = emptyList(),
                role = QuroModelGroupRole.LIGHT,
            ),
            QuroModelGroup(
                id = "preset-review",
                name = "审查组",
                members = emptyList(),
                role = QuroModelGroupRole.CUSTOM,
            ),
        ),
        agentBindings = emptyMap(),
    )

    companion object {
        private const val KEY = "model_groups_json"
    }
}

/**
 * 模型组路由：把「意图 / 钉住的组」解析成最终的模型目标。
 *
 * 规则（与 OpenMinis 同源思路，独立实现）：
 * 1. **钉住优先**：指定了组就用该组，忽略意图；
 * 2. 组内**按顺序**取第一个可用成员（provider 存在且模型名非空）；
 * 3. 钉住的组不可用 → 回落父会话模型，并置 `pinnedGroupUnavailable = true`；
 * 4. 意图为默认组 / 轻量组但该组不可用 → 同样回落并标记。
 */
object QuroModelGroupRouter {

    /** 组内第一个可用成员对应的模型目标。 */
    fun resolve(
        group: QuroModelGroup?,
        providers: QuroMultiProviderRepository,
    ): QuroModelTarget? {
        val g = group ?: return null
        for (m in g.members) {
            val target = targetOf(m, providers, g) ?: continue
            return target
        }
        return null
    }

    /** 组内下一个可用成员（轮转，失败切换用）。 */
    fun nextFallback(
        group: QuroModelGroup?,
        currentModel: String,
        providers: QuroMultiProviderRepository,
    ): QuroModelTarget? {
        val g = group ?: return null
        val list = g.members
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.model == currentModel }
        val start = if (idx < 0) 0 else idx
        for (i in 1..list.size) {
            val m = list[(start + i) % list.size]
            val t = targetOf(m, providers, g) ?: continue
            return t
        }
        return null
    }

    /**
     * 综合解析一次调用的模型目标。
     *
     * @param intent 委派请求表达的意图
     * @param pinnedGroup 用户为该子代理钉死的组（null = 未钉）
     * @param settings 全部组设置
     * @param parentTarget 当前会话正在用的模型（回落去向）
     */
    fun resolveForCall(
        intent: QuroModelIntent,
        pinnedGroup: QuroModelGroup?,
        settings: QuroModelGroupSettings,
        providers: QuroMultiProviderRepository,
        parentTarget: QuroModelTarget?,
    ): QuroModelTarget {
        // ① 钉住优先——用户配置压过模型判断
        if (pinnedGroup != null) {
            val t = resolve(pinnedGroup, providers)
            if (t != null) return t
            return (parentTarget ?: return FALLBACK_TARGET).copy(
                fellBack = true,
                pinnedGroupUnavailable = true,
            )
        }
        // ② 意图
        val byIntent = when (intent) {
            QuroModelIntent.SAME_AS_PARENT -> parentTarget
            QuroModelIntent.DEFAULT_GROUP -> resolve(settings.defaultGroup, providers)
            QuroModelIntent.LIGHT_GROUP -> resolve(settings.lightGroup, providers)
        }
        if (byIntent != null) return byIntent
        return (parentTarget ?: FALLBACK_TARGET).copy(fellBack = true)
    }

    private fun targetOf(
        m: QuroModelGroupMember,
        providers: QuroMultiProviderRepository,
        group: QuroModelGroup,
    ): QuroModelTarget? {
        if (m.model.isBlank()) return null
        val cfg = providers.getConfigById(m.providerId)
        if (cfg == null) {
            // provider 已删除 → 该成员不可用
            return null
        }
        return QuroModelTarget(
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            model = m.model,
            groupId = group.id,
            groupName = group.name,
        )
    }

    /** 兜底目标（无任何可用配置时，交给上层按全局配置处理）。 */
    val FALLBACK_TARGET: QuroModelTarget = QuroModelTarget("", "", "")
}
