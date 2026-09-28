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

private val FairyBgTop = Color(0xFF0B1733)
private val FairyBgBottom = Color(0xFF050A18)
private val FairyPanel = Color(0xFF0E1A36)
private val FairyPill = Color(0xFF16244A)
private val FairyIce = Color(0xFFEAF0FF)
private val FairyNavy = Color(0xFF0A1633)
private val FairyBlue = Color(0xFF1F55E0)
private val FairyGlow = Color(0xFF3D8BFF)
private val FairyLink = Color(0xFF8FB8FF)
private val FairyDanger = Color(0xFFFF5A4E)
private val FairyMuted = Color(0xFF8C9AB8)

/**
 * The fixed Fairy dark palette. [AgoraTheme] always produces this scheme,
 * ignoring theme mode, preset, style, dynamic color, and AMOLED settings.
 * Surfaces step panel -> pill; generic interaction is ice white on navy.
 */
fun fairyColorScheme(): ColorScheme = ColorScheme(
    primary = FairyIce, onPrimary = FairyNavy,
    primaryContainer = FairyPill, onPrimaryContainer = FairyIce,
    secondary = FairyLink, onSecondary = FairyNavy,
    secondaryContainer = FairyPill, onSecondaryContainer = FairyIce,
    tertiary = FairyBlue, onTertiary = FairyIce,
    tertiaryContainer = FairyBlue, onTertiaryContainer = FairyIce,
    error = FairyDanger, onError = FairyNavy,
    errorContainer = Color(0xFF3A1622), onErrorContainer = Color(0xFFFFB4AC),
    background = FairyBgBottom, onBackground = FairyIce,
    surface = FairyBgBottom, onSurface = FairyIce,
    surfaceVariant = FairyPill, onSurfaceVariant = FairyMuted,
    outline = Color(0xFF3A4666), outlineVariant = Color(0xFF1E2A47),
    inversePrimary = FairyBlue,
    inverseSurface = FairyIce, inverseOnSurface = FairyNavy,
    // Tonal elevation must not tint the navy panels.
    surfaceTint = Color.Transparent, scrim = Color.Black,
    surfaceDim = FairyBgBottom, surfaceBright = Color(0xFF1C2B55),
    surfaceContainerLowest = Color(0xFF081226),
    surfaceContainerLow = FairyBgTop,
    surfaceContainer = FairyPanel,
    surfaceContainerHigh = Color(0xFF122043),
    surfaceContainerHighest = FairyPill,
    primaryFixed = FairyIce, primaryFixedDim = Color(0xFFC9D6F5),
    onPrimaryFixed = FairyNavy, onPrimaryFixedVariant = FairyNavy,
    secondaryFixed = FairyLink, secondaryFixedDim = Color(0xFF6E9BE8),
    onSecondaryFixed = FairyNavy, onSecondaryFixedVariant = FairyNavy,
    tertiaryFixed = FairyBlue, tertiaryFixedDim = FairyGlow,
    onTertiaryFixed = FairyIce, onTertiaryFixedVariant = FairyIce,
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
