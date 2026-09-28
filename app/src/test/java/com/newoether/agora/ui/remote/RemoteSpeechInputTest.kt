package com.newoether.agora.ui.remote

import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.speech.SpeechInputPhase
import com.newoether.agora.speech.SpeechRecognitionEngine
import com.newoether.agora.speech.SpeechSessionController
import com.newoether.agora.ui.chat.bottombar.ChatComposerLayout
import com.newoether.agora.ui.chat.bottombar.ComposerSendButton
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteSpeechInputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val voiceDesc get() = context.getString(R.string.remote_voice_input)
    private val sendDesc get() = context.getString(R.string.action)
    private val threshold = 200f

    private class FakeEngine : SpeechRecognitionEngine {
        var listener: SpeechRecognitionEngine.Listener? = null
        var destroyed = 0
        override fun start(listener: SpeechRecognitionEngine.Listener) { this.listener = listener }
        override fun stopListening() {}
        override fun cancel() {}
        override fun destroy() { destroyed++ }
    }

    private fun controller(
        field: TextFieldState,
        engine: FakeEngine? = FakeEngine(),
        permission: Boolean = true,
        onRequest: () -> Unit = {},
    ) = SpeechSessionController(
        engineFactory = { engine },
        hasPermission = { permission },
        requestPermission = onRequest,
        readDraft = { field.text.toString() },
        writeDraft = { text -> field.edit { replace(0, length, text) } },
        cancelThresholdPx = threshold,
    )

    private fun mount(field: TextFieldState, controller: SpeechSessionController) {
        compose.setContent {
            MaterialTheme {
                ChatComposerLayout(
                    textFieldState = field,
                    focusRequester = FocusRequester(),
                    onInputFocusChanged = {},
                    isExpanded = false,
                    isExpandAnimating = false,
                    onExpand = {},
                    onCollapse = {},
                    inputReadOnly = controller.exclusive,
                    statusContent = { RemoteSpeechStatus(controller) },
                    controls = {
                        RemoteSpeechButton(controller, enabled = true)
                        ComposerSendButton(
                            isActionable = !controller.exclusive && field.text.isNotBlank(),
                            isBusy = false,
                            onClick = {},
                        )
                    },
                )
            }
        }
    }

    @Test fun voiceControlIsAvailableBesideTheEditableComposer() {
        val field = TextFieldState("draft")
        mount(field, controller(field))
        compose.onNodeWithContentDescription(voiceDesc).assertIsDisplayed()
        compose.onNodeWithContentDescription(sendDesc).assertIsDisplayed().assertIsEnabled()
    }

    @Test fun listeningOwnsTheComposerAndShowsPartialText() {
        val field = TextFieldState("draft")
        val engine = FakeEngine()
        val controller = controller(field, engine)
        mount(field, controller)
        compose.runOnIdle { controller.pressStarted() }
        compose.onNodeWithContentDescription(sendDesc).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.remote_voice_listening)).assertIsDisplayed()
        compose.runOnIdle { engine.listener?.onPartialResult("spoken words") }
        assertEquals("draft spoken words", field.text.toString())
    }

    @Test fun releaseRestoresEditableControlsAndCommitsTheFinalText() {
        val field = TextFieldState()
        val engine = FakeEngine()
        val controller = controller(field, engine)
        mount(field, controller)
        compose.runOnIdle {
            controller.pressStarted()
            engine.listener?.onPartialResult("hel")
            controller.pressReleased()
            engine.listener?.onFinalResult("hello")
        }
        assertEquals("hello", field.text.toString())
        assertEquals(SpeechInputPhase.IDLE, controller.phase)
        compose.onNodeWithContentDescription(sendDesc).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.remote_voice_listening)).assertDoesNotExist()
    }

    @Test fun slideToCancelShowsFeedbackAndRestoresTheDraft() {
        val field = TextFieldState("keep")
        val engine = FakeEngine()
        val controller = controller(field, engine)
        mount(field, controller)
        compose.runOnIdle {
            controller.pressStarted()
            engine.listener?.onPartialResult("junk")
            controller.pressMoved(threshold)
        }
        compose.onNodeWithText(context.getString(R.string.remote_voice_release_cancel)).assertIsDisplayed()
        compose.runOnIdle { controller.pressReleased() }
        assertEquals("keep", field.text.toString())
        compose.onNodeWithText(context.getString(R.string.remote_voice_release_cancel)).assertDoesNotExist()
        compose.onNodeWithContentDescription(sendDesc).assertIsEnabled()
    }

    @Test fun firstUseRequestsMicrophoneAndDenialOffersARecoveryPath() {
        val field = TextFieldState("draft")
        var requested = false
        val controller = controller(field, permission = false, onRequest = { requested = true })
        mount(field, controller)
        assertFalse(requested)
        compose.runOnIdle { controller.pressStarted() }
        assertTrue(requested)
        compose.onNodeWithText(context.getString(R.string.remote_voice_permission_request)).assertIsDisplayed()
        compose.runOnIdle { controller.onPermissionResult(granted = false) }
        compose.onNodeWithText(context.getString(R.string.remote_voice_permission_denied)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.remote_voice_open_settings)).assertIsDisplayed()
        assertEquals("draft", field.text.toString())
    }

    @Test fun unavailableRecognitionIsSurfacedWithoutTouchingTheDraft() {
        val field = TextFieldState("draft")
        val controller = controller(field, engine = null)
        mount(field, controller)
        compose.runOnIdle { controller.pressStarted() }
        compose.onNodeWithText(context.getString(R.string.remote_voice_unavailable)).assertIsDisplayed()
        assertEquals("draft", field.text.toString())
    }

    @Test fun manifestDeclaresMicrophoneForVoiceInput() {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        )
        assertTrue(info.requestedPermissions.orEmpty().contains(android.Manifest.permission.RECORD_AUDIO))
    }

    @Test fun mergedManifestDeclaresTheRecognitionServiceQuery() {
        // Service discovery is subject to package visibility on Android 11+; the
        // <queries> entry must survive manifest merging. Robolectric cannot enforce
        // visibility, so assert on the merged manifest artifact directly.
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        var moduleRoot: File? = null
        while (directory != null && moduleRoot == null) {
            moduleRoot = when {
                File(directory, "src/main/AndroidManifest.xml").isFile -> directory
                File(directory, "app/src/main/AndroidManifest.xml").isFile -> File(directory, "app")
                else -> null
            }
            directory = directory.parentFile
        }
        val merged = File(requireNotNull(moduleRoot), "build/intermediates")
            .walkTopDown()
            .filter { it.isFile && it.path.contains("merged_manifest") && it.name == "AndroidManifest.xml" }
            .toList()
        assertTrue("no merged manifest found for this variant", merged.isNotEmpty())
        val queries = merged.map { it.readText().substringAfter("<queries>").substringBefore("</queries>") }
        assertTrue(queries.any { it.contains("android.speech.RecognitionService") })
    }

    @Test fun voiceInputLabelFollowsTheTitleCaseConvention() {
        // development/application-ui.md: standalone command labels use Title Case.
        assertEquals("Voice Input", context.getString(R.string.remote_voice_input))
    }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        compose.activity.dispatchTouchEvent(
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0),
        )
        compose.waitForIdle()
    }

    @Test fun aRealHoldSlideUpAndReleaseCancelsThroughThePointerSeam() {
        val field = TextFieldState("keep")
        val engine = FakeEngine()
        val controller = controller(field, engine)
        mount(field, controller)
        val bounds = compose.onNodeWithContentDescription(voiceDesc).fetchSemanticsNode().boundsInRoot
        val x = bounds.left + bounds.width / 2
        val y = bounds.top + bounds.height / 2
        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, downTime)
        assertEquals(SpeechInputPhase.LISTENING, controller.phase)
        compose.runOnIdle { engine.listener?.onPartialResult("junk") }
        touch(MotionEvent.ACTION_MOVE, x, y - 250f, downTime)
        assertEquals(SpeechInputPhase.CANCELLING, controller.phase)
        compose.onNodeWithText(context.getString(R.string.remote_voice_release_cancel)).assertIsDisplayed()
        touch(MotionEvent.ACTION_UP, x, y - 250f, downTime)
        assertEquals(SpeechInputPhase.IDLE, controller.phase)
        assertEquals("keep", field.text.toString())
        compose.onNodeWithContentDescription(sendDesc).assertIsEnabled()
    }

    @Test fun aRealHoldWithAnEarlyProviderFinalStillCancelsOnSlideUp() {
        val field = TextFieldState("draft")
        val engine = FakeEngine()
        val controller = controller(field, engine)
        mount(field, controller)
        val bounds = compose.onNodeWithContentDescription(voiceDesc).fetchSemanticsNode().boundsInRoot
        val x = bounds.left + bounds.width / 2
        val y = bounds.top + bounds.height / 2
        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, downTime)
        assertEquals(SpeechInputPhase.LISTENING, controller.phase)
        compose.runOnIdle { engine.listener?.onFinalResult("spoken") }
        assertEquals(SpeechInputPhase.LISTENING, controller.phase)
        touch(MotionEvent.ACTION_MOVE, x, y - 250f, downTime)
        assertEquals(SpeechInputPhase.CANCELLING, controller.phase)
        touch(MotionEvent.ACTION_UP, x, y - 250f, downTime)
        assertEquals(SpeechInputPhase.IDLE, controller.phase)
        assertEquals("draft", field.text.toString())
    }
}
