package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.newoether.agora.R
import com.newoether.agora.ui.components.fairyPanel
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.LocalFairyTokens
import com.newoether.agora.util.noOpBringIntoView

/** Shared composer drawing; callers own drafts, attachment work and submission. */
@Composable
internal fun ChatComposerLayout(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    onInputFocusChanged: (Boolean) -> Unit,
    isExpanded: Boolean,
    isExpandAnimating: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    statusContent: @Composable () -> Unit = {},
    attachmentContent: @Composable () -> Unit = {},
    // Single-line layout: one fully rounded capsule holding leadingControls,
    // the input and controls; status and attachment rows sit above it.
    singleLine: Boolean = false,
    leadingControls: @Composable RowScope.() -> Unit = {},
    controls: @Composable RowScope.() -> Unit,
) {
    if (singleLine) {
        SingleLineComposerLayout(
            textFieldState = textFieldState,
            focusRequester = focusRequester,
            onInputFocusChanged = onInputFocusChanged,
            isExpanded = isExpanded,
            isExpandAnimating = isExpandAnimating,
            onExpand = onExpand,
            onCollapse = onCollapse,
            modifier = modifier,
            inputModifier = inputModifier,
            scrollState = scrollState,
            statusContent = statusContent,
            attachmentContent = attachmentContent,
            leadingControls = leadingControls,
            controls = controls,
        )
        return
    }
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val tokens = LocalFairyTokens.current
    val composerOcclusionShape = RoundedCornerShape(28.dp)
    Box(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = EnterTransition.None,
                exit = if (allowSpatialTransitions) {
                    shrinkVertically(tween(250)) + fadeOut(tween(250))
                } else {
                    fadeOut(tween(250))
                },
            ) {
                Spacer(modifier = Modifier.height(44.dp))
            }
            statusContent()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.weight(1f) else Modifier)
                    .then(
                        if (allowSpatialTransitions) {
                            Modifier.animateContentSize(
                                animationSpec = tween(durationMillis = 400),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(composerOcclusionShape)
                    .fairyPanel(shape = composerOcclusionShape, color = tokens.panel)
                    .zIndex(1f),
            ) {
        attachmentContent()

        Box(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).noOpBringIntoView()) {
            TextField(
                state = textFieldState,
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                    .then(inputModifier)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        onInputFocusChanged(focusState.isFocused)
                    }
                    .verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                placeholder = {
                    Text(
                        stringResource(R.string.ask_agora),
                        style = ChatType.input,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface)
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = !isExpanded,
                enter = fadeIn(tween(250)),
                exit = ExitTransition.None,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                IconButton(onClick = { if (!isExpandAnimating) onExpand() }, modifier = Modifier.padding(end = 4.dp, top = 4.dp).size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.expand_all_24px), contentDescription = stringResource(R.string.expand), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
            }
        }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            controls()
        }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)
        ) {
            val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            IconButton(onClick = { if (!isExpandAnimating) onCollapse() }, modifier = Modifier.size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.collapse_all_24px), contentDescription = stringResource(R.string.collapse), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
        }
    }

}

private const val SINGLE_LINE_EXPAND_THRESHOLD_LINES = 3

@Composable
private fun SingleLineComposerLayout(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    onInputFocusChanged: (Boolean) -> Unit,
    isExpanded: Boolean,
    isExpandAnimating: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier,
    inputModifier: Modifier,
    scrollState: ScrollState,
    statusContent: @Composable () -> Unit,
    attachmentContent: @Composable () -> Unit,
    leadingControls: @Composable RowScope.() -> Unit,
    controls: @Composable RowScope.() -> Unit,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val tokens = LocalFairyTokens.current
    val capsule = if (isExpanded) tokens.panelShape else RoundedCornerShape(50)
    var lineCount by remember { mutableIntStateOf(1) }
    Box(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = EnterTransition.None,
                exit = if (allowSpatialTransitions) {
                    shrinkVertically(tween(250)) + fadeOut(tween(250))
                } else {
                    fadeOut(tween(250))
                },
            ) {
                Spacer(modifier = Modifier.height(44.dp))
            }
            statusContent()
            Box(Modifier.padding(bottom = 6.dp)) { attachmentContent() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.weight(1f) else Modifier)
                    .then(
                        if (allowSpatialTransitions) {
                            Modifier.animateContentSize(animationSpec = tween(durationMillis = 400))
                        } else {
                            Modifier
                        },
                    )
                    .fairyPanel(shape = capsule)
                    .clip(capsule)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = leadingControls)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                        .noOpBringIntoView(),
                ) {
                    TextField(
                        state = textFieldState,
                        scrollState = scrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            // One line matches the 44 dp side controls instead of
                            // the 56 dp TextField default.
                            .heightIn(min = 44.dp)
                            .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                            .then(inputModifier)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                onInputFocusChanged(focusState.isFocused)
                            }
                            .verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        placeholder = {
                            Text(
                                stringResource(R.string.ask_agora),
                                style = ChatType.input,
                                color = tokens.textMuted,
                                maxLines = 1,
                            )
                        },
                        onTextLayout = { result -> lineCount = result()?.lineCount ?: 1 },
                        lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6),
                        contentPadding = PaddingValues(start = 8.dp, top = 11.dp, end = 8.dp, bottom = 11.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            cursorColor = tokens.primary,
                        ),
                        textStyle = ChatType.input.copy(color = tokens.textPrimary),
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isExpanded && lineCount > SINGLE_LINE_EXPAND_THRESHOLD_LINES,
                        enter = fadeIn(tween(250)),
                        exit = fadeOut(tween(250)),
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        IconButton(onClick = { if (!isExpandAnimating) onExpand() }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.expand_all_24px),
                                contentDescription = stringResource(R.string.expand),
                                modifier = Modifier.size(18.dp),
                                tint = tokens.textMuted,
                            )
                        }
                    }
                }
                Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = controls)
                }
            }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp),
        ) {
            IconButton(onClick = { if (!isExpandAnimating) onCollapse() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.collapse_all_24px),
                    contentDescription = stringResource(R.string.collapse),
                    modifier = Modifier.size(20.dp),
                    tint = tokens.textMuted,
                )
            }
        }
    }
}
