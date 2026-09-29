package com.newoether.agora.di

import android.content.Context
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.RemoteShellViewModel

/**
 * Centralized dependency container (manual DI) for the remote-only shell.
 *
 * The upstream BYOK graph — Room, local inference, providers, MCP, automation,
 * sandbox, backup/import-export, task execution — is no longer constructed here.
 * The shell needs only settings; RemoteViewModel self-constructs inside the
 * RemoteOverlay.
 */
class AppContainer(
    private val appContext: Context,
) {
    /** App-lifetime scope backing the shared settings StateFlows. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                com.newoether.agora.util.DebugLog.e("AppContainer", "Uncaught in appScope", e)
            }
    )

    // ── Data Layer ────────────────────────────────────────────

    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsManager, appScope)
    }

    // ── Shell ViewModel ───────────────────────────────────────

    internal fun remoteShellViewModel(): RemoteShellViewModel =
        RemoteShellViewModel(settingsRepository)
}
