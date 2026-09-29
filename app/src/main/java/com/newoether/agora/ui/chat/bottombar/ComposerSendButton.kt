package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.motion.fairyPress
import com.newoether.agora.ui.theme.LocalFairyTokens

private enum class ComposerActionIcon {
    BUSY,
    STOP,
    SEND,
}

@Composable
@OptIn(ExperimentalAnimationApi::class)
internal fun ComposerSendButton(
    isActionable: Boolean,
    isBusy: Boolean,
    showStop: Boolean = false,
    onBusyShown: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val icon = when {
        isBusy -> ComposerActionIcon.BUSY
        showStop -> ComposerActionIcon.STOP
        else -> ComposerActionIcon.SEND
    }
    val transition = updateTransition(targetState = icon, label = "composerActionIcon")
    val latestBusyShown by rememberUpdatedState(onBusyShown)
    LaunchedEffect(isBusy, transition.currentState, transition.isRunning) {
        if (isBusy && transition.currentState == ComposerActionIcon.BUSY && !transition.isRunning && latestBusyShown != null) {
            withFrameNanos { }
            latestBusyShown?.invoke()
        }
    }
    val tokens = LocalFairyTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = when {
            !isActionable -> tokens.pill
            showStop -> tokens.danger
            else -> tokens.primary
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !isActionable -> tokens.textMuted
            showStop -> tokens.onDanger
            else -> tokens.onPrimary
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContent",
    )

    Surface(
        onClick = onClick,
        enabled = isActionable,
        interactionSource = interactionSource,
        modifier = Modifier.size(44.dp).fairyPress(interactionSource),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            transition.Crossfade(
                animationSpec = tween(
                    durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS,
                    easing = LinearEasing,
                ),
            ) { renderedIcon ->
                when (renderedIcon) {
                    ComposerActionIcon.BUSY -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = contentColor,
                    )
                    ComposerActionIcon.STOP -> Icon(
                        Icons.Default.Stop,
                        stringResource(R.string.action),
                        modifier = Modifier.size(24.dp),
                    )
                    ComposerActionIcon.SEND -> Icon(
                        Icons.AutoMirrored.Filled.Send,
                        stringResource(R.string.action),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
