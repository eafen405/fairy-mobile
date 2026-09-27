package com.newoether.agora.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.ui.theme.LocalZzzTokens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
class ZzzVisualScreenshotTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { AgoraTheme { content() } }
        compose.waitForIdle()
        // captureToImage()'s forced Choreographer redraw never completes under
        // Robolectric; drawToBitmap() renders the same hierarchy in software.
        val image: Bitmap = compose.activity.window.decorView.drawToBitmap()
        val file = ZzzScreenshots.savePng(name, image)
        assertTrue("screenshot not written: $file", file.isFile && file.length() > 0)
    }

    @Test
    fun `background, panel card, and emblem sizes`() {
        shot("s1-background-panel-emblem") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.Center),
                ) {
                    val tokens = LocalZzzTokens.current
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .zzzPanel(shape = tokens.panelShape)
                            .padding(16.dp),
                    ) {
                        Text(
                            "Fairy",
                            fontFamily = tokens.titleFontFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "PANEL 24DP",
                            fontFamily = tokens.labelFontFamily,
                            fontSize = 12.sp,
                            color = tokens.textMuted,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FairyEmblem(animating = false, size = 40.dp)
                        Spacer(Modifier.width(16.dp))
                        FairyEmblem(animating = false, size = 96.dp)
                        Spacer(Modifier.width(16.dp))
                        FairyEmblem(animating = false, size = 96.dp, breathOverride = 1f)
                    }
                }
            }
        }
    }

    @Test
    fun `background alone`() {
        shot("s1-background") { ZzzBackground() }
    }

    @Test
    fun `emblem closeup at 192dp`() {
        shot("s1-emblem-closeup") {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF121212)),
                contentAlignment = Alignment.Center,
            ) {
                FairyEmblem(animating = false, size = 192.dp, breathOverride = 0f)
            }
        }
    }

    @Test
    fun `emblem at 40dp`() {
        shot("s1-emblem-40dp") {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF121212)),
                contentAlignment = Alignment.Center,
            ) {
                FairyEmblem(animating = false, size = 40.dp, breathOverride = 0f)
            }
        }
    }

    @Test
    fun `emblem side by side with reference`() {
        compose.setContent {
            AgoraTheme {
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF121212)),
                    contentAlignment = Alignment.Center,
                ) {
                    FairyEmblem(animating = false, size = 192.dp, breathOverride = 0f)
                }
            }
        }
        compose.waitForIdle()
        val rendered: Bitmap = compose.activity.window.decorView.drawToBitmap()
        val refPath = "/mnt/d/file/Fufu/Fairy/细节.png"
        val ref = android.graphics.BitmapFactory.decodeFile(refPath)
        assertTrue("reference not readable: $refPath", ref != null)
        // Composite: reference left (scaled to match height), ours right.
        val scale = rendered.height.toFloat() / ref.height
        val refW = (ref.width * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(ref, refW, rendered.height, true)
        val out = Bitmap.createBitmap(
            refW + rendered.width, rendered.height, Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(0xFF121212.toInt())
        canvas.drawBitmap(scaled, 0f, 0f, null)
        canvas.drawBitmap(rendered, refW.toFloat(), 0f, null)
        val file = ZzzScreenshots.savePng("s1-emblem-sidebyside", out)
        assertTrue(file.isFile && file.length() > 0)
    }
}
