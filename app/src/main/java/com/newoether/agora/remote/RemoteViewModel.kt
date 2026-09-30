package com.newoether.agora.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** Remote owns saved connections and presentation; native Codex owns durable execution. */
internal class RemoteViewModel(
    connections: RemoteConnectionStore,
    private val projectionDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val attachmentStore: RemoteAttachmentStore? = null,
    private val fileStore: RemoteFileStore? = null,
    createClient: (String, String) -> FiloClient = { address, token -> FiloClient(address, token) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteState())
    private val attachmentDrafts = RemoteAttachmentDrafts(attachmentStore, viewModelScope, mutableState) { owner, error ->
        if (state.value.owner == owner) trace("attachment_failed", error)
    }
    fun addAttachments(owner: String, uris: List<android.net.Uri>) = attachmentDrafts.add(owner, uris)
    fun removeAttachment(owner: String, id: String) = attachmentDrafts.remove(owner, id)
    fun retryAttachment(owner: String, id: String) = attachmentDrafts.retry(owner, id)
    val state = mutableState.asStateFlow()
    private val noticeChannel = Channel<RemoteNotice>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()
    private val hydration = RemoteMessageHydration(state, { owner, cursor ->
        val snapshot = state.value
        if (snapshot.owner != owner) throw CancellationException()
        val client = clients[snapshot.deviceId] ?: throw CancellationException()
        client.conversation(snapshot.session!!.id, cursor)
    }, { trace("payload_failed", it) }, projectionDispatcher = projectionDispatcher)
    private val historyMutation = kotlinx.coroutines.sync.Mutex()
    fun cachedMessage(owner: String, id: String) = state.value.messageGroups.firstOrNull { it.stub.id == id }
        ?.let { hydration.cachedMessage(owner, it) }
    fun observeMessage(owner: String, id: String) = hydration.observeMessage(owner, id)
    suspend fun searchMessages(owner: String, ids: List<String>) = try {
        hydration.loadMessages(owner, ids)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { trace("payload_failed", error); emptyList() }
    private val scrollRequests = ScrollRequestCoordinator()
    val animatedScrollRequest = scrollRequests.request
    fun completeAnimatedScroll(id: Long) = scrollRequests.complete(id)
    /** Trigger for a proactive-turn anchor: the UI supplies the target and the threshold check. */
    fun requestAnchor(messageId: String) {
        scrollRequests.requestAnchor(state.value.owner, messageId)
    }
    private val checkSlots = Semaphore(2)
    private var epoch = 0L
    private var selectionEpoch = 0L
    private var visible = false
    private var polling: Job? = null
    private var paging: Job? = null
    private val createdAt = System.nanoTime()
    private val diagnosticContext = DiagnosticRequestContext(
        requestId = UUID.randomUUID().toString(), provider = "Fairy", model = "Fairy", requestKind = "remote",
    )

    private val deviceDirectory = RemoteDeviceDirectory(
        connections = connections,
        scope = viewModelScope,
        mutableState = mutableState,
        checkSlots = checkSlots,
        createClient = createClient,
        selectionEpoch = { selectionEpoch },
        selectDevice = ::selectDevice,
        refresh = ::refresh,
        updateDevice = ::updateDevice,
        report = { stage, error -> trace(stage, error) },
    )
    private val clients get() = deviceDirectory.clients
    fun speechClient(owner: String): FiloClient? =
        state.value.takeIf { it.owner == owner }?.deviceId?.let { clients[it] }
    private val sendController = RemoteSendController(
        state = mutableState,
        scope = viewModelScope,
        attachmentStore = attachmentStore,
        scrollRequests = scrollRequests,
        client = { snapshot -> clients[snapshot.deviceId] },
        epoch = { selectionEpoch },
        sendBlocked = { !visible || state.value.controlling || state.value.isStopping },
        stale = { selected, client -> selected != selectionEpoch || clients[state.value.deviceId] !== client },
        trace = { stage, error -> trace(stage, error) },
    )
    private val fileDownloads = RemoteFileDownloads(
        state = mutableState,
        fileStore = fileStore,
        client = { snapshot -> clients[snapshot.deviceId] },
        trace = { stage, error -> trace(stage, error) },
    )
    private val attachmentPreviews = RemoteAttachmentPreviews(
        state, fileStore, { snapshot -> clients[snapshot.deviceId] }, viewModelScope,
        { stage, error -> trace(stage, error) },
    )

    init { trace("owner_created"); restoreConnections() }

    private fun trace(stage: String, error: Exception? = null, notify: Boolean = true): RemoteFailure? {
        val failure = error?.let(::classifyRemoteFailure)
        if (failure == RemoteFailure.AUTHENTICATION) expireAuthentication()
        if (failure != null && notify) noticeChannel.trySend(RemoteNotice(stage, failure, selectionEpoch, remoteErrorDetail(error), remoteErrorCode(error)))
        val suffix = if (failure == null) "" else ".${failure.name}.${error.javaClass.simpleName}"
        // Preserve the existing privacy wrapper and diagnostic logging preferences.
        if (failure != null) runCatching {
            com.newoether.agora.util.DebugLog.w("AgoraRemote", "remote.$stage$suffix" +
                (if (error is FiloHttpException) " code=${error.status}" else "") +
                " cause=${error.cause?.javaClass?.simpleName.orEmpty()}")
        }
        DeveloperDiagnostics.recordHttpStage(diagnosticContext, "remote.$stage$suffix",
            (System.nanoTime() - createdAt) / 1_000_000,
            "addresses=${state.value.devices.size}" + if (error is FiloHttpException) " code=${error.status}" else "")
        return failure
    }

    fun isNoticeCurrent(notice: RemoteNotice): Boolean = notice.selection == selectionEpoch
    fun retryNotice(notice: RemoteNotice) {
        if (!isNoticeCurrent(notice)) return
        when (notice.stage) {
            "restore_failed" -> restoreConnections()
            "page_failed" -> loadMore()
            "payload_failed" -> mutableState.value = state.value.copy(hydrationRevision = state.value.hydrationRevision + 1)
            "check_failed", "read_failed" -> refresh()
        }
    }

    override fun onCleared() {
        trace("owner_cleared")
        noticeChannel.close()
        clearPendingFileExports()
        super.onCleared()
    }

    private fun clearPendingFileExports() {
        fileDownloads.clear()
        attachmentPreviews.clear()
    }

    fun restoreConnections() = deviceDirectory.restoreConnections()

    fun editorConnection(): RemoteConnection? = deviceDirectory.editorConnection()

    fun login(origin: String, username: String, password: String, invite: String? = null) =
        deviceDirectory.login(origin, username, password, invite)

    fun logout() {
        val id = state.value.deviceId ?: return
        val client = clients[id]
        viewModelScope.launch {
            if (client != null) runCatching { client.logout() }
            deviceDirectory.expireConnection(id)
        }
    }

    private fun expireAuthentication() {
        if (state.value.addingDevice || state.value.restoring) return
        val id = state.value.deviceId ?: clients.keys.singleOrNull() ?: return
        deviceDirectory.expireConnection(id)
    }

    private fun checkDevice(id: String) = deviceDirectory.checkDevice(id)

    private fun updateDevice(id: String, update: (RemoteDevice) -> RemoteDevice) {
        mutableState.value = state.value.copy(devices = state.value.devices.map { if (it.id == id) update(it) else it })
    }

    private fun beginSelection() {
        scrollRequests.clear()
        selectionEpoch++
        clearPendingFileExports()
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null,
            savingFiles = emptySet())
        invalidateReads()
    }

    fun selectDevice(id: String?) {
        if (id != null && id !in clients) return
        beginSelection()
        mutableState.value = state.value.copy(deviceId = id, addingDevice = false, editedDeviceId = null,
            sessions = emptyList(), sessionCursor = null,
            session = null, nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, failure = null,
            runtime = null, composerFocusOwner = null)
        refresh()
    }

    fun selectSession(session: RemoteSession?) {
        beginSelection()
        mutableState.value = state.value.copy(session = session, nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, failure = null, runtime = null, composerFocusOwner = null)
        refresh()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        trace(if (value) "visible" else "hidden")
        if (value) refresh() else invalidateReads()
    }

    private fun invalidateReads() {
        hydration.resetStreaming()
        epoch++
        polling?.cancel()
        paging?.cancel()
        // Suspend control readiness, not the last native presentation. Reconnecting is not completion.
        mutableState.value = state.value.copy(loading = false, loadingMore = false, runtime = null, hydrationEnabled = false)
    }

    fun refresh() {
        if (!visible || state.value.restoring || state.value.addingDevice) return
        invalidateReads()
        val id = state.value.deviceId
        if (id == null) {
            clients.keys.forEach(::checkDevice)
            return
        }
        val client = clients[id] ?: return
        if (state.value.devices.firstOrNull { it.id == id }?.status != RemoteDeviceStatus.CONNECTED) {
            mutableState.value = state.value.copy(loading = true, failure = null)
            checkDevice(id)
            return
        }
        val generation = epoch
        val session = state.value.session
        polling = viewModelScope.launch {
            mutableState.value = state.value.copy(loading = true)
            var consecutiveFailures = 0
            var notifiedFailure: RemoteFailure? = null
            do {
                if (state.value.devices.firstOrNull { it.id == id }?.status == RemoteDeviceStatus.ERROR) {
                    updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
                }
                try {
                    if (session == null) {
                        val page = client.sessions()
                        if (generation != epoch) return@launch
                        mutableState.value = state.value.copy(
                            sessions = page.sessions, sessionCursor = page.nextCursor,
                            loading = false, failure = null,
                        )
                    } else {
                        client.events(session.id).collect { page ->
                            if (generation == epoch) {
                                applyPage(client, session.id, generation, page)
                                consecutiveFailures = 0
                                notifiedFailure = null
                            }
                        }
                    }
                    updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (generation == epoch) {
                        val readFailure = requireNotNull(trace("read_failed", error, notify = false))
                        mutableState.value = state.value.copy(loading = false, runtime = null,
                            stoppingOwner = null, stoppingTurnId = null)
                        // A failed native read is not proof that the device is offline.
                        var reachable = false
                        if (session != null && readFailure in setOf(RemoteFailure.NETWORK, RemoteFailure.SERVICE)) {
                            try {
                                // Read the same original owner when subscription fails; never admit another host.
                                applyPage(client, session.id, generation, client.conversation(session.id), liveControl = false)
                                if (generation != epoch) return@launch
                                reachable = true
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (snapshotError: Exception) {
                                if (generation != epoch) return@launch
                                trace("read_snapshot_failed", snapshotError, notify = false)
                            }
                        }
                        val failure = if (readFailure == RemoteFailure.NETWORK && !reachable) {
                            updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
                            try {
                                checkSlots.withPermit { client.connect() }
                                if (generation != epoch) return@launch
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                                reachable = true
                                RemoteFailure.SERVICE
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (healthError: Exception) {
                                if (generation != epoch) return@launch
                                val healthFailure = requireNotNull(trace("read_health_failed", healthError, notify = false))
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = healthFailure) }
                                healthFailure
                            }
                        } else {
                            if (readFailure == RemoteFailure.AUTHENTICATION) {
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = readFailure) }
                            } else if (readFailure in setOf(RemoteFailure.SERVICE, RemoteFailure.PROTOCOL,
                                    RemoteFailure.SESSION_BUSY, RemoteFailure.CONTENT_TOO_LARGE)) {
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                            }
                            if (readFailure == RemoteFailure.NETWORK) RemoteFailure.SERVICE else readFailure
                        }
                        consecutiveFailures++
                        // One interrupted GET may recover on the existing three-second loop.
                        // Health failure and non-network errors remain immediately visible.
                        val recovering = session != null && readFailure == RemoteFailure.NETWORK &&
                            reachable && consecutiveFailures == 1
                        mutableState.value = state.value.copy(failure = failure.takeUnless { recovering })
                        if (!recovering && notifiedFailure != failure) {
                            noticeChannel.trySend(RemoteNotice("read_failed", failure, selectionEpoch,
                                remoteErrorDetail(error).takeIf { failure == readFailure },
                                remoteErrorCode(error).takeIf { failure == readFailure }))
                            notifiedFailure = failure
                        }
                    }
                }
                if (session == null) break
                delay(3000)
            } while (isActive && visible && generation == epoch)
        }
    }

    private suspend fun applyPage(
        client: FiloClient, sessionId: String, generation: Long, page: RemoteConversationPage, liveControl: Boolean = true,
    ) {
        if (generation != epoch) return
        val old = state.value.nodes
        val latestIds = page.nodes.mapTo(HashSet()) { it.id }
        val settledLive = old.any { it.id.startsWith("live-") && it.id !in latestIds }
        // A surviving live suffix does not cover earlier temporary nodes that have
        // just become persisted. Bridge those through history before replacing IDs.
        val oldIds = old.filterNot { settledLive && it.id.startsWith("live-") }.mapTo(HashSet()) { it.id }
        val owner = state.value.owner ?: return
        val incoming = mutableListOf(cachePage(owner, page, live = true))
        var cursor = page.nextCursor
        val cursors = mutableSetOf<String>()
        // Opening publishes exactly the latest packet; only live updates bridge existing history.
        while (old.isNotEmpty() && cursor != null && incoming.last().nodes.none { it.id in oldIds }) {
            require(cursors.add(cursor)) { "Filo history cursor did not advance" }
            val raw = client.conversation(sessionId, cursor)
            if (generation != epoch) return
            val older = cachePage(owner, raw, live = false)
            incoming += older
            cursor = older.nextCursor
        }
        if (generation != epoch) return
        historyMutation.withLock {
            if (generation != epoch) return
            val previousNodes = state.value.nodes
            val (nodes, groups) = kotlinx.coroutines.withContext(projectionDispatcher) {
                // Re-admit the complete live tail after bridged history so a surviving
                // suffix stays after the newly persisted messages.
                var admitted = previousNodes.filterNot { node ->
                    settledLive && node.id.startsWith("live-")
                }
                for (chunk in incoming.asReversed()) admitted = admitRemoteNodes(admitted, chunk.nodes)
                admitted to projectRemoteTopology(admitted, page.runtime)
            }
            if (generation != epoch) return
            for (chunk in incoming.asReversed()) hydration.accept(owner, chunk, groups, live = false)
            if (generation != epoch) return
            mutableState.value = state.value.copy(
                nodes = nodes, messageGroups = groups, hydrationEnabled = visible,
                historyCursor = if (old.isEmpty()) incoming.last().nextCursor else state.value.historyCursor,
                loading = false, failure = null,
                runtime = page.runtime.takeIf { liveControl },
            )
        }
        state.value.deviceId?.let { id -> updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) } }
        state.value.attempts[owner]?.let { attempt -> confirmDelivery(owner, attempt, state.value.nodes) }
        settleStop()
    }

    private fun settleStop() {
        val snapshot = state.value
        val runtime = snapshot.runtime ?: return
        if (!snapshot.isStopping || snapshot.controlling) return
        if (!runtime.isRunning || runtime.activeTurnId != null && runtime.activeTurnId != snapshot.stoppingTurnId) {
            mutableState.value = snapshot.copy(stoppingOwner = null, stoppingTurnId = null)
        }
    }

    fun completeComposerFocus(owner: String) {
        if (state.value.composerFocusOwner == owner) mutableState.value = state.value.copy(composerFocusOwner = null)
    }

    fun stop() {
        val snapshot = state.value
        val session = snapshot.session ?: return
        if (snapshot.runtime?.isRunning != true) return
        val turn = snapshot.runtime.activeTurnId ?: return
        control(stoppingTurnId = turn) { client -> client.stop(session.id, turn) }
    }

    private fun control(stoppingTurnId: String? = null, operation: suspend (FiloClient) -> Unit) {
        if (state.value.controlling || state.value.isStopping) return
        val client = clients[state.value.deviceId] ?: return
        val selected = selectionEpoch
        val owner = state.value.owner
        mutableState.value = state.value.copy(controlling = true,
            stoppingOwner = if (stoppingTurnId != null) owner else state.value.stoppingOwner,
            stoppingTurnId = stoppingTurnId ?: state.value.stoppingTurnId)
        viewModelScope.launch {
            var succeeded = false
            try { operation(client); succeeded = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = trace("control_failed", error)
                if (selected == selectionEpoch) mutableState.value = state.value.copy(failure = failure)
            } finally {
                if (selected == selectionEpoch) mutableState.value = state.value.copy(controlling = false)
                if (stoppingTurnId != null && state.value.stoppingOwner == owner &&
                    state.value.stoppingTurnId == stoppingTurnId && (!succeeded || selected != selectionEpoch)) {
                    mutableState.value = state.value.copy(stoppingOwner = null, stoppingTurnId = null)
                }
                // A Stop receipt and the native end-of-turn snapshot may arrive in either order.
                settleStop()
            }
        }
    }

    fun loadMore() {
        if (paging?.isActive == true || state.value.loading) return
        val snapshot = state.value
        val client = clients[snapshot.deviceId] ?: return
        val cursor = (if (snapshot.session != null) snapshot.historyCursor else snapshot.sessionCursor) ?: return
        val generation = epoch
        mutableState.value = state.value.copy(loadingMore = true)
        paging = viewModelScope.launch {
            try {
                if (snapshot.session != null) {
                    prependPage(client, snapshot.session.id, generation, cursor)
                } else {
                    val page = client.sessions(cursor)
                    require(page.nextCursor != cursor) { "Filo session cursor did not advance" }
                    if (generation == epoch) {
                        val sessions = (state.value.sessions + page.sessions).distinctBy { it.id }
                        mutableState.value = state.value.copy(
                            sessions = sessions,
                            sessionCursor = page.nextCursor, failure = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == epoch) mutableState.value = state.value.copy(failure = trace("page_failed", error))
            } finally {
                if (generation == epoch) mutableState.value = state.value.copy(loadingMore = false)
            }
        }
    }

    private suspend fun cachePage(owner: String, page: RemoteConversationPage, live: Boolean): RemoteConversationPage {
        require(page.nodes.map { it.id } == page.messages.map { it.id }) { "Filo page metadata is missing" }
        hydration.accept(owner, page, emptyList(), live)
        // Retain only topology here; body retention stays in the bounded LRU.
        return page.copy(messages = emptyList(), nodes = page.nodes.map { it.copy(pageCursor = it.pageCursor ?: page.pageCursor) })
    }

    private suspend fun prependPage(client: FiloClient, session: String, generation: Long, cursor: String) {
        val owner = state.value.owner ?: return
        val page = cachePage(owner, client.conversation(session, cursor), live = false)
        require(page.nextCursor != cursor) { "Filo history cursor did not advance" }
        historyMutation.withLock {
            if (generation != epoch || state.value.historyCursor != cursor) return
            val nodes = admitRemoteNodes(state.value.nodes, page.nodes, older = true)
            val groups = projectRemoteTopology(nodes, state.value.runtime)
            hydration.accept(owner, page, groups, live = false)
            if (generation != epoch) return
            mutableState.value = state.value.copy(nodes = nodes, messageGroups = groups,
                historyCursor = page.nextCursor, failure = null)
        }
    }

    fun searchHistory(query: String): kotlinx.coroutines.flow.Flow<List<com.newoether.agora.ui.chat.ConversationSearchMatch>> {
        val snapshot = state.value
        val owner = snapshot.owner ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val client = clients[snapshot.deviceId] ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val session = snapshot.session ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val generation = epoch
        return remoteHistorySearch(state, owner, query,
            loadMessages = { ids -> hydration.loadMessages(owner, ids) },
            loadEarlier = { cursor ->
                if (generation != epoch) throw CancellationException()
                prependPage(client, session.id, generation, cursor)
            },
            failed = { trace("page_failed", it) },
            isCached = { group -> hydration.cachedMessage(owner, group) != null },
        )
    }

    fun editDraft(owner: String, text: String) {
        mutableState.value = state.value.copy(drafts = state.value.drafts + (owner to text))
    }

    fun acknowledgeUnknown(owner: String) = sendController.acknowledgeUnknown(owner)

    fun send() = sendController.send()

    private fun confirmDelivery(owner: String, attempt: RemoteAttempt, fresh: List<RemoteMessageNode> = state.value.nodes) =
        sendController.confirmDelivery(owner, attempt, fresh)

    /**
     * User-initiated file save: authenticated download into verified private
     * staging. Returns the staged handle for the SAF prompt, or null after the
     * failure has been reported through the notice channel.
     */
    suspend fun prepareFileDownload(owner: String, file: com.newoether.agora.model.RemoteFile): StagedRemoteFile? =
        fileDownloads.prepare(owner, file)

    suspend fun prepareFileView(owner: String, file: com.newoether.agora.model.RemoteFile): StagedRemoteFile? =
        fileDownloads.prepareView(owner, file)

    suspend fun prepareAttachmentPreview(
        owner: String, attachment: com.newoether.agora.model.RemoteAttachmentRef,
    ): StagedRemoteFile? = attachmentPreviews.prepare(owner, attachment)

    /** Final export to the user-chosen SAF document; honest false on any failure. */
    suspend fun exportPreparedFile(owner: String, token: String, target: android.net.Uri): Boolean =
        fileDownloads.export(owner, token, target)

    fun discardPreparedFile(token: String) = fileDownloads.discard(token)
}
