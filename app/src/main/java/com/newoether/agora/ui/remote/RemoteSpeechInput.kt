package com.newoether.agora.ui.remote

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.newoether.agora.R
import com.newoether.agora.speech.SpeechInputFailure
import com.newoether.agora.speech.SpeechInputPhase
import com.newoether.agora.speech.SpeechSessionController
import com.newoether.agora.speech.createSpeechRecognitionEngine
import com.newoether.agora.ui.chat.bottombar.QUEUED_MESSAGE_HEIGHT
import com.newoether.agora.ui.chat.bottombar.QUEUED_MESSAGE_HORIZONTAL_INSET
import com.newoether.agora.ui.chat.bottombar.QUEUED_MESSAGE_SHAPE
import com.newoether.agora.ui.common.LocalAgoraHaptics
import java.util.Locale

internal val SPEECH_CANCEL_THRESHOLD = 80.dp
private const val SPEECH_FINALIZE_TIMEOUT_MS = 8_000L

/**
 * Speech input scoped to the active composer owner: the recognizer is created on
 * each press and destroyed when the session ends, the owner changes, or the
 * composable leaves composition. Microphone permission is requested only when the
 * user actually presses the control.
 */
@Composable
internal fun rememberRemoteSpeechController(
    owner: String,
    field: TextFieldState,
    active: Boolean,
    persistDraft: (String) -> Unit = {},
): SpeechSessionController {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val focusManager = LocalFocusManager.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val threshold = with(LocalDensity.current) { SPEECH_CANCEL_THRESHOLD.toPx() }
    val permissionRequest = remember(owner) { mutableStateOf(false) }
    val controller = remember(owner) {
        SpeechSessionController(
            engineFactory = { createSpeechRecognitionEngine(appContext) { locale } },
            hasPermission = {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            requestPermission = { permissionRequest.value = true },
            readDraft = { field.text.toString() },
            writeDraft = { text ->
                field.edit {
                    replace(0, length, text)
                    placeCursorAtEnd()
                }
                // The field→drafts sync can already be gone during owner disposal;
                // the persisted draft is what a later visit restores the field from.
                persistDraft(text)
            },
            cancelThresholdPx = threshold,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequest.value = false
        controller.onPermissionResult(granted)
    }
    LaunchedEffect(permissionRequest.value) {
        if (permissionRequest.value) permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(controller.phase) {
        if (controller.exclusive) focusManager.clearFocus()
        if (controller.phase == SpeechInputPhase.FINALIZING) {
            kotlinx.coroutines.delay(SPEECH_FINALIZE_TIMEOUT_MS)
            controller.finalizeExpired()
        }
    }
    LaunchedEffect(active) {
        if (!active) controller.dispose()
    }
    DisposableEffect(owner) {
        onDispose { controller.dispose() }
    }
    return controller
}

/** Press-and-hold microphone control; sliding up past the cancel threshold discards the take. */
@Composable
internal fun RemoteSpeechButton(
    controller: SpeechSessionController,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAgoraHaptics.current
    val phase = controller.phase
    val listening = phase == SpeechInputPhase.LISTENING
    val cancelling = phase == SpeechInputPhase.CANCELLING
    val description = stringResource(R.string.remote_voice_input)
    val stateText = stringResource(
        when {
            cancelling -> R.string.remote_voice_release_cancel
            listening -> R.string.remote_voice_listening
            else -> R.string.remote_voice_hold_hint
        },
    )
    val container = when {
        cancelling -> MaterialTheme.colorScheme.errorContainer
        controller.exclusive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        cancelling -> MaterialTheme.colorScheme.onErrorContainer
        controller.exclusive -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier
            .size(46.dp)
            .semantics {
                contentDescription = description
                stateDescription = stateText
            }
            .pointerInput(controller, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    controller.pressStarted()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            val wasCancelling = controller.phase == SpeechInputPhase.CANCELLING
                            controller.pressMoved(down.position.y - change.position.y)
                            if ((controller.phase == SpeechInputPhase.CANCELLING) != wasCancelling) {
                                haptics.selection()
                            }
                        }
                    } finally {
                        // A restarted pointerInput must still end the session cleanly.
                        controller.pressReleased()
                    }
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                if (cancelling) Icons.Default.Close else Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Composer status row mirroring the speech session: progress hint, cancel feedback, or a failure with retry affordances. */
@Composable
internal fun RemoteSpeechStatus(controller: SpeechSessionController) {
    val context = LocalContext.current
    val phase = controller.phase
    val failure = controller.failure
    val text = when {
        phase == SpeechInputPhase.LISTENING -> stringResource(R.string.remote_voice_listening)
        phase == SpeechInputPhase.CANCELLING -> stringResource(R.string.remote_voice_release_cancel)
        phase == SpeechInputPhase.FINALIZING -> stringResource(R.string.remote_voice_processing)
        phase == SpeechInputPhase.AWAITING_PERMISSION -> stringResource(R.string.remote_voice_permission_request)
        phase == SpeechInputPhase.ERROR -> stringResource(
            when (failure) {
                SpeechInputFailure.PERMISSION -> R.string.remote_voice_permission_denied
                SpeechInputFailure.UNAVAILABLE -> R.string.remote_voice_unavailable
                SpeechInputFailure.NO_MATCH -> R.string.remote_voice_no_match
                SpeechInputFailure.NETWORK -> R.string.remote_voice_network
                else -> R.string.remote_voice_failed
            },
        )
        else -> null
    } ?: return
    val error = phase == SpeechInputPhase.ERROR
    Column {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QUEUED_MESSAGE_HORIZONTAL_INSET)
            .height(QUEUED_MESSAGE_HEIGHT),
        shape = QUEUED_MESSAGE_SHAPE,
        color = when {
            phase == SpeechInputPhase.CANCELLING || error -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = when {
            phase == SpeechInputPhase.CANCELLING || error -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (error && failure == SpeechInputFailure.PERMISSION) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }) {
                    Text(stringResource(R.string.remote_voice_open_settings))
                }
            }
            if (error) {
                IconButton(
                    onClick = controller::dismissError,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    }
}
