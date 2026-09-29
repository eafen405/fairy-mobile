package com.newoether.agora.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest

/** Process-wide producer facade for the credential-sanitized persistent diagnostic capture. */
object DeveloperDiagnostics {
    private val buffer = DiagnosticEventBuffer()

    val snapshots: StateFlow<DiagnosticSnapshot> = buffer.snapshots
    val isCaptureActive: Boolean get() = buffer.isCaptureActive

    suspend fun initialize(
        noBackupFilesDirectory: File,
        scope: CoroutineScope,
    ) {
        buffer.initialize(
            store = DiagnosticCaptureStore(File(noBackupFilesDirectory, CAPTURE_DIRECTORY)),
            scope = scope,
        )
    }

    suspend fun startCapture(): DiagnosticSession? = buffer.start().session

    suspend fun pauseCapture() = buffer.pause()

    suspend fun clear() = buffer.clear()

    suspend fun disableAndClear() = buffer.disableAndClear()

    suspend fun flush(): DiagnosticSnapshot = buffer.flush()

    fun newRequestContext(
        requestId: String,
        conversationId: String,
        runId: String?,
        pass: Int?,
        provider: String,
        model: String,
        requestKind: String,
    ): DiagnosticRequestContext? {
        if (!buffer.isCaptureActive) return null
        return DiagnosticRequestContext(
            requestId = DiagnosticRedactor.safeIdentifier(requestId).take(MAX_IDENTIFIER_LENGTH),
            conversationIdHash = hashConversationId(conversationId),
            runId = runId?.let(DiagnosticRedactor::safeIdentifier)?.take(MAX_IDENTIFIER_LENGTH),
            pass = pass,
            provider = DiagnosticRedactor.safeIdentifier(provider).take(MAX_IDENTIFIER_LENGTH),
            model = DiagnosticRedactor.safeIdentifier(model).take(MAX_IDENTIFIER_LENGTH),
            requestKind = DiagnosticRedactor.safeIdentifier(requestKind).take(MAX_IDENTIFIER_LENGTH),
        )
    }

    fun recordHttpStage(
        context: DiagnosticRequestContext?,
        stage: String,
        elapsedMillis: Long,
        detail: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.HttpStage(
            stage = DiagnosticRedactor.safeIdentifier(stage).take(MAX_STAGE_LENGTH),
            elapsedMillis = elapsedMillis.coerceAtLeast(0L),
            attributes = safeHttpAttributes(detail),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordHttpRequest(
        context: DiagnosticRequestContext?,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.HttpRequest(
            method = DiagnosticRedactor.safeIdentifier(method).take(16),
            url = DiagnosticRedactor.captureUrl(url),
            headers = DiagnosticRedactor.captureHeaders(headers),
            body = DiagnosticRedactor.captureJson(
                body,
                DiagnosticRedactor.credentialValues(headers),
            ),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordHttpResponseBody(
        context: DiagnosticRequestContext?,
        code: Int,
        body: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.HttpResponseBody(
            code = code,
            body = DiagnosticRedactor.captureJson(body),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordWireLine(
        context: DiagnosticRequestContext?,
        lineNumber: Long,
        line: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.WireLine(
            lineNumber = lineNumber,
            line = DiagnosticRedactor.captureWireLine(line),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    /** Unknown transport detail keys are discarded before a diagnostic command is queued. */
    internal fun safeHttpAttributes(detail: String): Map<String, String> {
        if (detail.isBlank()) return emptyMap()
        return DETAIL_PAIR.findAll(detail)
            .mapNotNull { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                if (key !in SAFE_HTTP_ATTRIBUTE_KEYS) {
                    null
                } else {
                    key to DiagnosticRedactor.safeIdentifier(value).take(MAX_ATTRIBUTE_LENGTH)
                }
            }
            .toMap()
    }

    private val SAFE_HTTP_ATTRIBUTE_KEYS = setOf(
        "acceptedDelayMs",
        "addresses",
        "bodyBytes",
        "bytes",
        "chars",
        "code",
        "messages",
        "protocol",
        "proxy",
        "tools",
        "version",
    )
    private val DETAIL_PAIR = Regex("""([A-Za-z][A-Za-z0-9_]*)=([^\s]+)""")

    private fun hashConversationId(conversationId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray(Charsets.UTF_8))
            .take(HASH_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    private const val CAPTURE_DIRECTORY = "diagnostic-capture"
    private const val MAX_IDENTIFIER_LENGTH = 160
    private const val MAX_STAGE_LENGTH = 80
    private const val MAX_ATTRIBUTE_LENGTH = 80
    private const val HASH_BYTES = 12
}
