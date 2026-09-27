package com.newoether.agora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ZzzThemeTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `zzzColorScheme produces the fixed dark token table`() {
        val scheme = zzzColorScheme()
        assertEquals(Color(0xFF121212), scheme.background)
        assertEquals(Color(0xFF121212), scheme.surface)
        assertEquals(Color(0xFF0E0E0E), scheme.surfaceContainerLowest)
        assertEquals(Color(0xFF1C1C1C), scheme.surfaceContainer)
        assertEquals(Color(0xFF2A2A2A), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFFFFE000), scheme.primary)
        assertEquals(Color.Black, scheme.onPrimary)
        assertEquals(Color(0xFF9BC400), scheme.secondary)
        assertEquals(Color(0xFF1F55E0), scheme.tertiary)
        assertEquals(Color.White, scheme.onTertiary)
        assertEquals(Color(0xFFE53A1E), scheme.error)
        assertEquals(Color(0xFF3A3A3A), scheme.outline)
    }

    @Test
    fun `AgoraTheme ignores every theme-related preference`() {
        data class Combo(val mode: ThemeMode, val preset: ColorSchemePreset, val dynamic: Boolean)
        var combo by mutableStateOf(Combo(ThemeMode.LIGHT, ColorSchemePreset.ROSE, true))
        var captured: ColorScheme? = null
        compose.setContent {
            AgoraTheme(
                themeMode = combo.mode,
                colorSchemePreset = combo.preset,
                schemeStyle = SchemeStyle.VIBRANT,
                dynamicColor = combo.dynamic,
                amoledEnabled = combo.dynamic,
            ) {
                captured = MaterialTheme.colorScheme
            }
        }
        val schemes = listOf(
            Combo(ThemeMode.LIGHT, ColorSchemePreset.ROSE, true),
            Combo(ThemeMode.DARK, ColorSchemePreset.OCEAN, false),
            Combo(ThemeMode.FOLLOW_DEVICE, ColorSchemePreset.FOREST, true),
        ).map { next ->
            compose.runOnIdle { combo = next }
            compose.waitForIdle()
            captured!!
        }
        schemes.forEach { scheme ->
            assertEquals(schemes[0].primary.toArgb(), scheme.primary.toArgb())
            assertEquals(schemes[0].background.toArgb(), scheme.background.toArgb())
            assertEquals(schemes[0].surfaceContainerHighest.toArgb(), scheme.surfaceContainerHighest.toArgb())
        }
    }

    @Test
    fun `ZzzTokens carry the spec palette`() {
        val tokens = ZzzTokens()
        assertEquals(Color(0xFF1B1B1B), tokens.watermark)
        assertEquals(Color(0xFF1C1C1C), tokens.panel)
        assertEquals(Color(0xFF2A2A2A), tokens.pill)
        assertEquals(Color(0xFFD4D400), tokens.tab)
        assertEquals(Color(0xFF1F55E0), tokens.fairyBlue)
        assertEquals(Color(0xFF3D8BFF), tokens.fairyGlow)
        assertEquals(Color(0xFFF4F4F4), tokens.fairyBubble)
        assertEquals(Color(0xFF2A2A2A), tokens.onFairyBubble)
        assertEquals(Color(0xFF8A8A8A), tokens.textMuted)
        assertEquals(Color(0xFFE53A1E), tokens.danger)
    }
}
