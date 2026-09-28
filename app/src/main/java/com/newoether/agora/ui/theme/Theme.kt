package com.newoether.agora.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

enum class ThemeMode { LIGHT, DARK, FOLLOW_DEVICE }

/**
 * Returns the effective [FontFamily] for non-mono typography based on the font preference.
 */
@Composable
private fun effectiveFontFamily(
    fontPreference: String,
    customFontPath: String
): FontFamily = remember(fontPreference, customFontPath) {
    when (fontPreference) {
        "system" -> FontFamily.Default
        "custom" -> {
            val file = File(customFontPath)
            if (file.exists()) {
                try {
                    FontFamily(
                        Font(file, FontWeight.ExtraLight),
                        Font(file, FontWeight.Light),
                        Font(file, FontWeight.Normal),
                        Font(file, FontWeight.Medium),
                        Font(file, FontWeight.Bold),
                    )
                } catch (_: Exception) {
                    OutfitFamily
                }
            } else OutfitFamily
        }
        else -> OutfitFamily
    }
}

/**
 * Builds the [Typography]: display/headline/title/label tiers always use the
 * Fairy title family and body tiers follow the user's body font preference.
 * Anton ([FairyTokens.labelFontFamily]) is applied only explicitly at call sites
 * for small Latin/numeric labels — it has no CJK glyphs. Mono styles untouched.
 */
private fun typographyWithFont(family: FontFamily): Typography {
    fun TextStyle.withFamily(f: FontFamily) = copy(fontFamily = f)
    return Typography.copy(
        displayLarge = Typography.displayLarge.withFamily(TitleFamily),
        displayMedium = Typography.displayMedium.withFamily(TitleFamily),
        displaySmall = Typography.displaySmall.withFamily(TitleFamily),
        headlineLarge = Typography.headlineLarge.withFamily(TitleFamily),
        headlineMedium = Typography.headlineMedium.withFamily(TitleFamily),
        headlineSmall = Typography.headlineSmall.withFamily(TitleFamily),
        titleLarge = Typography.titleLarge.withFamily(TitleFamily),
        titleMedium = Typography.titleMedium.withFamily(TitleFamily),
        titleSmall = Typography.titleSmall.withFamily(TitleFamily),
        bodyLarge = Typography.bodyLarge.withFamily(family),
        bodyMedium = Typography.bodyMedium.withFamily(family),
        bodySmall = Typography.bodySmall.withFamily(family),
        labelLarge = Typography.labelLarge.withFamily(TitleFamily),
        labelMedium = Typography.labelMedium.withFamily(TitleFamily),
        labelSmall = Typography.labelSmall.withFamily(TitleFamily),
    )
}

/**
 * The app theme is a fixed Fairy deep-navy scheme: theme mode, color preset, scheme
 * style, dynamic color, and AMOLED preferences are ignored (their DataStore
 * settings remain untouched). Status/navigation bar icons are always light;
 * [MainActivity] sets them once.
 */
@Composable
fun AgoraTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_DEVICE,
    colorSchemePreset: ColorSchemePreset = ColorSchemePreset.FOREST,
    schemeStyle: SchemeStyle = SchemeStyle.TONAL_SPOT,
    dynamicColor: Boolean = false,
    amoledEnabled: Boolean = false,
    fontPreference: String = "app_default",
    customFontPath: String = "",
    content: @Composable () -> Unit
) {
    val colorScheme = remember { fairyColorScheme() }
    val tokens = remember { FairyTokens() }

    val fontFamily = effectiveFontFamily(fontPreference, customFontPath)
    chatFontFamily = fontFamily
    val typography = remember(fontFamily) { typographyWithFont(fontFamily) }

    CompositionLocalProvider(LocalFairyTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            // Fairy chrome: dialogs and bottom sheets share the 20dp panel radius.
            shapes = Shapes(
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(20.dp),
            ),
            content = content
        )
    }
}
