@file:OptIn(ExperimentalLayoutApi::class)

package com.zerotimes.picocart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.zerotimes.picocart.speech.LocalCachedSpeechEngine
import com.zerotimes.picocart.speech.MamboVoiceListener
import com.zerotimes.picocart.speech.SpeechEngine
import com.zerotimes.picocart.ui.PicoCartTheme
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var speechEngine: SpeechEngine

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
        speechEngine.shutdown()
        super.onDestroy()
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
                                    else -> "赛博驾驶舱"
                                },
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
                        IconButton(
                            onClick = onStop,
                            enabled = state.connected,
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "急停")
                        }
                        StatusBadge(connected = state.connected)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == "drive",
                        onClick = { onSelectTab("drive") },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("驾驶") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == "voice",
                        onClick = { onSelectTab("voice") },
                        icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                        label = { Text("语音") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == "status",
                        onClick = { onSelectTab("status") },
                        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                        label = { Text("状态") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == "debug",
                        onClick = { onSelectTab("debug") },
                        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                        label = { Text("调试") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == "settings",
                        onClick = { onSelectTab("settings") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") },
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                shape = RoundedCornerShape(8.dp),
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
    val transition = rememberInfiniteTransition(label = "mambo-voice-orb")
    val pulse by transition.animateFloat(
        initialValue = if (active) 0.92f else 0.98f,
        targetValue = if (active) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 820 else 1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val drift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 1_200 else 1_900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )

    Canvas(modifier = modifier) {
        val diameter = min(size.width, size.height)
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = diameter * 0.34f
        drawCircle(
            color = colors.primary.copy(alpha = 0.18f),
            radius = diameter * 0.48f * pulse,
            center = center,
        )
        drawCircle(
            color = colors.tertiary.copy(alpha = 0.34f),
            radius = baseRadius * (1.05f + drift * 0.04f),
            center = center + Offset(diameter * 0.08f * drift, -diameter * 0.05f),
        )
        drawCircle(
            color = colors.secondary.copy(alpha = 0.30f),
            radius = baseRadius * (0.92f - drift * 0.04f),
            center = center + Offset(-diameter * 0.11f * drift, diameter * 0.08f),
        )
        drawCircle(
            color = colors.primary.copy(alpha = 0.42f),
            radius = baseRadius * 0.74f * pulse,
            center = center + Offset(diameter * 0.05f, diameter * 0.03f * drift),
        )
        drawCircle(
            color = colors.surface.copy(alpha = 0.26f),
            radius = baseRadius * 0.32f,
            center = center + Offset(-diameter * 0.08f, -diameter * 0.10f),
        )
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
        Button(onClick = onBluetooth, enabled = !state.connecting) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null)
            ButtonGap()
            Text("蓝牙")
        }
        FilledTonalButton(onClick = onScan, enabled = state.adapterReady && !state.scanning) {
            Icon(Icons.Filled.Search, contentDescription = null)
            ButtonGap()
            Text("扫描")
        }
        FilledTonalButton(onClick = onStopScan, enabled = state.scanning) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            ButtonGap()
            Text("停扫")
        }
        OutlinedButton(onClick = onDisconnect, enabled = state.connected) {
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
private fun StatusBadge(connected: Boolean) {
    val container = if (connected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (connected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (connected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (connected) "已连接" else "未连接", style = MaterialTheme.typography.labelMedium)
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
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
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
        modifier = modifier.sizeIn(minHeight = 44.dp),
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, content.copy(alpha = 0.22f)),
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
    val estop = state.status.isTruthy("estop") || state.status.isTruthy("unsafe")
    Section(title = "顶部状态") {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                label = "蓝牙",
                value = if (state.connected) "已连接" else if (state.adapterReady) "待连接" else "未就绪",
                icon = Icons.Filled.Bluetooth,
                tone = if (state.connected) StatusTone.Normal else StatusTone.Warning,
            )
            StatusPill(
                label = "电池",
                value = state.status.displayValue("vbat", "未知"),
                icon = Icons.Filled.CheckCircle,
                tone = StatusTone.Accent,
            )
            StatusPill(
                label = "模式",
                value = state.status.displayValue("mode", if (state.connected) "手动" else "离线"),
                icon = Icons.Filled.PlayArrow,
                tone = if (state.connected) StatusTone.Accent else StatusTone.Muted,
            )
            StatusPill(
                label = "安全",
                value = if (estop) "急停/异常" else "安全",
                icon = if (estop) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                tone = if (estop) StatusTone.Danger else StatusTone.Normal,
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
    val estop = state.status.isTruthy("estop") || state.status.isTruthy("unsafe")
    val enabled = state.connected && !estop
    val speedLevel = when {
        state.manualPower < 10f -> 1
        state.manualPower < 16f -> 2
        state.manualPower < 22f -> 3
        else -> 4
    }
    Section(
        title = "驾驶舱",
        trailing = {
            Text(
                "D$speedLevel / ${state.manualPower.roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        },
    ) {
        CyberSteeringWheel(
            gear = if (estop) "P" else if (enabled) "D$speedLevel" else "N",
            steer = state.status.displayValue("steer", "0"),
            active = enabled,
            danger = estop,
        )
        Spacer(Modifier.height(14.dp))
        GearSelector(
            selected = if (estop) "P" else if (enabled) "D" else "N",
            danger = estop,
        )
        Spacer(Modifier.height(8.dp))
        SpeedLevelSelector(selected = speedLevel)
        Spacer(Modifier.height(12.dp))
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
            enabled = state.connected,
        )
        Text(
            "按住方向键行驶，松手自动停车",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        DrivePad(
            enabled = enabled,
            onDrivePress = onDrivePress,
            onDriveRelease = onDriveRelease,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = state.connected,
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
private fun CyberSteeringWheel(
    gear: String,
    steer: String,
    active: Boolean,
    danger: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val accent = when {
        danger -> colors.error
        active -> colors.secondary
        else -> colors.outline
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(208.dp)) {
            val diameter = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = accent.copy(alpha = if (active || danger) 0.18f else 0.08f),
                radius = diameter * 0.48f,
                center = center,
            )
            drawCircle(
                color = accent.copy(alpha = 0.74f),
                radius = diameter * 0.42f,
                center = center,
                style = Stroke(width = 6.dp.toPx()),
            )
            drawCircle(
                color = colors.primary.copy(alpha = 0.28f),
                radius = diameter * 0.28f,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = colors.surfaceVariant.copy(alpha = 0.88f),
                radius = diameter * 0.24f,
                center = center,
            )
            drawLine(
                color = accent.copy(alpha = 0.74f),
                start = Offset(center.x - diameter * 0.36f, center.y),
                end = Offset(center.x - diameter * 0.14f, center.y),
                strokeWidth = 5.dp.toPx(),
            )
            drawLine(
                color = accent.copy(alpha = 0.74f),
                start = Offset(center.x + diameter * 0.14f, center.y),
                end = Offset(center.x + diameter * 0.36f, center.y),
                strokeWidth = 5.dp.toPx(),
            )
            drawLine(
                color = accent.copy(alpha = 0.52f),
                start = center,
                end = Offset(center.x, center.y + diameter * 0.34f),
                strokeWidth = 5.dp.toPx(),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                gear,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
            Text(
                "转向 $steer°",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun GearSelector(selected: String, danger: Boolean) {
    val gears = listOf("P", "N", "D", "R")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gears.forEach { gear ->
            FilterChip(
                selected = selected == gear,
                onClick = {},
                label = { Text(gear, fontFamily = FontFamily.Monospace) },
                leadingIcon = if (selected == gear) {
                    {
                        Icon(
                            if (danger && gear == "P") Icons.Filled.Warning else Icons.Filled.CheckCircle,
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

@Composable
private fun SpeedLevelSelector(selected: Int) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (1..4).forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = {},
                label = { Text("D$level", fontFamily = FontFamily.Monospace) },
            )
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
            TextButton(onClick = onStatus, enabled = state.connected) {
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
            FilledTonalButton(onClick = onManual, enabled = state.connected) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("手动")
            }
            FilledTonalButton(onClick = onAuto, enabled = state.connected) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                ButtonGap()
                Text("自动")
            }
        }
    }
}

@Composable
private fun VoiceCoreSection(state: CartUiState) {
    val normalized = state.mamboLastTranscript.normalizeMamboTranscript()
    val toolItems = state.agentMessages.filter { it.role == "tool" }.takeLast(5)
    Section(
        title = "曼波控制核心",
        trailing = {
            StatusPill(
                label = "VAD",
                value = state.mamboVoiceStatus,
                icon = if (state.mamboListening) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                tone = when {
                    state.mamboOverlayStatus.contains("执行") -> StatusTone.Accent
                    state.mamboListening -> StatusTone.Normal
                    else -> StatusTone.Muted
                },
            )
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MamboVoiceOrb(
                active = state.mamboListening || state.agentRunning,
                modifier = Modifier.size(156.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                state.mamboOverlayStatus.ifBlank { state.mamboVoiceStatus },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                state.mamboOverlayCaption.ifBlank { if (state.mamboWakeEnabled) "等待唤醒词：曼波" else "语音唤醒未开启" },
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            WaveformView(active = state.mamboListening || state.agentRunning)
        }
        Spacer(Modifier.height(14.dp))
        VoiceSignalRow("原始识别", state.mamboLastTranscript.ifBlank { "暂无" })
        VoiceSignalRow("归一化", normalized.ifBlank { "暂无" })
        VoiceSignalRow(
            "DeepSeek",
            when {
                state.agentRunning -> "正在思考"
                state.agentMessages.any { it.role == "assistant" } -> "就绪"
                else -> "未开始"
            },
        )
        Spacer(Modifier.height(12.dp))
        ToolCallTimeline(items = toolItems)
    }
}

@Composable
private fun WaveformView(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "voice-waveform")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 640 else 1_600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wave-shift",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bars = listOf(0.28f, 0.46f, 0.72f, 0.92f, 0.62f, 0.36f, 0.54f, 0.82f, 0.48f)
        bars.forEachIndexed { index, base ->
            val height = if (active) {
                10.dp + (28.dp * ((base + shift + index * 0.08f) % 1f))
            } else {
                8.dp + (12.dp * base)
            }
            Surface(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(5.dp)
                    .height(height),
                shape = RoundedCornerShape(8.dp),
                color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
            ) {}
        }
    }
}

@Composable
private fun VoiceSignalRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                shape = RoundedCornerShape(8.dp),
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
    val estop = state.status.isTruthy("estop") || state.status.isTruthy("unsafe")
    Section(
        title = "健康卡片",
        trailing = {
            AssistChip(
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
            HealthCardData("Pico", state.status.displayValue("mode", "-"), state.status.displayValue("sensor", "等待心跳"), if (state.connected) StatusTone.Normal else StatusTone.Muted),
            HealthCardData("安全", if (estop) "异常" else "正常", "estop=${state.status.displayValue("estop", "-")}", if (estop) StatusTone.Danger else StatusTone.Normal),
            HealthCardData("左轮", state.status.displayValue("pwml", "0"), "sensor=${state.status.displayValue("l", "0")}", StatusTone.Accent),
            HealthCardData("右轮", state.status.displayValue("pwmr", "0"), "sensor=${state.status.displayValue("r", "0")}", StatusTone.Accent),
            HealthCardData("DeepSeek", if (state.agentRunning) "思考中" else "就绪", if (state.agentApiKey.isBlank()) "未配置 API Key" else "API Key 已配置", if (state.agentApiKey.isBlank()) StatusTone.Warning else StatusTone.Normal),
            HealthCardData("语音", state.mamboVoiceStatus, if (state.mamboWakeEnabled) "唤醒开启" else "唤醒关闭", if (state.mamboWakeEnabled) StatusTone.Normal else StatusTone.Muted),
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
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
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
                selected = state.streaming,
                onClick = onToggleStream,
                enabled = state.connected,
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
            ActionButton("Stop", Icons.Filled.Stop, onStop, enabled = state.connected, danger = true)
            ActionButton("读取状态", Icons.Filled.Refresh, onStatus, enabled = state.connected)
            ActionButton("手动", Icons.Filled.Bluetooth, onManual, enabled = state.connected)
            ActionButton("自动", Icons.Filled.PlayArrow, onAuto, enabled = state.connected)
            ActionButton("HX711 归零", Icons.Filled.Refresh, onTare, enabled = state.connected)
            ActionButton("闪灯识别", Icons.Filled.Warning, onIdentify, enabled = state.connected)
            ActionButton(if (state.streaming) "关流" else "开流", Icons.Filled.PlayArrow, onToggleStream, enabled = state.connected)
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
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            shape = RoundedCornerShape(8.dp),
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
                enabled = !state.agentRunning && state.agentInput.isNotBlank(),
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
        shape = RoundedCornerShape(8.dp),
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
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
private fun ControlSection(
    state: CartUiState,
    onAuto: () -> Unit,
    onManual: () -> Unit,
    onStop: () -> Unit,
    onTare: () -> Unit,
    onIdentify: () -> Unit,
    onToggleStream: () -> Unit,
    onStatus: () -> Unit,
    onPowerChange: (Float) -> Unit,
    onDrivePress: (String) -> Unit,
    onDriveRelease: () -> Unit,
) {
    Section(
        title = "控制",
        trailing = {
            FilterChip(
                selected = state.streaming,
                onClick = onToggleStream,
                label = { Text(if (state.streaming) "stream on" else "stream off") },
            )
        },
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("停止", Icons.Filled.Stop, onStop)
            ActionButton("自动", Icons.Filled.PlayArrow, onAuto)
            ActionButton("手动", Icons.Filled.Bluetooth, onManual)
            ActionButton("归零", Icons.Filled.Refresh, onTare)
            ActionButton("闪灯", Icons.Filled.Warning, onIdentify)
            ActionButton(if (state.streaming) "关流" else "开流", Icons.Filled.PlayArrow, onToggleStream)
            ActionButton("状态", Icons.Filled.Refresh, onStatus)
        }
        Spacer(Modifier.height(12.dp))
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
        )
        DrivePad(onDrivePress = onDrivePress, onDriveRelease = onDriveRelease)
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
        enabled = enabled,
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
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
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
            TextButton(onClick = onParamQuery) {
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
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            key,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                            OutlinedButton(onClick = { onApplyParam(key) }) {
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
            Button(onClick = onSendCustom) {
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
            shape = RoundedCornerShape(8.dp),
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
            shape = RoundedCornerShape(8.dp),
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
    OutlinedButton(onClick = onClick) {
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

private fun Map<String, String>.isTruthy(key: String): Boolean {
    return this[key]?.lowercase() in setOf("1", "true", "yes", "on", "unsafe")
}

private fun String.normalizeMamboTranscript(): String {
    if (isBlank()) return ""
    return replace(Regex("漫步|慢不|兰博|蓝波|曼播|mambo", RegexOption.IGNORE_CASE), "曼波")
        .trim()
}
