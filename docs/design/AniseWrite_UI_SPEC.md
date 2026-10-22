# Anise Write 界面设计规范 v1

> 状态：冻结（Phase 6 实施依据）
> 设计方法论来源：**OpenMinis**（`github.com/OpenMinis/OpenMinis`）的界面规范范式——iOS 对齐、逐像素测量、单一真相源。
> 令牌唯一真相源：[`design_tokens.json`](./design_tokens.json)；所有界面代码必须消费该文件定义的常量，**禁止内联硬编码数值**。

---

## 0. UI 路线（已敲定）：完全沿用 Minis 界面布局

> **决策（用户，2026-10-22）：Anise Write 的界面完全使用 Minis 的界面布局。**
> 不再自行设计视觉语言——任何新界面**先在 Minis 里找对应页面/组件**，再映射到本项目的实现。

**含义三条**：
1. **结构同源**：页面骨架、分组列表、Tab 栏、底部 Sheet、设置条目，一律照 Minis 的布局来；
2. **组件同构**：Minis 的每个组件都有本项目的对应实现（见 §0.2 对照表），数值以 Minis 实测值为准；
3. **只借不抄**：Minis = OpenMinis（GPL-3.0），**只取布局与数值，代码独立编写**（见 §0 许可边界）。

### 0.1 数值来源

Minis 的布局数值是**从 iOS 参考截图逐像素测量**得来的（OpenMinis 的 `SectionDesign` 注释原文：
"the values below are derived from the iOS reference screenshot and its pixel measurements"），
本规范的令牌表即取自该测量结果。**改数值时先改本文件的令牌表，再改 `NovelDesignTokens.kt`**，
两者必须一致。

### 0.2 组件对照表（Minis → Anise Write）

| Minis 组件 | 本项目实现 | 关键数值 |
|---|---|---|
| `SectionCard` | `NovelGroupedCard` | 圆角 12dp、`cardSurface`、**无阴影**（靠底色差分层） |
| `SectionHeader` | `NovelSectionHeader` | accent 色、`footnote` 字级、水平内缩 4dp、垂直 8dp |
| `SectionFooter` | `NovelSectionFooter` | `textTertiary`、`footnote` 字级 |
| `SectionDivider` | `NovelDivider` | 0.5dp、**自卡片左缘内缩 16dp**（避开圆角） |
| 设置行（44pt + 29pt 圆角图标 + chevron） | `NovelGroupedRow` / `NovelRowIcon` | 行高 ≥44dp、图标 29dp、圆角 6.5dp、chevron 18dp secondary |
| 彩色圆角图标 | `NovelRowIcon` + `NovelCategoryColors` | 纯色填充 + 白色字形，等饱和度等明度 |
| 底部 Tab | `NovelTabBar` | 高 49dp、不透明 `pageBackground`、选中 accent |
| 底部 Sheet | **待补** `NovelSheet` | 顶部圆角 10dp、拖拽把手 36×5dp、选中项 accent + 对勾 |
| 按钮 | **待补** | 主按钮圆角 14dp、高 48dp；次级用描边 |
| 侧滑行操作 | **待补** `NovelSwipeRowActions` | 阈值 96dp、动作色 accent / danger |
| 对话框 | **待补** | 圆角 20dp、`glass3` 层级、scrim |
| 数据卡 | `NovelStatCard` | 圆角 12dp、`cardSurface`、数字 `title2` |
| 空态 | `NovelEmptyState` | `title3` 标题 + `callout` 引导、上下留白 40dp |
| 列表项拖拽排序 | **待补** | 长按 500ms 提起 |

**尚未映射的 Minis 组件**（Phase 6c 待补）：`MinisButton` / `MinisOutlinedButton` / `MinisMenu` /
`MinisAlertDialog` / `SectionDropdown` / `SheetScrollGuard` / `IosBounceOverscrollEffect`。

### 0.3 明令禁止

- 禁止在 Composable 里写裸 dp（数值一律取自 `NovelDesign`）；
- 禁止自创 Minis 里没有的视觉语言（新增界面先在 Minis 找对应物，找不到再讨论）；
- 禁止在 `drawBehind` / Canvas 绘制块里读取 `@Composable` 状态（先在外部取值再进绘制层）。

---

## 0.4 许可边界（重要）

OpenMinis 采用 **GPL-3.0**；Anise Write 采用 **Apache-2.0**（继承自基座 ZorvAI）。

因此本规范**只借鉴三类内容**：
1. **方法论**——「以 iOS 参考截图为基准逐像素测量」「单一真相源对象」「对比度实测值写进注释」；
2. **视觉数值**——间距、圆角、字号、颜色值属于事实性数值，不受版权保护；
3. **设计范式**——iOS 分组列表、inset 分隔线、系统色映射等**通用设计模式**。

**禁止**：复制 OpenMinis 的 Kotlin / Swift 源码、组件实现、图标素材、截图资产到本仓库。任何实现代码必须独立编写。

---

## 1. 方法论（四条硬规矩）

1. **iOS 对齐优先**。移动端界面以 iOS 系统界面为参照基准，数值从 iOS 参考截图测量得出（例：分组卡片圆角 ≈ 10–12pt、行高 ≈ 44pt、屏幕左右内缩 16pt）。Android 侧以 Compose 复现该视觉节奏，而非照搬 Material 3 默认外观。
2. **单一真相源**。所有节奏性数值（内缩、圆角、行高、间距、字号）集中在一个设计对象里（Kotlin 侧 `NovelDesign`），任何界面只能引用它。改一处，全应用原子更新。**禁止在 Composable 里写裸数字。**
3. **对比度实测写进注释**。每个色值在定义处注明其对比度实测值（正文 ≥ 4.5:1，大字与图标 ≥ 3:1），暗色模式下重新实测，不得凭感觉调。
4. **语义命名**。令牌名描述**角色**（`pageBackground`、`cardSurface`）而非颜色外观（`gray1`、`teal`）。当值变了，名字不必改——**值才是契约**。

## 2. 色彩

### 2.1 中性分组表面（iOS grouped 语义）

| 角色 | 亮色 | 暗色 | 说明 |
|---|---|---|---|
| `pageBackground` | `#F2F2F7` | `#000000` | 页面底（iOS systemGroupedBackground） |
| `cardSurface` | `#FFFFFF` | `#1C1C1E` | 卡片 / 分组容器（iOS secondarySystemGroupedBackground） |
| `cardElevated` | `#F7F7FA` | `#2C2C2E` | 卡片内次级块 |
| `separator` | `#D1D1D6` | `#38383A` | 分隔线（iOS separator） |
| `outline` | `#C6C6C8` | `#48484A` | 卡片外描边 |

> 关键：分组卡片用**中性色**而非主色着色，避免整屏被强调色染色——这是 iOS 分组列表与 Material 3 tonal 最大的观感差异。

### 2.2 强调色（去饱和 iOS 蓝）

| 角色 | 亮色 | 暗色 |
|---|---|---|
| `accent` | `#528AD2` | `#6A94CE` |
| `onAccent` | `#FFFFFF` | `#0B1A2E` |
| `accentSoft` | `#E4EDFB` | `#1B2C48` |
| `sectionHeader` | `#528AD2` | `#6A94CE` |

**取值理由（照抄自实测记录）**：色相取 iOS 系统蓝（`#3686EE` / `#5490E4`），但 iOS 原生饱和度（84% / 73%）在 Android 较暗表面上刺眼，故**饱和度下调约 30%**、色相与明度保持不变：

- 亮色：`#3686EE`（S84% L57%）→ `#528AD2`（S59% L57%）
- 暗色：`#5490E4`（S73% L61%）→ `#6A94CE`（S51% L61%）

**明度刻意不抬高**——抬高会让暗色更柔和，但会把亮色模式在白底上的对比度从 3.62 压到 2.62（低于 WCAG AA 的 4.5）。**降饱和保住了暗色 5.93 的对比度**，亮色也维持原状。

> 对比度实测（正文色 `#1C1C1E` / 深色 `#F2F2F7`）：亮色模式 15.6:1，暗色模式 14.2:1，均远超 AA。

### 2.3 状态色

| 角色 | 值 | 用法 |
|---|---|---|
| `success` | `#34C759` | 伏笔已回收、章节完成 |
| `warning` | `#FF9F0A` | 字数偏低、爽点不足 |
| `danger` | `#FF3B30` | 删除、失败、冲突 |
| `info` | `#5AC8FA` | 中性提示 |

## 3. 排版

| 级别 | 字号/行高 | 字重 | 用途 |
|---|---|---|---|
| largeTitle | 34/41 | 700 | 页面大标题（iOS largeTitle） |
| title1 | 28/34 | 600 | 章节名、作品名 |
| title2 | 22/28 | 600 | 区块标题 |
| title3 | 20/25 | 600 | 小节标题 |
| headline | 17/22 | 600 | 行标题（列表行主文） |
| body | 17/22 | 400 | 正文（阅读区 19/30） |
| callout | 16/21 | 400 | 描述文字 |
| subhead | 15/20 | 500 | 辅助说明 |
| footnote | 13/18 | 400 | 字数、时间等元信息 |
| caption1 | 12/16 | 400 | 角标、分组脚注 |

- 字体族：系统无衬线（iOS 用 SF Pro / Android 用 Roboto + Noto Sans SC），**不引入衬线**。
- 字号单位 sp，跟随系统缩放；行高以倍数给出。
- 章节名可用 `title3` + 单行省略（ellipsis），不换行两行。

## 4. 节奏（单一真相源 `NovelDesign`）

| 常量 | 值 | 依据 |
|---|---|---|
| `screenHorizontalPadding` | 16dp | iOS UIKit insets.left/.right = 16 |
| `cardCornerRadius` | 12dp | iOS UITableViewCell rounded section ≈ 10pt，取 12 兼顾 Android 观感 |
| `firstSectionTopGap` | 16dp | 屏幕顶 → 首个分组标题 |
| `sectionTopGap` | 24dp | 上一分组脚注 → 下一分组标题 |
| `headerToCardGap` | 8dp | 分组标题 → 卡片 |
| `cardToFooterGap` | 8dp | 卡片 → 分组脚注 |
| `interRowGap` | 8dp | 组内相邻行间距（Android 分隔线对比弱于 iOS，故保留 8dp 视觉断点） |
| `rowMinHeight` | 44dp | iOS 标准行高 |
| `rowIconSize` | 29dp | iOS 设置行彩色圆角图标 29pt |
| `rowIconRadius` | 6.5dp | iOS squircle 近似 |
| `dividerInset` | 16dp | 分隔线自卡片左缘内缩，避开圆角 |
| `tabBarHeight` | 49dp | iOS Tab Bar 标准高度 + 安全区 |
| `sheetCornerRadius` | 10dp | iOS 底部 Sheet 顶部圆角 |
| `touchTargetMin` | 44dp | 最小可点区域 |
| `swipeThreshold` | 96dp | 侧滑操作阈值 |

## 5. 组件规范

### 5.1 分组列表（主力布局）
```
[ 章节 ]  ← 大写、footnote、accent 色、水平内缩 4dp
┌────────────────────────────┐
│ ● 作品名            >      │  ← 行高 44+，图标 29 圆角方块
├────────────────────────────┤  ← 分隔线自左侧内缩 16dp
│   第二行                 > │
└────────────────────────────┘
[ 脚注 ]  ← footnote、secondary 色
```
- 卡片圆角 12dp、背景 `cardSurface`、**无阴影**（iOS 分组卡片靠底色差分层，不靠阴影）。
- 分隔线 0.5dp `separator`，**内缩**避免压住圆角。
- 行右侧 chevron（`>`）使用 secondary 色，16dp。
- 分组标题**大写** + accent 色（iOS Settings 范式），中文按需不强制大写。
- 脚注用于统计或提示，置于卡片下方。

### 5.2 彩色圆角图标（行首）
- 29dp 见方、圆角 6.5dp、**纯色填充**（非描边），白字符号居中。
- 色相按类别映射到一组**等饱和度**的色板（人物=蓝、势力=橙、地点=绿、物品=紫、能力=红、情节线=靛、设定=青、参考=灰），饱和度与明度统一，不因类别而异。

### 5.3 底部 Tab（4 个：章节/资料库/关系图/统计）
- 高度 49dp + 安全区；材质 `pageBackground`（**不透明**，iOS Tab Bar 不透明）。
- 选中项：图标+文字 `accent`；未选中：`secondary`。
- 图标 25dp（iOS tab icon 尺寸），选中不放大、不加粗。

### 5.4 章节行
- 标题 `title3`（20sp）+ 右侧状态芯片（草稿/已完成/大纲已定，字号 12）+ 下方脚注（字数 + 更新时间）。
- 字数用等宽数字（`tabular-nums`），避免数值变化时跳动。

### 5.5 编辑 / 阅读面
- 背景 `pageBackground`，正文 `#1C1C1E`（亮）/ `#F2F2F7`（暗），19sp/30sp。
- 顶部工具条：透明 → 滚动时渐显 `cardSurface` + 底部 0.5dp 分隔线。
- 底部操作区：输入框 `cardSurface` 圆角 12dp + 内嵌分隔线。

### 5.6 底部 Sheet
- 顶部圆角 10dp、背景 `cardSurface`、拖拽把手 36×5dp（separator 色）。
- 选中项 accent 色 + 对勾。

### 5.7 统计
- 顶部 3 张数据卡横排（`cardSurface` 圆角 12dp），数字 `title2` 等宽字形，标签 `footnote`。
- 走势图自绘折线 + 主色渐变填充（alpha 12% → 0）。

### 5.8 关系图
- 画布 `pageBackground`；节点为 `cardSurface` 圆形（直径 56dp）+ 1px `outline` 描边；标签 `subhead` 置于节点下方。
- 边：强度 1~5 → 线宽 1~4dp，类别 → 色板取色，不透明度 60%；选中节点时邻边升至 100% 并加 6dp 模糊光晕。

## 6. 动效

| 场景 | 时长 | 曲线 |
|---|---|---|
| 按下/选中 | 120ms | standard `cubic-bezier(0.2,0,0,1)` |
| Tab 切换、Sheet 内展开 | 200ms | standard |
| Sheet 弹出/收起、页面转场 | 320ms | spring(damping 0.9, stiffness 380) |
| 侧滑操作 | 跟随手指 | 释放时 200ms 吸附 |
| 按压缩放 | 0.97 | standard |

- 顶部不做过渡动画（iOS largeTitle 惯例）。
- 系统「减弱动效」开启时，位移/缩放降级为淡入淡出。

## 7. 无障碍

- 正文对比度 ≥ 4.5:1；大字（≥18sp）与图标 ≥ 3:1。色值定义处必须附实测值。
- 触控目标 ≥ 44dp；图标按钮视觉 22–25dp、热区 44dp。
- 全部字号用 sp；系统字号缩放上限 130%，超出时按比例增加留白而非裁切。
- 装饰性图标 `contentDescription = null`；可点元素必须有描述。
- 分组列表支持键盘/外接键盘焦点遍历，焦点态用 2dp accent 描边。

## 8. 落地清单（Phase 6）

1. `ui/novel/NovelDesignTokens.kt` → 重写为 `NovelDesign` 单一真相源 + 亮/暗调色板 + 字阶。
2. 新建 `ui/novel/NovelComponents.kt`：`GroupedSection`、`GroupedRow`、`RowIcon`、`Chevron`、`StatCard`、`ColorDot` 等基础件。
3. `NovelStudioScreen` 四 Tab 按 §5 落地，聊天入口沿用基座 `showNovelStudio` 开关范式。
4. 编辑器按 §5.5 落地；富文本模块缺失时降级纯文本。
5. 关系图按 §5.8 落地（Compose Canvas + 斥力物理）。
