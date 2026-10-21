package com.ai.assistance.quro.core.cms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单元测试：验证 CMS v4 内置种子模块（终端运行时）与 Runtime Host 路由。
 * - **v4 精简**：内置模块仅 3 个终端（proot/Ubuntu）模块，旧的「应用内执行」手机模块
 *   （quro.web/quro.system/quro.code 等 13 个）已在 [QuroCmsRepository.purgeLegacy] 中移除，
 *   本测试对齐该设计（v2 时代的 16 模块断言已废弃）。
 * - 每个能力的 requiresPermissions 都能在所属模块中找到声明（权限配对正确）。
 * - runOn 宿主推导（terminal 通道 → TERMINAL）与 effectiveFor 模板切换正确。
 * - CmsHostRouter 显式 / auto / 单候选 / 空候选解析正确（Context 可为 null，便于 JVM 单测）。
 * 不依赖 Android Context（仅调用 companion builtInModules() 与纯路由逻辑）。
 */
class QuroCmsPluginsTest {

    private val mods = QuroCmsRepository.builtInModules()
    private val byId = mods.associateBy { it.id }

    @Test
    fun builtInModules_has3TerminalModules() {
        assertEquals("内置模块应为 3 个终端模块（v4 精简）", 3, mods.size)
        assertEquals("模块 id 应唯一", 3, mods.map { it.id }.toSet().size)
        assertEquals(
            "v4 精简后的模块目录",
            setOf("quro.term.python", "quro.term.node", "quro.term.httpd"),
            mods.map { it.id }.toSet(),
        )
        mods.forEach { m ->
            assertTrue("模块 ${m.id} 应至少含 1 项能力", m.capabilities.isNotEmpty())
            assertTrue("模块 ${m.id} 应带终端入口脚本（terminalEntry）", m.terminalEntry.isNotBlank())
        }
    }

    @Test
    fun builtInModules_terminalCapabilitiesPresent() {
        val py = byId["quro.term.python"] ?: error("缺少 quro.term.python")
        assertNotNull("quro.term.python 应含 term_python_run", py.capabilities.firstOrNull { it.id == "term_python_run" })
        assertNotNull("quro.term.python 应含 term_python_serve（常驻后端）", py.capabilities.firstOrNull { it.id == "term_python_serve" })
        assertNotNull("quro.term.python 应含 term_python_stop", py.capabilities.firstOrNull { it.id == "term_python_stop" })

        val node = byId["quro.term.node"] ?: error("缺少 quro.term.node")
        assertNotNull("quro.term.node 应含 term_node_run", node.capabilities.firstOrNull { it.id == "term_node_run" })
        assertNotNull("quro.term.node 应含 term_node_serve", node.capabilities.firstOrNull { it.id == "term_node_serve" })

        val httpd = byId["quro.term.httpd"] ?: error("缺少 quro.term.httpd")
        val start = httpd.capabilities.firstOrNull { it.id == "term_httpd_start" }
        assertNotNull("quro.term.httpd 应含 term_httpd_start", start)
        assertTrue("term_httpd_start 应为常驻服务", start!!.resident)
        assertNotNull("quro.term.httpd 应含 term_httpd_list", httpd.capabilities.firstOrNull { it.id == "term_httpd_list" })
    }

    @Test
    fun builtInModules_permissionPairingComplete() {
        mods.forEach { m ->
            m.capabilities.forEach { c ->
                c.requiresPermissions.forEach { pid ->
                    assertTrue(
                        "能力 ${c.id}(模块 ${m.id}) 声明的权限 $pid 必须在模块权限表中存在",
                        m.permissions.any { it.id == pid },
                    )
                }
            }
        }
    }

    @Test
    fun builtInModules_runOnDerivation() {
        // terminal 通道能力 → 默认仅 TERMINAL 宿主
        val run = byId["quro.term.python"]!!.capabilities.first { it.id == "term_python_run" }
        assertEquals(setOf(RuntimeHost.TERMINAL), run.runOn)
        val start = byId["quro.term.httpd"]!!.capabilities.first { it.id == "term_httpd_start" }
        assertEquals(setOf(RuntimeHost.TERMINAL), start.runOn)
        val stop = byId["quro.term.node"]!!.capabilities.first { it.id == "term_node_stop" }
        assertEquals(setOf(RuntimeHost.TERMINAL), stop.runOn)
        assertTrue("term_node_stop 应为常驻停止动作", stop.residentStop)
    }

    @Test
    fun capability_templateSubstitutionAndEffectiveFor() {
        val run = byId["quro.term.python"]!!.capabilities.first { it.id == "term_python_run" }
        val resolved = run.resolveTemplate(run.action, mapOf("code" to "print(1)"))
        assertTrue("模板代入后应包含查询代码", resolved.contains("print(1)"))

        // terminal 类能力无 terminalAction 时，effectiveFor 返回 (action, actionType) 原样
        val (tpl, type) = run.effectiveFor(RuntimeHost.TERMINAL)
        assertEquals("terminal", type)
        assertEquals(run.action, tpl)
    }

    @Test
    fun invocationTarget_parse() {
        assertEquals(InvocationTarget.APP, InvocationTarget.parse("app"))
        assertEquals(InvocationTarget.TERMINAL, InvocationTarget.parse("terminal"))
        assertEquals(InvocationTarget.AUTO, InvocationTarget.parse("auto"))
        assertEquals(InvocationTarget.AUTO, InvocationTarget.parse(null))
        assertEquals(InvocationTarget.AUTO, InvocationTarget.parse("garbage"))
    }

    @Test
    fun hostRouter_explicitAndAutoResolution() {
        val run = byId["quro.term.python"]!!.capabilities.first { it.id == "term_python_run" }
        // 显式指定且能力支持 → 直接采用
        assertEquals(RuntimeHost.TERMINAL, CmsHostRouter.resolve(run, InvocationTarget.TERMINAL, null).host)
        // auto + 单候选（JVM 无 Context）→ 直接返回该候选
        assertEquals(RuntimeHost.TERMINAL, CmsHostRouter.resolve(run, InvocationTarget.AUTO, null).host)
    }

    @Test
    fun hostRouter_singleCandidateAndFallback() {
        val start = byId["quro.term.httpd"]!!.capabilities.first { it.id == "term_httpd_start" }
        // 单候选 → 直接返回
        assertEquals(RuntimeHost.TERMINAL, CmsHostRouter.resolve(start, InvocationTarget.AUTO, null).host)
        // 显式 APP 不被支持 → 退回唯一候选 TERMINAL
        assertEquals(RuntimeHost.TERMINAL, CmsHostRouter.resolve(start, InvocationTarget.APP, null).host)
    }

    @Test
    fun hostRouter_emptyRunOnProducesGuidance() {
        val empty = QuroCmsCapability(
            id = "x", summary = "x", schema = "{}",
            requiresPermissions = emptyList(), constraints = PermissionConstraints(),
            actionType = "api", action = "y", runOn = emptySet(),
        )
        val r = CmsHostRouter.resolve(empty, InvocationTarget.AUTO, null)
        assertNull("未声明任何宿主时应返回 null 宿主并给出引导", r.host)
        assertNotNull("应给出引导文案", r.guidance)
    }
}
