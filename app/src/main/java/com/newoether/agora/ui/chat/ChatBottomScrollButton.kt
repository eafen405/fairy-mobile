package com.newoether.agora.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.fairyPanel
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.LocalFairyTokens
import androidx.compose.ui.graphics.Color

@Composable
internal fun BoxScope.ChatBottomScrollButton(
    showButton: Boolean,
    bottomBarHeight: Dp,
    onClick: () -> Unit,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    AnimatedVisibility(
        visible = showButton,
        enter = if (motionPolicy.allowSpatialTransitions) {
            fadeIn(tween(400)) +
                scaleIn(initialScale = 0.6f, animationSpec = tween(400))
        } else {
            fadeIn(tween(400))
        },
        exit = if (motionPolicy.allowSpatialTransitions) {
            fadeOut(tween(400)) +
                scaleOut(targetScale = 0.6f, animationSpec = tween(400))
        } else {
            fadeOut(tween(400))
        },
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomBarHeight + 12.dp)
    ) {
        val tokens = LocalFairyTokens.current
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = tokens.textPrimary,
            modifier = Modifier
                .size(36.dp)
                .fairyPanel(CircleShape, tokens.panelOpaque.copy(alpha = 0.85f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom), modifier = Modifier.size(22.dp))
            }
        }
    }
}
