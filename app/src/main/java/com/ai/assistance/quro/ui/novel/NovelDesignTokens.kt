package com.ai.assistance.quro.ui.novel

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

/**
 * 小说工作台视觉令牌（现代文学出版风，落地 design_tokens.json / 规划书 §6.7）。
 *
 * 基座主题是陶土暖色（用户已明确否决），小说工作台自带独立配色：
 * 暖白纸底、墨黑正文、衬线标题、低饱和深靛蓝强调、1px 细线、16/24/32dp 留白梯度。
 * 暗色模式给出暖炭黑变体（纸感的暗面），不跟随基座陶土暗色。
 */

/** 小说工作台调色板（亮 / 暗两套，令牌值与 design_tokens.json 对齐）。 */
data class NovelPalette(
    val paper: Color,        // 页面底：暖白纸 #FAF8F3
    val surface: Color,      // 卡片 / 底纹
    val ink: Color,          // 正文：墨黑 #1F1E1C
    val inkSecondary: Color, // 辅助文字：暖灰
    val heading: Color,      // 标题：墨黑偏蓝 #2B3A4A
    val accent: Color,       // 强调：低饱和深靛蓝 #3E5C76
    val accentSoft: Color,   // 辅助强调：灰蓝
    val onAccent: Color,     // 强调色上的文字
    val divider: Color,      // 1px 细线 #E5E1D8
    val emphasis: Color,     // 数据强调（哑砖红）
)

private val LightNovelPalette = NovelPalette(
    paper = Color(0xFFFAF8F3),
    surface = Color(0xFFF5F2EA),
    ink = Color(0xFF1F1E1C),
    inkSecondary = Color(0xFF6B6660),
    heading = Color(0xFF2B3A4A),
    accent = Color(0xFF3E5C76),
    accentSoft = Color(0xFF6E8697),
    onAccent = Color(0xFFFFFFFF),
    divider = Color(0xFFE5E1D8),
    emphasis = Color(0xFF9E4A4A),
)

private val DarkNovelPalette = NovelPalette(
    paper = Color(0xFF16140F),
    surface = Color(0xFF221F19),
    ink = Color(0xFFEAE4D8),
    inkSecondary = Color(0xFFA39B8B),
    heading = Color(0xFFE8E2D4),
    accent = Color(0xFF8FA9BE),
    accentSoft = Color(0xFF6E8697),
    onAccent = Color(0xFF12202B),
    divider = Color(0xFF3A362E),
    emphasis = Color(0xFFC97B7B),
)

private val LocalNovelPalette = staticCompositionLocalOf { LightNovelPalette }

/** 当前小说工作台调色板（跟随系统暗色切换）。 */
val novelPalette: NovelPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalNovelPalette.current

/** 留白梯度：16 / 24 / 32dp（规划书 §6.7）。 */
object NovelSpacing {
    val gutter: Dp = 16.dp   // 页面左右边距 / 列表内距
    val section: Dp = 24.dp  // 区块间距
    val page: Dp = 32.dp     // 大留白（页头 / 空状态）
}

/** 字体：各级标题用衬线（Noto Serif SC 族在安卓上回落 FontFamily.Serif），正文用默认无衬线。 */
object NovelFonts {
    val heading: FontFamily = FontFamily.Serif
    val titleSize: TextUnit = 22.sp
    val sectionSize: TextUnit = 16.sp
}

@Composable
internal fun ProvideNovelPalette(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalNovelPalette provides if (dark) DarkNovelPalette else LightNovelPalette,
        content = content,
    )
}

/** 用亮度粗略判定基座主题是否暗色（0.5 阈值，够用于调色板切换）。 */
private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
