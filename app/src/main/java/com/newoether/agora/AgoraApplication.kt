package com.newoether.agora

import android.app.Application
import com.newoether.agora.di.AppContainer
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LEGACY_DATABASE_NAME = "agora_db"

/**
 * Application entry point. Owns the process-scoped AppContainer.
 *
 * The remote-only shell keeps no local database. The legacy Room `agora_db`
 * file is deleted once at startup; there is no migration and no recovery UI.
 */
class AgoraApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val containerDeferred = CompletableDeferred<AppContainer>()

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
            withContext(Dispatchers.IO) {
                applicationContext.deleteDatabase(LEGACY_DATABASE_NAME)
            }
            try {
                DeveloperDiagnostics.initialize(noBackupFilesDir, startupScope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.e(
                    "AgoraApplication",
                    "Diagnostic capture initialization failed closed",
                    error,
                )
            }
            val container = withContext(Dispatchers.IO) {
                AppContainer(this@AgoraApplication)
            }
            containerDeferred.complete(container)
        }
    }

    suspend fun awaitContainer(): AppContainer = containerDeferred.await()
}
