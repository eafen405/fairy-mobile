package com.newoether.agora

import android.app.Application
import com.newoether.agora.data.local.DatabaseCompatibility
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.di.AppContainer
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application entry point. Owns the process-scoped AppContainer.
 *
 * The startup gate is kept (so the Blocked dialog plumbing in MainActivity still
 * compiles) but no longer inspects or opens Room: the remote-only shell has no
 * database dependency, so the gate always passes straight to Ready. The legacy
 * `agora_db` teardown is S3's one-time cleanup.
 */
class AgoraApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val startupGate = DatabaseStartupGate(
        inspectDatabase = { DatabaseCompatibility.Missing },
        openResource = {
            withContext(Dispatchers.IO) {
                AppContainer(this@AgoraApplication)
            }
        },
        closeResource = { },
        deleteDatabase = {
            withContext(Dispatchers.IO) {
                val databasePath = getDatabasePath(ChatDatabase.DB_NAME)
                !databasePath.exists() ||
                    this@AgoraApplication.deleteDatabase(ChatDatabase.DB_NAME)
            }
        },
        reportFailure = { error ->
            DebugLog.e(
                "AgoraApplication",
                "Database startup gate failed closed",
                error,
            )
        },
    )

    val databaseStartupState: StateFlow<DatabaseStartupState>
        get() = startupGate.state

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
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
            startupGate.initialize()
        }
    }

    suspend fun awaitDatabaseStartup(): DatabaseStartupState =
        startupGate.awaitState()

    suspend fun awaitContainer(): AppContainer? =
        startupGate.awaitReadyResource()

    fun requireContainer(): AppContainer =
        startupGate.requireReadyResource()

    suspend fun clearIncompatibleDatabase(): Boolean =
        startupGate.clearBlockedDatabase()
}
