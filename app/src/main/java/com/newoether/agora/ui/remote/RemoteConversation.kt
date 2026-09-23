package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.gradientBlur
import kotlinx.coroutines.flow.filterNotNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteConversation(
    state: RemoteState, vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit,
) {
    val owner = state.owner ?: return
    val session = state.session ?: return
    val connectionStatus = when (state.devices.firstOrNull { it.id == state.deviceId }?.status) {
        RemoteDeviceStatus.CONNECTED -> com.newoether.agora.mcp.McpConnectionStatus.CONNECTED
        RemoteDeviceStatus.CONNECTING -> com.newoether.agora.mcp.McpConnectionStatus.CONNECTING
        RemoteDeviceStatus.ERROR -> com.newoether.agora.mcp.McpConnectionStatus.ERROR
        else -> com.newoether.agora.mcp.McpConnectionStatus.IDLE
    }
    val density = LocalDensity.current
    val motion = LocalAgoraMotionPolicy.current
    val haptics = LocalAgoraHaptics.current
    val chatWindow = androidx.compose.ui.platform.LocalWindowInfo.current
    val blur by settings.blurEffectsEnabled.collectAsState(initial = false)
    val amoled by settings.amoledEnabled.collectAsState(initial = false)
    val inlineMath by settings.parseInlineDollarMath.collectAsState(initial = false)
    val stickToBottom by settings.stickToBottom.collectAsState(initial = true)
    val toolCallDisplayMode by settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by settings.autoExpandActiveGroup.collectAsState()
    var expanded by remember(owner) { mutableStateOf(false) }
    BackHandler(active && expanded) { expanded = false }
    val spacer = rememberComposerSpacerAnimation(expanded, motion.allowSpatialTransitions, with(density) { 44.dp.toPx() })
    val field = remember(owner) { TextFieldState(state.drafts[owner].orEmpty()) }
    val focus = remember { FocusRequester() }
    val attempt = state.attempts[owner]
    val running = state.runtime?.isRunning == true
    val stopping = state.isStopping
    val ready = state.isDraft || state.runtime?.status in setOf("idle", "active", "ready")
    val newChatEntry = remember(owner) { state.composerFocusOwner == owner }
    ChatLaunchInteractionEffects(
        initialComposerFocusReady = active && ready && state.composerFocusOwner == owner,
        inputFocusRequester = focus,
        onShowLaunchContent = {},
        onInitialFocusRequested = { vm.completeComposerFocus(owner) },
    )
    val messages = remember(state.messageGroups) { state.messageGroups.map { it.stub } }
    val tail = messages.lastOrNull()?.takeIf {
        it.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING)
    }
    val generationVisible = tail != null
    var activeMenu by remember(owner) { mutableStateOf<String?>(null) }
    var lastModelDismissTime by remember(owner) { mutableLongStateOf(0L) }
    var lastContextDismissTime by remember(owner) { mutableLongStateOf(0L) }
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
    val loadToolImage: suspend (String, String) -> com.newoether.agora.model.ToolImageAttachment = remember(owner, vm) {
        { id, revision -> vm.loadToolImage(owner, id, revision) }
    }
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
    var initiallyPositioned by remember(owner) { mutableStateOf(state.isDraft) }
    val switching = !initiallyPositioned
    scroll.BindLayoutObservation(owner, owner, ime, density)
    scroll.BindImeEffects(owner, messageState, density, barHeight, 0.dp, ime)
    scroll.BindRequestEffects(owner, false, generationVisible, false, switching, interaction.searchActive, false, null, animatedScrollRequest,
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
    val follow = streamingTailAvailability(
        generationActive = generationVisible,
        blocked = switching || interaction.searchActive || !motion.allowProgrammaticScrollMotion,
        programmaticHandoff = scroll.imeBottomAnchorState.active ||
            scroll.absoluteBottomScrollPhase.isActive || animatedScrollRequest?.conversationId == owner,
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clearFocusOnTap()
        .onSizeChanged { scroll.recordViewportHeight(it.height) }) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        if (!amoled) AnimatedBlobBackground(centerAlpha = if (dark) 0.02f else 0f,
            quarterAlpha = if (dark) 0.01f else 0f, blurRadius = 40f, dark = dark,
            blurEnabled = blur, motionEnabled = false)
        // Insets are declared explicitly via contentWindowInsets above; the empty
        // content padding is intentional, so the Material3 usage lint does not apply.
        @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ChatTopBar(
                isNewChatMode = false, conversations = emptyList(),
                currentConversationId = session.id, currentConversationTitle = session.displayTitle(stringResource(R.string.new_chat)),
                totalTokens = state.runtime?.contextTokens ?: 0,
                contextTokenBudget = state.runtime?.contextWindow ?: 0,
                contextAvailable = state.runtime?.contextTokens != null && state.runtime?.contextWindow != null,
                subtitle = stringResource(when (connectionStatus) {
                    com.newoether.agora.mcp.McpConnectionStatus.CONNECTED -> R.string.remote_online
                    com.newoether.agora.mcp.McpConnectionStatus.CONNECTING -> R.string.remote_connecting
                    else -> R.string.remote_offline
                }),
                subtitleLeading = { com.newoether.agora.ui.settings.McpStatusDot(connectionStatus) },
                searchActive = interaction.searchActive, searchQuery = interaction.searchQuery,
                searchMatchIndex = interaction.searchMatchIndex, searchMatchCount = interaction.searchMatches.size,
                onSearchQueryChange = interaction::updateSearchQuery,
                onSearchPrevious = { if (interaction.previousSearchMatch()) haptics.selection() },
                onSearchNext = { if (interaction.nextSearchMatch()) haptics.selection() },
                onSearchDismiss = { interaction.dismissSearch(); focusManager.clearFocus() },
                onNavigateBack = onBack, onOpenDrawer = onBack, onSystemPromptClick = {},
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
                CompositionLocalProvider(com.newoether.agora.ui.chat.message.LocalToolImageLoader provides loadToolImage) {
                MessageList(messages = StableMessageList(renderMessages.value), allMessages = StableMessageList(messages),
                    authoritativeMessages = StableMessageList(messages), conversationId = owner,
                    state = scroll.listState, overscrollEffect = historyOverscroll, onMediaClick = onMediaClick, messageActionsEnabled = false, readOnlyActions = true, parseInlineDollarMath = inlineMath,
                    isLoading = generationVisible, isSwitching = switching, streamingMessage = streaming,
                    searchQuery = if (interaction.searchActive) interaction.searchQuery else "",
                    activeSearchMatch = searchMatch,
                    searchScrollRequestKey = interaction.searchScrollRequestKey,
                    onSearchMatchDistance = interaction::recordSearchMatchDistance,
                    onSearchTurnsChanged = interaction::recordSearchTurns,
                    streamingAutoFollowEnabled = follow.enabled && stickToBottom,
                    streamingAutoFollowPaused = follow.paused,
                    streamingTailWithinAttachThreshold = scroll.isWithinAbsoluteBottomAttachThreshold,
                    streamingTailController = scroll.streamingTailController,
                    toolCallDisplayMode = toolCallDisplayMode, thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                    autoExpandActiveGroup = autoExpandActiveGroup,
                    modifier = Modifier.fillMaxSize().gradientBlur(blurAtTopDp = if (blur) 8f else 0f,
                        blurAtBottomDp = 0f, fadeHeightDp = 40f, bottomOverlayHeight = barHeight + with(density) { spacer.outerHeightPx.toDp() } + 12.dp),
                    bottomBarHeight = barHeight, viewportHeight = scroll.viewportHeightPx,
                    messageHeights = scroll.messageHeights, observeMessage = observe, initialMessage = initialMessage,
                    programmaticScrollActive = animatedScrollRequest?.conversationId == owner,
                    onMessageHydrated = scroll::recordMessageHydrated,
                    lifecycleAppearanceRegistry = scroll.messageLifecycleAppearanceRegistry,
                    lifecycleEntranceTargetMessageId = animatedScrollRequest?.takeIf { it.conversationId == owner }?.targetMessageId,
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
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 140.dp + leadingSpace.dp, bottom = barHeight + 8.dp))
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
        ChatComposerSurface(expanded, { barHeightPx = it }, Modifier.align(Alignment.BottomCenter), spacer.outerHeightPx) {
            ChatComposerLayout(field, focus, scroll::setComposerInputFocused, expanded, spacer.isRunning,
                onExpand = { expanded = true }, onCollapse = { expanded = false },
                statusContent = {
                    ComposerStatusColumn(state.queued, { it.id }) { QueuedMessageRow(text = it.text) }
                },
                attachmentContent = {},
                controls = {
                    ComposerControlGroup {
                        ComposerModelSelector(
                            displayText = (state.models.firstOrNull { it.id == state.selectedModel }?.name
                                ?: state.selectedModel)?.replace('-', ' ') ?: stringResource(
                                    if (state.modelsLoading || state.loading) R.string.loading_label else R.string.remote_model_unavailable),
                            isModelValid = state.selectedModel != null, expanded = activeMenu == "model",
                            // Fairy 单模型：选择器只作标签展示，不提供切换。
                            enabled = false,
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (activeMenu == "model") activeMenu = null
                                else if (now - lastModelDismissTime > 200) activeMenu = "model"
                            },
                            onDismissRequest = {
                                if (activeMenu == "model") {
                                    activeMenu = null
                                    lastModelDismissTime = System.currentTimeMillis()
                                }
                            },
                            menuContent = {
                                val sortedModels = remember(state.models) { state.models.sortedBy { it.id.lowercase() } }
                                sortedModels.forEach { model ->
                                    ComposerModelMenuItem(
                                        displayText = model.name.replace('-', ' '),
                                        selected = model.id == state.selectedModel,
                                        onClick = {
                                            haptics.selection()
                                            vm.setModel(model.id)
                                            activeMenu = null
                                            lastModelDismissTime = 0L
                                        },
                                    )
                                }
                            },
                        )
                        ComposerContextIndicator(
                            estimatedTokens = state.runtime?.contextTokens, tokenBudget = state.runtime?.contextWindow,
                            expanded = activeMenu == "context",
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (activeMenu == "context") activeMenu = null
                                else if (now - lastContextDismissTime > 200) activeMenu = "context"
                            },
                            onDismissRequest = {
                                if (activeMenu == "context") {
                                    activeMenu = null
                                    lastContextDismissTime = System.currentTimeMillis()
                                }
                            },
                        )
                    }
                    val showStop = running && !stopping && field.text.isBlank()
                    ComposerSendButton(isActionable = active && !stopping && !state.controlling && !submitting &&
                        (if (showStop) state.runtime?.activeTurnId != null else field.text.isNotBlank()),
                        isBusy = submitting || stopping, showStop = showStop,
                        onBusyShown = { shownBusyAttempt = attempt?.clientId }) {
                        if (showStop) vm.stop()
                        else if (attempt?.delivery == RemoteDelivery.UNKNOWN) onMessage(unknownDeliveryText, checkedDeliveryText) {
                            vm.acknowledgeUnknown(owner)
                        }
                        else { vm.editDraft(owner, field.text.toString()); vm.send() }
                    }
                })
        }
    }
}
