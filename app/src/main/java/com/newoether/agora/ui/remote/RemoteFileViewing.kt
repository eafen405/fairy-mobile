package com.newoether.agora.ui.remote

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.newoether.agora.remote.StagedRemoteFile

/** A viewer gets read access to one verified local file, never the server credentials. */
internal fun remoteFileViewIntent(context: Context, staged: StagedRemoteFile): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged.file, staged.name)
    val declared = staged.mime?.substringBefore(';')?.trim()?.lowercase()
    val mime = declared?.takeUnless { it.isBlank() || it == "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(staged.name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(staged.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
