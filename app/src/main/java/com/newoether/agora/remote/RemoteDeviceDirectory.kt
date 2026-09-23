package com.newoether.agora.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Saved clients and their health jobs share the ViewModel lifetime and its sole page state. */
internal class RemoteDeviceDirectory(
    private val connections: RemoteConnectionStore,
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<RemoteState>,
    private val checkSlots: Semaphore,
    private val createClient: (String, String) -> FiloClient,
    private val selectionEpoch: () -> Long,
    private val selectDevice: (String?) -> Unit,
    private val refresh: () -> Unit,
    private val updateDevice: (String, (RemoteDevice) -> RemoteDevice) -> Unit,
    private val report: (String, Exception?) -> RemoteFailure?,
) {
    private val state = mutableState.asStateFlow()
    private val mutableClients = mutableMapOf<String, FiloClient>()
    val clients: Map<String, FiloClient> get() = mutableClients
    private val configurations = mutableMapOf<String, RemoteConnection>()
    private val checks = mutableMapOf<String, Job>()
    private var storing: Job? = null

    fun restoreConnections() {
        if (storing?.isActive == true) return
        report("restore_started", null)
        mutableState.value = state.value.copy(restoring = true, storageError = false)
        selectDevice(null)
        storing = scope.launch {
            try {
                val restored = connections.load().map { connection ->
                    createClient(connection.address, connection.token) to connection
                }
                checks.values.forEach { it.cancel() }
                checks.clear()
                mutableClients.clear()
                configurations.clear()
                restored.forEach { (client, _) -> mutableClients[client.address] = client }
                restored.forEach { (client, connection) -> configurations[client.address] = connection }
                mutableState.value = state.value.copy(viewedTurns = restored.flatMap { (client, connection) ->
                    connection.viewedTurns.map { (session, turn) -> "${client.address}/$session" to turn }
                }.toMap(), devices = restored.map { (client, connection) ->
                    RemoteDevice(client.address, remoteDeviceName(connection.name), client.address)
                })
                report("restore_completed", null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                report("restore_failed", error)
                mutableState.value = state.value.copy(storageError = true)
            }
            finally { mutableState.value = state.value.copy(restoring = false) }
            if (!state.value.storageError) refresh()
        }
    }

    fun editorConnection(): RemoteConnection? = configurations[state.value.editedDeviceId]

    fun login(origin: String, username: String, password: String, invite: String? = null) {
        if (state.value.saving || state.value.restoring) return
        val generation = selectionEpoch()
        mutableState.value = state.value.copy(saving = true, failure = null, storageError = false)
        report("save_started", null)
        storing = scope.launch {
            try {
                val client = createClient(origin, "")
                val name = if (invite == null) client.login(username, password)
                    else client.register(username, password, invite)
                client.connect()
                val id = client.address
                for (stale in configurations.keys) connections.remove(stale)
                val credential = client.sessionCredential ?: throw FiloConfigurationException()
                val connection = RemoteConnection(name, id, credential)
                connections.save(connection)
                checks.values.forEach { it.cancel() }
                checks.clear()
                mutableClients.clear()
                configurations.clear()
                mutableClients[id] = client
                configurations[id] = connection
                mutableState.value = state.value.copy(
                    devices = listOf(RemoteDevice(id, name, id)),
                    drafts = emptyMap(), attempts = emptyMap(),
                    sessionOwners = emptyMap(), sessionStatuses = emptyMap(),
                )
                // Saving an explicitly submitted connection must not undo a later Back/navigation.
                if (generation == selectionEpoch()) selectDevice(id)
                report("saved", null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: RemoteStorageException) {
                report("save_failed", error)
                mutableState.value = state.value.copy(storageError = true)
            }
            catch (error: Exception) {
                val failure = report("save_failed", error)
                mutableState.value = state.value.copy(
                    failure = if (generation == selectionEpoch()) failure else state.value.failure)
            }
            finally { mutableState.value = state.value.copy(saving = false) }
        }
    }

    fun expireConnection(id: String) {
        checks.remove(id)?.cancel()
        mutableClients.remove(id)
        scope.launch { runCatching { connections.remove(id) } }
        mutableState.value = state.value.copy(
            devices = state.value.devices.filterNot { it.id == id },
            drafts = state.value.drafts.filterKeys { !it.startsWith("$id/") },
            attempts = state.value.attempts.filterKeys { !it.startsWith("$id/") },
            sessionOwners = state.value.sessionOwners.filterKeys { !it.startsWith("$id/") },
            sessionStatuses = state.value.sessionStatuses.filterKeys { !it.startsWith("$id/") },
        )
        selectDevice(null)
        // The record stays in `configurations` so the login form can prefill origin/username.
        mutableState.value = state.value.copy(addingDevice = true, editedDeviceId = id)
    }

    fun removeDevice(id: String) {
        if (state.value.saving || state.value.restoring || id !in mutableClients) return
        mutableState.value = state.value.copy(saving = true, storageError = false)
        storing = scope.launch {
            try {
                connections.remove(id)
                checks.remove(id)?.cancel()
                mutableClients.remove(id)
                configurations.remove(id)
                mutableState.value = state.value.copy(
                    devices = state.value.devices.filterNot { it.id == id },
                    drafts = state.value.drafts.filterKeys { !it.startsWith("$id/") },
                    attempts = state.value.attempts.filterKeys { !it.startsWith("$id/") },
                    sessionOwners = state.value.sessionOwners.filterKeys { !it.startsWith("$id/") },
                    sessionStatuses = state.value.sessionStatuses.filterKeys { !it.startsWith("$id/") },
                )
                if (state.value.deviceId == id) selectDevice(null)
                report("device_removed", null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                report("remove_failed", error)
                mutableState.value = state.value.copy(storageError = true)
            }
            finally { mutableState.value = state.value.copy(saving = false) }
        }
    }

    fun checkDevice(id: String) {
        val client = mutableClients[id] ?: return
        if (checks[id]?.isActive == true) return
        updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
        checks[id] = scope.launch {
            try {
                checkSlots.withPermit { client.connect() }
                if (mutableClients[id] !== client) return@launch
                updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                if (state.value.deviceId == id) refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (mutableClients[id] !== client) return@launch
                val failure = report("check_failed", error)
                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = failure) }
                if (state.value.deviceId == id) mutableState.value = state.value.copy(loading = false, failure = failure)
            }
        }
    }
}
