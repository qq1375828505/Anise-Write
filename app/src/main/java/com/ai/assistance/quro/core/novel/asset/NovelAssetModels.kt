package com.ai.assistance.quro.core.novel.asset

import org.json.JSONArray
import org.json.JSONObject

/**
 * PenAi 网文资料域数据模型（Phase 3b）。
 *
 * 八类资料（规划书 §6.4「模型直译 novelIDE 的 8 类资料 + AIGC-NetNov 的实体设计」，
 * 类别定名按任务书八类口径并结合网文创作实践调整）：
 *
 * | 类别 | 对应参考实现 | 说明 |
 * | --- | --- | --- |
 * | [CharacterAsset] 人物 | novelIDE Character / AIGC-NetNov NovelCharacter | 角色卡：定位/外貌/性格/目标/关系速记 |
 * | [FactionAsset] 势力 | novelIDE Faction / AIGC-NetNov NovelFaction | 领袖/地盘/实力/成员 |
 * | [LocationAsset] 地点 | novelIDE Location / AIGC-NetNov NovelLocation | 分类/特征/规则 |
 * | [ItemAsset] 物品 | novelIDE Item / AIGC-NetNov NovelItem | 品阶/持有者/功效/关键道具标记 |
 * | [AbilityAsset] 能力 | 两类参考实现均无，按任务书新增 | 修炼体系/技能卡：品阶/效果/代价/条件 |
 * | [PlotlineAsset] 情节线 | novelIDE PlotHook 的多章弧线扩展 | 状态/涉及章节/相关人物 |
 * | [SettingAsset] 世界观设定 | novelIDE SettingCard / AIGC-NetNov NovelSetting | 设定正文 + 铁律清单 |
 * | [ReferenceAsset] 参考资料 | novelIDE ReferenceMaterial / AIGC-NetNov ReferenceMaterial | 素材/引文/出处 |
 *
 * 边界说明：
 * - **大纲**（OutlineNode）不归本域——规划书 §6.1 工具清单中 `novel_get_outline` /
 *   `novel_save_outline` 属「作品/结构」行（Phase 3a 结构域）。
 * - **伏笔埋收与闲置章数统计**不归本域——`novel_hooks_status` 属规划书「伏笔」行（Phase 3c 图谱域）；
 *   本域的情节线只管多章弧线的推进状态。
 * - **结构化人物关系图**（关系/事件/参与者三表）归 Phase 3c；人物卡内的
 *   [CharacterAsset.relations] 只是卡片上的关系速记，与关系图数据同源不同形。
 *
 * 序列化统一 org.json（对齐基座 `core/QuroConversationPersistence.kt` 与 Phase 1
 * `core/novel/NovelModels.kt` 风格，不引入 Room 与第三方序列化库）。
 */

/** 资料域八类资产的公共契约。 */
interface NovelAsset {
    val id: String
    val workId: String
    val name: String
    val description: String
    val tags: List<String>
    val createdAt: Long
    val updatedAt: Long

    /** 所属类别（用于目录落位、路由分类与删除时的引用提示）。 */
    val type: AssetType

    /**
     * 本资料在卡内以**名字**引用到的其他实体（领袖、持有者、成员、关系人、相关人物）。
     *
     * 删除资料时逐类扫描这些名字做引用完整性提醒（只警告不阻断，见 [NovelAssetRepository.delete]）。
     * 按名字匹配是刻意选择：资料库是 LLM 驱动的自由文本库，不为名字维护外键；
     * ID 级引用留给 Phase 3c 关系图三表。
     */
    val referencedNames: List<String>

    fun toJson(): JSONObject
}

/** 人物卡上的关系速记一条（如「叶青 / 宿敌 / 宗门大比结怨」）。 */
data class AssetRelation(
    val name: String,
    val relation: String,
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("relation", relation)
        put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject): AssetRelation = AssetRelation(
            name = o.optString("name", "").trim(),
            relation = o.optString("relation", "").trim(),
            note = o.optString("note", "").trim(),
        )
    }
}

/**
 * 情节线状态机：推进中 → 已完成 / 已废弃。
 * 与 Phase 3c 的伏笔状态（已埋/已回收/已废弃）是两套口径，勿混用。
 */
enum class PlotlineStatus(val key: String) {
    ONGOING("ongoing"),
    COMPLETED("completed"),
    ABANDONED("abandoned"),
    ;

    companion object {
        /** 旧数据 / 损坏数据无匹配 key 时回落 ONGOING（新情节线初值）。 */
        fun fromKey(key: String?): PlotlineStatus =
            entries.firstOrNull { it.key == key } ?: ONGOING
    }
}

// ─────────────────────────── 八类资料 ───────────────────────────

/** 人物卡：role/gender/age/appearance/personality/background/goal/relations。 */
data class CharacterAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val role: String = "",
    val gender: String = "",
    val age: String = "",
    val appearance: String = "",
    val personality: String = "",
    val background: String = "",
    val goal: String = "",
    val relations: List<AssetRelation> = emptyList(),
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.CHARACTER

    /** 关系人是卡内以名字引用的实体（删除人物时的引用提醒来源）。 */
    override val referencedNames: List<String> get() = relations.map { it.name }

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("role", role)
        put("gender", gender)
        put("age", age)
        put("appearance", appearance)
        put("personality", personality)
        put("background", background)
        put("goal", goal)
        put("relations", JSONArray().also { arr -> relations.forEach { arr.put(it.toJson()) } })
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): CharacterAsset = CharacterAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            role = o.optString("role", ""),
            gender = o.optString("gender", ""),
            age = o.optString("age", ""),
            appearance = o.optString("appearance", ""),
            personality = o.optString("personality", ""),
            background = o.optString("background", ""),
            goal = o.optString("goal", ""),
            relations = parseRelations(o.opt("relations")),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 势力卡：category/leader/territory/strength/members。 */
data class FactionAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val category: String = "",
    val leader: String = "",
    val territory: String = "",
    val strength: String = "",
    val members: List<String> = emptyList(),
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.FACTION

    /** 领袖与成员都是卡内以名字引用的人物。 */
    override val referencedNames: List<String>
        get() = (if (leader.isBlank()) emptyList() else listOf(leader)) + members

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("category", category)
        put("leader", leader)
        put("territory", territory)
        put("strength", strength)
        putStrings("members", members)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): FactionAsset = FactionAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            category = o.optString("category", ""),
            leader = o.optString("leader", ""),
            territory = o.optString("territory", ""),
            strength = o.optString("strength", ""),
            members = jsonToStringList(o, "members"),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 地点卡：category/features/rules。 */
data class LocationAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val category: String = "",
    val features: String = "",
    val rules: String = "",
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.LOCATION
    override val referencedNames: List<String> = emptyList()

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("category", category)
        put("features", features)
        put("rules", rules)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): LocationAsset = LocationAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            category = o.optString("category", ""),
            features = o.optString("features", ""),
            rules = o.optString("rules", ""),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 物品卡：category/grade/owner/effect/isKey。 */
data class ItemAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val category: String = "",
    val grade: String = "",
    val owner: String = "",
    val effect: String = "",
    val isKey: Boolean = false,
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.ITEM

    /** 持有者是以名字引用的人物。 */
    override val referencedNames: List<String>
        get() = if (owner.isBlank()) emptyList() else listOf(owner)

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("category", category)
        put("grade", grade)
        put("owner", owner)
        put("effect", effect)
        put("isKey", isKey)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ItemAsset = ItemAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            category = o.optString("category", ""),
            grade = o.optString("grade", ""),
            owner = o.optString("owner", ""),
            effect = o.optString("effect", ""),
            isKey = o.optBoolean("isKey", false),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 能力/技能卡：category/grade/effect/cost/requirements（两类参考实现均无此类，按任务书新增）。 */
data class AbilityAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val category: String = "",
    val grade: String = "",
    val effect: String = "",
    val cost: String = "",
    val requirements: String = "",
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.ABILITY
    override val referencedNames: List<String> = emptyList()

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("category", category)
        put("grade", grade)
        put("effect", effect)
        put("cost", cost)
        put("requirements", requirements)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): AbilityAsset = AbilityAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            category = o.optString("category", ""),
            grade = o.optString("grade", ""),
            effect = o.optString("effect", ""),
            cost = o.optString("cost", ""),
            requirements = o.optString("requirements", ""),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 情节线卡：status/chapters/characters（多章弧线推进，单点伏笔埋收归 Phase 3c）。 */
data class PlotlineAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val status: PlotlineStatus = PlotlineStatus.ONGOING,
    val chapters: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.PLOTLINE

    /** 相关人物是以名字引用的人物。 */
    override val referencedNames: List<String> get() = characters

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("status", status.key)
        putStrings("chapters", chapters)
        putStrings("characters", characters)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): PlotlineAsset = PlotlineAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            status = PlotlineStatus.fromKey(o.optString("status", PlotlineStatus.ONGOING.key)),
            chapters = jsonToStringList(o, "chapters"),
            characters = jsonToStringList(o, "characters"),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 世界观设定卡：category/content/constraints（constraints = 铁律，供一致性检查与 Phase 5 上下文预算）。 */
data class SettingAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val category: String = "",
    val content: String = "",
    val constraints: List<String> = emptyList(),
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.SETTING
    override val referencedNames: List<String> = emptyList()

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("category", category)
        put("content", content)
        putStrings("constraints", constraints)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SettingAsset = SettingAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            category = o.optString("category", ""),
            content = o.optString("content", ""),
            constraints = jsonToStringList(o, "constraints"),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 参考资料卡：category/content/source/sourceUrl。 */
data class ReferenceAsset(
    override val id: String,
    override val workId: String,
    override val name: String,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    val category: String = "",
    val content: String = "",
    val source: String = "",
    val sourceUrl: String = "",
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
) : NovelAsset {
    override val type: AssetType get() = AssetType.REFERENCE
    override val referencedNames: List<String> = emptyList()

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workId", workId)
        put("name", name)
        put("description", description)
        putStrings("tags", tags)
        put("category", category)
        put("content", content)
        put("source", source)
        put("sourceUrl", sourceUrl)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ReferenceAsset = ReferenceAsset(
            id = o.optString("id", ""),
            workId = o.optString("workId", ""),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            tags = jsonToStringList(o, "tags"),
            category = o.optString("category", ""),
            content = o.optString("content", ""),
            source = o.optString("source", ""),
            sourceUrl = o.optString("sourceUrl", ""),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/**
 * 资料类别枚举：key（工具 type 参数与索引用）/ 目录名 / 中文名 / 该类特有的可写字段。
 *
 * [fields] 是**工具层**的字段白名单与契约（与数据类属性一一对应，改模型必须同步改这里），
 * 用于拒绝 LLM 传错字段名时给出可自纠正的报错。
 */
enum class AssetType(
    val key: String,
    val dirName: String,
    val displayName: String,
    val fields: List<String>,
) {
    CHARACTER("character", "character", "人物", listOf("role", "gender", "age", "appearance", "personality", "background", "goal", "relations")),
    FACTION("faction", "faction", "势力", listOf("category", "leader", "territory", "strength", "members")),
    LOCATION("location", "location", "地点", listOf("category", "features", "rules")),
    ITEM("item", "item", "物品", listOf("category", "grade", "owner", "effect", "isKey")),
    ABILITY("ability", "ability", "能力", listOf("category", "grade", "effect", "cost", "requirements")),
    PLOTLINE("plotline", "plotline", "情节线", listOf("status", "chapters", "characters")),
    SETTING("setting", "setting", "世界观设定", listOf("category", "content", "constraints")),
    REFERENCE("reference", "reference", "参考资料", listOf("category", "content", "source", "sourceUrl")),
    ;

    /** 按类别把 JSON 解析为对应模型（缺失字段取默认值，非法类型按 org.json opt* 规整）。 */
    fun parse(o: JSONObject): NovelAsset = when (this) {
        CHARACTER -> CharacterAsset.fromJson(o)
        FACTION -> FactionAsset.fromJson(o)
        LOCATION -> LocationAsset.fromJson(o)
        ITEM -> ItemAsset.fromJson(o)
        ABILITY -> AbilityAsset.fromJson(o)
        PLOTLINE -> PlotlineAsset.fromJson(o)
        SETTING -> SettingAsset.fromJson(o)
        REFERENCE -> ReferenceAsset.fromJson(o)
    }

    companion object {
        /** 工具 type 参数解析；未知 key 返回 null（由工具层给出可自纠正报错）。 */
        fun fromKey(key: String?): AssetType? = entries.firstOrNull { it.key == key }
    }
}

// ─────────────────────────── JSON 边界宽容解析 ───────────────────────────

/**
 * 字符串数组读取：数组原样收；LLM 常把数组传成 "a,b、c" 这样的单字符串，
 * 边界处按分隔符切开规整（入库前统一形态，不改变已持久化数据）。
 */
internal fun jsonToStringList(o: JSONObject, key: String): List<String> {
    val arr = o.optJSONArray(key)
    if (arr != null) {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> if (v.isNotBlank()) out.add(v.trim())
                is Number -> out.add(v.toString())
            }
        }
        return out
    }
    val s = o.optString(key, "").trim()
    if (s.isEmpty()) return emptyList()
    return s.split(',', '，', '、', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 人物关系速记读取：数组项为对象时按字段解析，为字符串时按 "姓名:关系:备注" 解析
 * （LLM 两种形态都会给，入库前统一为 [AssetRelation]）。
 */
internal fun parseRelations(value: Any?): List<AssetRelation> {
    val arr = value as? JSONArray ?: return emptyList()
    val out = mutableListOf<AssetRelation>()
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is JSONObject -> AssetRelation.fromJson(v).takeIf { it.name.isNotEmpty() }?.let { out.add(it) }
            is String -> {
                val parts = v.split(':', '：').map { it.trim() }
                val name = parts.firstOrNull().orEmpty()
                if (name.isNotEmpty()) {
                    out.add(AssetRelation(name = name, relation = parts.getOrElse(1) { "" }, note = parts.getOrElse(2) { "" }))
                }
            }
        }
    }
    return out
}

/** toJson 用的字符串数组写入（八类模型的 tags/members/chapters 等共用）。 */
internal fun JSONObject.putStrings(key: String, values: List<String>) {
    val arr = JSONArray()
    values.forEach { arr.put(it) }
    put(key, arr)
}
