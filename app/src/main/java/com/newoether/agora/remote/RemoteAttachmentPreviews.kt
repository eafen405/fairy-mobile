package com.newoether.agora.remote

import com.newoether.agora.model.RemoteAttachmentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Verified, private preview files shared by visible attachment rows. */
internal class RemoteAttachmentPreviews(
    private val state: StateFlow<RemoteState>,
    private val fileStore: RemoteFileStore?,
    private val client: (RemoteState) -> FiloClient?,
    private val scope: CoroutineScope,
    private val trace: (String, Exception) -> RemoteFailure?,
) {
    private data class Key(val owner: String, val ref: RemoteAttachmentRef)
    private val lock = Any()
    private val slots = Semaphore(2)
    private val cached = LinkedHashMap<Key, StagedRemoteFile>(16, 0.75f, true)
    private val active = mutableMapOf<Key, Deferred<StagedRemoteFile?>>()
    private var weight = 0L
    private var generation = 0L

    fun clear() = synchronized(lock) {
        generation++
        active.values.forEach { it.cancel() }
        active.clear()
        cached.values.forEach { discard(it) }
        cached.clear()
        weight = 0
    }

    suspend fun prepare(owner: String, ref: RemoteAttachmentRef): StagedRemoteFile? {
        val snapshot = state.value
        if (snapshot.owner != owner || ref.messageId.isBlank() ||
            ref.index !in 0 until REMOTE_ATTACHMENT_COUNT) return null
        if (ref.bytes < 0 || ref.bytes > PREVIEW_BYTES) {
            trace("attachment_preview_failed", RemoteContentLimitException())
            return null
        }
        val store = fileStore ?: run {
            trace("attachment_preview_failed", RemoteAttachmentException("File storage is unavailable"))
            return null
        }
        val remote = client(snapshot) ?: return null
        val sessionId = snapshot.session?.id ?: return null
        val key = Key(owner, ref)
        val task = synchronized(lock) {
            cached[key]?.let { if (it.file.isFile) return it else {
                cached.remove(key)
                weight -= it.bytes
            } }
            active[key] ?: run {
                val expected = generation
                scope.async(start = CoroutineStart.LAZY) {
                    var staged: StagedRemoteFile? = null
                    try {
                        slots.withPermit {
                            remote.downloadAttachment(sessionId, ref.messageId, ref.index) { input, length, mime ->
                                staged = store.stage(input, ref.bytes, length, ref.name, mime ?: ref.mime)
                            }
                        }
                        val ready = staged ?: return@async null
                        synchronized(lock) {
                            if (generation != expected || state.value.owner != owner) {
                                discard(ready)
                                return@synchronized null
                            }
                            cached[key] = ready
                            weight += ready.bytes
                            while (cached.size > PREVIEW_ENTRIES || weight > PREVIEW_BYTES) {
                                val oldest = cached.entries.iterator().next()
                                cached.remove(oldest.key)
                                weight -= oldest.value.bytes
                                discard(oldest.value)
                            }
                            ready
                        }
                    } catch (cancelled: CancellationException) {
                        staged?.let(::discard)
                        throw cancelled
                    } catch (error: Exception) {
                        staged?.let(::discard)
                        if (generation == expected && state.value.owner == owner) {
                            trace("attachment_preview_failed", error)
                        }
                        null
                    } finally {
                        synchronized(lock) { if (generation == expected) active.remove(key) }
                    }
                }.also { active[key] = it; it.start() }
            }
        }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            null
        }
    }

    private fun discard(staged: StagedRemoteFile) {
        fileStore?.discard(staged) ?: staged.file.delete()
    }
}

private const val PREVIEW_ENTRIES = 16
private const val PREVIEW_BYTES = 64L * 1024 * 1024
