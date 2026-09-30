package com.newoether.agora.ui.chat.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Shape
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.chat.FileThumbnail
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.LocalFairyTokens

/**
 * The right-aligned user message bubble: attachment metadata cards, the message
 * text, and the long-press copy / select-text / info menu. Remote conversations
 * are read-only — there are no edit, delete, or branch actions here.
 * Extracted from [MessageItem]; the parent owns the info/select-text sheets,
 * triggered here via [onShowInfo] / [onSelectText].
 */
internal fun userBubbleSizeAnimationEnabled(
    sizeAnimationReady: Boolean,
    allowSpatialTransitions: Boolean,
): Boolean = sizeAnimationReady && allowSpatialTransitions

@Composable
internal fun UserMessageBubble(
    message: ChatMessage,
    shape: Shape,
    backgroundColor: Color,
    textColor: Color,
    sizeAnimationReady: Boolean,
    showActions: Boolean,
    actionCopyText: String?,
    onSelectText: () -> Unit,
    onShowInfo: () -> Unit,
    searchHighlight: SearchHighlightSpec?,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    var showMenu by remember { mutableStateOf(false) }

    val fairyTokens = LocalFairyTokens.current
    Column(
        horizontalAlignment = Alignment.End,
        // Glass bubble: at most 80 % of the available width, 8 dp in from the
        // list padding on the end side.
        modifier = Modifier.fillMaxWidth(0.8f).padding(end = 8.dp).then(
            if (userBubbleSizeAnimationEnabled(sizeAnimationReady, allowSpatialTransitions)) {
                Modifier.animateContentSize(animationSpec = tween(durationMillis = 500))
            } else {
                Modifier
            },
        ),
    ) {
        Box {
            Surface(
            shape = shape,
            color = backgroundColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, fairyTokens.userBubbleBorder),
            modifier = Modifier
                .clip(shape)
                .combinedClickable(
                    enabled = showActions,
                    hapticFeedbackEnabled = false,
                    onClick = {},
                    onLongClick = {
                        haptics.longPress()
                        showMenu = true
                    },
                )
        ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).noOpBringIntoView(),
                    horizontalAlignment = Alignment.Start
                ) {
                    val metaItems = remember(message.attachmentMeta) {
                        message.attachmentMeta?.items.orEmpty()
                    }
                    if (metaItems.isNotEmpty()) {
                        // Original bytes load through the authenticated attachment reader.
                        LazyRow(
                            modifier = Modifier.padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(metaItems) { item ->
                                RemoteAttachmentContent(item)
                            }
                        }
                    }
                    if (message.remoteFiles.isNotEmpty()) {
                        RemoteFileCardList(
                            files = message.remoteFiles,
                            modifier = Modifier.padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                        )
                    }
                    if (message.text.isNotEmpty()) {
                        SearchHighlightedPlainText(
                            text = message.text,
                            style = ChatType.userBody,
                            color = textColor,
                            spec = searchHighlight,
                        )
                    }
                }
        }

            DropdownMenu(
                containerColor = fairyTokens.panelOpaque,
                tonalElevation = 0.dp,
                shape = fairyTokens.panelShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, fairyTokens.hairline),
                expanded = showMenu && showActions,
                onDismissRequest = { showMenu = false },
            ) {
                if (!actionCopyText.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(actionCopyText))
                            haptics.confirm()
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    )
                }
                if (message.text.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.select_text)) },
                        onClick = {
                            showMenu = false
                            onSelectText()
                        },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.info)) },
                    onClick = {
                        showMenu = false
                        onShowInfo()
                    },
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                )
            }
        }
    }
}
