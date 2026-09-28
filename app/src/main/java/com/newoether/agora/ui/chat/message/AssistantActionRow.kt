package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.GenerationActivityDot
import com.newoether.agora.ui.chat.shouldShowStreamingTailIndicator
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.LocalFairyTokens

/**
 * Shared per-message citation UI state so the inline citation taps inside the
 * bubble and the action row below it open the same dialogs/sheets.
 */
internal class AssistantCitationUiState {
    var selectedCitation by mutableStateOf<CitationRecord?>(null)
    var showSources by mutableStateOf(false)
    var groupedSources by mutableStateOf<List<CitationRecord>?>(null)
}

@Composable
internal fun rememberAssistantCitationUi(messageId: String): AssistantCitationUiState =
    remember(messageId) { AssistantCitationUiState() }

/**
 * The assistant action row: citation summary capsule, streaming tail dot, and
 * the copy / regenerate / fork / share / overflow buttons plus the branch
 * selector. Rendered inside [AssistantMessageContent] for the error path, or
 * hoisted below the Fairy bubble by [MessageItem] for MODEL messages — where
 * the icons sit on the dark background in the muted token color.
 *
 * [iconTint] is the enabled-state tint; pass [LocalFairyTokens]' textMuted when
 * the row lives on the dark page background.
 */
@Composable
internal fun AssistantActionRow(
    message: ChatMessage,
    citations: List<CitationRecord>,
    citationUi: AssistantCitationUiState,
    isStreaming: Boolean,
    isLoading: Boolean,
    isStopping: Boolean,
    isRegenerationExiting: Boolean,
    isEditingAllowed: Boolean,
    actionCopyText: String?,
    showBranchSelector: Boolean,
    branchIndex: Int,
    totalBranches: Int,
    iconTint: Color? = null,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: (String) -> Boolean,
    onFork: () -> Unit,
    onShare: () -> Unit,
    onShowInfo: () -> Unit,
    onShowDelete: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current
    var showMenu by remember(message.id) { mutableStateOf(false) }
    var regenerateRequested by remember(message.id) { mutableStateOf(false) }
    var observedRegenerationExit by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(isRegenerationExiting) {
        if (isRegenerationExiting) {
            observedRegenerationExit = true
        } else if (observedRegenerationExit) {
            // An aborted transition keeps the old answer composed. Restore its controls only
            // after the externally-owned regeneration state has genuinely ended.
            regenerateRequested = false
            observedRegenerationExit = false
        }
    }
    val regenerationActionsExiting = regenerateRequested || isRegenerationExiting
    val actionAvailability = assistantActionAvailability(
        isStreaming = isStreaming,
        isLoading = isLoading,
        regenerateRequested = regenerationActionsExiting,
    )
    val sourcesSummaryVisible = citationSummaryVisible(
        showActions = true,
        informationVisible = actionAvailability.informationVisible,
        sourceCount = citations.size,
    )
    LaunchedEffect(regenerationActionsExiting, sourcesSummaryVisible) {
        if (regenerationActionsExiting) showMenu = false
        if (!sourcesSummaryVisible) citationUi.showSources = false
    }
    val informationActionsAlpha by animateFloatAsState(
        targetValue = if (actionAvailability.informationVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (actionAvailability.informationVisible) {
                ACTIONS_ENTER_DURATION_MS
            } else {
                ACTIONS_EXIT_DURATION_MS
            },
            easing = LinearEasing,
        ),
        label = "assistantInformationActions:${message.id}",
    )
    val terminalActionsAlpha by animateFloatAsState(
        targetValue = if (actionAvailability.terminalVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (actionAvailability.terminalVisible) {
                ACTIONS_ENTER_DURATION_MS
            } else {
                ACTIONS_EXIT_DURATION_MS
            },
            easing = LinearEasing,
        ),
        label = "assistantActions:${message.id}",
    )
    val defaultTint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
    val enabledActionTint = defaultTint.copy(alpha = 0.6f)
    val terminalActionTint =
        defaultTint.copy(alpha = if (actionAvailability.terminalEnabled) 0.6f else 0.3f)
    val destructiveActionTint =
        MaterialTheme.colorScheme.error.copy(
            alpha = if (actionAvailability.terminalEnabled) 1f else 0.38f
        )
    if (sourcesSummaryVisible || informationActionsAlpha > 0f) {
        CitationSourcesSummaryCapsule(
            messageId = message.id,
            citations = citations,
            searchSpec = null,
            visible = sourcesSummaryVisible,
            enabled = sourcesSummaryVisible,
            onClick = {
                citationUi.groupedSources = null
                citationUi.showSources = true
            },
            modifier = Modifier
                .offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)
                .padding(top = 12.dp)
                .graphicsLayer { alpha = informationActionsAlpha },
        )
    }
    val answerTailVisible = shouldShowStreamingTailIndicator(isStreaming, isStopping, message)
    val actionContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (!actionCopyText.isNullOrBlank()) {
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(actionCopyText))
                    haptics.confirm()
                },
                enabled = actionAvailability.informationEnabled,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { alpha = informationActionsAlpha },
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = enabledActionTint,
                )
            }
        }
        IconButton(
            onClick = {
                if (onRegenerate(message.id)) {
                    regenerateRequested = true
                    showMenu = false
                }
            },
            enabled = actionAvailability.terminalEnabled,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer { alpha = terminalActionsAlpha },
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = terminalActionTint,
            )
        }
        IconButton(
            onClick = onFork,
            enabled = actionAvailability.terminalEnabled,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer { alpha = terminalActionsAlpha },
        ) {
            Icon(
                Icons.Default.CallSplit,
                contentDescription = stringResource(R.string.conversation_fork_from_here),
                modifier = Modifier.size(18.dp),
                tint = terminalActionTint,
            )
        }
        IconButton(
            onClick = onShare,
            enabled = actionAvailability.terminalEnabled,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer { alpha = terminalActionsAlpha },
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(R.string.conversation_share),
                modifier = Modifier.size(16.dp),
                tint = terminalActionTint,
            )
        }
        Box {
            IconButton(
                onClick = {
                    showMenu = true
                },
                enabled = actionAvailability.informationEnabled,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { alpha = informationActionsAlpha },
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = enabledActionTint,
                )
            }
            DropdownMenu(
                containerColor = com.newoether.agora.ui.theme.LocalFairyTokens.current.panelOpaque,
                tonalElevation = 0.dp,
                shape = com.newoether.agora.ui.theme.LocalFairyTokens.current.panelShape,
                expanded = showMenu && actionAvailability.informationVisible,
                onDismissRequest = { showMenu = false },
                border = androidx.compose.foundation.BorderStroke(1.dp, com.newoether.agora.ui.theme.LocalFairyTokens.current.hairline),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.info)) },
                    onClick = {
                        showMenu = false
                        onShowInfo()
                    },
                    enabled = actionAvailability.informationEnabled,
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.delete),
                            color = destructiveActionTint,
                        )
                    },
                    onClick = {
                        if (actionAvailability.terminalEnabled) {
                            showMenu = false
                            onShowDelete()
                        }
                    },
                    enabled = actionAvailability.terminalEnabled,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = destructiveActionTint,
                        )
                    },
                )
            }
        }

        if (showBranchSelector && totalBranches > 1) {
            AssistantBranchSelector(
                branchIndex = branchIndex,
                totalBranches = totalBranches,
                terminalActionsAlpha = terminalActionsAlpha,
                terminalEnabled = actionAvailability.terminalEnabled,
                isEditingAllowed = isEditingAllowed,
                onSwitchBranch = onSwitchBranch,
            )
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (answerTailVisible) GenerationActivityDot()
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actionContent,
        )
    }
}
