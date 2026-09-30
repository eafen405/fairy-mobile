package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.RemoteAttachmentRef
import com.newoether.agora.remote.StagedRemoteFile
import com.newoether.agora.ui.chat.FileThumbnail
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import kotlinx.coroutines.launch

internal class RemoteAttachmentActions(
    val owner: String,
    val load: suspend (RemoteAttachmentRef) -> StagedRemoteFile?,
    val open: (StagedRemoteFile) -> Unit,
    val image: (StagedRemoteFile) -> Unit,
)
internal val LocalRemoteAttachmentActions = compositionLocalOf<RemoteAttachmentActions?> { null }

/** Incoming attachment cards load original bytes through the authenticated client. */
@Composable
internal fun RemoteAttachmentContent(item: AttachmentItem) {
    val actions = LocalRemoteAttachmentActions.current
    val latestActions by rememberUpdatedState(actions)
    val ref = item.remote
    val isImage = item.type == "image" || item.mimeType?.startsWith("image/") == true
    var staged by remember(actions?.owner, ref) { mutableStateOf<StagedRemoteFile?>(null) }
    var loading by remember(actions?.owner, ref) { mutableStateOf(false) }
    var failed by remember(actions?.owner, ref) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(): StagedRemoteFile? {
        if (ref == null || latestActions == null) return null
        loading = true
        return try {
            latestActions?.load?.invoke(ref).also { staged = it; failed = it == null }
        } finally { loading = false }
    }
    LaunchedEffect(actions?.owner, ref, actions != null) {
        if (isImage && ref != null && actions != null) load()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(if (isImage) 160.dp else 88.dp)
            .clickable(enabled = ref != null && actions != null && !loading) {
                scope.launch {
                    val ready = staged?.takeIf { it.file.isFile } ?: load()
                    if (ready != null) {
                        if (isImage) latestActions?.image?.invoke(ready)
                        else latestActions?.open?.invoke(ready)
                    }
                }
            },
    ) {
        Box(Modifier.size(if (isImage) 160.dp else 64.dp), contentAlignment = Alignment.Center) {
            val ready = staged
            if (isImage && ready != null) {
                AsyncImage(model = ready.file, contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            } else {
                FileThumbnail(fileName = item.fileName.orEmpty(), isPdf = item.mimeType == "application/pdf",
                    modifier = Modifier.size(64.dp), fallbackLabel = item.type.uppercase().take(4))
            }
            if (loading) MotionAwareCircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Text(text = if (failed) stringResource(R.string.retry) else item.fileName.orEmpty(),
            style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
