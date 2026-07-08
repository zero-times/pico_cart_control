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
import com.zerotimes.picocart.speech.AndroidSpeechEngine
import com.zerotimes.picocart.speech.MamboVoiceListener
import com.zerotimes.picocart.ui.PicoCartTheme
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var speechEngine: AndroidSpeechEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        speechEngine = AndroidSpeechEngine(this)
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
    var selectedTab by rememberSaveable { mutableStateOf("debug") }
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
                                if (selectedTab == "agent") "Pico Cart Agent" else "Pico Cart Debug",
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
                        selected = selectedTab == "debug",
                        onClick = { onSelectTab("debug") },
                        icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                        label = { Text("调试") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == "agent",
                        onClick = { onSelectTab("agent") },
                        icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        label = { Text("助手") },
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
                item {
                    Toolbar(
                        state = state,
                        onBluetooth = onBluetooth,
                        onScan = onScan,
                        onStopScan = onStopScan,
                        onDisconnect = onDisconnect,
                    )
                }

                if (selectedTab == "agent") {
                    item {
                        AgentSection(
                            state = state,
                            onAgentApiKeyInput = onAgentApiKeyInput,
                            onAgentInput = onAgentInput,
                            onToggleAgentMovement = onToggleAgentMovement,
                            onRunAgent = onRunAgent,
                            onToggleMamboWake = onToggleMamboWake,
                        )
                    }
                    if (state.connected) {
                        item { StatusSection(state = state, onSpeakStatus = onSpeakStatus) }
                    } else {
                        item {
                            DeviceSection(
                                devices = state.devices,
                                scanning = state.scanning,
                                onConnect = onConnect,
                                onConnectIdentify = onConnectIdentify,
                            )
                        }
                    }
                } else {
                    if (!state.connected) {
                        item {
                            DeviceSection(
                                devices = state.devices,
                                scanning = state.scanning,
                                onConnect = onConnect,
                                onConnectIdentify = onConnectIdentify,
                            )
                        }
                    } else {
                        item { StatusSection(state = state, onSpeakStatus = onSpeakStatus) }
                        item {
                            ControlSection(
                                state = state,
                                onAuto = onAuto,
                                onManual = onManual,
                                onStop = onStop,
                                onTare = onTare,
                                onIdentify = onIdentify,
                                onToggleStream = onToggleStream,
                                onStatus = onStatus,
                                onPowerChange = onPowerChange,
                                onDrivePress = onDrivePress,
                                onDriveRelease = onDriveRelease,
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
                            CommandLogSection(
                                state = state,
                                onCustomInput = onCustomInput,
                                onSendCustom = onSendCustom,
                                onCopyLogs = onCopyLogs,
                                onSaveLogs = onSaveLogs,
                                onShareLogs = onShareLogs,
                                onClearLogs = onClearLogs,
                            )
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
private fun AgentSection(
    state: CartUiState,
    onAgentApiKeyInput: (String) -> Unit,
    onAgentInput: (String) -> Unit,
    onToggleAgentMovement: () -> Unit,
    onRunAgent: () -> Unit,
    onToggleMamboWake: () -> Unit,
) {
    Section(
        title = "AI 助手",
        trailing = {
            FilterChip(
                selected = state.agentMovementUnlocked,
                onClick = onToggleAgentMovement,
                label = { Text(if (state.agentMovementUnlocked) "移动已解锁" else "移动锁定") },
                leadingIcon = {
                    Icon(
                        if (state.agentMovementUnlocked) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        },
    ) {
        OutlinedTextField(
            value = state.agentApiKey,
            onValueChange = onAgentApiKeyInput,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("DeepSeek API Key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.mamboWakeEnabled,
                onClick = onToggleMamboWake,
                label = { Text(if (state.mamboWakeEnabled) "曼波唤醒开" else "曼波唤醒关") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
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
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        ButtonGap()
        Text(label)
    }
}

@Composable
private fun DrivePad(
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
            onPress = { onDrivePress("forward") },
            onRelease = onDriveRelease,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveButton(
                label = "左",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onPress = { onDrivePress("left") },
                onRelease = onDriveRelease,
            )
            ElevatedButton(
                onClick = onDriveRelease,
                modifier = Modifier.sizeIn(minWidth = 80.dp, minHeight = 56.dp),
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
                onPress = { onDrivePress("right") },
                onRelease = onDriveRelease,
            )
        }
        DriveButton(
            label = "后",
            icon = Icons.Filled.KeyboardArrowDown,
            wide = true,
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
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .sizeIn(minWidth = if (wide) 168.dp else 80.dp, minHeight = 56.dp)
            .pointerInput(Unit) {
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
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        key,
                        modifier = Modifier.weight(0.95f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { onParamInput(key, it) },
                        modifier = Modifier.weight(1.25f),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandLogSection(
    state: CartUiState,
    onCustomInput: (String) -> Unit,
    onSendCustom: () -> Unit,
    onCopyLogs: () -> Unit,
    onSaveLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
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
