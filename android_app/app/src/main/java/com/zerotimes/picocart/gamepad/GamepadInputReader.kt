package com.zerotimes.picocart.gamepad

import android.view.InputDevice
import android.view.MotionEvent

object GamepadInputReader {
    fun isGamepadSource(source: Int): Boolean {
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    fun findGamepads(): List<InputDevice> {
        return InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { deviceId -> InputDevice.getDevice(deviceId) }
            .filter { device -> isGamepadSource(device.sources) }
            .toList()
    }

    fun readAxes(event: MotionEvent): Map<Int, Float> {
        return mapOf(
            MotionEvent.AXIS_X to event.axisOrZero(MotionEvent.AXIS_X),
            MotionEvent.AXIS_Y to event.axisOrZero(MotionEvent.AXIS_Y),
            MotionEvent.AXIS_Z to event.axisOrZero(MotionEvent.AXIS_Z),
            MotionEvent.AXIS_RZ to event.axisOrZero(MotionEvent.AXIS_RZ),
            MotionEvent.AXIS_LTRIGGER to event.axisOrZero(MotionEvent.AXIS_LTRIGGER),
            MotionEvent.AXIS_RTRIGGER to event.axisOrZero(MotionEvent.AXIS_RTRIGGER),
            MotionEvent.AXIS_GAS to event.axisOrZero(MotionEvent.AXIS_GAS),
            MotionEvent.AXIS_BRAKE to event.axisOrZero(MotionEvent.AXIS_BRAKE),
        )
    }

    private fun MotionEvent.axisOrZero(axis: Int): Float {
        return runCatching { getAxisValue(axis) }.getOrDefault(0f)
    }
}
