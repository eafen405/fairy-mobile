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
class FairyThemeTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `fairyColorScheme produces the fixed deep-navy token table`() {
        val scheme = fairyColorScheme()
        assertEquals(Color(0xFF050A18), scheme.background)
        assertEquals(Color(0xFF050A18), scheme.surface)
        assertEquals(Color(0xFF0E1A36), scheme.surfaceContainer)
        assertEquals(Color(0xFF16244A), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFFEAF0FF), scheme.primary)
        assertEquals(Color(0xFF0A1633), scheme.onPrimary)
        assertEquals(Color(0xFFEAF0FF), scheme.onSurface)
        assertEquals(Color(0xFF8C9AB8), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFFF5A4E), scheme.error)
        assertEquals(Color.Transparent, scheme.surfaceTint)
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
            assertEquals(Color(0xFFEAF0FF).toArgb(), scheme.primary.toArgb())
            assertEquals(schemes[0].primary.toArgb(), scheme.primary.toArgb())
            assertEquals(schemes[0].background.toArgb(), scheme.background.toArgb())
            assertEquals(schemes[0].surfaceContainerHighest.toArgb(), scheme.surfaceContainerHighest.toArgb())
        }
    }

    @Test
    fun `FairyTokens carry the spec palette`() {
        val tokens = FairyTokens()
        assertEquals(Color(0xFF0B1733), tokens.bgTop)
        assertEquals(Color(0xFF050A18), tokens.bgBottom)
        assertEquals(Color(0xFF0D2257), tokens.screenCenter)
        assertEquals(Color(0xFF050B1F), tokens.screenEdge)
        assertEquals(Color(0xFF0E1A36).copy(alpha = 0.92f), tokens.panel)
        assertEquals(Color(0xFF16244A), tokens.pill)
        assertEquals(Color.White.copy(alpha = 0.08f), tokens.hairline)
        assertEquals(Color(0xFF1F55E0), tokens.fairyBlue)
        assertEquals(Color(0xFF3D8BFF), tokens.fairyGlow)
        assertEquals(Color(0xFF8FB8FF), tokens.fairyLink)
        assertEquals(Color(0xFFEAF0FF), tokens.primary)
        assertEquals(Color(0xFF0A1633), tokens.onPrimary)
        assertEquals(Color.White.copy(alpha = 0.12f), tokens.userBubble)
        assertEquals(Color.White.copy(alpha = 0.06f), tokens.codeSurface)
        assertEquals(Color(0xFF5BE49B), tokens.online)
        assertEquals(Color(0xFFFF5A4E), tokens.danger)
        assertEquals(Color(0xFFEAF0FF), tokens.textPrimary)
        assertEquals(Color(0xFF8C9AB8), tokens.textMuted)
        assertEquals(tokens.fairyGlow.copy(alpha = 0.16f), tokens.ambientGlow)
    }
}
