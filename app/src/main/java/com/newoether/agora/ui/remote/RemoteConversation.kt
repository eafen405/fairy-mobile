package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.remote.*
import com.newoether.agora.ui.chat.*
import com.newoether.agora.ui.chat.bottombar.*
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.components.FairyBackground
import com.newoether.agora.ui.components.FairyWindowBarHeight
import com.newoether.agora.ui.components.FairyWindowState
import com.newoether.agora.ui.components.fairyPresence
import com.newoether.agora.ui.components.rememberFairyTextGrowth
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.gradientBlur
import com.newoether.agora.ui.chat.message.LocalRemoteFileAction
import com.newoether.agora.ui.chat.message.LocalRemoteFileSaving
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteConversation(
    state: RemoteState, vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = state.owner ?: return
    val session = state.session ?: return
    val connectionStatus = when (state.devices.firstOrNull { it.id == state.deviceId }?.status) {
        RemoteDeviceStatus.CONNECTED -> com.newoether.agora.model.McpConnectionStatus.CONNECTED
        RemoteDeviceStatus.CONNECTING -> com.newoether.agora.model.McpConnectionStatus.CONNECTING
        RemoteDeviceStatus.ERROR -> com.newoether.agora.model.McpConnectionStatus.ERROR
        else -> com.newoether.agora.model.McpConnectionStatus.IDLE
    }
    val density = LocalDensity.current
    val motion = LocalAgoraMotionPolicy.current
    val haptics = LocalAgoraHaptics.current
    val chatWindow = androidx.compose.ui.platform.LocalWindowInfo.current
    val inlineMath by settings.parseInlineDollarMath.collectAsState(initial = false)
    val toolCallDisplayMode by settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by settings.autoExpandActiveGroup.collectAsState()
    var expanded by remember(owner) { mutableStateOf(false) }
    BackHandler(active && expanded) { expanded = false }
    val spacer = rememberComposerSpacerAnimation(expanded, motion.allowSpatialTransitions, with(density) { 44.dp.toPx() })
    val field = remember(owner) { TextFieldState(state.drafts[owner].orEmpty()) }
    val focus = remember { FocusRequester() }
    val attempt = state.attempts[owner]
    val attachments = state.attachments[owner].orEmpty()
    val running = state.runtime?.isRunning == true
    val stopping = state.isStopping
    val ready = state.runtime?.status in setOf("idle", "active", "ready")
    val newChatEntry = remember(owner) { state.composerFocusOwner == owner }
    ChatLaunchInteractionEffects(
        initialComposerFocusReady = active && ready && state.composerFocusOwner == owner,
        inputFocusRequester = focus,
        onShowLaunchContent = {},
        onInitialFocusRequested = { vm.completeComposerFocus(owner) },
    )
    val speech = rememberRemoteSpeechController(owner, field, active) { vm.editDraft(owner, it) }
    val messages = remember(state.messageGroups) { state.messageGroups.map { it.stub } }
    val tail = messages.lastOrNull()?.takeIf {
        it.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING)
    }
    val generationVisible = tail != null
    val tailMessage = messages.lastOrNull()
    var activeMenu by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(active) {
        if (!active) activeMenu = null
    }
    LaunchedEffect(owner, field) { snapshotFlow { field.text.toString() }.collect { vm.editDraft(owner, it) } }
    var clearedAttempt by remember(owner) {
        mutableStateOf(attempt?.takeIf { it.delivery == RemoteDelivery.DELIVERED }?.clientId)
    }
    var shownBusyAttempt by remember(owner) { mutableStateOf(clearedAttempt) }
    val acceptedPendingClear = attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId
    val submitting = attempt?.delivery in setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED) || acceptedPendingClear
    LaunchedEffect(attempt, shownBusyAttempt) {
        if (attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId && shownBusyAttempt == attempt.clientId) {
            clearedAttempt = attempt.clientId
            if (state.drafts[owner].isNullOrEmpty() && field.text.toString() == attempt.text) {
                field.edit { replace(0, length, "") }
            }
            expanded = false
            if (chatWindow.isWindowFocused && activeMenu == null) haptics.confirm()
        }
    }
    val observe = remember(owner) { { id: String -> vm.observeMessage(owner, id) } }
    val initialMessage = remember(owner) { { id: String ->
        if (vm.state.value.owner == owner) vm.cachedMessage(owner, id) else null
    } }
    val streaming = tail?.let { message ->
        key(owner, message.id) {
            // A suspended observer does not delete the body already shown during navigation.
            val flow = remember(owner, message.id) { vm.observeMessage(owner, message.id).filterNotNull() }
            val payload by flow.collectAsState(initial = vm.cachedMessage(owner, message.id))
            payload
        }
    }
    val textGrowth = rememberFairyTextGrowth(
        key = tail?.id,
        visibleLength = if (tail != null) streaming?.text?.length ?: 0 else 0,
    )
    val presence = fairyPresence(
        connection = connectionStatus,
        tailParticipant = tailMessage?.participant,
        tailStatus = tailMessage?.status,
        tailTextGrowing = textGrowth.growing,
    )
    // Reply completion: one selection tick when the tail assistant message
    // leaves its generating status for SUCCESS while this window is focused
    // and uncovered.
    var generatingTailId by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(tailMessage?.id, tailMessage?.status) {
        val id = tailMessage?.id
        val status = tailMessage?.status
        if (tailMessage?.participant == com.newoether.agora.model.Participant.MODEL &&
            status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING)
        ) {
            generatingTailId = id
        } else {
            if (id != null && id == generatingTailId && status == MessageStatus.SUCCESS &&
                active && chatWindow.isWindowFocused && activeMenu == null
            ) {
                haptics.selection()
            }
            generatingTailId = null
        }
    }
    val messageState = rememberUpdatedState(messages)
    val ime = WindowInsets.ime.getBottom(density)
    val scroll = rememberChatScrollCoordinator(owner, ime)
    val historyOverscroll = rememberRemoteHistoryOverscroll(scroll.listState)
    val focusManager = LocalFocusManager.current
    val searchMessages: suspend (String, List<String>) -> List<com.newoether.agora.model.ChatMessage> =
        remember(owner, state.hydrationRevision) { { _, ids -> vm.searchMessages(owner, ids) } }
    val searchAllMessages = remember(owner, vm, state.hydrationEnabled) {
        { query: String -> vm.searchHistory(query) }
    }
    val interaction = rememberConversationInteractionState(owner, messageState, scroll.listState, searchMessages,
        searchAllMessages = searchAllMessages)
    val searchMatch = interaction.searchMatches.getOrNull(interaction.searchMatchIndex)
    BackHandler(active && interaction.searchActive) {
        interaction.dismissSearch()
        focusManager.clearFocus()
    }
    val animatedScrollRequest by vm.animatedScrollRequest.collectAsState()
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    val barHeight = with(density) { barHeightPx.toDp() }
    SnackbarOffsetEffect(drawerProgress = 0f, isExpanded = expanded, bottomBarHeight = barHeight,
        settingsButtonTopDp = 0f, bottomInset = maxOf(
            WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        onOffsetChanged = { if (active) onSnackbarOffsetChanged(it) })
    var initiallyPositioned by remember(owner) { mutableStateOf(false) }
    val switching = !initiallyPositioned
    scroll.BindLayoutObservation(owner, owner, ime, density)
    scroll.BindImeEffects(owner, messageState, density, barHeight, 0.dp, ime)
    scroll.BindRequestEffects(owner, false, generationVisible, false, switching, interaction.searchActive, false, animatedScrollRequest,
        messageState, density, motion, barHeight, 0.dp, onAnimatedScrollFinished = vm::completeAnimatedScroll)
    val renderMessages = rememberScrollIsolatedMessages(owner, messageState, scroll.listState,
        bypassScrollIsolation = scroll.absoluteBottomScrollPhase.isActive || scroll.streamingTailController.isAutoFollowing)
    var initialLeadingSpace by remember(owner) { mutableStateOf<Int?>(null) }
    val leadingSpace = initialLeadingSpace ?: messageListPageLeadingSpacing(renderMessages.value.firstOrNull())
    SideEffect { if (initialLeadingSpace == null && renderMessages.value.isNotEmpty()) initialLeadingSpace = leadingSpace }
    LaunchedEffect(owner, state.hydrationEnabled) {
        if (state.hydrationEnabled && !initiallyPositioned) {
            scroll.settleOpenedConversation(messageState)
            initiallyPositioned = true
        }
    }
    // A proactive or relayed turn lands on its own MODEL tail. While the reader is at the
    // bottom that new tail takes over the anchor; otherwise only the bottom button signals it.
    BindIncomingTurnAnchorEffect(
        conversationId = owner,
        messages = messages,
        enabled = initiallyPositioned && active && !interaction.searchActive,
        hasPendingAttempt = attempt?.delivery in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN),
        withinAttachThreshold = { scroll.isWithinAbsoluteBottomAttachThreshold },
        onRequestAnchor = vm::requestAnchor,
    )
    val historyStartId = messages.firstOrNull()?.id
    val atHistoryBoundary by remember(scroll.listState, historyStartId) {
        derivedStateOf {
            historyStartId != null && !scroll.listState.canScrollBackward &&
                scroll.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.key == historyStartId
        }
    }
    LaunchedEffect(owner, active, switching, interaction.searchActive, state.historyCursor, state.loadingMore, state.error, historyStartId) {
        if (!active || switching || interaction.searchActive || state.historyCursor == null ||
            state.loadingMore || state.error) return@LaunchedEffect
        // Topology passes through scroll isolation before the list measures it. An old
        // layout is not the boundary of the newly admitted page, even while still at index 0.
        snapshotFlow { atHistoryBoundary }.collect { atTop ->
            if (atTop) vm.loadMore()
        }
    }
    val historyProgress = remember(owner) { androidx.compose.animation.core.MutableTransitionState(false) }
    SideEffect {
        historyProgress.targetState = active && !switching && !interaction.searchActive &&
            state.loadingMore && atHistoryBoundary
    }
    val unknownDeliveryText = stringResource(R.string.remote_unknown)
    val checkedDeliveryText = stringResource(R.string.remote_check)
    val fileSavedText = stringResource(R.string.remote_file_saved)
    val fileSaveFailedText = stringResource(R.string.remote_file_save_failed)
    // A staged download survives the SAF picker round-trip by token; the file
    // name is only the suggested document name, never a path.
    val fileScope = rememberCoroutineScope()
    var pendingFileExport by remember(owner) {
        mutableStateOf<com.newoether.agora.remote.StagedRemoteFile?>(null)
    }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val pending = pendingFileExport
        pendingFileExport = null
        if (uri == null || pending == null) {
            pending?.let { vm.discardPreparedFile(it.token) }
        } else {
            fileScope.launch {
                val saved = vm.exportPreparedFile(owner, pending.token, uri)
                onMessage(if (saved) fileSavedText else fileSaveFailedText, null, null)
            }
        }
    }
    val saveRemoteFile = remember(owner, vm) {
        { file: com.newoether.agora.model.RemoteFile ->
            fileScope.launch {
                vm.prepareFileDownload(owner, file)?.let { staged ->
                    // A second save replaces the outstanding prompt; its staged bytes go too.
                    pendingFileExport?.let { vm.discardPreparedFile(it.token) }
                    pendingFileExport = staged
                    createDocument.launch(staged.name)
                }
            }
            Unit
        }
    }
    fun viewFile(staged: StagedRemoteFile) {
        try {
            context.startActivity(remoteFileViewIntent(context, staged))
        } catch (_: android.content.ActivityNotFoundException) {
            onMessage(context.getString(R.string.remote_file_no_viewer), null, null)
        } catch (_: SecurityException) {
            onMessage(fileSaveFailedText, null, null)
        }
    }
    val openRemoteFile: (com.newoether.agora.model.RemoteFile) -> Unit = { file ->
        fileScope.launch {
            vm.prepareFileDownload(owner, file)?.let { staged ->
                if (file.mime?.startsWith("image/") == true) onMediaClick(listOf(staged.file.absolutePath), 0)
                else viewFile(staged)
            }
        }
    }
    val attachmentActions = com.newoether.agora.ui.chat.message.RemoteAttachmentActions(
        owner = owner,
        load = { ref -> vm.prepareAttachmentPreview(owner, ref) },
        open = { staged -> viewFile(staged) },
        image = { staged -> onMediaClick(listOf(staged.file.absolutePath), 0) },
    )
    val windowExpanded = messages.isEmpty() && initiallyPositioned && !state.loading
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clearFocusOnTap()
        .onSizeChanged { scroll.recordViewportHeight(it.height) }) {
        FairyBackground()
        // Insets are declared explicitly via contentWindowInsets above; the empty
        // content padding is intentional, so the Material3 usage lint does not apply.
        @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ChatTopBar(
                subtitle = stringResource(when (connectionStatus) {
                    com.newoether.agora.model.McpConnectionStatus.CONNECTED -> R.string.remote_online
                    com.newoether.agora.model.McpConnectionStatus.CONNECTING -> R.string.remote_connecting
                    else -> R.string.remote_offline
                }),
                subtitleLeading = { com.newoether.agora.ui.settings.McpStatusDot(connectionStatus) },
                searchActive = interaction.searchActive, searchQuery = interaction.searchQuery,
                searchMatchIndex = interaction.searchMatchIndex, searchMatchCount = interaction.searchMatches.size,
                onSearchQueryChange = interaction::updateSearchQuery,
                onSearchPrevious = { if (interaction.previousSearchMatch()) haptics.selection() },
                onSearchNext = { if (interaction.nextSearchMatch()) haptics.selection() },
                onSearchDismiss = { interaction.dismissSearch(); focusManager.clearFocus() },
                onNavigateBack = onBack,
                fairyWindow = FairyWindowState(
                    presence = presence,
                    expanded = windowExpanded,
                    speechPulse = textGrowth.pulse,
                ),
                moreMenuContent = { dismiss ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        enabled = active && !switching,
                        onClick = { dismiss(); interaction.activateSearch() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_logout)) },
                        leadingIcon = { Icon(Icons.Default.Logout, null) },
                        enabled = active,
                        onClick = { dismiss(); vm.logout() },
                    )
                },
            )
        }) { _ ->
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalRemoteFileAction provides saveRemoteFile,
                    com.newoether.agora.ui.chat.message.LocalRemoteFileOpen provides openRemoteFile,
                    com.newoether.agora.ui.chat.message.LocalRemoteAttachmentActions provides attachmentActions,
                    LocalRemoteFileSaving provides state.savingFiles,
                ) {
                MessageList(messages = StableMessageList(renderMessages.value),
                    authoritativeMessages = StableMessageList(messages), conversationId = owner,
                    state = scroll.listState, overscrollEffect = historyOverscroll, parseInlineDollarMath = inlineMath,
                    isLoading = generationVisible, isSwitching = switching, streamingMessage = streaming,
                    searchQuery = if (interaction.searchActive) interaction.searchQuery else "",
                    activeSearchMatch = searchMatch,
                    searchScrollRequestKey = interaction.searchScrollRequestKey,
                    onSearchMatchDistance = interaction::recordSearchMatchDistance,
                    onSearchTurnsChanged = interaction::recordSearchTurns,
                    streamingAutoFollowEnabled = false,
                    streamingAutoFollowPaused = false,
                    streamingTailWithinAttachThreshold = scroll.isWithinAbsoluteBottomAttachThreshold,
                    streamingTailController = scroll.streamingTailController,
                    toolCallDisplayMode = toolCallDisplayMode, thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                    autoExpandActiveGroup = autoExpandActiveGroup,
                    modifier = Modifier.fillMaxSize().gradientBlur(blurAtTopDp = 0f,
                        blurAtBottomDp = 0f, fadeHeightDp = 40f, bottomOverlayHeight = barHeight + with(density) { spacer.outerHeightPx.toDp() } + 12.dp),
                    bottomBarHeight = barHeight, viewportHeight = scroll.viewportHeightPx,
                    messageHeights = scroll.messageHeights, observeMessage = observe, initialMessage = initialMessage,
                    programmaticScrollActive = animatedScrollRequest?.conversationId == owner,
                    onMessageHydrated = scroll::recordMessageHydrated,
                    lifecycleAppearanceRegistry = scroll.messageLifecycleAppearanceRegistry,
                    lifecycleEntranceTargetMessageId = animatedScrollRequest?.takeIf { it.conversationId == owner }?.targetMessageId,
                    anchoredMessageId = scroll.activeAnchor?.takeIf { it.conversationId == owner }?.messageId,
                    leadingContentLayer = {
                        Box(
                            modifier = Modifier.matchParentSize().offset(y = (-30).dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            AnimatedVisibility(
                                visibleState = historyProgress,
                                enter = fadeIn(tween(300)),
                                exit = fadeOut(tween(300)),
                            ) {
                                MotionAwareCircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp,
                        top = statusBarTop + FairyWindowBarHeight + 12.dp + leadingSpace.dp, bottom = barHeight + 8.dp))
                }
                AnimatedVisibility(
                    visible = windowExpanded,
                    enter = fadeIn(tween(350)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.padding(top = statusBarTop + FairyWindowBarHeight + 12.dp + 140.dp + 24.dp),
                ) {
                    FairyEmptyGreeting(
                        enabled = active,
                        onQuickPrompt = { prompt ->
                            field.edit {
                                replace(0, length, prompt)
                                placeCursorAtEnd()
                            }
                            focus.requestFocus()
                        },
                    )
                }
                ChatBottomScrollButton(
                    shouldShowAbsoluteBottomButton(
                        isNewChatMode = newChatEntry && messages.isEmpty(),
                        isSwitching = switching,
                        conversationContentReady = initiallyPositioned,
                        shareSelectionActive = false,
                        hasItems = scroll.listState.layoutInfo.totalItemsCount > 1,
                        canScrollForward = scroll.listState.canScrollForward,
                        isNearBottom = scroll.isNearAbsoluteBottom,
                        isStreamingAutoFollowing = scroll.streamingTailController.isAutoFollowing,
                        scrollPhase = scroll.absoluteBottomScrollPhase,
                        competingProgrammaticScrollActive = scroll.imeBottomAnchorState.active,
                    ),
                    barHeight,
                ) {
                    scroll.requestAbsoluteBottomScroll()
                }

                AnimatedVisibility(
                    visible = switching && !newChatEntry && !state.error,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        MotionAwareCircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        ChatComposerSurface(expanded, { barHeightPx = it }, Modifier.align(Alignment.BottomCenter), spacer.outerHeightPx, bare = true) {
            ChatComposerLayout(field, focus, scroll::setComposerInputFocused, expanded, spacer.isRunning,
                onExpand = { expanded = true }, onCollapse = { expanded = false },
                singleLine = true,
                leadingControls = {
                    RemoteAttachmentPicker(owner, active && !submitting && !speech.exclusive, vm)
                },
                inputReadOnly = speech.exclusive,
                statusContent = {
                    RemoteSpeechStatus(speech)
                },
                attachmentContent = {
                    if (attachments.isNotEmpty()) {
                        AttachmentPreviewRow(
                            attachments = attachments,
                            editable = active && !submitting && !speech.exclusive,
                            onRemove = { vm.removeAttachment(owner, it) },
                            onRetry = { vm.retryAttachment(owner, it) },
                            onAllMediaClick = onMediaClick,
                        )
                    }
                },
                controls = {
                    val showStop = running && !stopping && field.text.isBlank() && attachments.isEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteSpeechButton(speech, enabled = active && !submitting)
                        Spacer(Modifier.width(8.dp))
                        ComposerSendButton(isActionable = active && !stopping && !state.controlling && !submitting &&
                            !speech.exclusive &&
                            (if (showStop) state.runtime?.activeTurnId != null
                                else field.text.isNotBlank() || attachments.isNotEmpty()),
                            isBusy = submitting || stopping, showStop = showStop,
                            onBusyShown = { shownBusyAttempt = attempt?.clientId }) {
                            if (showStop) vm.stop()
                            else if (attempt?.delivery == RemoteDelivery.UNKNOWN) onMessage(unknownDeliveryText, checkedDeliveryText) {
                                vm.acknowledgeUnknown(owner)
                            }
                            else { vm.editDraft(owner, field.text.toString()); vm.send() }
                        }
                    }
                })
        }
    }
}
