package com.newoether.agora.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.McpConnectionStatus
import com.newoether.agora.util.noOpBringIntoView

@Composable
internal fun McpStatusDot(status: McpConnectionStatus) {
    val description = stringResource(
        when (status) {
            McpConnectionStatus.IDLE -> R.string.mcp_status_idle
            McpConnectionStatus.CONNECTING -> R.string.mcp_status_connecting
            McpConnectionStatus.CONNECTED -> R.string.mcp_status_connected
            McpConnectionStatus.ERROR -> R.string.mcp_status_error
        },
    )
    val color = when (status) {
        // Fairy status dots: green online, ice-white connecting, red offline.
        McpConnectionStatus.IDLE -> MaterialTheme.colorScheme.error
        McpConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.primary
        McpConnectionStatus.CONNECTED -> com.newoether.agora.ui.theme.LocalFairyTokens.current.online
        McpConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape)
            .semantics { contentDescription = description },
    )
}

// ── Extracted from the removed MCP settings page ─────────────────────────────
@Composable
internal fun McpLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    placeholder: String? = null,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = isError,
                supportingText = supportingText?.let { text -> { Text(text) } },
                placeholder = placeholder?.let { text -> {
                    Text(text)
                } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = trailingContent,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
