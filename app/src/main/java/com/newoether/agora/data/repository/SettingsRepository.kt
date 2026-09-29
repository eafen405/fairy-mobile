package com.newoether.agora.data.repository

import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository wrapping DataStore-backed SettingsManager for the remote shell.
 *
 * Exposes each live setting as a hot, eagerly-shared [StateFlow] so the UI can
 * `collectAsState` and callers can read `.value` synchronously. The upstream
 * write surface and provider/model/MCP/automation keys were removed with the
 * systems that consumed them.
 */
class SettingsRepository(
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
) {
    /** One latch per eagerly-shared DataStore flow; populated completely during construction. */
    private val initialLoadSignals = mutableListOf<CompletableDeferred<Unit>>()

    private fun <T> hot(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> {
        val loaded = CompletableDeferred<Unit>()
        initialLoadSignals += loaded
        val state = MutableStateFlow(initial)
        flow.publishSetting(scope, loaded) { state.value = it }
        return state.asStateFlow()
    }

    suspend fun awaitInitialLoad() {
        initialLoadSignals.forEach { it.await() }
    }

    val hapticsEnabled: StateFlow<Boolean> = hot(settingsManager.hapticsEnabled, true)
    val parseInlineDollarMath: StateFlow<Boolean> = hot(settingsManager.parseInlineDollarMath, false)
    val toolCallDisplayMode: StateFlow<String> =
        hot(settingsManager.toolCallDisplayMode, ToolCallDisplayModes.DEFAULT)
    val thinkingSegmentDisplayMode: StateFlow<String> = hot(
        settingsManager.thinkingSegmentDisplayMode,
        ThinkingSegmentDisplayModes.DEFAULT,
    )
    val autoExpandActiveGroup: StateFlow<Boolean> =
        hot(settingsManager.autoExpandActiveGroup, true)
    val customProviders: StateFlow<List<CustomProviderConfig>> =
        hot(settingsManager.customProviders, emptyList())
}
