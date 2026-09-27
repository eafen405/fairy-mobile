package com.newoether.agora.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fixed Zenless Zone Zero-inspired dark tokens that sit outside the Material3
 * [androidx.compose.material3.ColorScheme] slots. Provided by [AgoraTheme] via
 * [LocalZzzTokens]; components read from here instead of hard-coding values.
 */
@Immutable
data class ZzzTokens(
    /** Diagonal watermark text and hatch color on the page background. */
    val watermark: Color = Color(0xFF1B1B1B),
    /** Panel / top-bar / card / input-bar surface. */
    val panel: Color = Color(0xFF1C1C1C),
    /** List items, secondary pills, file cards. */
    val pill: Color = Color(0xFF2A2A2A),
    /** Thick outer outline: black, 2 dp. */
    val outlineOuterColor: Color = Color(0xFF000000),
    val outlineOuterWidth: Dp = 2.dp,
    /** Inner highlight outline: 1 dp. */
    val outlineInnerColor: Color = Color(0xFF3A3A3A),
    val outlineInnerWidth: Dp = 1.dp,
    /** Panel surface dot matrix: white at 4 % alpha. */
    val dotMatrix: Color = Color(0xFFFFFFFF).copy(alpha = 0.04f),
    /** Selected tab / segmented control accent (on it: black). */
    val tab: Color = Color(0xFFD4D400),
    /** Fairy identity: self bubble, emblem ring, send button, badges. */
    val fairyBlue: Color = Color(0xFF1F55E0),
    /** Fairy working state: breathing peak, glow, thinking dots. */
    val fairyGlow: Color = Color(0xFF3D8BFF),
    /** Fairy bubble surface and its content color. */
    val fairyBubble: Color = Color(0xFFF4F4F4),
    val onFairyBubble: Color = Color(0xFF2A2A2A),
    /** Muted text: subtitles, descriptions. */
    val textMuted: Color = Color(0xFF8A8A8A),
    /** Back/close buttons, errors, stop key (on it: black). */
    val danger: Color = Color(0xFFE53A1E),
    val panelShape: Shape = RoundedCornerShape(24.dp),
    val bubbleShape: Shape = RoundedCornerShape(20.dp),
    /** Rounded corner on the bubble side that carries the tail. */
    val bubbleTailCorner: Dp = 6.dp,
    /** Noto Sans SC Black subset: titles, nameplates, tabs, watermark. */
    val titleFontFamily: FontFamily = TitleFamily,
    /** Anton: small latin labels and numeric badges. */
    val labelFontFamily: FontFamily = AntonFamily,
)

val LocalZzzTokens = staticCompositionLocalOf { ZzzTokens() }
