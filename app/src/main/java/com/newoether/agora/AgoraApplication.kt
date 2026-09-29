package com.newoether.agora

import android.app.Application
import com.newoether.agora.di.AppContainer
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LEGACY_DATABASE_NAME = "agora_db"
private const val LEGACY_WORK_DATABASE_NAME = "androidx.work.workdb"

/**
 * Application entry point. Owns the process-scoped AppContainer.
 *
 * The remote-only shell keeps no local database. Legacy state from the
 * upstream Agora build is deleted once at startup; there is no migration
 * and no recovery UI.
 */
class AgoraApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val containerDeferred = CompletableDeferred<AppContainer>()

    // One-time cleanup for installs upgraded from the upstream Agora lineage:
    // the Room agora_db, the WorkManager store (any queued task/loop/backup
    // work can no longer run without the library), and the on-disk
    // memory/skill stores. Armed automation alarms are not cancelled — they
    // are keyed by per-item data URIs we can no longer enumerate, their
    // receiver is gone (delivery is a no-op), and they do not survive reboot.
    private fun deleteLegacyState() {
        applicationContext.deleteDatabase(LEGACY_DATABASE_NAME)
        applicationContext.deleteDatabase(LEGACY_WORK_DATABASE_NAME)
        listOf("memory_db", "skill_db").forEach { name ->
            File(applicationContext.filesDir, name).deleteRecursively()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
            withContext(Dispatchers.IO) {
                deleteLegacyState()
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
