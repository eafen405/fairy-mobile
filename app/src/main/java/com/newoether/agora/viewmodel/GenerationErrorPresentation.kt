package com.newoether.agora.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import com.newoether.agora.R
import com.newoether.agora.model.extractStructuredProviderHttpErrorMessage
import java.util.Locale

private val persistedNetworkErrorRegex =
    Regex("""^Network error \((-?\d+)\):\s*(.+)$""", RegexOption.IGNORE_CASE)
private val persistedNetworkDetailRegex =
    Regex("""^Network error:\s*(.+)$""", RegexOption.IGNORE_CASE)
private val opaqueGenerationIdentifierRegex =
    Regex("^[A-Za-z0-9.-]+(?:_[A-Za-z0-9.-]+)+$")

internal enum class KnownGenerationErrorDetail {
    CONNECTION_CLOSED,
    CONNECTION_REFUSED,
    CONNECTION_RESET,
    UNKNOWN_HOST,
    TLS_FAILURE,
}

internal fun knownGenerationErrorDetail(detail: String): KnownGenerationErrorDetail? {
    val normalized = detail.trim().trimEnd('.').lowercase(Locale.ROOT)
    return when (normalized) {
        "connection closed",
        "closed connection",
        "connection was closed" -> KnownGenerationErrorDetail.CONNECTION_CLOSED
        "connection refused",
        "failed to connect" -> KnownGenerationErrorDetail.CONNECTION_REFUSED
        "connection reset",
        "connection reset by peer" -> KnownGenerationErrorDetail.CONNECTION_RESET
        "unknown host",
        "unable to resolve host" -> KnownGenerationErrorDetail.UNKNOWN_HOST
        "tls failure",
        "tls handshake failed",
        "ssl handshake failed" -> KnownGenerationErrorDetail.TLS_FAILURE
        else -> null
    }
}

@StringRes
internal fun KnownGenerationErrorDetail.stringResourceId(): Int = when (this) {
    KnownGenerationErrorDetail.CONNECTION_CLOSED -> R.string.generation_error_connection_closed
    KnownGenerationErrorDetail.CONNECTION_REFUSED -> R.string.generation_error_connection_refused
    KnownGenerationErrorDetail.CONNECTION_RESET -> R.string.generation_error_connection_reset
    KnownGenerationErrorDetail.UNKNOWN_HOST -> R.string.generation_error_unknown_host
    KnownGenerationErrorDetail.TLS_FAILURE -> R.string.generation_error_tls_failure
}

/**
 * Re-localizes well-known persisted error text before it renders in the terminal
 * bar. Remote errors arrive as server-curated text; anything unrecognized falls
 * through to capitalization-only normalization and the original wording survives.
 */
internal fun normalizePersistedGenerationErrorText(
    context: Context,
    rawText: String,
): String {
    val trimmed = rawText.trim()
    knownGenerationErrorDetail(trimmed)?.let {
        return context.getString(it.stringResourceId())
    }
    persistedNetworkErrorRegex.matchEntire(trimmed)?.let { match ->
        val statusCode = match.groupValues[1].toIntOrNull() ?: 0
        val detail = match.groupValues[2].trim()
        knownGenerationErrorDetail(detail)?.let {
            return context.getString(it.stringResourceId())
        }
        return if (statusCode <= 0) {
            context.getString(
                R.string.generation_error_network,
                normalizeGenerationErrorDetailForDisplay(detail),
            )
        } else {
            context.getString(
                R.string.generation_error_network_http,
                statusCode,
                normalizeGenerationErrorDetailForDisplay(detail),
            )
        }
    }
    persistedNetworkDetailRegex.matchEntire(trimmed)?.let { match ->
        val detail = match.groupValues[1].trim()
        knownGenerationErrorDetail(detail)?.let {
            return context.getString(it.stringResourceId())
        }
        return context.getString(
            R.string.generation_error_network,
            normalizeGenerationErrorDetailForDisplay(detail),
        )
    }
    return when {
        trimmed.equals(
            "Authentication failed. Please check your API key.",
            ignoreCase = true,
        ) -> context.getString(R.string.generation_error_authentication)
        trimmed.equals(
            "Rate limit exceeded. Please wait and try again.",
            ignoreCase = true,
        ) -> context.getString(R.string.generation_error_rate_limit)
        trimmed.equals("Generation cancelled.", ignoreCase = true) ->
            context.getString(R.string.generation_error_cancelled)
        trimmed.equals("Request timed out.", ignoreCase = true) ->
            context.getString(R.string.generation_error_timeout)
        trimmed.equals("An unexpected error occurred.", ignoreCase = true) ->
            context.getString(R.string.generation_error_unexpected)
        trimmed.startsWith("error:", ignoreCase = true) ->
            normalizeGenerationErrorDetailForDisplay(trimmed.substringAfter(':').trim())
        else -> normalizeGenerationErrorDetailForDisplay(trimmed)
    }
}

internal fun extractStructuredGenerationErrorDetail(detail: String): String? =
    extractStructuredProviderHttpErrorMessage(detail)

private fun normalizeGenerationErrorDetailForDisplay(detail: String): String =
    normalizeGenerationErrorDetail(
        extractStructuredGenerationErrorDetail(detail) ?: detail,
    )

internal fun normalizeGenerationErrorDetail(detail: String): String {
    val trimmed = detail.trim()
    if (trimmed.isEmpty() || isOpaqueGenerationErrorDetail(trimmed)) return trimmed
    val firstLetter = trimmed.indexOfFirst(Char::isLetter)
    if (firstLetter < 0 || !trimmed[firstLetter].isLowerCase()) return trimmed
    return buildString(trimmed.length) {
        append(trimmed, 0, firstLetter)
        append(trimmed[firstLetter].titlecase())
        append(trimmed, firstLetter + 1, trimmed.length)
    }
}

private fun isOpaqueGenerationErrorDetail(detail: String): Boolean {
    if (detail.contains(' ')) return false
    return opaqueGenerationIdentifierRegex.matches(detail)
}
