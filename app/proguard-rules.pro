# Anise Write — R8 keep 规则
#
# 原则：release 开启 minify/shrink 后，凡「反射加载 / JNI / 序列化 / 插件 / AI 工具名」
# 相关的类一律 keep，其余允许收缩与混淆（包体优化目标）。
#
# 裁剪说明（Phase 9）：多角色集群 / MCP / 插件框架 / ACI / CMS / KaleidoBox / 小程序 /
# GenUI 子应用工具已不注册，但类文件保留，R8 可安全剔除其代码（无反射入口即 dead code）。

# ─── 流体云（OPPO Fluid Cloud）────────────────────────────
-keep class com.ai.assistance.quro.core.fluidcloud.** { *; }
-keep class com.ai.assistance.quro.core.tools.QuroFluidCloudTool { *; }

# ─── JSON（org.json 全量反射调用）────────────────────────
-keep class org.json.** { *; }
-dontwarn org.json.**

# ─── AI 工具契约（QuroTool 通过注册表反射实例化）────────────
-keep class com.ai.assistance.quro.core.tools.QuroTool { *; }
-keep class com.ai.assistance.quro.core.QuroToolSpec { *; }
-keep class com.ai.assistance.quro.core.QuroToolCall { *; }
-keep class com.ai.assistance.quro.core.QuroToolResult { *; }
-keep class com.ai.assistance.quro.core.tools.QuroToolRegistry { *; }
-keep class com.ai.assistance.quro.core.tools.QuroToolEngine { *; }

# ─── 小说工具域（AI 经工具名反射调用，禁止改/删）──────────────
-keep class com.ai.assistance.quro.core.tools.novel.** { *; }
-keep class com.ai.assistance.quro.core.novel.** { *; }

# ─── 模型组 / 子代理（反射构造 / 意图路由）─────────────────
-keep class com.ai.assistance.quro.core.model.QuroModelGroup { *; }
-keep class com.ai.assistance.quro.core.model.QuroModelTarget { *; }
-keep class com.ai.assistance.quro.core.tools.novel.NovelSubagentMatcher { *; }
-keep class com.ai.assistance.quro.core.tools.novel.NovelTaskWeightRouter { *; }
-keep class com.ai.assistance.quro.core.network.QuroXmlToolCallParser { *; }

# ─── 记忆双轨（NovelMemorySearch 纯函数 + NovelTextMemory 缓存）──
-keep class com.ai.assistance.quro.core.novel.graph.memory.** { *; }

# ─── Workflow（Phase 5 契约对齐：七阶段 JSON 编解码）────────
-keep class com.ai.assistance.quro.core.novel.workflow.** { *; }

# ─── 资料库（8 类卡片 + RAG 检索）─────────────────────────
-keep class com.ai.assistance.quro.core.novel.asset.** { *; }

# ─── 对话卡片（AI 下发渲染，类名作为 type 字段存在 JSON 中）──
-keep class com.ai.assistance.quro.core.cards.** { *; }

# ─── UI 动作桥（AI 调用 ui_open_* 反射到对话框）────────────
-keep class com.ai.assistance.quro.core.tools.QuroUiActionBridge { *; }

# ─── 动态 UI DSL（AI 生成 QuroUi JSON，反序列化要保留字段名）───
-keep class com.ai.assistance.quro.core.ui.dynamicui.** { *; }
-keep class com.ai.assistance.quro.core.ui.card.** { *; }

# ─── 技能系统（SKILL.md 签名校验 + 运行时反射）──────────────
-keep class com.ai.assistance.quro.core.skill.** { *; }
-keep class com.ai.assistance.quro.core.cluster.** { *; }

# ─── QuroAssistant / Agent 闭环（动态派发）─────────────────
-keep class com.ai.assistance.quro.core.agent.loop.** { *; }
-keep class com.ai.assistance.quro.core.QuroAssistant { *; }

# ─── CodeCanvas（脚本引擎动态执行）─────────────────────────
-keep class com.codecanvas.core.** { *; }

# ─── GenUI SDK（AI 生成 GenUI JSON → 原生 Compose）──────────
-keep class com.ai.assistance.quro.genui.sdk.** { *; }

# ─── AIDL / ACI（Binder 反序列化）─────────────────────────
-keep class ai.aci.core.** { *; }
-keep class com.ai.assistance.quro.aci.core.** { *; }

# ─── Xposed Stub（LSPosed 注入）───────────────────────────
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# ─── Sherpa / MNN / llama.cpp JNI（native 层按名查找方法）────
-keep class com.k2fsa.sherpa.ncnn.** { *; }
-keep class com.ai.assistance.quro.core.localmnn.** { *; }
-keep class com.ai.assistance.quro.core.localllama.** { *; }

# ─── Apache Commons Compress（tar.gz 解压，反射构造）────────
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# ─── BouncyCastle（端侧签名，Provider 反射）────────────────
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-dontwarn org.bouncycastle.**

# ─── OkHttp（网络层，协程/Selector 内部反射）────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ─── Coroutines（Continuation / Dispatchers 反射）─────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ─── Compose / Material（反射查找 @Composable 函数元数据）───
-keep class androidx.compose.runtime.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn androidx.compose.**
-dontwarn com.google.android.material.**

# ─── JGit（Java 工具的 Git 实现，反射加载 Provider）─────────
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# ─── R8 内部混淆规则兜底（annotation / enum / Parcelable）────
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-keep class * extends android.os.Parcelable { public static final android.os.Parcelable$Creator *; }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, MethodParameters
