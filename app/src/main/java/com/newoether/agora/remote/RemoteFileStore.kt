package com.newoether.agora.remote

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal class RemoteFileException(message: String) : IOException(message)

/**
 * A verified download held in app-private staging until the user picks a target.
 * [token] is the only cross-layer handle; [name] is a display/suggested name
 * only — it is never used to build a filesystem path.
 */
internal data class StagedRemoteFile(
    val token: String, val file: File, val name: String, val mime: String?, val bytes: Long,
)

/** The export seam: opening the provider target and abandoning a partial write. */
internal interface RemoteFileSink {
    @Throws(IOException::class)
    fun open(): OutputStream
    fun abandon()
}

/**
 * Verified-download staging under `cacheDir/remote-files`. Staged bytes never
 * survive a store (re)creation: staging is per-operation, and a successful export
 * is the only path that keeps bytes.
 */
internal class RemoteFileStore private constructor(
    private val directory: File, private val resolver: android.content.ContentResolver?,
) {
    constructor(context: Context) : this(
        File(context.cacheDir, "remote-files"), context.applicationContext.contentResolver)

    /** Test seam: staging without a ContentResolver; export still injects a sink. */
    internal constructor(directory: File) : this(directory, null)

    init {
        if (directory.isDirectory) directory.listFiles()?.forEach { it.delete() }
    }

    /**
     * Streams the response body into private staging with a bounded buffer and
     * verifies it: a present Content-Length must equal the card's byte count, the
     * actual byte count must match, and the stream must end at a clean EOF. Any
     * failure deletes the staged file and throws.
     */
    suspend fun stage(
        input: InputStream, expectedBytes: Long, declaredLength: Long,
        name: String, mime: String?,
    ): StagedRemoteFile = withContext(Dispatchers.IO) {
        if (expectedBytes < 0 || expectedBytes > REMOTE_FILE_LIMIT) throw RemoteContentLimitException()
        if (declaredLength >= 0 && declaredLength != expectedBytes) {
            throw RemoteFileException("The file does not match the announced size")
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw RemoteFileException("File staging is unavailable")
        }
        val file = File(directory, UUID.randomUUID().toString())
        try {
            var count = 0L
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > expectedBytes || count > REMOTE_FILE_LIMIT) {
                        throw RemoteFileException("The file is larger than announced")
                    }
                    output.write(buffer, 0, read)
                }
            }
            if (count != expectedBytes) throw RemoteFileException("The file ended before it was complete")
            StagedRemoteFile(
                token = UUID.randomUUID().toString(), file = file,
                name = sanitizeRemoteFileName(name), mime = mime, bytes = count,
            )
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Provider target for a SAF document. Partial writes are abandoned on failure. */
    fun uriSink(target: Uri): RemoteFileSink = object : RemoteFileSink {
        override fun open(): OutputStream = requireNotNull(resolver) { "File export is unavailable" }
            .openOutputStream(target) ?: throw RemoteFileException("Could not open the destination")
        override fun abandon() {
            // Best effort only: some providers keep a partial document, which is an
            // honest failure report, not a promise that SAF writes are atomic.
            runCatching { resolver?.delete(target, null, null) }
        }
    }

    /**
     * Copies a verified staged file to the caller-opened sink. The copy must fully
     * write and close; any failure abandons the target and throws — success is
     * only reported after this returns.
     */
    suspend fun export(staged: StagedRemoteFile, sink: RemoteFileSink): Unit = withContext(Dispatchers.IO) {
        try {
            sink.open().use { output ->
                staged.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { sink.abandon() }
            throw error
        }
    }

    fun discard(staged: StagedRemoteFile) {
        staged.file.delete()
    }
}

private const val REMOTE_FILE_NAME_LIMIT = 128

/**
 * A wire-provided name is display text only: strip control characters and path
 * separators, bound the length, and fall back to a neutral name. Unicode and
 * extensionless names stay intact; duplicate names are the provider's problem.
 */
internal fun sanitizeRemoteFileName(name: String): String =
    name.replace(Regex("[\\u0000-\\u001F\\u007F/\\\\]"), "")
        .trim().trimEnd('.')
        .take(REMOTE_FILE_NAME_LIMIT)
        .takeIf { it.isNotBlank() } ?: "file"
