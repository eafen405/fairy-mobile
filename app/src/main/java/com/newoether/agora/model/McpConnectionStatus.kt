package com.newoether.agora.model

/**
 * Connection-state enum shared by the remote shell UI surfaces.
 *
 * Extracted from the removed `mcp/` package: the shell reuses it as the
 * connectivity indicator for the remote session (FairyPresence, conversation
 * subtitle, status dots). No MCP transport remains in the app.
 */
enum class McpConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}
