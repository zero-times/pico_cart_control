@file:OptIn(ExperimentalLayoutApi::class)

package com.zerotimes.picocart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerotimes.picocart.ble.BleDeviceItem
import com.zerotimes.picocart.gamepad.GamepadController
import com.zerotimes.picocart.gamepad.GamepadInputReader
import com.zerotimes.picocart.gamepad.GamepadState
import com.zerotimes.picocart.protocol.PicoProtocol
import com.zerotimes.picocart.speech.LocalCachedSpeechEngine
import com.zerotimes.picocart.speech.MamboVoiceListener
import com.zerotimes.picocart.speech.SpeechEngine
import com.zerotimes.picocart.ui.PicoCartTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var speechEngine: SpeechEngine
    private var gamepadController: GamepadController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        speechEngine = LocalCachedSpeechEngine(this)
        setContent {
            PicoCartTheme {
                val viewModel: MainViewModel = viewModel()
                DisposableEffect(Unit) {
                    onDispose { viewModel.releaseDrive(sendStop = false) }
                }
                PicoCartApp(
                    viewModel = viewModel,
                    speak = speechEngine::speak,
                )
            }
        }
    }

    override fun onDestroy() {
        gamepadController = null
        speechEngine.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        gamepadController?.refreshConnectedDevices()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (GamepadInputReader.isGamepadSource(event.source) &&
            gamepadController?.onMotionEvent(event) == true
        ) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (GamepadInputReader.isGamepadSource(event.source) &&
            gamepadController?.onKeyEvent(event) == true
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun bindGamepadController(controller: GamepadController?) {
        gamepadController = controller
    }
}

@Composable
private fun PicoCartApp(
    viewModel: MainViewModel,
    speak: (String) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf("drive") }
    val mamboVoiceListener = remember(viewModel) {
        MamboVoiceListener(
            context = context,
            onWake = viewModel::onMamboWake,
            onCommand = viewModel::onMamboCommand,
            onStateChanged = viewModel::setMamboVoiceState,
            onErrorMessage = viewModel::onMamboVoiceError,
            onDebugMessage = viewModel::onMamboVoiceDebug,
        )
    }
    val gamepadController = remember(viewModel) {
        GamepadController(
            onStateChanged = viewModel::updateGamepadState,
            onLog = viewModel::onGamepadDebugLog,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            viewModel.initBluetooth()
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setMamboWakeEnabled(true)
        } else {
            viewModel.onMamboVoiceError("录音权限被拒绝")
        }
    }

    DisposableEffect(mamboVoiceListener) {
        onDispose { mamboVoiceListener.destroy() }
    }

    DisposableEffect(gamepadController) {
        val activity = context as? MainActivity
        activity?.bindGamepadController(gamepadController)
        gamepadController.refreshConnectedDevices()
        onDispose {
            activity?.bindGamepadController(null)
        }
    }

    LaunchedEffect(gamepadController) {
        while (true) {
            gamepadController.refreshConnectedDevices()
            delay(1_000L)
        }
    }

    LaunchedEffect(state.mamboWakeEnabled) {
        mamboVoiceListener.setEnabled(state.mamboWakeEnabled)
    }

    LaunchedEffect(state.mamboSpeechId) {
        if (state.mamboSpeechText.isNotBlank()) {
            mamboVoiceListener.suspendFor(estimateSpeechDurationMs(state.mamboSpeechText))
            speak(state.mamboSpeechText)
        }
    }

    PicoCartScreen(
        state = state,
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        onBluetooth = {
            if (viewModel.hasBlePermissions()) {
                viewModel.initBluetooth()
            } else {
                permissionLauncher.launch(viewModel.requiredBlePermissions())
            }
        },
        onScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onDisconnect = viewModel::disconnect,
        onConnect = { viewModel.connect(it) },
        onConnectIdentify = { viewModel.connect(it, identifyAfterConnect = true) },
        onStatus = viewModel::sendStatus,
        onParamQuery = viewModel::sendParamQuery,
        onAuto = viewModel::sendAuto,
        onManual = viewModel::sendManual,
        onStop = viewModel::sendStop,
        onTare = viewModel::sendTare,
        onIdentify = viewModel::sendIdentify,
        onToggleStream = viewModel::toggleStream,
        onPowerChange = viewModel::onPowerChange,
        onDrivePress = viewModel::holdDrive,
        onDriveRelease = { viewModel.releaseDrive() },
        onParamInput = viewModel::updateParam,
        onApplyParam = viewModel::applyParam,
        onCustomInput = viewModel::updateCustomCommand,
        onSendCustom = viewModel::sendCustom,
        onCopyLogs = { viewModel.copyLogs(context) },
        onSaveLogs = { viewModel.saveLogs(context) },
        onShareLogs = {
            val sendIntent = viewModel.shareLogs(context)
            context.startActivity(Intent.createChooser(sendIntent, "发送日志"))
        },
        onClearLogs = viewModel::clearLog,
        onSpeakStatus = { speak(viewModel.statusSpeechText()) },
        onAgentApiKeyInput = viewModel::updateAgentApiKey,
        onAgentInput = viewModel::updateAgentInput,
        onToggleAgentMovement = viewModel::toggleAgentMovementUnlocked,
        onRunAgent = viewModel::runAgentTurn,
        onToggleMamboWake = {
            if (!state.mamboWakeEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                viewModel.setMamboWakeEnabled(!state.mamboWakeEnabled)
            }
        },
    )
}

private fun estimateSpeechDurationMs(text: String): Long {
    val charCount = text.trim().length.coerceAtLeast(1)
    return (charCount * 240L + 1_200L).coerceIn(2_000L, 12_000L)
}

private val NeoShape = RoundedCornerShape(6.dp)
private val NeoSoftShape = RoundedCornerShape(8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicoCartScreen(
    state: CartUiState,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onBluetooth: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onConnect: (BleDeviceItem) -> Unit,
    onConnectIdentify: (BleDeviceItem) -> Unit,
    onStatus: () -> Unit,
    onParamQuery: () -> Unit,
    onAuto: () -> Unit,
    onManual: () -> Unit,
    onStop: () -> Unit,
    onTare: () -> Unit,
    onIdentify: () -> Unit,
    onToggleStream: () -> Unit,
    onPowerChange: (Float) -> Unit,
    onDrivePress: (String) -> Unit,
    onDriveRelease: () -> Unit,
    onParamInput: (String, String) -> Unit,
    onApplyParam: (String) -> Unit,
    onCustomInput: (String) -> Unit,
    onSendCustom: () -> Unit,
    onCopyLogs: () -> Unit,
    onSaveLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onSpeakStatus: () -> Unit,
    onAgentApiKeyInput: (String) -> Unit,
    onAgentInput: (String) -> Unit,
    onToggleAgentMovement: () -> Unit,
    onRunAgent: () -> Unit,
    onToggleMamboWake: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when (selectedTab) {
                                    "voice" -> "曼波语音核心"
                                    "status" -> "健康状态"
                                    "debug" -> "工程调试"
                                    "settings" -> "设置"
                                    else -> "小车驾驶"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (state.connected) state.deviceName else if (state.adapterReady) "蓝牙已就绪" else "蓝牙未初始化",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        StatusBadge(connected = state.connected, heartbeatOk = state.picoHeartbeatOk)
                        IconButton(
                            onClick = onStop,
                            enabled = state.cartReady,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "急停")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            },
            bottomBar = {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = selectedTab == "drive",
                        onClick = { onSelectTab("drive") },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("驾驶") },
                        colors = navColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == "voice",
                        onClick = { onSelectTab("voice") },
                        icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                        label = { Text("语音") },
                        colors = navColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == "status",
                        onClick = { onSelectTab("status") },
                        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                        label = { Text("状态") },
                        colors = navColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == "debug",
                        onClick = { onSelectTab("debug") },
                        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                        label = { Text("调试") },
                        colors = navColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == "settings",
                        onClick = { onSelectTab("settings") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") },
                        colors = navColors,
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!state.cartReady) {
                    item { PicoConnectionAlert(state) }
                }
                when (selectedTab) {
                    "voice" -> {
                        item { VoiceCoreSection(state = state) }
                        item {
                            AgentSection(
                                state = state,
                                onAgentInput = onAgentInput,
                                onRunAgent = onRunAgent,
                            )
                        }
                    }
                    "status" -> {
                        item { StatusDashboardSection(state = state, onSpeakStatus = onSpeakStatus) }
                        item { StatusSection(state = state, onSpeakStatus = onSpeakStatus) }
                    }
                    "debug" -> {
                        item {
                            Toolbar(
                                state = state,
                                onBluetooth = onBluetooth,
                                onScan = onScan,
                                onStopScan = onStopScan,
                                onDisconnect = onDisconnect,
                            )
                        }
                        if (!state.connected) {
                            item {
                                DeviceSection(
                                    devices = state.devices,
                                    scanning = state.scanning,
                                    onConnect = onConnect,
                                    onConnectIdentify = onConnectIdentify,
                                )
                            }
                        }
                        item {
                            DebugActionSection(
                                state = state,
                                onStop = onStop,
                                onTare = onTare,
                                onIdentify = onIdentify,
                                onToggleStream = onToggleStream,
                                onStatus = onStatus,
                                onAuto = onAuto,
                                onManual = onManual,
                            )
                        }
                        item { GamepadDebugPanel(gamepad = state.gamepadState) }
                        item {
                            CommandLogSection(
                                state = state,
                                onCustomInput = onCustomInput,
                                onSendCustom = onSendCustom,
                            )
                        }
                    }
                    "settings" -> {
                        item {
                            AssistantSettingsSection(
                                state = state,
                                onAgentApiKeyInput = onAgentApiKeyInput,
                                onToggleAgentMovement = onToggleAgentMovement,
                                onToggleMamboWake = onToggleMamboWake,
                            )
                        }
                        item {
                            ParamsSection(
                                state = state,
                                onParamQuery = onParamQuery,
                                onParamInput = onParamInput,
                                onApplyParam = onApplyParam,
                            )
                        }
                        item {
                            LogSettingsSection(
                                state = state,
                                onCopyLogs = onCopyLogs,
                                onSaveLogs = onSaveLogs,
                                onShareLogs = onShareLogs,
                                onClearLogs = onClearLogs,
                            )
                        }
                    }
                    else -> {
                        item { DriveStatusStrip(state = state) }
                        if (!state.connected) {
                            item {
                                Toolbar(
                                    state = state,
                                    onBluetooth = onBluetooth,
                                    onScan = onScan,
                                    onStopScan = onStopScan,
                                    onDisconnect = onDisconnect,
                                )
                            }
                            item {
                                DeviceSection(
                                    devices = state.devices,
                                    scanning = state.scanning,
                                    onConnect = onConnect,
                                    onConnectIdentify = onConnectIdentify,
                                )
                            }
                        } else {
                            item {
                                DriveCockpitSection(
                                    state = state,
                                    onStop = onStop,
                                    onPowerChange = onPowerChange,
                                    onDrivePress = onDrivePress,
                                    onDriveRelease = onDriveRelease,
                                )
                            }
                            item {
                                RecentCommandSection(
                                    state = state,
                                    onStatus = onStatus,
                                    onManual = onManual,
                                    onAuto = onAuto,
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(12.dp)) }
            }
        }
        MamboVoiceOverlay(state = state)
    }
}

@Composable
private fun MamboVoiceOverlay(state: CartUiState) {
    AnimatedVisibility(
        visible = state.mamboOverlayVisible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(animationSpec = tween(180)) + scaleIn(
            initialScale = 0.96f,
            animationSpec = tween(180),
        ),
        exit = fadeOut(animationSpec = tween(180)) + scaleOut(
            targetScale = 0.96f,
            animationSpec = tween(180),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f))
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 88.dp),
                shape = NeoSoftShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MamboVoiceOrb(
                        active = state.mamboOverlayStatus == "听指令",
                        modifier = Modifier.size(150.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = state.mamboOverlayStatus.ifBlank { state.mamboVoiceStatus },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.mamboOverlayCaption.ifBlank { "..." },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MamboVoiceOrb(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = if (active) colors.secondaryContainer else colors.surfaceVariant,
        ) {}
        Surface(
            modifier = Modifier.fillMaxSize(0.72f),
            shape = CircleShape,
            color = if (active) colors.secondary else colors.outlineVariant,
        ) {}
        Surface(
            modifier = Modifier.fillMaxSize(0.44f),
            shape = CircleShape,
            color = colors.surface,
            border = BorderStroke(1.dp, if (active) colors.secondary else colors.outline),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (active) colors.secondary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: CartUiState,
    onBluetooth: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onBluetooth,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
            enabled = !state.connecting,
            shape = NeoShape,
        ) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null)
            ButtonGap()
            Text("蓝牙")
        }
        FilledTonalButton(
            onClick = onScan,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
            enabled = state.adapterReady && !state.scanning,
            shape = NeoShape,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            ButtonGap()
            Text("扫描")
        }
        FilledTonalButton(
            onClick = onStopScan,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
            enabled = state.scanning,
            shape = NeoShape,
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            ButtonGap()
            Text("停扫")
        }
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
            enabled = state.connected,
            shape = NeoShape,
        ) {
            Icon(Icons.Filled.LinkOff, contentDescription = null)
            ButtonGap()
            Text("断开")
        }
    }
    if (state.connecting) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusBadge(connected: Boolean, heartbeatOk: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HeaderStatusLine("BLE", connected)
        HeaderStatusLine("PICO", heartbeatOk, warning = connected && !heartbeatOk)
    }
}

@Composable
private fun HeaderStatusLine(label: String, ok: Boolean, warning: Boolean = false) {
    val color = when {
        ok -> MaterialTheme.colorScheme.secondary
        warning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PicoConnectionAlert(state: CartUiState) {
    val timedOut = state.connected && state.picoHeartbeatStatus == "Pico 心跳超时"
    val message = when {
        !state.connected -> "请初始化蓝牙并扫描连接 Pico。控制命令保持锁定。"
        timedOut -> "心跳已超时。请检查供电与距离，恢复心跳后控制会自动解锁。"
        else -> "正在校验 Pico 心跳，确认设备安全后控制会自动解锁。"
    }
    Surface(
        color = if (timedOut) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (timedOut) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = NeoSoftShape,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
            Column {
                Text(
                    if (timedOut) "Pico 心跳中断" else "恢复控制连接",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoSoftShape,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                trailing?.invoke()
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            content()
        }
    }
}

private enum class StatusTone {
    Accent,
    Normal,
    Warning,
    Danger,
    Muted,
}

@Composable
private fun StatusPill(
    label: String,
    value: String,
    icon: ImageVector,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        StatusTone.Accent -> colors.primaryContainer to colors.onPrimaryContainer
        StatusTone.Normal -> colors.secondaryContainer to colors.onSecondaryContainer
        StatusTone.Warning -> colors.tertiaryContainer to colors.onTertiaryContainer
        StatusTone.Danger -> colors.errorContainer to colors.onErrorContainer
        StatusTone.Muted -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(
        modifier = modifier.sizeIn(minHeight = 48.dp),
        shape = NeoShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(
                    value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DriveStatusStrip(state: CartUiState) {
    val estop = state.status.isEstopActive() || state.status.hasUnsafeFault()
    val controlLocked = !state.cartReady
    Section(title = "系统就绪状态") {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPill(
                label = "蓝牙",
                value = if (state.connected) "已连接" else if (state.adapterReady) "待连接" else "未就绪",
                icon = Icons.Filled.Bluetooth,
                tone = if (state.connected) StatusTone.Normal else StatusTone.Warning,
            )
            StatusPill(
                label = "心跳",
                value = state.picoHeartbeatStatus,
                icon = if (state.picoHeartbeatOk) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                tone = if (state.picoHeartbeatOk) StatusTone.Normal else StatusTone.Warning,
            )
            StatusPill(
                label = "安全",
                value = when {
                    controlLocked -> "控制锁定"
                    estop -> "急停 / 异常"
                    else -> "安全可控"
                },
                icon = if (controlLocked || estop) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                tone = when {
                    estop -> StatusTone.Danger
                    controlLocked -> StatusTone.Warning
                    else -> StatusTone.Normal
                },
            )
            StatusPill(
                label = "电池",
                value = state.status.displayValue("vbat", "未知"),
                icon = Icons.Filled.CheckCircle,
                tone = StatusTone.Accent,
            )
            StatusPill(
                label = "模式",
                value = state.status.displayMode(if (state.connected) "手动" else "离线"),
                icon = Icons.Filled.PlayArrow,
                tone = if (state.connected) StatusTone.Accent else StatusTone.Muted,
            )
            StatusPill(
                label = "手柄",
                value = if (state.gamepadState.connected) "已连接" else "未检测",
                icon = if (state.gamepadState.connected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                tone = if (state.gamepadState.connected) StatusTone.Normal else StatusTone.Muted,
            )
        }
    }
}

@Composable
private fun DriveCockpitSection(
    state: CartUiState,
    onStop: () -> Unit,
    onPowerChange: (Float) -> Unit,
    onDrivePress: (String) -> Unit,
    onDriveRelease: () -> Unit,
) {
    val estop = state.status.isEstopActive() || state.status.hasUnsafeFault()
    val enabled = state.cartReady && !estop
    val speedLevel = when {
        state.manualPower < 10f -> 1
        state.manualPower < 16f -> 2
        state.manualPower < 22f -> 3
        else -> 4
    }
    Section(
        title = "方向控制",
        trailing = {
            Text(
                "D$speedLevel  ${state.manualPower.roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NeoShape,
            color = when {
                estop -> MaterialTheme.colorScheme.errorContainer
                enabled -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                estop -> MaterialTheme.colorScheme.onErrorContainer
                enabled -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (enabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    when {
                        estop -> "安全锁已触发，方向控制已禁用"
                        enabled -> "方向控制已启用，松手自动停车"
                        else -> "等待 Pico 就绪，方向控制保持锁定"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        DrivePad(
            enabled = enabled,
            onDrivePress = onDrivePress,
            onDriveRelease = onDriveRelease,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("手动功率", style = MaterialTheme.typography.bodyMedium)
            Text("${state.manualPower.roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = state.manualPower,
            onValueChange = onPowerChange,
            valueRange = 5f..25f,
            steps = 19,
            enabled = state.cartReady,
        )
        Text(
            "功率范围 5–25%，建议在架空车轮时逐级测试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = state.cartReady,
            shape = NeoShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            ButtonGap()
            Text("急停 / Stop", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RecentCommandSection(
    state: CartUiState,
    onStatus: () -> Unit,
    onManual: () -> Unit,
    onAuto: () -> Unit,
) {
    Section(
        title = "最近指令",
        trailing = {
            TextButton(onClick = onStatus, enabled = state.cartReady) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("刷新")
            }
        },
    ) {
        Text(
            state.logs.lastOrNull()?.text ?: "暂无指令记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onManual,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                enabled = state.cartReady,
                shape = NeoShape,
            ) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("手动")
            }
            FilledTonalButton(
                onClick = onAuto,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                enabled = state.cartReady,
                shape = NeoShape,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("牵引绳")
            }
        }
    }
}

@Composable
private fun GamepadDebugPanel(gamepad: GamepadState) {
    Section(
        title = "Gamepad Debug",
        trailing = {
            Text(
                if (gamepad.connected) "device=${gamepad.deviceId ?: "-"}" else "未检测到",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        },
    ) {
        Text(
            gamepad.lastEvent,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            gamepad.axisRows.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        AxisCell(label, value, Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Buttons", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gamepad.buttonRows.forEach { (label, pressed) ->
                FilterChip(
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                    selected = pressed,
                    onClick = {},
                    label = { Text(label, fontFamily = FontFamily.Monospace) },
                    leadingIcon = if (pressed) {
                        {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun AxisCell(label: String, value: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(74.dp),
        shape = NeoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value.formatAxis(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun VoiceCoreSection(state: CartUiState) {
    val normalized = state.mamboLastTranscript.normalizeMamboTranscript()
    val toolItems = state.agentMessages.filter { it.role == "tool" }.takeLast(5)
    Section(title = "曼波语音核心") {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPill(
                label = "唤醒",
                value = if (state.mamboWakeEnabled) "等待“曼波”" else "未开启",
                icon = if (state.mamboWakeEnabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                tone = if (state.mamboWakeEnabled) StatusTone.Normal else StatusTone.Muted,
            )
            StatusPill(
                label = "识别",
                value = if (state.mamboListening) "正在聆听" else state.mamboVoiceStatus,
                icon = if (state.mamboListening) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.CheckCircle,
                tone = if (state.mamboListening) StatusTone.Normal else StatusTone.Muted,
            )
            StatusPill(
                label = "AI 执行",
                value = if (state.agentRunning) "处理中" else "待命",
                icon = if (state.agentRunning) Icons.Filled.PlayArrow else Icons.Filled.CheckCircle,
                tone = if (state.agentRunning) StatusTone.Accent else StatusTone.Muted,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MamboVoiceOrb(
                active = state.mamboListening || state.agentRunning,
                modifier = Modifier.size(88.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.mamboOverlayStatus.ifBlank { state.mamboVoiceStatus },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.mamboListening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.mamboOverlayCaption.ifBlank {
                        if (state.mamboWakeEnabled) "说出“曼波”后下达指令" else "请在设置中开启语音唤醒"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                WaveformView(active = state.mamboListening || state.agentRunning)
            }
        }
        Spacer(Modifier.height(14.dp))
        VoiceSignalRow("识别文本", state.mamboLastTranscript.ifBlank { "等待语音输入" }, emphasized = true)
        VoiceSignalRow("归一化", normalized.ifBlank { "暂无" })
        VoiceSignalRow(
            "AI 执行",
            when {
                state.agentRunning -> "正在解析并执行指令"
                state.agentMessages.any { it.role == "assistant" } -> "最近一次执行已完成"
                else -> "等待已识别的控制意图"
            },
        )
        Spacer(Modifier.height(12.dp))
        ToolCallTimeline(items = toolItems)
    }
}

@Composable
private fun WaveformView(active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bars = if (active) {
            listOf(0.32f, 0.64f, 0.92f, 0.52f, 0.78f, 0.40f, 0.68f)
        } else {
            listOf(0.22f, 0.28f, 0.24f, 0.30f, 0.22f, 0.26f, 0.22f)
        }
        bars.forEach { level ->
            Surface(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .width(4.dp)
                    .height(6.dp + (20.dp * level)),
                shape = NeoShape,
                color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
            ) {}
        }
    }
}

@Composable
private fun VoiceSignalRow(label: String, value: String, emphasized: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShape,
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                label,
                modifier = Modifier.width(70.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToolCallTimeline(items: List<AgentChatEntry>) {
    Text("Tool Timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (items.isEmpty()) {
        Text(
            "暂无工具调用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { entry ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NeoShape,
                color = if (entry.ok == false) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (entry.ok == false) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (entry.ok == false) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        entry.text,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDashboardSection(
    state: CartUiState,
    onSpeakStatus: () -> Unit,
) {
    val estop = state.status.isEstopActive() || state.status.hasUnsafeFault()
    val controlLocked = !state.cartReady
    Section(
        title = "健康卡片",
        trailing = {
            AssistChip(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                onClick = onSpeakStatus,
                label = { Text("朗读") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        },
    ) {
        val cards = listOf(
            HealthCardData("电池", state.status.displayValue("vbat", "未知"), state.status.displayValue("battery", "等待数据"), StatusTone.Accent),
            HealthCardData("蓝牙", if (state.connected) "已连接" else "未连接", state.deviceName.ifBlank { "Pico BLE" }, if (state.connected) StatusTone.Normal else StatusTone.Warning),
            HealthCardData("Pico", state.picoHeartbeatStatus, state.status.displayValue("sensor", "等待心跳"), if (state.picoHeartbeatOk) StatusTone.Normal else StatusTone.Warning),
            HealthCardData(
                "安全",
                when {
                    controlLocked -> "控制锁定"
                    estop -> "异常"
                    else -> "正常"
                },
                if (controlLocked) "等待连接与心跳" else "estop=${state.status.displayValue("estop", "-")}",
                when {
                    estop -> StatusTone.Danger
                    controlLocked -> StatusTone.Warning
                    else -> StatusTone.Normal
                },
            ),
            HealthCardData("左轮", state.status.displayValue("pwml", "0"), "sensor=${state.status.displayValue("l", "0")}", StatusTone.Accent),
            HealthCardData("右轮", state.status.displayValue("pwmr", "0"), "sensor=${state.status.displayValue("r", "0")}", StatusTone.Accent),
            HealthCardData(
                "DeepSeek",
                when {
                    state.agentRunning -> "思考中"
                    state.agentApiKey.isBlank() -> "未配置"
                    else -> "就绪"
                },
                if (state.agentApiKey.isBlank()) "请在设置中填写 API Key" else "API Key 已配置",
                if (state.agentApiKey.isBlank()) StatusTone.Warning else StatusTone.Normal,
            ),
            HealthCardData("语音", state.mamboVoiceStatus, if (state.mamboWakeEnabled) "唤醒开启" else "唤醒关闭", if (state.mamboWakeEnabled) StatusTone.Normal else StatusTone.Muted),
            HealthCardData("手柄", if (state.gamepadState.connected) "已连接" else "未检测", state.gamepadState.deviceName ?: "Xbox HID", if (state.gamepadState.connected) StatusTone.Normal else StatusTone.Muted),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { card ->
                        HealthCard(card, Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class HealthCardData(
    val title: String,
    val value: String,
    val detail: String,
    val tone: StatusTone,
)

@Composable
private fun HealthCard(data: HealthCardData, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = when (data.tone) {
        StatusTone.Accent -> colors.primary
        StatusTone.Normal -> colors.secondary
        StatusTone.Warning -> colors.tertiary
        StatusTone.Danger -> colors.error
        StatusTone.Muted -> colors.outline
    }
    Surface(
        modifier = modifier.sizeIn(minHeight = 104.dp),
        shape = NeoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(data.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                data.value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                data.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DebugActionSection(
    state: CartUiState,
    onStop: () -> Unit,
    onTare: () -> Unit,
    onIdentify: () -> Unit,
    onToggleStream: () -> Unit,
    onStatus: () -> Unit,
    onAuto: () -> Unit,
    onManual: () -> Unit,
) {
    Section(
        title = "工程操作",
        trailing = {
            FilterChip(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                selected = state.streaming,
                onClick = onToggleStream,
                enabled = state.cartReady,
                label = { Text(if (state.streaming) "stream on" else "stream off") },
            )
        },
    ) {
        Text(
            "危险动作保留在调试页，执行前确认小车已架空或有足够空间。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("Stop", Icons.Filled.Stop, onStop, enabled = state.cartReady, danger = true)
            ActionButton("读取状态", Icons.Filled.Refresh, onStatus, enabled = state.cartReady)
            ActionButton("手动", Icons.Filled.Bluetooth, onManual, enabled = state.cartReady)
            ActionButton("牵引绳", Icons.Filled.PlayArrow, onAuto, enabled = state.cartReady)
            ActionButton("HX711 归零", Icons.Filled.Refresh, onTare, enabled = state.cartReady)
            ActionButton("闪灯识别", Icons.Filled.Warning, onIdentify, enabled = state.cartReady)
            ActionButton(if (state.streaming) "关流" else "开流", Icons.Filled.PlayArrow, onToggleStream, enabled = state.cartReady)
        }
    }
}

@Composable
private fun DeviceSection(
    devices: List<BleDeviceItem>,
    scanning: Boolean,
    onConnect: (BleDeviceItem) -> Unit,
    onConnectIdentify: (BleDeviceItem) -> Unit,
) {
    Section(
        title = "设备",
        trailing = {
            Text(
                if (scanning) "扫描中" else "${devices.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        if (devices.isEmpty()) {
            Text(
                text = if (scanning) "正在查找附近 BLE 设备" else "点击扫描查找 Pico-BLE 设备",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        onConnect = { onConnect(device) },
                        onConnectIdentify = { onConnectIdentify(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BleDeviceItem,
    onConnect: () -> Unit,
    onConnectIdentify: () -> Unit,
) {
    Surface(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${device.rssi} dBm", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onConnectIdentify) {
                    Text("连接闪灯")
                }
            }
        }
    }
}

@Composable
private fun AssistantSettingsSection(
    state: CartUiState,
    onAgentApiKeyInput: (String) -> Unit,
    onToggleAgentMovement: () -> Unit,
    onToggleMamboWake: () -> Unit,
) {
    Section(title = "助手设置") {
        OutlinedTextField(
            value = state.agentApiKey,
            onValueChange = onAgentApiKeyInput,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("DeepSeek API Key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(12.dp))
        SettingSwitchRow(
            title = "曼波语音唤醒",
            subtitle = state.mamboVoiceStatus,
            checked = state.mamboWakeEnabled,
            onToggle = onToggleMamboWake,
            icon = Icons.AutoMirrored.Filled.VolumeUp,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingSwitchRow(
            title = "解锁移动工具",
            subtitle = if (state.agentMovementUnlocked) "DeepSeek 可请求移动和转向工具" else "移动和转向工具会被本地安全锁拦截",
            checked = state.agentMovementUnlocked,
            onToggle = onToggleAgentMovement,
            icon = if (state.agentMovementUnlocked) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        )
        if (state.mamboLastTranscript.isNotBlank()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "最近语音：${state.mamboLastTranscript}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun AgentSection(
    state: CartUiState,
    onAgentInput: (String) -> Unit,
    onRunAgent: () -> Unit,
) {
    Section(
        title = "AI 助手",
        trailing = { Text(if (state.agentRunning) "处理中" else "就绪", style = MaterialTheme.typography.bodySmall) },
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                onClick = {},
                label = { Text(if (state.agentApiKey.isBlank()) "未配置 API Key" else "API Key 已配置") },
                leadingIcon = {
                    Icon(
                        if (state.agentApiKey.isBlank()) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            AssistChip(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                onClick = {},
                label = { Text(if (state.agentMovementUnlocked) "移动已解锁" else "移动锁定") },
                leadingIcon = {
                    Icon(
                        if (state.agentMovementUnlocked) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            AssistChip(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                onClick = {},
                label = { Text(state.mamboVoiceStatus) },
                leadingIcon = {
                    Icon(
                        if (state.mamboListening) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
        if (state.mamboLastTranscript.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "最近听到：${state.mamboLastTranscript}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            shape = NeoShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.agentMessages.forEach { entry ->
                    AgentMessageLine(entry)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.agentInput,
                onValueChange = onAgentInput,
                modifier = Modifier.weight(1f),
                enabled = !state.agentRunning,
                singleLine = false,
                maxLines = 3,
                placeholder = { Text("读取状态，然后低速测试左转") },
            )
            Button(
                onClick = onRunAgent,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                enabled = !state.agentRunning && state.agentInput.isNotBlank(),
                shape = NeoShape,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text(if (state.agentRunning) "运行中" else "发送")
            }
        }
    }
}

@Composable
private fun AgentMessageLine(entry: AgentChatEntry) {
    val containerColor = when (entry.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "tool" -> if (entry.ok == false) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (entry.role) {
        "user" -> MaterialTheme.colorScheme.onPrimaryContainer
        "tool" -> if (entry.ok == false) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        }
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (entry.role) {
        "user" -> "用户"
        "tool" -> "工具"
        else -> "助手"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Text(entry.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusSection(
    state: CartUiState,
    onSpeakStatus: () -> Unit,
) {
    Section(
        title = "状态",
        trailing = {
            AssistChip(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                onClick = onSpeakStatus,
                label = { Text("朗读") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        },
    ) {
        Text(
            "${state.status["mode"]} / ${state.status["sensor"]}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        MetricGrid(state.status)
        Spacer(Modifier.height(10.dp))
        Text(
            "estop=${state.status["estop"]} unsafe=${state.status["unsafe"]} err=${state.status["err"]}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MetricGrid(status: Map<String, String>) {
    val metrics = listOf(
        "总拉力" to status["total"].orEmpty(),
        "转向" to status["steer"].orEmpty(),
        "左 PWM" to status["pwml"].orEmpty(),
        "右 PWM" to status["pwmr"].orEmpty(),
        "左传感" to status["l"].orEmpty(),
        "右传感" to status["r"].orEmpty(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { metric ->
                    MetricCell(
                        label = metric.first,
                        value = metric.second,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(76.dp),
        shape = NeoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minHeight = 48.dp),
        enabled = enabled,
        shape = NeoShape,
        colors = if (danger) {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        ButtonGap()
        Text(label)
    }
}

@Composable
private fun DrivePad(
    enabled: Boolean = true,
    onDrivePress: (String) -> Unit,
    onDriveRelease: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DriveButton(
            label = "前",
            icon = Icons.Filled.KeyboardArrowUp,
            wide = true,
            enabled = enabled,
            onPress = { onDrivePress("forward") },
            onRelease = onDriveRelease,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveButton(
                label = "左",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                enabled = enabled,
                onPress = { onDrivePress("left") },
                onRelease = onDriveRelease,
            )
            ElevatedButton(
                onClick = onDriveRelease,
                modifier = Modifier.sizeIn(minWidth = 80.dp, minHeight = 56.dp),
                enabled = enabled,
                shape = NeoShape,
                colors = androidx.compose.material3.ButtonDefaults.elevatedButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("停")
            }
            DriveButton(
                label = "右",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = enabled,
                onPress = { onDrivePress("right") },
                onRelease = onDriveRelease,
            )
        }
        DriveButton(
            label = "后",
            icon = Icons.Filled.KeyboardArrowDown,
            wide = true,
            enabled = enabled,
            onPress = { onDrivePress("backward") },
            onRelease = onDriveRelease,
        )
    }
}

@Composable
private fun DriveButton(
    label: String,
    icon: ImageVector,
    wide: Boolean = false,
    enabled: Boolean = true,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .sizeIn(minWidth = if (wide) 168.dp else 80.dp, minHeight = 56.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            onRelease()
                        }
                    },
                )
            },
        shape = NeoShape,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            ButtonGap()
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ParamsSection(
    state: CartUiState,
    onParamQuery: () -> Unit,
    onParamInput: (String, String) -> Unit,
    onApplyParam: (String) -> Unit,
) {
    Section(
        title = "参数",
        trailing = {
            TextButton(onClick = onParamQuery, enabled = state.cartReady) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("刷新")
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.paramRows.forEach { (key, value) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NeoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            PicoProtocol.parameterLabel(key),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (PicoProtocol.parameterLabel(key) != key) {
                            Text(
                                key,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { onParamInput(key, it) },
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            OutlinedButton(
                                onClick = { onApplyParam(key) },
                                modifier = Modifier.sizeIn(minHeight = 48.dp),
                                enabled = state.cartReady,
                                shape = NeoShape,
                            ) {
                                Text("写入")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandLogSection(
    state: CartUiState,
    onCustomInput: (String) -> Unit,
    onSendCustom: () -> Unit,
) {
    Section(
        title = "命令",
        trailing = {
            Text("${state.logs.size} 条", style = MaterialTheme.typography.bodySmall)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.customCommand,
                onValueChange = onCustomInput,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("status") },
            )
            Button(
                onClick = onSendCustom,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                enabled = state.cartReady,
                shape = NeoShape,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("发送")
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            shape = NeoShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(10.dp),
            ) {
                state.logs.forEach { entry ->
                    Text(
                        entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogSettingsSection(
    state: CartUiState,
    onCopyLogs: () -> Unit,
    onSaveLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Section(
        title = "日志",
        trailing = {
            Text("${state.logs.size} 条", style = MaterialTheme.typography.bodySmall)
        },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogButton("发送", Icons.Filled.Share, onShareLogs)
            LogButton("保存", Icons.Filled.Save, onSaveLogs)
            LogButton("复制", Icons.Filled.ContentCopy, onCopyLogs)
            LogButton("清空", Icons.Filled.Delete, onClearLogs)
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            shape = NeoShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(10.dp),
            ) {
                state.logs.forEach { entry ->
                    Text(
                        entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minHeight = 48.dp),
        shape = NeoShape,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        ButtonGap()
        Text(label)
    }
}

@Composable
private fun ButtonGap() {
    Spacer(Modifier.width(6.dp))
}

private fun Map<String, String>.displayValue(key: String, fallback: String): String {
    return this[key]?.takeIf { it.isNotBlank() && it != "-" } ?: fallback
}

private fun Map<String, String>.isEstopActive(): Boolean {
    return this["estop"]?.trim()?.lowercase(Locale.US) in setOf("0", "true", "active", "pressed")
}

private fun Map<String, String>.hasUnsafeFault(): Boolean {
    val normalized = this["unsafe"]?.trim()?.lowercase(Locale.US).orEmpty()
    return normalized.isNotBlank() && normalized !in setOf(
        "-", "0", "false", "ok", "none", "manual_timeout",
    )
}

private fun Map<String, String>.displayMode(fallback: String): String {
    return when (this["mode"]?.trim()?.lowercase(Locale.US)) {
        "tow", "auto" -> "牵引绳"
        "manual" -> "手动"
        "idle" -> "待命"
        else -> displayValue("mode", fallback)
    }
}

private fun Float.formatAxis(): String {
    return String.format(Locale.US, "%.2f", this)
}

private fun String.normalizeMamboTranscript(): String {
    if (isBlank()) return ""
    return replace(Regex("漫步|慢不|兰博|蓝波|曼播|mambo", RegexOption.IGNORE_CASE), "曼波")
        .trim()
}
