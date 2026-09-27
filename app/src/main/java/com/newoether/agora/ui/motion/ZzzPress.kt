package com.newoether.agora.ui.motion

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * ZZZ press feedback: the element sinks ~1.5 dp while pressed. Under Reduced
 * Motion the offset applies instantly (no spatial animation) rather than
 * animating down.
 */
@Composable
fun Modifier.zzzPress(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val allowSpatial = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val offset by animateDpAsState(
        targetValue = if (pressed) 1.5.dp else 0.dp,
        animationSpec = if (allowSpatial) tween(durationMillis = 100) else snap(),
        label = "zzzPress",
    )
    return graphicsLayer { translationY = offset.toPx() }
}
