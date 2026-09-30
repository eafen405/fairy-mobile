package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.FileThumbnail
import com.newoether.agora.ui.chat.MEDIA_LOADING_INDICATOR_STROKE_WIDTH
import com.newoether.agora.ui.chat.MediaLoadPresentation
import com.newoether.agora.ui.chat.rememberMediaLoadingVisible
import com.newoether.agora.ui.chat.toMediaLoadPresentation
import com.newoether.agora.ui.common.LocalAgoraHaptics

private const val ATTACHMENT_STATUS_CROSSFADE_MS = 200

internal enum class AttachmentPreviewPresentation {
    INITIAL,
    IMPORT_LOADING,
    IMPORT_FAILED,
    READY_FILE,
    MEDIA_LOADING,
    MEDIA_SUCCESS,
    MEDIA_ERROR,
}

internal fun attachmentPreviewPresentation(
    importState: AttachmentImportState,
    type: String,
    mediaLoadState: MediaLoadPresentation,
): AttachmentPreviewPresentation = when {
    importState == AttachmentImportState.PROCESSING ->
        AttachmentPreviewPresentation.IMPORT_LOADING
    importState == AttachmentImportState.FAILED ->
        AttachmentPreviewPresentation.IMPORT_FAILED
    type != "image" -> AttachmentPreviewPresentation.READY_FILE
    mediaLoadState == MediaLoadPresentation.LOADED ->
        AttachmentPreviewPresentation.MEDIA_SUCCESS
    mediaLoadState == MediaLoadPresentation.FAILED ->
        AttachmentPreviewPresentation.MEDIA_ERROR
    else -> AttachmentPreviewPresentation.MEDIA_LOADING
}

/** Projects the exact conversation-owned attachment snapshot and emits identity commands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentPreviewRow(
    attachments: List<SelectedAttachment>,
    editable: Boolean,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)?,
) {
    val haptics = LocalAgoraHaptics.current
    val mediaAttachments = remember(attachments) {
        attachments.filter {
            it.importState == AttachmentImportState.READY && it.type == "image"
        }
    }
    val allMediaUrls = remember(mediaAttachments) {
        mediaAttachments.map { it.localPath ?: it.uri }
    }
    val mediaIndexById = remember(mediaAttachments) {
        mediaAttachments.mapIndexed { index, attachment -> attachment.localId to index }.toMap()
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = SelectedAttachment::localId) { attachment ->
            val uriString = attachment.uri
            val isFile = attachment.type != "image"
            val isReady = attachment.importState == AttachmentImportState.READY
            val mediaIndex = mediaIndexById[attachment.localId]
            val mediaModel = when {
                !isReady || isFile -> null
                else -> attachment.localPath ?: uriString
            }
            val mediaPainter = rememberAsyncImagePainter(model = mediaModel)
            val mediaLoadState = mediaPainter.state.toMediaLoadPresentation()
            val targetPresentation = attachmentPreviewPresentation(
                importState = attachment.importState,
                type = attachment.type,
                mediaLoadState = mediaLoadState,
            )
            val isLoading = targetPresentation == AttachmentPreviewPresentation.IMPORT_LOADING ||
                targetPresentation == AttachmentPreviewPresentation.MEDIA_LOADING
            val delayedLoadingVisible = rememberMediaLoadingVisible(
                loadingKey = attachment.localId,
                isLoading = isLoading && attachment.type == "image",
            )
            val loadingVisible = isLoading && (attachment.type != "image" || delayedLoadingVisible)
            var presentedState by remember(attachment.localId) {
                mutableStateOf(AttachmentPreviewPresentation.INITIAL to false)
            }
            LaunchedEffect(attachment.localId, targetPresentation, loadingVisible) {
                presentedState = targetPresentation to loadingVisible
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(64.dp)
                    .padding(top = 5.dp),
            ) {
                Box {
                    val clickableModifier = when {
                        !isReady || isFile -> Modifier
                        mediaIndex != null -> Modifier.combinedClickable(
                            onClick = {
                                onAllMediaClick?.invoke(allMediaUrls, mediaIndex)
                            },
                            onLongClick = { haptics.longPress() },
                            hapticFeedbackEnabled = false,
                        )
                        else -> Modifier
                    }
                    Crossfade(
                        targetState = presentedState,
                        animationSpec = tween(ATTACHMENT_STATUS_CROSSFADE_MS),
                        label = "attachmentPresentation",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(clickableModifier),
                    ) { (presentation, showLoading) ->
                        AttachmentPresentationContent(
                            presentation = presentation,
                            showLoading = showLoading,
                            attachment = attachment,
                            mediaPainter = mediaPainter,
                            editable = editable,
                            onRetry = {
                                haptics.selection()
                                onRetry(attachment.localId)
                            },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 5.dp, y = (-5).dp)
                            .size(18.dp)
                            .background(Color.Black.copy(alpha = 0.8f), CircleShape)
                            .clip(CircleShape)
                            .clickable(enabled = editable) {
                                haptics.selection()
                                onRemove(attachment.localId)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.remove),
                            tint = Color.White,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                Crossfade(
                    targetState = presentedState.first,
                    animationSpec = tween(ATTACHMENT_STATUS_CROSSFADE_MS),
                    label = "attachmentCaption",
                ) { presentation ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isFile) {
                            attachment.fileName?.let { fileName ->
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPresentationContent(
    presentation: AttachmentPreviewPresentation,
    showLoading: Boolean,
    attachment: SelectedAttachment,
    mediaPainter: AsyncImagePainter,
    editable: Boolean,
    onRetry: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (presentation) {
            AttachmentPreviewPresentation.INITIAL -> Unit
            AttachmentPreviewPresentation.READY_FILE -> FileThumbnail(
                fileName = attachment.fileName ?: attachment.uri,
                isPdf = attachment.type == "pdf",
                modifier = Modifier.fillMaxSize(),
                fallbackLabel = attachment.type.uppercase().take(4).ifEmpty { "FILE" },
            )
            AttachmentPreviewPresentation.IMPORT_LOADING -> {
                FileThumbnail(
                    fileName = attachment.fileName ?: attachment.uri,
                    isPdf = attachment.type == "pdf",
                    modifier = Modifier.fillMaxSize(),
                    fallbackLabel = attachment.type.uppercase().take(4).ifEmpty { "FILE" },
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color.Black.copy(alpha = 0.4f)),
                )
                if (showLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            AttachmentPreviewPresentation.IMPORT_FAILED,
            AttachmentPreviewPresentation.MEDIA_ERROR -> {
                FileThumbnail(
                    fileName = attachment.fileName ?: attachment.uri,
                    isPdf = attachment.type == "pdf",
                    modifier = Modifier.fillMaxSize(),
                    fallbackLabel = attachment.type.uppercase().take(4).ifEmpty { "FILE" },
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable(enabled = editable, onClick = onRetry),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (attachment.type == "image") Icons.Default.BrokenImage
                        else Icons.Default.ErrorOutline,
                        contentDescription = stringResource(R.string.retry),
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            AttachmentPreviewPresentation.MEDIA_LOADING -> {
                // AsyncImagePainter must stay attached to an Image while its request is running.
                // Rendering only the spinner can leave size-sensitive requests permanently Loading.
                Image(
                    painter = mediaPainter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (showLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            AttachmentPreviewPresentation.MEDIA_SUCCESS -> {
                Image(
                    painter = mediaPainter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}
