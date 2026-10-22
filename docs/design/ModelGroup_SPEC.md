# 模型组 / 写作档 · 设计规范 v1

> 状态：**设计已敲定，UI 与接线待做**（数据层已写，WIP 提交 `0835675`）
> 决策人：用户，2026-10-22（"全套做" + "放在设置页面，单独，不要放在模型设置子界面"）
> 界面规范：[AniseWrite_UI_SPEC.md](./AniseWrite_UI_SPEC.md)（完全沿用 Minis 界面布局）

---

## 1. 为什么要做

今天（2026-10-22）实测发现：Anise Write 的 **6 个写作子代理 + 全部 novel 工具共用同一个模型**
（`NovelWriteSupport.callLlm` 里写死 `model = cfg.model`）。写作场景里各任务对模型的诉求完全不同：

| 任务 | 诉求 | 现状 |
|---|---|---|
| 细纲 / 正文生成 | 强模型、长上下文 | 共用一个模型 |
| 润色 / 标题候选 | 快、便宜的小模型即可 | 共用一个模型 |
| 一致性审查 / 全文审读 | 强模型 + 长上下文 | 共用一个模型 |

结果就是：要么全程烧最贵的模型，要么全程用弱模型导致正文质量塌。

**Minis（= OpenMinis）的模型组**提供了范式：**把「用哪个模型」从一次会话的全局选择，变成按任务可配置的策略**。
但要清楚它解决的是**另一类问题**（provider 韧性 + 负载均衡），而我们基座**已经有**那一层
（`QuroMultiProviderRepository` 多提供商 + 优先级 + 健康检查，`QuroFailoverManager` 故障转移）。
所以本规范**不复制 OpenMinis 的 provider 韧性模型**，只借它的**方法学**，做一层"模型维度"的分组与路由。

## 2. 与基座既有能力的关系（不重复造）

| 能力 | 基座已有 | 本层新增 |
|---|---|---|
| 多提供商配置 | ✅ `QuroMultiProviderRepository`（priority / enabled / 健康 / 连续失败 / 每 provider 的 maxTokens+contextWindow / 本地模型） | 引用，不改造 |
| 故障转移 | ✅ `QuroFailoverManager`（provider 级） | 引用，不重复造 |
| 单一当前模型 | ✅ `QuroModelConfigRepository.load()` | 作为**回落目标**（父会话模型） |
| **命名模型组** | ❌ | ✅ 新增 |
| **默认组 / 轻量组** | ❌ | ✅ 新增（组的角色） |
| **子代理绑组** | ❌ | ✅ 新增 |
| **按任务选模** | ❌ | ✅ 新增（意图三选一） |

## 3. 数据模型

```kotlin
data class QuroModelGroupMember(providerId, model, displayName)   // 指向基座某 provider 下的某模型
enum class QuroModelGroupRole { DEFAULT, LIGHT, CUSTOM }          // 默认组 / 轻量组 / 普通自定义组
data class QuroModelGroup(
    id, name, members: List<Member>, role,
    contextLimitTokens: Int = 0,        // 组级上下文上限，0 = 不限
    defaultThinkingLevel: String = "",  // 组级默认思考档，空 = 不覆盖
)
enum class QuroModelIntent { SAME_AS_PARENT, DEFAULT_GROUP, LIGHT_GROUP }  // 委派方能表达的意图
data class QuroModelGroupSettings(groups, agentBindings: Map<子代理名, 组id>)
```

**存储**：`SharedPreferences("quro_model_groups")` + JSON（与基座 provider 配置同风格）。
**预置三档**（首次进入自动创建，成员为空，添加后生效）：
`默认写作组`（DEFAULT） / `轻量组`（LIGHT） / `审查组`（CUSTOM）。

## 4. 路由规则（Minis 的核心学法，独立实现）

### 4.1 三个意图——委派方只说意图，不说模型名

```jsonc
"model_group": "same_as_me"    // 跟当前会话同一模型（默认；无法解析也回落这个）
"model_group": "default_model"  // 用用户的默认组（通常最强）
"model_group": "sub_model"     // 用用户的轻量组（通常更快更便宜）
```

> **为什么不让模型直接选模型名**：哪个模型强、哪个便宜是**用户的知识**；任务有多难才是**模型的知识**。
> 让模型在没有依据的情况下做路由决策，等于让它瞎猜。Minis 自己在源码注释里废掉过旧的两档机制
> （primary/sub），理由就是这个。

### 4.2 优先级：钉住 > 意图 > 父会话

1. **用户为该子代理钉了组** → 用该组，**忽略**委派请求里的意图；
2. 未钉住 → 按意图解析（默认组 / 轻量组 / 跟当前会话）；
3. 目标组**没有可用成员**（成员为空 / provider 被删）→ 回落到父会话模型，并**显式标记**。

### 4.3 显式标记，不静默换模型

钉住的组不可用时，委派结果里必须带：

```json
{
  "model_used": "默认写作组 · gpt-4o",
  "model_group_unavailable": true,
  "model_group_note": "为该子代理指定的模型组不可用（组内无可用模型或 provider 已删除），已回落到当前会话模型。"
}
```

> 静默跑在别的模型上，几个月后看起来就像 bug。宁可吵闹地回落。

### 4.4 组内顺序与故障切换

组内成员**有序**，默认取第一个可用成员；失败时可按序切下一个（轮转，绕回）。
provider 级故障转移仍由基座 `QuroFailoverManager` 负责，两层职责不重叠。

## 5. UI 规格（Minis 界面布局）

**入口**：**设置 → 独立条目「模型组」**（用户决策：单独一条，不挂在「模型配置」子页面下，不进工作台页签）。

### 5.1 列表页（`ModelGroupsScreen`）

- 分组卡片列表，每行：组名 + 成员数（trailing）+ 角色徽标（默认组 / 轻量组，用 accent 色小芯片）。
- 右上"＋"新建组；行左滑删除（danger 色）。
- 底部两个入口：
  - 「子代理模型」→ 6 个子代理各自绑定哪一档；
  - 「默认组 / 轻量组」→ 标记哪两组扮演这两个角色。

### 5.2 详情页（`ModelGroupDetailScreen`）

- 组名（可改）、角色（分段控件：普通 / 默认 / 轻量）、上下文上限、默认思考档；
- **成员列表**：每行 = provider 名 · 模型名 + 拖拽排序手柄 + 删除按钮；
- 「添加模型」→ 选 provider（来自 `QuroMultiProviderRepository.getAllConfigs()`）→ 选模型（该 provider 的 `/models` 列表）→ 加入组。

### 5.3 子代理绑定页

- 6 行（outline / character / foreshadow / polish / review / consistency），每行右侧是当前选择：
  `跟随委派请求` / `默认组` / `轻量组` / `某个自定义组`；
- 行首图标沿用 29dp 彩色圆角（人物类图标）。

### 5.4 视觉

全部按 §5 的组件对照表实现（`NovelGroupedCard` / `NovelGroupedRow` / `NovelRowIcon` / `NovelDivider`），
**不新造视觉**。

## 6. 许可边界

Minis = OpenMinis，**GPL-3.0**。本项目（Anise Write，Apache-2.0）**只借鉴其方法学与语义**
（组的概念、意图三选一、钉住优先、显式回落标记、界面布局），**代码独立编写**，
不复制其 Swift/Kotlin 实现。该声明已写入仓库 `NOTICE` 的 "DESIGN METHODOLOGY REFERENCE" 条目。

## 7. 当前进度

| 部分 | 状态 |
|---|---|
| 数据模型 + 仓储 + 路由 | ✅ `core/model/QuroModelGroup.kt` |
| 委派工具接入 `model_group` 字段 | ✅ 信封解析 → 路由 → `callLlm(target=…)` |
| `DELEGATION.md` 信封 schema 加 `model_group` 字段 | ✅ 意图三选一 `same_as_me`/`default_model`/`sub_model` |
| UI：列表页 / 详情页 / 成员选择器 / 子代理绑定页 | ✅ `ui/QuroModelGroupScreen.kt`（Minis 布局：分组列表 + inset 分隔线 + 彩色圆角图标） |
| 设置入口（独立条目「模型组」） | ✅ `ChatScreen` → `SettingsSheetContent` → 全屏覆盖层（三态：列表/详情/绑定） |
| 预置三档的成员引导与空态 | ✅ 列表页空态 + 「尚未添加模型」提示 |
| 委派结果可观测（`model_used` / `model_group_unavailable`） | ✅ 已写入返回体 |
| 真机验证（切组 → 委派 → 看 `model_used`） | ⬜ 待做（需装 APK 实测） |
| 成员排序拖拽、组级思考档真正生效（当前只存不用） | ⬜ 待做 |
