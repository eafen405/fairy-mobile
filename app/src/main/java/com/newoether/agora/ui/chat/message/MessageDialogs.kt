package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only message metadata (timestamp + model name) shown from the overflow menu. */
@Composable
internal fun MessageInfoDialog(
    message: ChatMessage,
    onDismiss: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val dateString = sdf.format(Date(message.timestamp))
    val modelDisplay = message.modelName ?: stringResource(R.string.unknown)

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_info), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.time_with_label, dateString), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                if (message.participant == Participant.MODEL) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.model_with_label, modelDisplay), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) }
        }
    )
}
