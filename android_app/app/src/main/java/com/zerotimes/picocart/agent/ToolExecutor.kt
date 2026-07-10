package com.zerotimes.picocart.agent

/**
 * Abstraction over the cart hardware control layer.
 *
 * This interface is intended to be implemented by a class that communicates
 * with Pico over BLE or USB serial. The [ToolExecutor] uses it to translate
 * tool calls into hardware commands.
 *
 * V1 implementation is a no-op / stub until the caller wires it to
 * [com.zerotimes.picocart.ble.PicoBleClient] or a serial client.
 */
interface CartHardware {
    /**
     * Returns a user-facing reason when the transport is not safe to use.
     * A BLE GATT connection alone is not sufficient: Pico must also have a
     * recent application-level heartbeat.
     */
    fun connectionError(): String? = null

    /** Details about the most recent command or status request failure. */
    fun lastOperationError(): String? = null

    /** Read full status from the cart. Returns null on failure. */
    suspend fun getStatus(): CartStatus?

    /** Immediate stop. */
    suspend fun stop(): Boolean

    /** Set cart operating mode. */
    suspend fun setMode(mode: String): Boolean

    /** Move forward/reverse at a given speed level (1-3) for a duration. */
    suspend fun move(direction: String, speedLevel: Int, durationMs: Int): Boolean

    /** Turn left/right at a given speed level (1-2) for a duration. */
    suspend fun turn(direction: String, speedLevel: Int, durationMs: Int): Boolean
}

/**
 * Executes a [ToolCall] by routing it to the appropriate handler and
 * returning a [ToolResult].
 *
 * The default implementation delegates to [CartHardware], but the interface
 * is kept separate to allow testing or alternative execution strategies.
 */
interface ToolExecutor {
    /**
     * Execute a single tool call.
     *
     * Implementations should assume the call has already passed
     * [SafetyGuard.validate] — no need to re-check safety here.
     */
    suspend fun execute(toolCall: ToolCall): ToolResult
}

/**
 * Concrete [ToolExecutor] that maps cart_* tool calls to [CartHardware] calls.
 *
 * Unknown tool names are returned as errors.
 */
class CartToolExecutor(
    private val hardware: CartHardware
) : ToolExecutor {

    override suspend fun execute(toolCall: ToolCall): ToolResult {
        return try {
            hardware.connectionError()?.let { error ->
                return ToolResult.error(error)
            }
            val args = org.json.JSONObject(toolCall.function.arguments)
            when (toolCall.function.name) {
                "cart_get_status" -> {
                    val status = hardware.getStatus()
                    if (status != null) {
                        ToolResult.success(mapOf(
                            "mode" to status.mode,
                            "battery_v" to (status.batteryV ?: JSON_NULL),
                            "estop" to status.estop,
                            "fault" to (status.fault ?: JSON_NULL),
                            "left_pwm" to (status.leftPwm ?: JSON_NULL),
                            "right_pwm" to (status.rightPwm ?: JSON_NULL)
                        ))
                    } else {
                        ToolResult.error(hardware.lastOperationError() ?: "Pico 状态读取失败")
                    }
                }

                "cart_stop" -> {
                    if (hardware.stop()) {
                        ToolResult.success(mapOf("action" to "stop"))
                    } else {
                        ToolResult.error(hardware.lastOperationError() ?: "Pico 停车命令发送失败")
                    }
                }

                "cart_set_mode" -> {
                    val mode = args.optString("mode", "")
                    if (mode.isEmpty()) {
                        ToolResult.error("Missing required 'mode' argument")
                    } else {
                        val ok = hardware.setMode(mode)
                        if (ok) ToolResult.success(mapOf("mode" to mode))
                        else ToolResult.error(hardware.lastOperationError() ?: "设置模式 '$mode' 失败")
                    }
                }

                "cart_move" -> {
                    val direction = args.optString("direction", "")
                    val speedLevel = args.optInt("speed_level", 1)
                    val durationMs = args.optInt("duration_ms", 0)
                    val ok = hardware.move(direction, speedLevel, durationMs)
                    if (ok) ToolResult.success(mapOf(
                        "direction" to direction,
                        "speed_level" to speedLevel,
                        "duration_ms" to durationMs
                    )) else ToolResult.error(hardware.lastOperationError() ?: "cart_move 执行失败")
                }

                "cart_turn" -> {
                    val direction = args.optString("direction", "")
                    val speedLevel = args.optInt("speed_level", 1)
                    val durationMs = args.optInt("duration_ms", 0)
                    val ok = hardware.turn(direction, speedLevel, durationMs)
                    if (ok) ToolResult.success(mapOf(
                        "direction" to direction,
                        "speed_level" to speedLevel,
                        "duration_ms" to durationMs
                    )) else ToolResult.error(hardware.lastOperationError() ?: "cart_turn 执行失败")
                }

                else -> ToolResult.error("Unknown tool: ${toolCall.function.name}")
            }
        } catch (e: Exception) {
            ToolResult.error("Execution error: ${e.message}")
        }
    }
}

/** Placeholder used in JSON maps when a value is absent. */
private val JSON_NULL = org.json.JSONObject.NULL
