package com.zerotimes.picocart.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.Locale
import kotlin.math.abs

class GamepadController(
    private val onStateChanged: (GamepadState) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private var state = GamepadState()
    private var lastMotionBucket = ""

    fun refreshConnectedDevices() {
        val devices = GamepadInputReader.findGamepads()
        val selected = state.deviceId?.let(InputDevice::getDevice)
            ?.takeIf { device -> devices.any { it.id == device.id } }
            ?: devices.firstOrNull()

        when {
            selected == null && state.connected -> {
                publish(
                    GamepadState(
                        lastEvent = "手柄断开",
                    ),
                )
                lastMotionBucket = ""
                onLog("gamepad disconnected")
            }
            selected != null && (!state.connected || selected.id != state.deviceId) -> {
                publish(
                    state.copy(
                        connected = true,
                        deviceId = selected.id,
                        deviceName = selected.name,
                        lastEvent = "检测到手柄：${selected.name}",
                    ),
                )
                onLog("gamepad connected id=${selected.id} name=${selected.name}")
            }
        }
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!GamepadInputReader.isGamepadSource(event.source)) return false
        val axes = GamepadInputReader.readAxes(event)
        val device = event.device
        val next = state.copy(
            connected = true,
            deviceId = device?.id ?: state.deviceId,
            deviceName = device?.name ?: state.deviceName,
            axisX = axes[MotionEvent.AXIS_X] ?: 0f,
            axisY = axes[MotionEvent.AXIS_Y] ?: 0f,
            axisZ = axes[MotionEvent.AXIS_Z] ?: 0f,
            axisRz = axes[MotionEvent.AXIS_RZ] ?: 0f,
            axisLeftTrigger = axes[MotionEvent.AXIS_LTRIGGER] ?: 0f,
            axisRightTrigger = axes[MotionEvent.AXIS_RTRIGGER] ?: 0f,
            axisGas = axes[MotionEvent.AXIS_GAS] ?: 0f,
            axisBrake = axes[MotionEvent.AXIS_BRAKE] ?: 0f,
            lastInputAtMs = System.currentTimeMillis(),
            lastEvent = "轴输入更新",
        )
        publish(next)
        logMotionIfSignificant(next)
        return true
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GamepadInputReader.isGamepadSource(event.source)) return false
        val pressed = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return true
        }
        val device = event.device
        val next = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> state.copy(buttonA = pressed)
            KeyEvent.KEYCODE_BUTTON_B -> state.copy(buttonB = pressed)
            KeyEvent.KEYCODE_BUTTON_X -> state.copy(buttonX = pressed)
            KeyEvent.KEYCODE_BUTTON_Y -> state.copy(buttonY = pressed)
            KeyEvent.KEYCODE_BUTTON_R1 -> state.copy(buttonRb = pressed)
            KeyEvent.KEYCODE_BUTTON_L1 -> state.copy(buttonLb = pressed)
            KeyEvent.KEYCODE_DPAD_UP -> state.copy(dpadUp = pressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> state.copy(dpadDown = pressed)
            KeyEvent.KEYCODE_DPAD_LEFT -> state.copy(dpadLeft = pressed)
            KeyEvent.KEYCODE_DPAD_RIGHT -> state.copy(dpadRight = pressed)
            KeyEvent.KEYCODE_BUTTON_START -> state.copy(buttonStart = pressed)
            KeyEvent.KEYCODE_BUTTON_SELECT -> state.copy(buttonSelect = pressed)
            else -> state
        }.copy(
            connected = true,
            deviceId = device?.id ?: state.deviceId,
            deviceName = device?.name ?: state.deviceName,
            lastInputAtMs = System.currentTimeMillis(),
            lastEvent = "${KeyEvent.keyCodeToString(event.keyCode)} ${if (pressed) "按下" else "松开"}",
        )
        publish(next)
        onLog("gamepad key ${KeyEvent.keyCodeToString(event.keyCode)} ${if (pressed) "down" else "up"}")
        return true
    }

    private fun publish(next: GamepadState) {
        state = next
        onStateChanged(next)
    }

    private fun logMotionIfSignificant(next: GamepadState) {
        val bucket = listOf(
            "x=${next.axisX.bucket()}",
            "rt=${next.axisRightTrigger.bucket()}",
            "lt=${next.axisLeftTrigger.bucket()}",
            "gas=${next.axisGas.bucket()}",
            "brake=${next.axisBrake.bucket()}",
        ).joinToString(" ")
        if (bucket != lastMotionBucket && listOf(
                next.axisX,
                next.axisRightTrigger,
                next.axisLeftTrigger,
                next.axisGas,
                next.axisBrake,
            ).any { abs(it) >= 0.08f }
        ) {
            lastMotionBucket = bucket
            onLog("gamepad axis $bucket")
        }
    }

    private fun Float.bucket(): String {
        val rounded = (this * 20f).toInt() / 20f
        return String.format(Locale.US, "%.2f", rounded)
    }
}
