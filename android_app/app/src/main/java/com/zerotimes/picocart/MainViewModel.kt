package com.zerotimes.picocart

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zerotimes.picocart.agent.AgentRuntime
import com.zerotimes.picocart.agent.AgentRuntimeEvent
import com.zerotimes.picocart.agent.CartHardware
import com.zerotimes.picocart.agent.CartStatus
import com.zerotimes.picocart.agent.SessionStore
import com.zerotimes.picocart.ble.BleChannel
import com.zerotimes.picocart.ble.BleDeviceItem
import com.zerotimes.picocart.ble.BleEvent
import com.zerotimes.picocart.ble.PicoBleClient
import com.zerotimes.picocart.gamepad.GamepadState
import com.zerotimes.picocart.logging.PersistentDebugLogStore
import com.zerotimes.picocart.protocol.PicoProtocol
import com.zerotimes.picocart.speech.MamboVoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val id: String,
    val text: String,
)

data class AgentChatEntry(
    val id: String,
    val role: String,
    val text: String,
    val ok: Boolean? = null,
)

data class CartUiState(
    val adapterReady: Boolean = false,
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
    val serviceId: String = "",
    val writeCharId: String = "",
    val notifyCharId: String = "",
    val writeNoResponse: Boolean = false,
    val devices: List<BleDeviceItem> = emptyList(),
    val streaming: Boolean = false,
    val manualPower: Float = 16f,
    val customCommand: String = "",
    val logs: List<LogEntry> = emptyList(),
    val info: Map<String, String> = emptyMap(),
    val status: Map<String, String> = defaultStatus,
    val params: Map<String, String> = emptyMap(),
    val paramInputs: Map<String, String> = PicoProtocol.defaultParamInputs,
    val agentApiKey: String = "",
    val agentInput: String = "",
    val agentRunning: Boolean = false,
    val agentMovementUnlocked: Boolean = false,
    val agentMessages: List<AgentChatEntry> = emptyList(),
    val mamboWakeEnabled: Boolean = false,
    val mamboListening: Boolean = false,
    val mamboVoiceStatus: String = "关闭",
    val mamboLastTranscript: String = "",
    val mamboOverlayVisible: Boolean = false,
    val mamboOverlayCaption: String = "",
    val mamboOverlayStatus: String = "",
    val mamboSpeechText: String = "",
    val mamboSpeechId: Long = 0L,
    val gamepadState: GamepadState = GamepadState(),
) {
    val paramRows: List<Pair<String, String>>
        get() = PicoProtocol.paramKeys.map { it to paramInputs.orEmptyValue(it) }

    companion object {
        val defaultStatus = linkedMapOf(
            "mode" to "-",
            "sensor" to "-",
            "err" to "-",
            "lraw" to "0",
            "rraw" to "0",
            "l" to "0",
            "r" to "0",
            "total" to "0",
            "steer" to "0",
            "pwml" to "0",
            "pwmr" to "0",
            "estop" to "-",
            "unsafe" to "-",
        )
    }
}

private fun Map<String, String>.orEmptyValue(key: String): String = this[key].orEmpty()

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("agent_host", Context.MODE_PRIVATE)
    private val persistentLogs = PersistentDebugLogStore(application)
    private val sessionStore = SessionStore(application)
    private val agentSessionId = prefs.getString(PREF_SESSION_ID, null)
        ?: sessionStore.createSession("车载助手").also { sessionId ->
            prefs.edit().putString(PREF_SESSION_ID, sessionId).apply()
        }
    private val _uiState = MutableStateFlow(
        CartUiState(
            agentApiKey = prefs.getString(PREF_DEEPSEEK_API_KEY, "").orEmpty(),
            agentMessages = listOf(
                AgentChatEntry(
                    id = "agent-welcome",
                    role = "assistant",
                    text = "我是曼波，已准备好。可以先问我读取状态、进入调试模式、低速前进或左转；移动工具默认锁定。",
                ),
            ),
        ),
    )
    val uiState: StateFlow<CartUiState> = _uiState.asStateFlow()

    private val client = PicoBleClient(application, viewModelScope, ::handleBleEvent)
    private val agentRuntime = AgentRuntime(
        store = sessionStore,
        hardware = object : CartHardware {
            override suspend fun getStatus(): CartStatus? {
                if (!sendCommandForAgent("status")) return currentCartStatus()
                delay(250)
                return currentCartStatus()
            }

            override suspend fun stop() {
                sendCommandForAgent("stop")
            }

            override suspend fun setMode(mode: String): Boolean {
                val command = when (mode) {
                    "manual", "debug" -> "manual"
                    "tow", "assist" -> "auto"
                    else -> return false
                }
                return sendCommandForAgent(command)
            }

            override suspend fun move(direction: String, speedLevel: Int, durationMs: Int): Boolean {
                val command = when (direction) {
                    "forward" -> "f ${speedForLevel(speedLevel)}"
                    "reverse" -> "b ${speedForLevel(speedLevel)}"
                    else -> return false
                }
                if (!sendCommandForAgent(command)) return false
                delay(durationMs.toLong())
                sendCommandForAgent("stop")
                return true
            }

            override suspend fun turn(direction: String, speedLevel: Int, durationMs: Int): Boolean {
                val command = when (direction) {
                    "left" -> "l ${turnSpeedForLevel(speedLevel)}"
                    "right" -> "r ${turnSpeedForLevel(speedLevel)}"
                    else -> return false
                }
                if (!sendCommandForAgent(command)) return false
                delay(durationMs.toLong())
                sendCommandForAgent("stop")
                return true
            }
        },
    )
    private val deviceMap = linkedMapOf<String, BleDeviceItem>()
    private val fullLogs = mutableListOf<LogEntry>()
    private var driveJob: Job? = null
    private var mamboOverlayHideJob: Job? = null
    private var lastLogFile: File? = null
    private var identifyAfterConnected = false

    init {
        persistentLogs.appendApp("session_start package=${application.packageName} app_log=${persistentLogs.appLogPath}")
        persistentLogs.appendVoice("session_start package=${application.packageName} voice_log=${persistentLogs.voiceLogPath}")
        addLog("persistent logs app=${persistentLogs.appLogPath} voice=${persistentLogs.voiceLogPath}")
    }

    fun requiredBlePermissions(): Array<String> = client.requiredPermissions()
    fun hasBlePermissions(): Boolean = client.hasBlePermissions()

    fun initBluetooth() {
        if (!client.isBluetoothEnabled) {
            addLog("bluetooth disabled")
            _uiState.update { it.copy(adapterReady = false) }
            return
        }
        _uiState.update { it.copy(adapterReady = true) }
        addLog("bluetooth adapter ready")
        startScan()
    }

    fun startScan() {
        if (!hasBlePermissions()) {
            addLog("missing bluetooth permission")
            return
        }
        if (!client.isBluetoothEnabled) {
            addLog("bluetooth disabled")
            _uiState.update { it.copy(adapterReady = false) }
            return
        }
        _uiState.update { it.copy(adapterReady = true) }
        client.startScan()
    }

    fun stopScan() {
        client.stopScan()
    }

    fun connect(device: BleDeviceItem, identifyAfterConnect: Boolean = false) {
        identifyAfterConnected = identifyAfterConnect
        _uiState.update { it.copy(connecting = true) }
        client.connect(device)
    }

    fun disconnect() {
        releaseDrive(sendStop = false)
        client.disconnect()
        _uiState.update {
            it.copy(
                connected = false,
                streaming = false,
                connecting = false,
                deviceId = "",
                deviceName = "",
                serviceId = "",
                writeCharId = "",
                notifyCharId = "",
                writeNoResponse = false,
            )
        }
    }

    fun sendStatus() = sendCommand("status")
    fun sendParamQuery() = sendCommand("param")
    fun sendAuto() = sendCommand("auto")
    fun sendManual() = sendCommand("manual")
    fun sendStop() = sendCommand("stop")
    fun sendTare() = sendCommand("tare")
    fun sendIdentify() = sendCommand("identify 5")

    fun toggleStream() {
        val next = !_uiState.value.streaming
        _uiState.update { it.copy(streaming = next) }
        sendCommand(if (next) "stream on" else "stream off")
    }

    fun onPowerChange(value: Float) {
        _uiState.update { it.copy(manualPower = value) }
    }

    fun holdDrive(direction: String) {
        val command = directionCommand(direction)
        releaseDrive(sendStop = false)
        sendCommand(command)
        driveJob = viewModelScope.launch {
            while (true) {
                delay(260)
                sendCommand(command)
            }
        }
    }

    fun releaseDrive(sendStop: Boolean = true) {
        driveJob?.cancel()
        driveJob = null
        if (sendStop && _uiState.value.connected) {
            sendCommand("stop")
        }
    }

    fun updateParam(key: String, value: String) {
        _uiState.update { state ->
            state.copy(paramInputs = state.paramInputs + (key to value))
        }
    }

    fun applyParam(key: String) {
        val value = _uiState.value.paramInputs[key].orEmpty()
        if (value.isNotBlank()) {
            sendCommand("set $key $value")
        }
    }

    fun updateCustomCommand(value: String) {
        _uiState.update { it.copy(customCommand = value) }
    }

    fun sendCustom() {
        val command = _uiState.value.customCommand.trim()
        if (command.isNotEmpty()) {
            sendCommand(command)
        }
    }

    fun sendCommand(command: String) {
        if (!_uiState.value.connected) {
            addLog("not connected")
            return
        }
        client.sendCommand(command)
    }

    fun updateGamepadState(gamepadState: GamepadState) {
        _uiState.update { it.copy(gamepadState = gamepadState) }
    }

    fun onGamepadDebugLog(message: String) {
        addLog(message)
    }

    fun updateAgentApiKey(value: String) {
        _uiState.update { it.copy(agentApiKey = value) }
        prefs.edit().putString(PREF_DEEPSEEK_API_KEY, value).apply()
    }

    fun updateAgentInput(value: String) {
        _uiState.update { it.copy(agentInput = value) }
    }

    fun toggleAgentMovementUnlocked() {
        _uiState.update { it.copy(agentMovementUnlocked = !it.agentMovementUnlocked) }
    }

    fun setMamboWakeEnabled(enabled: Boolean) {
        persistentLogs.appendVoice("wake_enabled=$enabled")
        if (!enabled) {
            mamboOverlayHideJob?.cancel()
        }
        _uiState.update {
            it.copy(
                mamboWakeEnabled = enabled,
                mamboListening = if (enabled) it.mamboListening else false,
                mamboVoiceStatus = if (enabled) "启动中" else "关闭",
                mamboOverlayVisible = if (enabled) it.mamboOverlayVisible else false,
                mamboOverlayCaption = if (enabled) it.mamboOverlayCaption else "",
                mamboOverlayStatus = if (enabled) it.mamboOverlayStatus else "",
            )
        }
        appendAgentMessage(
            role = "tool",
            text = if (enabled) "曼波语音唤醒已打开" else "曼波语音唤醒已关闭",
            ok = true,
        )
    }

    fun setMamboVoiceState(state: MamboVoiceState) {
        val status = when (state) {
            MamboVoiceState.OFF -> "关闭"
            MamboVoiceState.LOADING_MODEL -> "加载模型"
            MamboVoiceState.WAITING_WAKE -> "待唤醒"
            MamboVoiceState.LISTENING_COMMAND -> "听指令"
        }
        persistentLogs.appendVoice("state=$state status=$status")
        if (state == MamboVoiceState.LISTENING_COMMAND) {
            mamboOverlayHideJob?.cancel()
        }
        _uiState.update {
            it.copy(
                mamboListening = state != MamboVoiceState.OFF,
                mamboVoiceStatus = status,
                mamboOverlayVisible = when (state) {
                    MamboVoiceState.LISTENING_COMMAND -> true
                    MamboVoiceState.OFF -> false
                    else -> it.mamboOverlayVisible
                },
                mamboOverlayStatus = when (state) {
                    MamboVoiceState.LISTENING_COMMAND -> "听指令"
                    MamboVoiceState.OFF -> ""
                    else -> it.mamboOverlayStatus
                },
                mamboOverlayCaption = when {
                    state == MamboVoiceState.LISTENING_COMMAND && it.mamboOverlayCaption.isBlank() -> "我在"
                    state == MamboVoiceState.OFF -> ""
                    else -> it.mamboOverlayCaption
                },
            )
        }
    }

    fun onMamboVoiceError(message: String) {
        persistentLogs.appendVoice("error=$message")
        showMamboOverlay(status = "未听清", caption = message, autoHide = true)
        appendAgentMessage(role = "tool", text = "曼波语音：$message", ok = false)
    }

    fun onMamboVoiceDebug(message: String) {
        persistentLogs.appendVoice(message)
        if (message.startsWith("命令候选：")) {
            val candidate = message.removePrefix("命令候选：").trim()
            showMamboOverlay(
                status = "识别中",
                caption = candidate.takeUnless { it == "未识别" }.orEmpty(),
                autoHide = false,
            )
        }
        _uiState.update { it.copy(mamboLastTranscript = message) }
        appendAgentMessage(role = "tool", text = "曼波语音：$message", ok = null)
    }

    fun onMamboWake() {
        persistentLogs.appendVoice("wake_matched")
        showMamboOverlay(status = "听指令", caption = "我在")
        _uiState.update { it.copy(mamboLastTranscript = "曼波") }
        appendAgentMessage(role = "tool", text = "曼波已唤醒，开始听指令。", ok = true)
        emitMamboSpeech("我在")
    }

    fun onMamboCommand(command: String) {
        val cleanCommand = command.trim()
        if (cleanCommand.isEmpty()) {
            return
        }
        persistentLogs.appendVoice("command=$cleanCommand")
        _uiState.update {
            it.copy(
                mamboLastTranscript = cleanCommand,
                mamboOverlayVisible = true,
                mamboOverlayStatus = "正在执行",
                mamboOverlayCaption = cleanCommand,
            )
        }
        scheduleMamboOverlayHide()
        if (cleanCommand in LOCAL_STOP_COMMANDS) {
            appendAgentMessage(role = "tool", text = "曼波本地执行：$cleanCommand", ok = true)
            releaseDrive(sendStop = true)
            emitMamboSpeech("已停车。")
            return
        }
        appendAgentMessage(role = "tool", text = "曼波指令：$cleanCommand", ok = true)
        startAgentTurn(cleanCommand, clearTypedInput = false)
    }

    fun runAgentTurn() {
        val snapshot = _uiState.value
        val userText = snapshot.agentInput.trim()
        startAgentTurn(userText, clearTypedInput = true)
    }

    private fun startAgentTurn(userText: String, clearTypedInput: Boolean) {
        val snapshot = _uiState.value
        if (snapshot.agentRunning || userText.isBlank()) {
            return
        }

        appendAgentMessage(role = "user", text = userText)
        _uiState.update {
            it.copy(
                agentInput = if (clearTypedInput) "" else it.agentInput,
                agentRunning = true,
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                agentRuntime.runTurn(
                    sessionId = agentSessionId,
                    userText = userText,
                    apiKey = snapshot.agentApiKey,
                    cartStatus = currentCartStatus(),
                    movementUnlocked = snapshot.agentMovementUnlocked,
                    onEvent = ::handleAgentEvent,
                )
            }

            result.onSuccess { turn ->
                val usage = turn.usage?.let {
                    " tokens=${it.totalTokens} cache_hit=${it.promptCacheHitTokens ?: 0}"
                }.orEmpty()
                appendAgentMessage(role = "assistant", text = turn.text + usage)
                turn.speakText?.takeIf { it.isNotBlank() }?.let(::emitMamboSpeech)
            }.onFailure { error ->
                appendAgentMessage(
                    role = "assistant",
                    text = "助手执行失败：${error.message ?: error::class.java.simpleName}",
                    ok = false,
                )
            }

            _uiState.update { it.copy(agentRunning = false) }
        }
    }

    fun clearLog() {
        fullLogs.clear()
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun buildLogText(): String {
        val state = _uiState.value
        val lines = mutableListOf(
            "Pico Cart Debug Log",
            "created_at=${formatDateTime(Date())}",
            "device_name=${state.deviceName.ifBlank { "-" }}",
            "device_id=${state.deviceId.ifBlank { "-" }}",
            "connected=${if (state.connected) "1" else "0"}",
            "service_id=${state.serviceId.ifBlank { "-" }}",
            "write_char=${state.writeCharId.ifBlank { "-" }}",
            "notify_char=${state.notifyCharId.ifBlank { "-" }}",
            "",
            "[info]",
            state.info.toSortedMap().toString(),
            "",
            "[status]",
            state.status.toSortedMap().toString(),
            "",
            "[params]",
            state.paramInputs.toSortedMap().toString(),
            "",
            "[gamepad]",
            state.gamepadState.toString(),
            "",
            "[logs]",
        )
        val source = if (fullLogs.isNotEmpty()) fullLogs else state.logs
        source.forEach { lines += it.text }
        return lines.joinToString("\n", postfix = "\n")
    }

    fun copyLogs(context: Context) {
        val content = buildLogText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Pico Cart Debug Log", content))
        addLog("log copied chars=${content.length}")
    }

    fun saveLogs(context: Context): File? {
        return runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, buildLogFileName())
            file.writeText(buildLogText(), Charsets.UTF_8)
            lastLogFile = file
            addLog("log saved ${file.absolutePath}")
            file
        }.onFailure {
            addLog("save log error ${it.message ?: it}")
        }.getOrNull()
    }

    fun shareLogs(context: Context): Intent {
        val file = lastLogFile ?: saveLogs(context)
        return if (file != null) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, buildLogText())
            }
        }
    }

    fun statusSpeechText(): String {
        val status = _uiState.value.status
        return "当前模式 ${status["mode"].orEmpty()}，传感器 ${status["sensor"].orEmpty()}，总拉力 ${status["total"].orEmpty()}，左 PWM ${status["pwml"].orEmpty()}，右 PWM ${status["pwmr"].orEmpty()}。"
    }

    override fun onCleared() {
        releaseDrive(sendStop = false)
        client.close()
        sessionStore.close()
        persistentLogs.appendApp("session_end")
        persistentLogs.appendVoice("session_end")
        persistentLogs.close()
        super.onCleared()
    }

    private fun handleBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.DeviceFound -> {
                deviceMap[event.device.address] = event.device
                _uiState.update {
                    it.copy(devices = deviceMap.values.sortedByDescending(BleDeviceItem::rssi))
                }
            }
            is BleEvent.ScanState -> _uiState.update { it.copy(scanning = event.scanning) }
            is BleEvent.Connected -> handleConnected(event.device, event.channel)
            BleEvent.Disconnected -> {
                releaseDrive(sendStop = false)
                _uiState.update {
                    it.copy(connected = false, connecting = false, streaming = false)
                }
            }
            is BleEvent.LineReceived -> applyLine(event.line)
            is BleEvent.Log -> addLog(event.message)
            is BleEvent.Error -> {
                addLog(event.message)
                _uiState.update { it.copy(connecting = false) }
            }
        }
    }

    private fun handleConnected(device: BleDeviceItem, channel: BleChannel) {
        _uiState.update {
            it.copy(
                connected = true,
                connecting = false,
                deviceId = device.address,
                deviceName = device.name,
                serviceId = channel.serviceId,
                writeCharId = channel.writeCharId,
                notifyCharId = channel.notifyCharId,
                writeNoResponse = channel.writeNoResponse,
            )
        }
        viewModelScope.launch {
            delay(180)
            sendCommand("status")
            delay(120)
            sendCommand("param")
            if (identifyAfterConnected) {
                delay(200)
                identifyAfterConnected = false
                sendCommand("identify 5")
            }
        }
    }

    private fun applyLine(line: String) {
        addLog("< $line")
        val parsed = PicoProtocol.parseLine(line)
        when (parsed.type) {
            "stat" -> _uiState.update { state ->
                state.copy(status = state.status + parsed.fields + ("type" to parsed.type))
            }
            "param" -> _uiState.update { state ->
                val updatedInputs = state.paramInputs.toMutableMap()
                updatedInputs.keys.toList().forEach { key ->
                    parsed[key]?.let { updatedInputs[key] = it }
                }
                state.copy(
                    params = parsed.fields + ("type" to parsed.type),
                    paramInputs = updatedInputs,
                )
            }
            "info" -> _uiState.update { it.copy(info = parsed.fields + ("type" to parsed.type)) }
            "ok" -> {
                parsed["stream"]?.let { stream ->
                    _uiState.update { it.copy(streaming = stream == "on") }
                }
            }
        }
    }

    private fun addLog(message: String) {
        persistentLogs.appendApp(message)
        val entry = LogEntry(
            id = "log-${System.currentTimeMillis()}-${fullLogs.size}",
            text = "${timeFormat.format(Date())} $message",
        )
        fullLogs += entry
        if (fullLogs.size > 1000) {
            fullLogs.removeAt(0)
        }
        _uiState.update { state ->
            state.copy(logs = (state.logs + entry).takeLast(80))
        }
    }

    private fun handleAgentEvent(event: AgentRuntimeEvent) {
        appendAgentMessage(
            role = "tool",
            text = "${event.label}: ${event.detail}",
            ok = event.ok,
        )
    }

    private fun appendAgentMessage(role: String, text: String, ok: Boolean? = null) {
        persistentLogs.appendAgent(role = role, message = text, ok = ok)
        val entry = AgentChatEntry(
            id = "agent-${System.currentTimeMillis()}-${text.hashCode()}",
            role = role,
            text = text,
            ok = ok,
        )
        _uiState.update { state ->
            state.copy(agentMessages = (state.agentMessages + entry).takeLast(80))
        }
    }

    private fun emitMamboSpeech(text: String) {
        persistentLogs.appendVoice("speak=$text")
        _uiState.update {
            it.copy(
                mamboSpeechText = text,
                mamboSpeechId = System.currentTimeMillis(),
            )
        }
    }

    private fun showMamboOverlay(status: String, caption: String, autoHide: Boolean = false) {
        if (!autoHide) {
            mamboOverlayHideJob?.cancel()
        }
        _uiState.update {
            it.copy(
                mamboOverlayVisible = true,
                mamboOverlayStatus = status,
                mamboOverlayCaption = caption.ifBlank { it.mamboOverlayCaption },
            )
        }
        if (autoHide) {
            scheduleMamboOverlayHide()
        }
    }

    private fun scheduleMamboOverlayHide(delayMs: Long = 2_400L) {
        mamboOverlayHideJob?.cancel()
        mamboOverlayHideJob = viewModelScope.launch {
            delay(delayMs)
            _uiState.update {
                it.copy(
                    mamboOverlayVisible = false,
                    mamboOverlayCaption = "",
                    mamboOverlayStatus = "",
                )
            }
        }
    }

    private fun sendCommandForAgent(command: String): Boolean {
        if (!_uiState.value.connected) {
            addLog("agent not connected")
            return false
        }
        client.sendCommand(command)
        return true
    }

    private fun currentCartStatus(): CartStatus {
        val status = _uiState.value.status
        val err = status["err"].asFault()
        val unsafe = status["unsafe"].asFault()
        return CartStatus(
            mode = status["mode"].orEmpty().takeUnless { it.isBlank() || it == "-" } ?: "idle",
            estop = status["estop"].isEstopActive(),
            fault = err ?: unsafe,
            leftPwm = status["pwml"]?.toDoubleOrNull(),
            rightPwm = status["pwmr"]?.toDoubleOrNull(),
            leftForce = status["l"]?.toDoubleOrNull(),
            rightForce = status["r"]?.toDoubleOrNull(),
        )
    }

    private fun directionCommand(direction: String): String {
        val power = String.format(Locale.US, "%.2f", _uiState.value.manualPower / 100f)
        return when (direction) {
            "forward" -> "f $power"
            "backward" -> "b $power"
            "left" -> "l $power"
            "right" -> "r $power"
            else -> "stop"
        }
    }

    private fun buildLogFileName(): String {
        val base = _uiState.value.deviceName
            .ifBlank { "pico-cart" }
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "-")
            .trim('-')
            .take(32)
            .ifBlank { "pico-cart" }
        return "${base}_${fileTimeFormat.format(Date())}.txt"
    }

    private companion object {
        const val PREF_DEEPSEEK_API_KEY = "deepseek_api_key"
        const val PREF_SESSION_ID = "session_id"
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val LOCAL_STOP_COMMANDS = setOf("停车", "急停", "取消")
        fun formatDateTime(date: Date): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)

        fun speedForLevel(level: Int): String = when (level.coerceIn(1, 3)) {
            1 -> "0.08"
            2 -> "0.14"
            else -> "0.20"
        }

        fun turnSpeedForLevel(level: Int): String = when (level.coerceIn(1, 2)) {
            1 -> "0.08"
            else -> "0.13"
        }

        fun String?.isEstopActive(): Boolean {
            val normalized = this?.trim()?.lowercase(Locale.US)
            return normalized == "0" || normalized == "true" || normalized == "active"
        }

        fun String?.asFault(): String? {
            val normalized = this?.trim().orEmpty()
            return normalized.takeIf { it.isNotBlank() && it != "-" && it != "ok" }
        }
    }
}
