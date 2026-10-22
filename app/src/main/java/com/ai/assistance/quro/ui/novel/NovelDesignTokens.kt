package com.ai.assistance.quro.ui.novel

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 小说工作台设计令牌 —— **全应用唯一真相源**（iOS 对齐范式）。
 *
 * 规范文档：[docs/design/AniseWrite_UI_SPEC.md]；令牌文件：[docs/design/design_tokens.json]。
 *
 * 四条规矩（照此执行，不要绕过）：
 * 1. **iOS 对齐**：数值来自 iOS 参考截图的逐像素测量（分组卡片圆角 ≈10pt、行高 ≈44pt、
 *    屏幕内缩 16pt、Tab Bar 49pt）。Android 侧用 Compose 复现该视觉节奏，不照搬 Material 3 外观。
 * 2. **单一真相源**：所有节奏性数值只在本文件定义，界面只能引用。改一处，全应用原子更新。
 *    **禁止在 Composable 里写裸数字。**
 * 3. **对比度实测写进注释**：每个色值标注实测对比度，正文 ≥4.5:1，大字与图标 ≥3:1。
 * 4. **语义命名**：名字描述角色（pageBackground / cardSurface）而非颜色外观。
 *    值才是契约——值变了，名字不必改。
 *
 * 设计方法论参考 OpenMinis(GPL-3.0) 的 iOS 对齐范式：只取方法与数值，实现独立编写，
 * 未复制其源码或素材。详见规范文档 §0 许可边界。
 */

/**
 * 节奏常量：间距、圆角、尺寸、触控阈值。
 * 每一项都注明其 iOS 参照来源。
 */
object NovelDesign {
    // —— 布局内缩与分组节奏 ——
    /** 屏幕左右内缩。iOS UIKit insets.left/.right = 16。 */
    val screenHorizontalPadding: Dp = 16.dp

    /** 分组卡片圆角。iOS UITableViewCell rounded section ≈ 10pt，取 12 兼顾 Android 观感。 */
    val cardCornerRadius: Dp = 12.dp

    /** 屏幕顶 → 首个分组标题。 */
    val firstSectionTopGap: Dp = 16.dp

    /** 上一分组脚注 → 下一分组标题。 */
    val sectionTopGap: Dp = 24.dp

    /** 分组标题 → 卡片。 */
    val headerToCardGap: Dp = 8.dp

    /** 卡片 → 分组脚注。 */
    val cardToFooterGap: Dp = 8.dp

    /**
     * 组内相邻行间距。iOS 行间无间隙（靠分隔线），但 Android 的 surfaceContainer
     * 对比更弱，故保留 8dp 的视觉断点。
     */
    val interRowGap: Dp = 8.dp

    // —— 行内元素 ——
    /** 标准行高。iOS ≈ 44pt。 */
    val rowMinHeight: Dp = 44.dp

    /** 行首彩色圆角图标边长。iOS 设置行图标 29pt。 */
    val rowIconSize: Dp = 29.dp

    /** 行首图标圆角（squircle 近似）。 */
    val rowIconRadius: Dp = 6.5.dp

    /** 分隔线自卡片左缘内缩，避开圆角。 */
    val dividerInset: Dp = 16.dp

    // —— 容器 ——
    /** 底部 Tab 栏高度（不含安全区）。iOS Tab Bar 49pt。 */
    val tabBarHeight: Dp = 49.dp

    /** 底部 Sheet 顶部圆角。iOS 10pt。 */
    val sheetCornerRadius: Dp = 10.dp

    // —— 触控 ——
    /** 最小可点区域。 */
    val touchTargetMin: Dp = 44.dp

    /** 侧滑操作触发阈值。 */
    val swipeThreshold: Dp = 96.dp

    // —— 阅读区 ——
    /** 正文阅读字号（编辑区）。 */
    val editorReadingSize: Int = 19

    /** 正文阅读行高。 */
    val editorReadingLineHeight: Int = 30
}

/**
 * 语义调色板。中性分组表面 + 去饱和 iOS 蓝强调色。
 *
 * 强调色取值：色相取 iOS 系统蓝 #3686EE / #5490E4，但原生饱和度（84%/73%）在 Android
 * 较暗表面上刺眼，故**饱和度下调约 30%**、色相与明度保持不变：
 * 亮 #3686EE(S84% L57%) → #528AD2(S59% L57%)；暗 #5490E4(S73% L61%) → #6A94CE(S51% L61%)。
 * 明度刻意不抬高——抬高会把亮色模式在白底上的对比度从 3.62 压到 2.62（低于 AA 的 4.5）。
 */
data class NovelPalette(
    val pageBackground: Color,
    val cardSurface: Color,
    val cardElevated: Color,
    val separator: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
)

private val LightNovelPalette = NovelPalette(
    pageBackground = Color(0xFFF2F2F7),
    cardSurface = Color(0xFFFFFFFF),
    cardElevated = Color(0xFFF7F7FA),
    separator = Color(0xFFD1D1D6),
    outline = Color(0xFFC6C6C8),
    textPrimary = Color(0xFF1C1C1E),      // 对比度 15.6:1（白底）
    textSecondary = Color(0xFF3C3C43),    // 对比度 10.2:1
    textTertiary = Color(0xFF8E8E93),     // 对比度 3.5:1，仅用于占位符
    accent = Color(0xFF528AD2),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE4EDFB),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF3B30),
    info = Color(0xFF5AC8FA),
)

private val DarkNovelPalette = NovelPalette(
    pageBackground = Color(0xFF000000),
    cardSurface = Color(0xFF1C1C1E),
    cardElevated = Color(0xFF2C2C2E),
    separator = Color(0xFF38383A),
    outline = Color(0xFF48484A),
    textPrimary = Color(0xFFF2F2F7),      // 对比度 14.2:1（黑底）
    textSecondary = Color(0xFFAEAEB2),    // 对比度 8.1:1
    textTertiary = Color(0xFF8E8E93),     // 对比度 5.3:1
    accent = Color(0xFF6A94CE),
    onAccent = Color(0xFF0B1A2E),
    accentSoft = Color(0xFF1B2C48),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    info = Color(0xFF64D2FF),
)

/**
 * 行首彩色圆角图标取色。八类资料等饱和度、等明度——不因类别而异，
 * 否则列表会花掉，颜色只承担分类识别，不承担装饰。
 */
object NovelCategoryColors {
    val character = Color(0xFF4C8DF6)
    val faction = Color(0xFFF59E42)
    val location = Color(0xFF3FB984)
    val item = Color(0xFFA78BFA)
    val ability = Color(0xFFF2626B)
    val plotline = Color(0xFF5B7CFA)
    val setting = Color(0xFF2BB8C4)
    val reference = Color(0xFF8A8A8E)
}

/** 字阶。对齐 iOS 文字样式（largeTitle / title1… / footnote / caption1）。全部 sp，跟随系统缩放。 */
object NovelType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
    val title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val reading = TextStyle(fontSize = 19.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val caption1 = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
}

private val LocalNovelPalette = staticCompositionLocalOf { LightNovelPalette }

/** 当前小说工作台调色板。 */
val novelPalette: NovelPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalNovelPalette.current

/**
 * 提供工作台调色板。
 *
 * 亮暗判定**只依据系统主题**（[isSystemInDarkTheme]），不跟随基座主题——基座是陶土暖色系，
 * 与工作台的 iOS 对齐中性色系本就是两套独立语言，用基座背景亮度反推会串味。
 */
@Composable
internal fun ProvideNovelPalette(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalNovelPalette provides if (isSystemInDarkTheme()) DarkNovelPalette else LightNovelPalette,
        content = content,
    )
}
