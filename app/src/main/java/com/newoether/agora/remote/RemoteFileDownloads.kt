package com.newoether.agora.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * User-initiated file save lifecycle: authenticated download into verified
 * private staging, then export to the user-chosen document. Staged bytes are
 * tracked by token and are cleaned up on every failure, cancellation and
 * owner change; success is only reported after the export stream closes.
 */
internal class RemoteFileDownloads(
    private val state: MutableStateFlow<RemoteState>,
    private val fileStore: RemoteFileStore?,
    private val client: (RemoteState) -> FiloClient?,
    private val trace: (String, Exception) -> RemoteFailure?,
) {
    // Staged verified downloads keyed by token; each is private until the user exports it.
    private val pending = mutableMapOf<String, StagedRemoteFile>()

    /** Discards every staged download, e.g. on owner change or ViewModel clear. */
    fun clear() {
        val store = fileStore
        pending.values.forEach { staged -> store?.discard(staged) ?: staged.file.delete() }
        pending.clear()
    }

    /**
     * Downloads [file] into verified private staging. Returns the staged handle
     * for the SAF prompt, or null after the failure has been reported.
     */
    suspend fun prepare(owner: String, file: com.newoether.agora.model.RemoteFile): StagedRemoteFile? {
        val snapshot = state.value
        if (snapshot.owner != owner || file.fileId.isBlank()) return null
        val client = client(snapshot) ?: return null
        val store = fileStore ?: run {
            trace("file_failed", RemoteAttachmentException("File storage is unavailable")); return null
        }
        if (file.fileId in state.value.savingFiles) return null
        state.value = state.value.copy(savingFiles = state.value.savingFiles + file.fileId)
        try {
            var staged: StagedRemoteFile? = null
            client.downloadFile(file.fileId) { input, declared, mime ->
                staged = store.stage(input, file.bytes, declared, file.name, mime ?: file.mime)
            }
            val ready = staged ?: return null
            if (state.value.owner != owner) {
                store.discard(ready)
                return null
            }
            pending[ready.token] = ready
            return ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (state.value.owner == owner) trace("file_failed", error)
            return null
        } finally {
            state.value = state.value.copy(savingFiles = state.value.savingFiles - file.fileId)
        }
    }

    /** Final export to the user-chosen SAF document; honest false on any failure. */
    suspend fun export(owner: String, token: String, target: android.net.Uri): Boolean {
        val staged = pending.remove(token) ?: return false
        val store = fileStore ?: run { staged.file.delete(); return false }
        return try {
            store.export(staged, store.uriSink(target))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (state.value.owner == owner) trace("file_export_failed", error)
            false
        } finally {
            store.discard(staged)
        }
    }

    fun discard(token: String) {
        pending.remove(token)?.let { staged ->
            fileStore?.discard(staged) ?: staged.file.delete()
        }
    }
}
