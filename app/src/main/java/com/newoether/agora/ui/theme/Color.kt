package com.newoether.agora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.hct.Hct

enum class SchemeStyle { TONAL_SPOT, EXPRESSIVE, VIBRANT, NEUTRAL }

enum class ColorSchemePreset { MIDNIGHT, NORDIC, FOREST, SUNSET, ROSE, LAVENDER, SLATE, OCEAN }

private val seedColors = mapOf(
    ColorSchemePreset.MIDNIGHT to 0xFF1A237E,
    ColorSchemePreset.NORDIC   to 0xFF546E7A,
    ColorSchemePreset.FOREST   to 0xFF2E7D32,
    ColorSchemePreset.SUNSET   to 0xFFE65100,
    ColorSchemePreset.ROSE     to 0xFFAD1457,
    ColorSchemePreset.LAVENDER to 0xFF7B1FA2,
    ColorSchemePreset.SLATE    to 0xFF455A64,
    ColorSchemePreset.OCEAN    to 0xFF0277BD,
)

fun colorSchemeForPreset(
    preset: ColorSchemePreset,
    style: SchemeStyle = SchemeStyle.TONAL_SPOT,
    isDark: Boolean = false
): ColorScheme {
    val seedArgb = seedColors[preset]!!.toInt()
    val hct = Hct.fromInt(seedArgb)
    val scheme: DynamicScheme = when (style) {
        SchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, isDark, 0.0)
        SchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, isDark, 0.0)
        SchemeStyle.VIBRANT   -> SchemeVibrant(hct, isDark, 0.0)
        SchemeStyle.NEUTRAL   -> SchemeNeutral(hct, isDark, 0.0)
    }
    return scheme.toColorScheme()
}

private fun DynamicScheme.toColorScheme(): ColorScheme {
    val c = { argb: Int -> Color(argb) }
    return ColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        outline = c(outline), outlineVariant = c(outlineVariant),
        inversePrimary = c(inversePrimary),
        inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
        surfaceTint = c(surfaceTint), scrim = c(scrim),
        surfaceDim = c(surfaceDim), surfaceBright = c(surfaceBright),
        surfaceContainerLowest = c(surfaceContainerLowest),
        surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainer = c(surfaceContainer),
        surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest),
        primaryFixed = c(primaryFixed), primaryFixedDim = c(primaryFixedDim),
        onPrimaryFixed = c(onPrimaryFixed), onPrimaryFixedVariant = c(onPrimaryFixedVariant),
        secondaryFixed = c(secondaryFixed), secondaryFixedDim = c(secondaryFixedDim),
        onSecondaryFixed = c(onSecondaryFixed), onSecondaryFixedVariant = c(onSecondaryFixedVariant),
        tertiaryFixed = c(tertiaryFixed), tertiaryFixedDim = c(tertiaryFixedDim),
        onTertiaryFixed = c(onTertiaryFixed), onTertiaryFixedVariant = c(onTertiaryFixedVariant),
    )
}

private val ZzzBackground = Color(0xFF121212)
private val ZzzPanel = Color(0xFF1C1C1C)
private val ZzzPill = Color(0xFF2A2A2A)
private val ZzzPrimary = Color(0xFFFFE000)
private val ZzzSecondary = Color(0xFF9BC400)
private val ZzzFairyBlue = Color(0xFF1F55E0)
private val ZzzError = Color(0xFFE53A1E)
private val ZzzOnDark = Color(0xFFFFFFFF)
private val ZzzMuted = Color(0xFF8A8A8A)
private val ZzzOutline = Color(0xFF3A3A3A)

/**
 * The fixed ZZZ dark palette. [AgoraTheme] always produces this scheme,
 * ignoring theme mode, preset, style, dynamic color, and AMOLED settings.
 */
fun zzzColorScheme(): ColorScheme = ColorScheme(
    primary = ZzzPrimary, onPrimary = Color.Black,
    primaryContainer = ZzzPill, onPrimaryContainer = ZzzPrimary,
    secondary = ZzzSecondary, onSecondary = Color.Black,
    secondaryContainer = ZzzPill, onSecondaryContainer = ZzzSecondary,
    tertiary = ZzzFairyBlue, onTertiary = ZzzOnDark,
    tertiaryContainer = ZzzFairyBlue, onTertiaryContainer = ZzzOnDark,
    error = ZzzError, onError = Color.Black,
    errorContainer = ZzzPill, onErrorContainer = ZzzError,
    background = ZzzBackground, onBackground = ZzzOnDark,
    surface = ZzzBackground, onSurface = ZzzOnDark,
    surfaceVariant = ZzzPill, onSurfaceVariant = ZzzMuted,
    outline = ZzzOutline, outlineVariant = Color(0xFF242424),
    inversePrimary = ZzzFairyBlue,
    inverseSurface = Color(0xFFF4F4F4), inverseOnSurface = ZzzPill,
    surfaceTint = ZzzPrimary, scrim = Color.Black,
    surfaceDim = Color(0xFF0C0C0C), surfaceBright = Color(0xFF303030),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = ZzzPanel,
    surfaceContainerHigh = Color(0xFF232323),
    surfaceContainerHighest = ZzzPill,
    primaryFixed = ZzzPrimary, primaryFixedDim = Color(0xFFD4D400),
    onPrimaryFixed = Color.Black, onPrimaryFixedVariant = Color(0xFF4B4600),
    secondaryFixed = ZzzSecondary, secondaryFixedDim = Color(0xFF7C9A00),
    onSecondaryFixed = Color.Black, onSecondaryFixedVariant = Color(0xFF2E3A00),
    tertiaryFixed = ZzzFairyBlue, tertiaryFixedDim = Color(0xFF3D8BFF),
    onTertiaryFixed = ZzzOnDark, onTertiaryFixedVariant = ZzzOnDark,
)

/** Applies the same endpoint treatment to preset and Android dynamic schemes. */
internal fun ColorScheme.withAmoledBackground(isDark: Boolean, enabled: Boolean): ColorScheme {
    if (!enabled) return this
    val base = if (isDark) Color.Black else Color.White
    return copy(
        background = base,
        surface = base,
        surfaceDim = if (isDark) base else surfaceDim,
        surfaceBright = if (isDark) surfaceBright else base,
    )
}
