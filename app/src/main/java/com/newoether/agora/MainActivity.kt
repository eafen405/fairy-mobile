package com.newoether.agora
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.ui.chat.FullScreenMediaPreviewDialog
import com.newoether.agora.ui.chat.MediaPreviewTarget
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.ProvideAgoraMotionPolicy
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.remote.RemoteShellViewModel
import com.newoether.agora.util.snackbarTimeoutMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private fun fullScreenPreviewEnterTransition(allowSpatialTransitions: Boolean): EnterTransition = fadeIn(tween(durationMillis = 220)) + (if (allowSpatialTransitions) scaleIn(tween(durationMillis = 300, easing = FastOutSlowInEasing), initialScale = 0.96f) else EnterTransition.None)
private fun fullScreenPreviewExitTransition(allowSpatialTransitions: Boolean): ExitTransition = fadeOut(tween(durationMillis = 180)) + (if (allowSpatialTransitions) scaleOut(tween(durationMillis = 220, easing = FastOutLinearInEasing), targetScale = 0.96f) else ExitTransition.None)
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SCREENSHOT_DESTINATION = "com.newoether.agora.extra.SCREENSHOT_DESTINATION"
        const val EXTRA_CONVERSATION_ID = "com.newoether.agora.extra.CONVERSATION_ID"
    }

    override fun attachBaseContext(newBase: Context) {
        val langCode = kotlinx.coroutines.runBlocking {
            SettingsManager(newBase).appLanguage.first()
        }
        val locale = when (langCode) {
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            "es" -> java.util.Locale("es")
            "fr" -> java.util.Locale("fr")
            "de" -> java.util.Locale("de")
            "ru" -> java.util.Locale("ru")
            "pt-BR" -> java.util.Locale("pt", "BR")
            "ja" -> java.util.Locale("ja")
            "ko" -> java.util.Locale("ko")
            "ar" -> java.util.Locale("ar")
            "vi" -> java.util.Locale("vi")
            "zh-Hant" -> java.util.Locale.forLanguageTag("zh-Hant")
            else -> null
        }
        if (locale != null) {
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        var startupReady = false
        splashScreen.setKeepOnScreenCondition { !startupReady }
        super.onCreate(savedInstanceState)

        com.newoether.agora.util.DebugLog.init(this)

        val settingsManager = SettingsManager(applicationContext)
        val agoraApplication = application as AgoraApplication
        lifecycleScope.launch {
            val databaseStartupState = agoraApplication.awaitDatabaseStartup()
            val needsErrorDialog = databaseStartupState is DatabaseStartupState.Blocked
            withContext(Dispatchers.IO) {
                intent?.getStringExtra(EXTRA_SCREENSHOT_DESTINATION)?.let { destination ->
                    runCatching {
                        Class.forName("com.newoether.agora.screenshot.ScreenshotFixture")
                            .getMethod("seed", AgoraApplication::class.java, String::class.java)
                            .invoke(null, agoraApplication, destination)
                    }.onFailure { error ->
                        if (error !is ClassNotFoundException) {
                            com.newoether.agora.util.DebugLog.e(
                                "MainActivity",
                                "Screenshot fixture failed",
                                error,
                            )
                        }
                    }
                }
                runCatching {
                    settingsManager.initializeFirstInstallDefaults(
                        locale = java.util.Locale.getDefault()
                    )
                }.onFailure { error ->
                    com.newoether.agora.util.DebugLog.e(
                        "MainActivity",
                        "First-install settings initialization failed",
                        error,
                    )
                }
            }

            enableEdgeToEdge()
            // Remove navigation bar scrim so it blends with app content
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            setContent {
            val themeMode by settingsManager.themeMode.collectAsState(initial = "FOLLOW_DEVICE")
            val amoledEnabled by settingsManager.amoledEnabled.collectAsState(initial = false)
            val colorSchemeName by settingsManager.colorScheme.collectAsState(initial = com.newoether.agora.data.DEFAULT_COLOR_SCHEME)
            val schemeStyleName by settingsManager.schemeStyle.collectAsState(initial = com.newoether.agora.data.DEFAULT_SCHEME_STYLE)
            val dynamicColor by settingsManager.dynamicColor.collectAsState(initial = com.newoether.agora.data.DEFAULT_DYNAMIC_COLOR)
            val fontPreference by settingsManager.fontPreference.collectAsState(initial = "app_default")
            val customFontPath by settingsManager.customFontPath.collectAsState(initial = "")
            val appReduceMotion by settingsManager.reduceMotion.collectAsState(initial = false)

            val themeModeEnum = try { com.newoether.agora.ui.theme.ThemeMode.valueOf(themeMode) } catch (_: Exception) { com.newoether.agora.ui.theme.ThemeMode.FOLLOW_DEVICE }
            val colorSchemePreset = try { com.newoether.agora.ui.theme.ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { com.newoether.agora.ui.theme.ColorSchemePreset.FOREST }
            val schemeStyle = try { com.newoether.agora.ui.theme.SchemeStyle.valueOf(schemeStyleName) } catch (_: Exception) { com.newoether.agora.ui.theme.SchemeStyle.TONAL_SPOT }

            // The app theme is a fixed dark scheme; system bars always use light icons.
            SideEffect {
                val window = this@MainActivity.window
                val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = false
                insetsController.isAppearanceLightNavigationBars = false
            }

            AgoraTheme(
                themeMode = themeModeEnum,
                amoledEnabled = amoledEnabled,
                colorSchemePreset = colorSchemePreset,
                schemeStyle = schemeStyle,
                dynamicColor = dynamicColor,
                fontPreference = fontPreference,
                customFontPath = customFontPath
            ) {
                ProvideAgoraMotionPolicy(appReduceMotion = appReduceMotion) {
                val activity = LocalActivity.current

                if (needsErrorDialog) {
                    val databaseScope = rememberCoroutineScope()
                    var clearingDatabase by remember { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { activity?.finish() },
                        title = { Text(stringResource(R.string.database_incompatible), fontWeight = FontWeight.Bold) },
                        text = { Text(stringResource(R.string.database_incompatible_desc)) },
                        dismissButton = {
                            TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.quit)) }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (!clearingDatabase) {
                                        clearingDatabase = true
                                        databaseScope.launch {
                                            val cleared = agoraApplication.clearIncompatibleDatabase()
                                            if (cleared) {
                                                activity?.recreate()
                                            } else {
                                                clearingDatabase = false
                                            }
                                        }
                                    }
                                },
                                enabled = !clearingDatabase,
                            ) { Text(stringResource(R.string.clear_database)) }
                        }
                    )
                } else {
                    // The shell ViewModel only carries what the remote surface consumes;
                    // RemoteViewModel self-constructs inside the overlay.
                    val container = agoraApplication.requireContainer()
                    val viewModel: RemoteShellViewModel = viewModel { container.remoteShellViewModel() }

                    MainNavigation(viewModel = viewModel)
                }
            }
            }
            }
            startupReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.setInForeground(true)
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.setInForeground(false)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainNavigation(
    viewModel: RemoteShellViewModel,
) {
    val activity = LocalActivity.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    var mediaPreviewTarget by remember { mutableStateOf<MediaPreviewTarget?>(null) }
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val pdfPages by viewModel.mediaPreview.pdfPages.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarVersionState = remember { mutableIntStateOf(0) }
    var snackbarVersion by snackbarVersionState
    val accessibilityManager = LocalAccessibilityManager.current
    var remoteSnackbarOffset by remember { mutableStateOf(0.dp) }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Full-screen media viewer drops the snackbar to the bottom (nav-bar inset only);
    // in chat it floats above the bottom bar. The animateDpAsState below turns the change
    // into a rise/fall animation as the viewer opens/closes.
    val targetSnackbarPadding = if (mediaPreviewTarget != null) navBarPadding else remoteSnackbarOffset
    val snackbarBottomPadding by animateDpAsState(
        targetValue = targetSnackbarPadding,
        animationSpec = if (motionPolicy.allowSpatialTransitions) {
            spring(dampingRatio = 1.0f, stiffness = 1000f)
        } else {
            snap()
        },
        label = "snackbarPadding"
    )
    val focusManager = LocalFocusManager.current
    val topLevelPresentation = remember {
        TopLevelPresentationState(baseOwner = TopLevelPresentation.REMOTE)
    }
    val openMediaPreview: (List<String>, Int) -> Unit = { urls, index ->
        focusManager.clearFocus()
        mediaPreviewTarget = MediaPreviewTarget(urls, index)
        topLevelPresentation.present(TopLevelPresentation.MEDIA_PREVIEW)
    }

    LaunchedEffect(Unit) {
        var snackbarJob: Job? = null
        viewModel.snackbarMessage.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob?.cancel()
            snackbarJob = launch {
                try {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = if (event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        event.onAction?.invoke()
                    }
                } finally {
                    snackbarVersion++
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            com.newoether.agora.ui.remote.RemoteOverlay(
                visible = true, settings = viewModel.settings,
                hapticsActive = true,
                onDismiss = { activity?.finish() },
                onExitFinished = {},
                onMessage = viewModel::emitSnackbar,
                onSnackbarOffsetChanged = { remoteSnackbarOffset = it },
                onMediaClick = openMediaPreview,
            )

            // A dedicated dialog gives the media viewer its own window above source sheets.
            FullScreenMediaPreviewDialog(
                currentTarget = mediaPreviewTarget,
                currentPdfPages = pdfPages,
                currentPdfSelectedPages = emptySet(),
                currentPdfSelectionEnabled = false,
                currentPdfTogglePage = null,
                enter = fullScreenPreviewEnterTransition(motionPolicy.allowSpatialTransitions),
                exit = fullScreenPreviewExitTransition(motionPolicy.allowSpatialTransitions),
                onHidden = {
                    topLevelPresentation.release(TopLevelPresentation.MEDIA_PREVIEW)
                },
                onClose = { target ->
                    if (mediaPreviewTarget?.requestId != target.requestId) return@FullScreenMediaPreviewDialog
                    viewModel.mediaPreview.clear()
                    mediaPreviewTarget = null
                },
                onNavigate = { target, idx ->
                    if (mediaPreviewTarget?.requestId == target.requestId) {
                        mediaPreviewTarget = target.copy(index = idx)
                    }
                },
                onMessage = { viewModel.emitSnackbar(it) },
                hapticsEnabled = hapticsEnabled,
            )

            val current = snackbarHostState.currentSnackbarData
            var showing by remember { mutableStateOf(false) }
            var content by remember { mutableStateOf<SnackbarData?>(null) }

            LaunchedEffect(current, snackbarVersion) {
                if (current != null) {
                    if (showing) { showing = false; delay(200) }
                    content = current
                    showing = true
                } else {
                    showing = false
                    delay(400)
                    content = null
                }
            }

            LaunchedEffect(content, accessibilityManager) {
                val data = content ?: return@LaunchedEffect
                val timeoutMillis = snackbarTimeoutMillis(data.visuals, accessibilityManager)
                if (timeoutMillis != Long.MAX_VALUE) {
                    delay(timeoutMillis)
                    if (snackbarHostState.currentSnackbarData === data) {
                        data.dismiss()
                    }
                }
            }

            AnimatedVisibility(
                visible = showing,
                enter = if (motionPolicy.allowSpatialTransitions) {
                    fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.8f)
                } else {
                    fadeIn(tween(400))
                },
                exit = if (motionPolicy.allowSpatialTransitions) {
                    fadeOut(tween(400)) + scaleOut(tween(400), targetScale = 0.8f)
                } else {
                    fadeOut(tween(400))
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = snackbarBottomPadding + 2.dp)
            ) {
                content?.let { data ->
                    Snackbar(
                        modifier = Modifier.padding(horizontal = 12.dp).padding(vertical = 10.dp).shadow(6.dp, RoundedCornerShape(12.dp), clip = false),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionContentColor = MaterialTheme.colorScheme.primary,
                        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        dismissAction = @Composable {
                            Box(modifier = Modifier.padding(end = 8.dp)) {
                                IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(28.dp).clip(CircleShape)) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        action = data.visuals.actionLabel?.let { label ->
                            @Composable { TextButton(onClick = { data.performAction() }) { Text(label) } }
                        },
                        content = { Text(data.visuals.message) }
                    )
                }
            }
        }
    }
}
