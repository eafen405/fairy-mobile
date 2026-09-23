package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.remote.RemoteConnectionStore
import java.io.File
import com.newoether.agora.R
import com.newoether.agora.SettingsOverlayHost
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.ui.settings.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.ui.motion.MotionAwareLinearProgressIndicator
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics

@Composable
internal fun RemoteOverlay(
    visible: Boolean,
    hapticsActive: Boolean,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
    onExitFinished: () -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
) {
    val messageContext by rememberUpdatedState(LocalContext.current)
    val context = LocalContext.current.applicationContext
    val remote: RemoteViewModel = viewModel {
        val imageDirectory = File(context.cacheDir, "remote-images")
        RemoteViewModel(RemoteConnectionStore(File(context.noBackupFilesDir, "remote-connections.json")),
            com.newoether.agora.tool.ToolImageStore(context, imageDirectory),
            com.newoether.agora.remote.RemoteImageCache(imageDirectory),
            attachmentStore = com.newoether.agora.remote.RemoteAttachmentStore(context))
    }
    val messageHandler by rememberUpdatedState(onMessage)
    LaunchedEffect(remote, visible) {
        if (!visible) return@LaunchedEffect
        remote.notices.collect { notice ->
            if (remote.isNoticeCurrent(notice)) messageHandler(
                remoteNoticeMessage(messageContext, notice),
                if (notice.canRetryRead) messageContext.getString(R.string.retry) else null,
                if (notice.canRetryRead) ({ remote.retryNotice(notice) }) else null,
            )
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(remote, visible, lifecycle) {
        fun update() = remote.setVisible(visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); remote.setVisible(false) }
    }
    SettingsOverlayHost(visible, onDismiss, onExitFinished = onExitFinished) {
        val hapticsEnabled by settings.hapticsEnabled.collectAsState(initial = false)
        CompositionLocalProvider(LocalAgoraHaptics provides rememberAgoraHaptics(hapticsEnabled && hapticsActive)) {
            RemoteScreen(remote, settings, visible, onDismiss, onSnackbarOffsetChanged, onMediaClick, onMessage)
        }
    }
}

@Composable
private fun RemoteScreen(vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit) {
    val state by vm.state.collectAsState()
    val inset = maxOf(WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    SideEffect { if (active && state.session == null) onSnackbarOffsetChanged(inset) }
    val focus = LocalFocusManager.current
    val back = {
        focus.clearFocus()
        onBack()
    }
    BackHandler(active, back)
    // 单账户形态：唯一连接与主会话直接选中，不经过设备/会话目录；出错留给重试按钮。
    LaunchedEffect(state.restoring, state.addingDevice, state.deviceId, state.devices,
        state.session, state.sessions, state.loading) {
        if (state.restoring || state.addingDevice) return@LaunchedEffect
        if (state.deviceId == null) {
            state.devices.singleOrNull()?.takeIf { it.status != RemoteDeviceStatus.ERROR }
                ?.let { vm.selectDevice(it.id) }
        } else if (state.session == null && !state.loading) {
            state.sessions.firstOrNull()?.let { vm.selectSession(it) }
        }
    }
    val target = when {
        state.restoring -> "restoring"
        state.addingDevice || state.devices.isEmpty() -> "login"
        state.session != null -> "conversation"
        else -> "connecting"
    }
    Box(Modifier.fillMaxSize()) {
    GuardedAnimatedContent(targetState = target, forward = true) { page ->
        var retained by remember(page) { mutableStateOf(state) }
        val current = page == target
        SideEffect { if (current) retained = state }
        val displayed = if (current) state else retained
        when (page) {
            "login" -> FairyLogin(displayed, vm, back)
            "conversation" -> RemoteConversation(displayed, vm, settings, active && current, back, onSnackbarOffsetChanged, onMediaClick, onMessage)
            else -> RemoteConnecting(displayed, vm)
        }
    }
    val progressVisible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    SideEffect {
        progressVisible.targetState = state.deviceId != null && state.session == null && !state.addingDevice &&
            (state.loading || state.loadingMore || state.controlling)
    }
    AnimatedVisibility(
        visibleState = progressVisible,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
    ) {
        MotionAwareLinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
    }
    }
}

/** 等待连接/主会话落地的过渡面：进度、失败与重试。 */
@Composable
private fun RemoteConnecting(state: RemoteState, vm: RemoteViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val failed = state.failure != null || state.devices.any { it.status == RemoteDeviceStatus.ERROR }
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.remote_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.retry)) }
            }
        } else if (!state.loading && state.sessions.isEmpty() && state.deviceId != null &&
            state.devices.firstOrNull { it.id == state.deviceId }?.status == RemoteDeviceStatus.CONNECTED) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.remote_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.retry)) }
            }
        } else {
            MotionAwareCircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 5.dp,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 登录/注册页：origin + 用户名/密码（注册加邀请码），替换上游 Add-Device 表单。 */
@Composable
private fun FairyLogin(state: RemoteState, vm: RemoteViewModel, onBack: () -> Unit) {
    val initial = remember { vm.editorConnection() }
    var origin by remember { mutableStateOf(initial?.address.orEmpty()) }
    var username by remember { mutableStateOf(initial?.name.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    CollapsingSettingsScaffold(
        title = stringResource(if (registering) R.string.remote_register else R.string.remote_sign_in),
        onBack = onBack,
        actions = { IconButton(onClick = {
                vm.login(origin.trim(), username.trim(), password, if (registering) invite.trim() else null)
            }, enabled = !state.restoring && !state.saving && origin.isNotBlank() &&
                username.isNotBlank() && password.isNotBlank() && (!registering || invite.isNotBlank())) {
                Icon(Icons.Default.Save, stringResource(R.string.save))
            } },
    ) {
        SettingsGroup(title = stringResource(R.string.remote_connection), items = buildList {
            add {
                SettingsIconContent(Icons.Default.Link) {
                    McpLabeledField(label = stringResource(R.string.remote_address), value = origin,
                        onValueChange = { origin = it }, keyboardType = KeyboardType.Uri,
                        supportingText = stringResource(R.string.remote_origin_hint),
                        placeholder = stringResource(R.string.remote_address_placeholder))
                }
            }
            add {
                SettingsIconContent(Icons.Default.Person) {
                    McpLabeledField(label = stringResource(R.string.remote_username), value = username,
                        onValueChange = { username = it },
                        placeholder = stringResource(R.string.remote_username))
                }
            }
            add {
                SettingsIconContent(Icons.Default.Key) {
                    McpLabeledField(label = stringResource(R.string.remote_password), value = password,
                        onValueChange = { password = it }, keyboardType = KeyboardType.Password, password = true,
                        placeholder = stringResource(R.string.remote_password))
                }
            }
            if (registering) add {
                SettingsIconContent(Icons.Default.CardGiftcard) {
                    McpLabeledField(label = stringResource(R.string.remote_invite), value = invite,
                        onValueChange = { invite = it },
                        placeholder = stringResource(R.string.remote_invite))
                }
            }
            add {
                SettingsItem(
                    modifier = Modifier.clickable { registering = !registering },
                    headlineContent = { Text(stringResource(
                        if (registering) R.string.remote_have_account else R.string.remote_need_invite)) },
                    leadingContent = { Icon(Icons.Default.SwapHoriz, null) },
                )
            }
        })
    }
}
