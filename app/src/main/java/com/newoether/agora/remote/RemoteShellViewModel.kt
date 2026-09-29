package com.newoether.agora.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.SnackbarEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Lightweight shell ViewModel for the remote-only client.
 *
 * Replaces the upstream ChatViewModel on the MainActivity/MainNavigation path: the
 * shell consumes only settings and the shared snackbar channel. Conversation/session
 * concerns belong to [RemoteViewModel], which the RemoteOverlay constructs itself.
 */
internal class RemoteShellViewModel(
    val settings: SettingsRepository,
) : ViewModel() {

    // replay=0 so an Activity recreation never re-shows a stale snackbar; the single
    // buffer slot keeps tryEmit-equivalent bursts lossless for slow collectors.
    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snackbarMessage = _snackbarMessage

    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }
}
