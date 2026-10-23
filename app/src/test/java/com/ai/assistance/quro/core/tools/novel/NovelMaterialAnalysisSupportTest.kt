package com.ai.assistance.quro.core.tools.novel

import com.ai.assistance.quro.core.novel.asset.AssetType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelMaterialAnalysisSupport] 行为契约测试（P1-3 AI 资料自动分析）。
 *
 * 覆盖纯函数：JSON 提取（剥围栏/取片段）、字段白名单与别名映射、
 * 「只补空」合并语义、伏笔条目解析。**合并绝不覆盖已有非空内容**是核心红线——
 * 自动分析写坏用户手写资料是不可逆事故，测试逐条钉死。
 */
class NovelMaterialAnalysisSupportTest {

    // ------------------------------------------------------------------ JSON 提取

    @Test
    fun `提取裸 JSON`() {
        val raw = """{"characters":[{"name":"林渊"}]}"""
        val json = NovelMaterialAnalysisSupport.extractAnalysisJson(raw)
        assertNotNull(json)
        assertEquals("林渊", json!!.getJSONArray("characters").getJSONObject(0).optString("name"))
    }

    @Test
    fun `提取带围栏的 JSON`() {
        val raw = "```json\n{\"items\":[{\"name\":\"断刃\"}]}\n```"
        val json = NovelMaterialAnalysisSupport.extractAnalysisJson(raw)
        assertNotNull(json)
        assertEquals(1, json!!.getJSONArray("items").length())
    }

    @Test
    fun `提取夹在解说文字中的 JSON`() {
        val raw = "好的，我来分析：\n{\"hooks\":[{\"title\":\"玉佩来历\"}]}\n以上。"
        val json = NovelMaterialAnalysisSupport.extractAnalysisJson(raw)
        assertNotNull(json)
        assertEquals("玉佩来历", json!!.getJSONArray("hooks").getJSONObject(0).optString("title"))
    }

    @Test
    fun `无法解析时返回 null`() {
        assertNull(NovelMaterialAnalysisSupport.extractAnalysisJson("完全没有 JSON"))
        assertNull(NovelMaterialAnalysisSupport.extractAnalysisJson("{不是合法 JSON"))
    }

    // ------------------------------------------------------------------ 字段白名单与别名

    @Test
    fun `人物字段白名单与空值过滤`() {
        val entry = JSONObject()
            .put("name", "林渊")
            .put("role", "主角")
            .put("description", "铁匠之子")
            .put("appearance", "")
            .put("unknownKey", "应被丢弃")
            .put("goal", "   ")
        val fields = NovelMaterialAnalysisSupport.assetFieldsFor(AssetType.CHARACTER, entry)
        assertEquals("林渊", fields.optString("name"))
        assertEquals("主角", fields.optString("role"))
        assertEquals("铁匠之子", fields.optString("description"))
        assertFalse(fields.has("appearance"))   // 空串不进结果
        assertFalse(fields.has("unknownKey"))   // 白名单外丢弃
        assertFalse(fields.has("goal"))         // 纯空白不进结果
    }

    @Test
    fun `道具别名映射 powerLevel 到 grade 与 isKeyItem 到布尔`() {
        val entry = JSONObject()
            .put("name", "断刃")
            .put("powerLevel", "灵器上品")
            .put("owner", "林渊")
            .put("isKeyItem", true)
        val fields = NovelMaterialAnalysisSupport.assetFieldsFor(AssetType.ITEM, entry)
        assertEquals("灵器上品", fields.optString("grade"))
        assertFalse(fields.has("powerLevel"))
        assertTrue(fields.getBoolean("isKey"))
        assertEquals("林渊", fields.optString("owner"))
    }

    @Test
    fun `isKeyItem 字符串真值兼容`() {
        val entry = JSONObject().put("name", "玉佩").put("isKeyItem", "是")
        assertTrue(NovelMaterialAnalysisSupport.assetFieldsFor(AssetType.ITEM, entry).getBoolean("isKey"))
        val entry2 = JSONObject().put("name", "玉佩").put("isKeyItem", "false")
        assertFalse(NovelMaterialAnalysisSupport.assetFieldsFor(AssetType.ITEM, entry2).getBoolean("isKey"))
    }

    // ------------------------------------------------------------------ 只补空合并（核心红线）

    @Test
    fun `合并只填空位不覆盖已有内容`() {
        val existing = JSONObject()
            .put("id", "a1")
            .put("name", "林渊")
            .put("role", "主角")
            .put("background", "")            // 空 → 可补
            .put("personality", "沉默寡言")     // 已有 → 不动
        val incoming = JSONObject()
            .put("name", "林渊")
            .put("role", "配角")              // 冲突 → 保留已有的「主角」
            .put("background", "铁匠之子")      // 空位 → 补
            .put("personality", "热血")        // 冲突 → 保留已有的「沉默寡言」
        val (merged, changed) = NovelMaterialAnalysisSupport.mergeFillEmpty(existing, incoming)
        assertTrue(changed)
        assertEquals("主角", merged.optString("role"))
        assertEquals("沉默寡言", merged.optString("personality"))
        assertEquals("铁匠之子", merged.optString("background"))
        assertEquals("a1", merged.optString("id"))
    }

    @Test
    fun `无非空可补时 changed 为 false`() {
        val existing = JSONObject().put("name", "林渊").put("role", "主角")
        val incoming = JSONObject().put("name", "林渊").put("role", "")
        val (_, changed) = NovelMaterialAnalysisSupport.mergeFillEmpty(existing, incoming)
        assertFalse(changed)
    }

    @Test
    fun `isKey 假值视为空可补`() {
        val existing = JSONObject().put("isKey", false)
        val incoming = JSONObject().put("isKey", true)
        val (merged, changed) = NovelMaterialAnalysisSupport.mergeFillEmpty(existing, incoming)
        assertTrue(changed)
        assertTrue(merged.getBoolean("isKey"))
    }

    @Test
    fun `合并不动托管字段`() {
        val existing = JSONObject().put("id", "a1").put("workId", "w1").put("createdAt", 111)
        val incoming = JSONObject()
            .put("id", "hacker")
            .put("workId", "other")
            .put("createdAt", 999)
            .put("description", "新描述")
        val (merged, changed) = NovelMaterialAnalysisSupport.mergeFillEmpty(existing, incoming)
        assertTrue(changed)
        assertEquals("a1", merged.optString("id"))
        assertEquals("w1", merged.optString("workId"))
        assertEquals(111, merged.optInt("createdAt"))
        assertEquals("新描述", merged.optString("description"))
    }

    // ------------------------------------------------------------------ 伏笔条目

    @Test
    fun `伏笔条目解析与 name 回退`() {
        val a = NovelMaterialAnalysisSupport.hookPair(
            JSONObject().put("title", "玉佩来历").put("description", "老者留下的玉佩")
        )
        assertEquals("玉佩来历", a!!.first)
        assertEquals("老者留下的玉佩", a.second)
        val b = NovelMaterialAnalysisSupport.hookPair(JSONObject().put("name", "无题回退"))
        assertEquals("无题回退", b!!.first)
        assertNull(NovelMaterialAnalysisSupport.hookPair(JSONObject().put("description", "没有标题")))
    }

    // ------------------------------------------------------------------ 提示词

    @Test
    fun `提示词包含六类键与条数上限`() {
        val p = NovelMaterialAnalysisSupport.buildSystemPrompt(8)
        for (key in listOf("characters", "settings", "locations", "factions", "items", "hooks")) {
            assertTrue("缺少 $key", p.contains("\"$key\""))
        }
        assertTrue(p.contains("每类最多 8 条"))
    }
}
