package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.RemoteFile
import com.newoether.agora.ui.chat.FileThumbnail
import com.newoether.agora.ui.common.LocalAgoraHaptics

/** Save handler for a published Remote file; absent outside Remote conversations. */
internal val LocalRemoteFileAction = compositionLocalOf<((RemoteFile) -> Unit)?> { null }

/** fileIds with an in-flight download/export, for the card progress affordance. */
internal val LocalRemoteFileSaving = compositionLocalOf<Set<String>> { emptySet() }

/**
 * Published-file cards: name, size and relay source only. No upload paths,
 * internal refs or tool payloads ever reach this surface. The save action is
 * the authenticated download + SAF export, wired by the Remote conversation.
 */
@Composable
internal fun RemoteFileCardList(files: List<RemoteFile>, modifier: Modifier = Modifier) {
    val save = LocalRemoteFileAction.current
    val saving = LocalRemoteFileSaving.current
    Column(modifier = modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { file ->
            RemoteFileCard(file, saving = file.fileId in saving, onSave = save)
        }
    }
}

@Composable
private fun RemoteFileCard(file: RemoteFile, saving: Boolean, onSave: ((RemoteFile) -> Unit)?) {
    val haptics = LocalAgoraHaptics.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumbnail(
                fileName = file.name, isPdf = file.mime == "application/pdf" ||
                    file.name.endsWith(".pdf", ignoreCase = true),
                modifier = Modifier.size(40.dp),
                fallbackLabel = file.mime?.substringAfter('/')?.uppercase()?.take(4) ?: "FILE",
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            ) {
                Text(
                    text = file.name.ifBlank { stringResource(R.string.remote_file_unnamed) },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(formatRemoteFileSize(file.bytes))
                        file.source?.takeIf { it.isNotBlank() }?.let {
                            append(" · ")
                            append(stringResource(R.string.remote_file_from, it))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (file.fileId.isNotBlank() && onSave != null) {
                if (saving) {
                    MotionAwareCircularProgressIndicator(
                        modifier = Modifier.size(36.dp).padding(8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    IconButton(
                        onClick = {
                            haptics.confirm()
                            onSave(file)
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.remote_file_save),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

internal fun formatRemoteFileSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
