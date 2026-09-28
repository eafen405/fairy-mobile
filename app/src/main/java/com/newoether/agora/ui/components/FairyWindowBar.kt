package com.newoether.agora.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.LocalFairyTokens

/** Window-bar content height below the status bar inset. */
internal val FairyWindowBarHeight = 60.dp

/**
 * Fairy's window bar: an opaque bgTop strip with a 1 dp hairline bottom edge
 * (content never shows through), holding back arrow, the 64x44 dp mini
 * screen, the "Fairy" nameplate with a slanted status subtitle, and the
 * frameless more menu. While [FairyWindowState.expanded] the screen opens
 * below the bar at full width (16 dp margins, 140 dp high, 88 dp eye) and
 * collapses back over 350 ms when the first message arrives; Reduced Motion
 * switches instantly.
 */
@Composable
internal fun FairyWindowTopBar(
    window: FairyWindowState,
    subtitle: String?,
    subtitleLeading: (@Composable () -> Unit)?,
    searchActive: Boolean,
    onNavigateBack: () -> Unit,
    moreMenuContent: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)?,
    searchContent: @Composable () -> Unit,
) {
    val tokens = LocalFairyTokens.current
    val allowSpatial = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    var moreMenuOpen by remember { mutableStateOf(false) }
    val windowSpec = tween<IntSize>(350, easing = FastOutSlowInEasing)
    val fadeSpec = tween<Float>(350, easing = FastOutSlowInEasing)
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panelOpaque)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRect(
                        tokens.hairline,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - stroke),
                        size = androidx.compose.ui.geometry.Size(size.width, stroke),
                    )
                }
                .statusBarsPadding()
                .height(FairyWindowBarHeight),
        ) {
            CompositionLocalProvider(LocalContentColor provides tokens.textPrimary) {
                Crossfade(
                    targetState = searchActive,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    label = "FairyWindowSearch",
                ) { searching ->
                    if (searching) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            searchContent()
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onNavigateBack, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = tokens.textPrimary,
                                )
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !window.expanded,
                                enter = if (allowSpatial) fadeIn(fadeSpec) + expandHorizontally(windowSpec) else EnterTransition.None,
                                exit = if (allowSpatial) fadeOut(fadeSpec) + shrinkHorizontally(windowSpec) else ExitTransition.None,
                            ) {
                                Row {
                                    Spacer(Modifier.width(4.dp))
                                    FairyScreen(
                                        presence = window.presence,
                                        eyeSize = 36.dp,
                                        speechPulse = window.speechPulse,
                                        modifier = Modifier.size(width = 64.dp, height = 44.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(verticalArrangement = Arrangement.Center) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    fontFamily = tokens.titleFontFamily,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    color = tokens.textPrimary,
                                    maxLines = 1,
                                )
                                if (subtitle != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        subtitleLeading?.invoke()
                                        Text(
                                            text = subtitle,
                                            fontFamily = tokens.titleFontFamily,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            color = tokens.textMuted,
                                            maxLines = 1,
                                            modifier = Modifier.fairySlant(),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (moreMenuContent != null) {
                                Box {
                                    IconButton(onClick = { moreMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.options),
                                            modifier = Modifier.size(24.dp),
                                            tint = tokens.textPrimary,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = moreMenuOpen,
                                        onDismissRequest = { moreMenuOpen = false },
                                        shape = tokens.panelShape,
                                        containerColor = tokens.panelOpaque,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.hairline),
                                        tonalElevation = 0.dp,
                                    ) {
                                        moreMenuContent { moreMenuOpen = false }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = window.expanded,
            enter = if (allowSpatial) fadeIn(fadeSpec) + expandVertically(windowSpec) else EnterTransition.None,
            exit = if (allowSpatial) fadeOut(fadeSpec) + shrinkVertically(windowSpec) else ExitTransition.None,
        ) {
            FairyScreen(
                presence = window.presence,
                eyeSize = 88.dp,
                speechPulse = window.speechPulse,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .fillMaxWidth()
                    .height(140.dp),
            )
        }
    }
}
