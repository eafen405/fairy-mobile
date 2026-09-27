package com.newoether.agora.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
 * Builds the [Typography]: display/headline/title tiers always use the ZZZ title
 * family, label tiers use the Anton label family, and body tiers follow the
 * user's body font preference. Mono styles are untouched.
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
        labelLarge = Typography.labelLarge.withFamily(AntonFamily),
        labelMedium = Typography.labelMedium.withFamily(AntonFamily),
        labelSmall = Typography.labelSmall.withFamily(AntonFamily),
    )
}

/**
 * The app theme is a fixed ZZZ dark scheme: theme mode, color preset, scheme
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
    val colorScheme = remember { zzzColorScheme() }
    val tokens = remember { ZzzTokens() }

    val fontFamily = effectiveFontFamily(fontPreference, customFontPath)
    chatFontFamily = fontFamily
    val typography = remember(fontFamily) { typographyWithFont(fontFamily) }

    CompositionLocalProvider(LocalZzzTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
