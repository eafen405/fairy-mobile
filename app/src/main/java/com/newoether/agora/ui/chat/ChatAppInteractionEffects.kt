package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val STREAM_SCROLL_RESUME_DELAY_MS = 160L

/**
 * Text/argument growth within an existing message tree can be coalesced while LazyColumn owns a
 * scroll animation. Structural changes remain immediate so a new thinking/tool block or lifecycle
 * state is never hidden behind the gate.
 */
internal fun sameStreamingRenderStructure(
    previous: List<ChatMessage>,
    next: List<ChatMessage>,
): Boolean {
    if (previous.size != next.size) return false
    return previous.indices.all { index ->
        val before = previous[index]
        val after = next[index]
        if (before === after) return@all true
        if (
            before.id != after.id ||
            before.parentId != after.parentId ||
            before.participant != after.participant ||
            before.status != after.status ||
            before.remoteFiles.size != after.remoteFiles.size ||
            before.thoughts.isNullOrBlank() != after.thoughts.isNullOrBlank()
        ) {
            return@all false
        }
        val beforeSegments = before.segments
        val afterSegments = after.segments
        if (beforeSegments == null || afterSegments == null) {
            return@all beforeSegments == null && afterSegments == null
        }
        if (beforeSegments.size != afterSegments.size) return@all false
        beforeSegments.indices.all { segmentIndex ->
            val beforeSegment = beforeSegments[segmentIndex]
            val afterSegment = afterSegments[segmentIndex]
            beforeSegment.type == afterSegment.type &&
                beforeSegment.toolCallId == afterSegment.toolCallId &&
                beforeSegment.toolState == afterSegment.toolState &&
                beforeSegment.toolDisplayName == afterSegment.toolDisplayName &&
                (beforeSegment.toolNote == null) == (afterSegment.toolNote == null)
        }
    }
}

@Composable
internal fun rememberScrollIsolatedMessages(
    conversationId: String?,
    upstream: State<List<ChatMessage>>,
    listState: LazyListState,
    bypassScrollIsolation: Boolean,
): State<List<ChatMessage>> {
    val rendered = remember(conversationId, upstream) {
        mutableStateOf(upstream.value)
    }
    val latestBypassScrollIsolation by rememberUpdatedState(bypassScrollIsolation)
    LaunchedEffect(conversationId, upstream, listState) {
        coroutineScope {
            var latest = upstream.value
            var deferred = listState.isScrollInProgress
            var hasOwnedScroll = listState.isScrollInProgress
            var resumeJob: Job? = null

            launch {
                snapshotFlow {
                    listState.isScrollInProgress to latestBypassScrollIsolation
                }
                    .distinctUntilChanged()
                    .collect { (scrolling, bypass) ->
                        resumeJob?.cancel()
                        if (bypass) {
                            deferred = false
                            hasOwnedScroll = false
                            if (rendered.value !== latest) rendered.value = latest
                        } else if (scrolling) {
                            hasOwnedScroll = true
                            deferred = true
                        } else if (hasOwnedScroll) {
                            deferred = true
                            resumeJob = launch {
                                delay(STREAM_SCROLL_RESUME_DELAY_MS)
                                deferred = false
                                hasOwnedScroll = false
                                if (rendered.value !== latest) {
                                    rendered.value = latest
                                }
                            }
                        } else {
                            // Initial idle observation: do not impose a synthetic 160 ms delay on
                            // the first provider token.
                            deferred = false
                        }
                    }
            }

            launch {
                snapshotFlow { upstream.value }
                    .distinctUntilChanged()
                    .collect { next ->
                        latest = next
                        if (
                            latestBypassScrollIsolation ||
                            !deferred ||
                            !sameStreamingRenderStructure(rendered.value, next)
                        ) {
                            rendered.value = next
                        }
                    }
            }
        }
    }
    return rendered
}

@Composable
internal fun ChatLaunchInteractionEffects(
    initialComposerFocusReady: Boolean,
    inputFocusRequester: FocusRequester,
    onShowLaunchContent: () -> Unit,
    onInitialFocusRequested: () -> Unit = {},
) {
    val latestOnShowLaunchContent by rememberUpdatedState(onShowLaunchContent)
    val latestOnInitialFocusRequested by rememberUpdatedState(onInitialFocusRequested)
    LaunchedEffect(Unit) {
        delay(50)
        latestOnShowLaunchContent()
    }
    LaunchedEffect(initialComposerFocusReady, inputFocusRequester) {
        if (initialComposerFocusReady) {
            delay(50)
            inputFocusRequester.requestFocus()
            latestOnInitialFocusRequested()
        }
    }
}

internal data class ComposerSpacerAnimation(
    val outerHeightPx: Float,
    val isRunning: Boolean,
)

@Composable
internal fun rememberComposerSpacerAnimation(
    isExpanded: Boolean,
    allowSpatialTransitions: Boolean,
    expandedHeightPx: Float,
): ComposerSpacerAnimation {
    val spacerProgress = remember { Animatable(0f) }
    val spacerEasing = remember { CubicBezierEasing(0.15f, 0.5f, 0.25f, 1.0f) }
    LaunchedEffect(isExpanded, allowSpatialTransitions) {
        if (isExpanded) {
            if (allowSpatialTransitions) {
                spacerProgress.snapTo(0f)
                spacerProgress.animateTo(1f, tween(400, easing = spacerEasing))
            } else {
                spacerProgress.snapTo(1f)
            }
        } else {
            spacerProgress.snapTo(0f)
        }
    }
    return ComposerSpacerAnimation(
        outerHeightPx = if (isExpanded) {
            expandedHeightPx * (1f - spacerProgress.value)
        } else {
            0f
        },
        isRunning = spacerProgress.isRunning,
    )
}

@Composable
internal fun SnackbarOffsetEffect(
    drawerProgress: Float,
    isExpanded: Boolean,
    bottomBarHeight: Dp,
    settingsButtonTopDp: Float,
    bottomInset: Dp,
    onOffsetChanged: (Dp) -> Unit,
) {
    val expandedCapsuleOffset = bottomInset + 74.dp
    val targetSnackbarOffset = if (drawerProgress <= 0.5f) {
        if (isExpanded) expandedCapsuleOffset else (bottomBarHeight - 4.dp).coerceAtLeast(0.dp)
    } else {
        val t = ((drawerProgress - 0.5f) * 2f).coerceIn(0f, 1f)
        (bottomBarHeight.value + (settingsButtonTopDp - bottomBarHeight.value) * t).dp
    }
    LaunchedEffect(targetSnackbarOffset) { onOffsetChanged(targetSnackbarOffset) }
}

