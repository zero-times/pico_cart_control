package com.zerotimes.picocart.gamepad

data class GamepadState(
    val connected: Boolean = false,
    val deviceId: Int? = null,
    val deviceName: String? = null,
    val axisX: Float = 0f,
    val axisY: Float = 0f,
    val axisZ: Float = 0f,
    val axisRz: Float = 0f,
    val axisLeftTrigger: Float = 0f,
    val axisRightTrigger: Float = 0f,
    val axisGas: Float = 0f,
    val axisBrake: Float = 0f,
    val buttonA: Boolean = false,
    val buttonB: Boolean = false,
    val buttonX: Boolean = false,
    val buttonY: Boolean = false,
    val buttonRb: Boolean = false,
    val buttonLb: Boolean = false,
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
    val buttonStart: Boolean = false,
    val buttonSelect: Boolean = false,
    val lastInputAtMs: Long = 0L,
    val lastEvent: String = "未检测到手柄",
) {
    val axisRows: List<Pair<String, Float>>
        get() = listOf(
            "AXIS_X" to axisX,
            "AXIS_Y" to axisY,
            "AXIS_Z" to axisZ,
            "AXIS_RZ" to axisRz,
            "AXIS_LTRIGGER" to axisLeftTrigger,
            "AXIS_RTRIGGER" to axisRightTrigger,
            "AXIS_GAS" to axisGas,
            "AXIS_BRAKE" to axisBrake,
        )

    val buttonRows: List<Pair<String, Boolean>>
        get() = listOf(
            "BUTTON_A" to buttonA,
            "BUTTON_B" to buttonB,
            "BUTTON_X" to buttonX,
            "BUTTON_Y" to buttonY,
            "BUTTON_R1" to buttonRb,
            "BUTTON_L1" to buttonLb,
            "DPAD_UP" to dpadUp,
            "DPAD_DOWN" to dpadDown,
            "DPAD_LEFT" to dpadLeft,
            "DPAD_RIGHT" to dpadRight,
            "MENU/START" to buttonStart,
            "VIEW/BACK" to buttonSelect,
        )
}
