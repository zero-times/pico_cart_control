package com.zerotimes.picocart.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.zerotimes.picocart.protocol.PicoProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.UUID

data class BleDeviceItem(
    val address: String,
    val name: String,
    val rssi: Int,
    val advertisedServices: List<String> = emptyList(),
)

data class BleChannel(
    val serviceId: String,
    val writeCharId: String,
    val notifyCharId: String,
    val writeNoResponse: Boolean,
)

sealed interface BleEvent {
    data class DeviceFound(val device: BleDeviceItem) : BleEvent
    data class ScanState(val scanning: Boolean) : BleEvent
    data class Connected(val device: BleDeviceItem, val channel: BleChannel) : BleEvent
    data object Disconnected : BleEvent
    data class LineReceived(val line: String) : BleEvent
    data class Log(val message: String) : BleEvent
    data class Error(val message: String) : BleEvent
}

@SuppressLint("MissingPermission")
class PicoBleClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onEvent: (BleEvent) -> Unit,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner
    private val commandQueue = ArrayDeque<OutgoingCommand>()
    private val commandQueueLock = Any()
    private val writeWake = Channel<Unit>(Channel.CONFLATED)

    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BleDeviceItem? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeNoResponse = false
    private var rxBuffer = ""
    private var discoveryStarted = false
    @Volatile private var pendingWriteAck: CompletableDeferred<Int>? = null

    init {
        scope.launch {
            for (ignored in writeWake) {
                drainCommandQueue()
            }
        }
    }

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emitDevice(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::emitDevice)
        }

        override fun onScanFailed(errorCode: Int) {
            onEvent(BleEvent.Error("scan failed code=$errorCode"))
            onEvent(BleEvent.ScanState(false))
        }
    }

    fun hasBlePermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun startScan() {
        if (!hasBlePermissions()) {
            onEvent(BleEvent.Error("missing bluetooth permission"))
            return
        }
        if (!isBluetoothEnabled) {
            onEvent(BleEvent.Error("bluetooth disabled"))
            return
        }
        scanner?.startScan(scanCallback)
        onEvent(BleEvent.ScanState(true))
        onEvent(BleEvent.Log("scan started"))
    }

    fun stopScan() {
        if (hasBlePermissions()) {
            runCatching { scanner?.stopScan(scanCallback) }
        }
        onEvent(BleEvent.ScanState(false))
    }

    fun connect(device: BleDeviceItem) {
        if (!hasBlePermissions()) {
            onEvent(BleEvent.Error("missing bluetooth permission"))
            return
        }
        val remoteDevice = runCatching {
            bluetoothAdapter?.getRemoteDevice(device.address)
        }.getOrNull()
        if (remoteDevice == null) {
            onEvent(BleEvent.Error("device not found ${device.address}"))
            return
        }
        stopScan()
        close()
        connectedDevice = device
        discoveryStarted = false
        onEvent(BleEvent.Log("connect ${device.name}"))
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            remoteDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            remoteDevice.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        close()
        onEvent(BleEvent.Disconnected)
    }

    fun close() {
        synchronized(commandQueueLock) {
            commandQueue.clear()
        }
        pendingWriteAck?.complete(BluetoothGatt.GATT_FAILURE)
        pendingWriteAck = null
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        writeNoResponse = false
        rxBuffer = ""
        discoveryStarted = false
    }

    fun sendCommand(command: String, logCommand: Boolean = true) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val outgoing = OutgoingCommand(trimmed, logCommand, commandKind(trimmed))
        synchronized(commandQueueLock) {
            when (outgoing.kind) {
                CommandKind.HARD_STOP -> {
                    commandQueue.clear()
                    commandQueue.addFirst(outgoing)
                }
                CommandKind.SOFT_STOP -> {
                    removeQueuedMotionCommands()
                    commandQueue.addFirst(outgoing)
                }
                CommandKind.MOVEMENT -> {
                    if (trimmed == "keepalive" && commandQueue.any { it.text == "keepalive" }) {
                        return
                    }
                    if (trimmed != "keepalive") {
                        removeQueuedMotionCommands()
                    }
                    commandQueue.addLast(outgoing)
                }
                CommandKind.REGULAR -> {
                    if (trimmed == "status" && commandQueue.any { it.text == "status" }) {
                        return
                    }
                    commandQueue.addLast(outgoing)
                }
            }
        }
        if (outgoing.kind == CommandKind.HARD_STOP) {
            pendingWriteAck?.complete(BluetoothGatt.GATT_FAILURE)
        }
        writeWake.trySend(Unit)
    }

    private fun emitDevice(result: ScanResult) {
        val device = result.device ?: return
        val name = runCatching {
            result.scanRecord?.deviceName ?: device.name
        }.getOrNull().orEmpty().ifBlank { "未命名设备" }
        val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString() }
        onEvent(
            BleEvent.DeviceFound(
                BleDeviceItem(
                    address = device.address,
                    name = name,
                    rssi = result.rssi,
                    advertisedServices = services,
                ),
            ),
        )
    }

    private suspend fun drainCommandQueue() {
        while (true) {
            val command = synchronized(commandQueueLock) {
                if (commandQueue.isEmpty()) null else commandQueue.removeFirst()
            } ?: return
            writeCommand(command)
        }
    }

    private suspend fun writeCommand(command: OutgoingCommand) {
        val currentGatt = gatt ?: run {
            onEvent(BleEvent.Log("not connected"))
            return
        }
        val characteristic = writeCharacteristic ?: run {
            onEvent(BleEvent.Log("write characteristic unavailable"))
            return
        }
        if (command.logCommand) {
            onEvent(BleEvent.Log("> ${command.text}"))
        }
        val bytes = "${command.text}\n".toByteArray(Charsets.UTF_8)
        for (offset in bytes.indices step WRITE_CHUNK_BYTES) {
            val end = minOf(offset + WRITE_CHUNK_BYTES, bytes.size)
            val payload = bytes.copyOfRange(offset, end)
            if (!writeChunk(currentGatt, characteristic, payload)) {
                onEvent(BleEvent.Log("write failed command=${command.text}"))
                return
            }
        }
    }

    private suspend fun writeChunk(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
    ): Boolean {
        val writeType = if (writeNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        repeat(WRITE_ATTEMPTS) { attempt ->
            if (writeNoResponse) {
                if (startGattWrite(currentGatt, characteristic, payload, writeType)) {
                    delay(NO_RESPONSE_WRITE_GAP_MS)
                    return true
                }
            } else {
                val ack = CompletableDeferred<Int>()
                pendingWriteAck = ack
                val accepted = startGattWrite(currentGatt, characteristic, payload, writeType)
                if (accepted) {
                    val status = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { ack.await() }
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (pendingWriteAck === ack) pendingWriteAck = null
                        return true
                    }
                    if (status == null) {
                        // Give a late callback a chance to drain before another write starts.
                        delay(WRITE_CALLBACK_SETTLE_MS)
                        if (pendingWriteAck === ack) pendingWriteAck = null
                        return false
                    }
                    if (pendingWriteAck === ack) pendingWriteAck = null
                } else if (pendingWriteAck === ack) {
                    pendingWriteAck = null
                }
            }
            if (hasQueuedHardStop()) return false
            if (attempt < WRITE_ATTEMPTS - 1) {
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private fun startGattWrite(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(characteristic, payload, writeType) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = payload
            currentGatt.writeCharacteristic(characteristic)
        }
    }

    private fun removeQueuedMotionCommands() {
        val iterator = commandQueue.iterator()
        while (iterator.hasNext()) {
            val kind = iterator.next().kind
            if (kind == CommandKind.MOVEMENT || kind == CommandKind.SOFT_STOP) {
                iterator.remove()
            }
        }
    }

    private fun hasQueuedHardStop(): Boolean = synchronized(commandQueueLock) {
        commandQueue.any { it.kind == CommandKind.HARD_STOP }
    }

    private fun commandKind(command: String): CommandKind {
        return when (command.substringBefore(' ').lowercase()) {
            "stop", "s" -> CommandKind.HARD_STOP
            "softstop" -> CommandKind.SOFT_STOP
            "f", "b", "l", "r", "drive", "keepalive" -> CommandKind.MOVEMENT
            else -> CommandKind.REGULAR
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWriteAck?.complete(status)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onEvent(BleEvent.Error("connection status=$status"))
                close()
                onEvent(BleEvent.Disconnected)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onEvent(BleEvent.Log("gatt connected"))
                    if (!gatt.requestMtu(128)) {
                        discoverServices(gatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onEvent(BleEvent.Log("device disconnected"))
                    close()
                    onEvent(BleEvent.Disconnected)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onEvent(BleEvent.Log("mtu $mtu status=$status"))
            discoverServices(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onEvent(BleEvent.Error("service discovery status=$status"))
                return
            }
            val selection = pickUartChannel(gatt.services)
            if (selection == null) {
                onEvent(BleEvent.Error("no writable notify characteristic"))
                gatt.disconnect()
                return
            }
            writeCharacteristic = selection.writeChar
            notifyCharacteristic = selection.notifyChar
            writeNoResponse = selection.channel.writeNoResponse
            if (enableNotifications(gatt, selection.notifyChar)) {
                connectedDevice?.let { onEvent(BleEvent.Connected(it, selection.channel)) }
                onEvent(BleEvent.Log("channel ${selection.channel.serviceId} ${selection.channel.writeCharId}"))
            } else {
                onEvent(BleEvent.Error("notify setup failed"))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleNotify(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(value)
        }
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        if (!gatt.discoverServices()) {
            onEvent(BleEvent.Error("discover services failed"))
        }
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        val localOk = gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            return localOk
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleNotify(bytes: ByteArray) {
        val chunk = bytes.toString(Charsets.UTF_8)
        var buffer = (rxBuffer + chunk).replace('\r', '\n')
        val lines = buffer.split('\n')
        buffer = lines.lastOrNull().orEmpty()
        lines.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            onEvent(BleEvent.LineReceived(line))
        }
        rxBuffer = if (buffer.length > 240) buffer.takeLast(240) else buffer
    }

    private fun pickUartChannel(services: List<BluetoothGattService>): ChannelSelection? {
        var best: ChannelSelection? = null
        services.filter { it.type == BluetoothGattService.SERVICE_TYPE_PRIMARY }.forEach { service ->
            val writes = service.characteristics.filter { characteristic ->
                characteristic.properties hasAny WRITE_PROPERTIES
            }
            val notifies = service.characteristics.filter { characteristic ->
                characteristic.properties hasAny NOTIFY_PROPERTIES
            }
            writes.forEach { writeChar ->
                notifies.forEach { notifyChar ->
                    val score = PicoProtocol.uuidScore(service.uuid.toString(), PicoProtocol.serviceHints) +
                        PicoProtocol.uuidScore(writeChar.uuid.toString(), PicoProtocol.characteristicHints) +
                        PicoProtocol.uuidScore(notifyChar.uuid.toString(), PicoProtocol.characteristicHints) +
                        (if (writeChar.properties hasAny BluetoothGattCharacteristic.PROPERTY_WRITE) 8 else 0) +
                        (if (notifyChar.properties hasAny BluetoothGattCharacteristic.PROPERTY_NOTIFY) 8 else 0)
                    if (best == null || score > best!!.score) {
                        best = ChannelSelection(
                            score = score,
                            writeChar = writeChar,
                            notifyChar = notifyChar,
                            channel = BleChannel(
                                serviceId = service.uuid.toString(),
                                writeCharId = writeChar.uuid.toString(),
                                notifyCharId = notifyChar.uuid.toString(),
                                writeNoResponse =
                                    (writeChar.properties hasAny
                                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) &&
                                        !(writeChar.properties hasAny
                                            BluetoothGattCharacteristic.PROPERTY_WRITE),
                            ),
                        )
                    }
                }
            }
        }
        return best
    }

    private data class ChannelSelection(
        val score: Int,
        val writeChar: BluetoothGattCharacteristic,
        val notifyChar: BluetoothGattCharacteristic,
        val channel: BleChannel,
    )

    private data class OutgoingCommand(
        val text: String,
        val logCommand: Boolean,
        val kind: CommandKind,
    )

    private enum class CommandKind {
        HARD_STOP,
        SOFT_STOP,
        MOVEMENT,
        REGULAR,
    }

    private infix fun Int.hasAny(mask: Int): Boolean = this and mask != 0

    private companion object {
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val WRITE_PROPERTIES: Int =
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        const val NOTIFY_PROPERTIES: Int =
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
        const val WRITE_CHUNK_BYTES = 18
        const val WRITE_ATTEMPTS = 3
        const val WRITE_ACK_TIMEOUT_MS = 1_200L
        const val WRITE_CALLBACK_SETTLE_MS = 250L
        const val WRITE_RETRY_DELAY_MS = 60L
        const val NO_RESPONSE_WRITE_GAP_MS = 35L
    }
}
