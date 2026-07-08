package com.zerotimes.picocart.agent

import org.json.JSONObject

/**
 * V1 safety guard that enforces the guide's safety rules before any tool is executed.
 *
 * Android-layer rules (Pico still provides its own hardware-level safety):
 *   1. Estop active → block all movement tools.
 *   2. Fault state → only get_status and stop allowed.
 *   3. cart_move / cart_turn must have duration_ms.
 *   4. Duration and speed level must not exceed configured limits.
 *   5. Reverse speed is capped lower than forward.
 *   6. If user has locked movement, block movement tools.
 *   7. Tool-call round limit enforcement.
 *
 * None of the "raw" tools (set_left_pwm, raw_gpio_write, disable_estop, etc.)
 * are defined in ToolRegistry, so they are rejected at the registry level;
 * this guard catches anything that slips through.
 */
class SafetyGuard(
    private val maxToolRounds: Int = 10,
    private val maxMoveDurationMs: Int = 800,
    private val maxTurnDurationMs: Int = 500,
    private val maxSpeedLevel: Int = 3,
    private val reverseMaxSpeedLevel: Int = 2
) {

    data class ValidationResult(
        val ok: Boolean,
        val reason: String? = null
    ) {
        companion object {
            fun allowed() = ValidationResult(true)
            fun denied(reason: String) = ValidationResult(false, reason)
        }
    }

    /**
     * Validate a single tool call before execution.
     *
     * @param toolCall  the tool call returned by the model
     * @param cartStatus  current cart hardware state (may be null if unknown)
     * @param isMovementLocked  true when the user has locked all movement
     */
    fun validate(
        toolCall: ToolCall,
        cartStatus: CartStatus?,
        isMovementLocked: Boolean
    ): ValidationResult {
        val name = toolCall.function.name
        val args = try {
            JSONObject(toolCall.function.arguments)
        } catch (e: Exception) {
            return ValidationResult.denied("Invalid arguments JSON: ${e.message}")
        }

        // --- Reject any tool not in the allow-list ---
        if (!isKnownTool(name)) {
            return ValidationResult.denied("Unknown or disallowed tool: '$name'")
        }

        // Rule 1: Estop blocks all movement-affecting tools
        if (cartStatus?.estop == true && isMovementTool(name)) {
            return ValidationResult.denied(
                "Estop is active — all movement tools are blocked. " +
                        "Only cart_get_status and cart_stop are allowed."
            )
        }

        // Rule 2: Fault state severely restricts tool access
        if (cartStatus?.fault != null && !isAllowedDuringFault(name)) {
            return ValidationResult.denied(
                "Fault state (${cartStatus.fault}) — only cart_get_status and cart_stop are allowed."
            )
        }

        // Rule 3: Movement tools must carry duration_ms
        if ((name == "cart_move" || name == "cart_turn") && !args.has("duration_ms")) {
            return ValidationResult.denied(
                "$name requires 'duration_ms' parameter"
            )
        }

        if (name == "cart_set_mode") {
            val mode = args.optString("mode", "")
            if (mode !in setOf("manual", "debug", "tow", "assist")) {
                return ValidationResult.denied("Invalid mode '$mode'")
            }
        }

        // Rule 4a: Duration limits for cart_move
        if (name == "cart_move") {
            val direction = args.optString("direction", "")
            if (direction !in setOf("forward", "reverse")) {
                return ValidationResult.denied("cart_move direction must be forward or reverse")
            }
            val duration = args.optInt("duration_ms", 0)
            if (duration > maxMoveDurationMs) {
                return ValidationResult.denied(
                    "cart_move duration $duration ms exceeds maximum $maxMoveDurationMs ms"
                )
            }
            if (duration < 100) {
                return ValidationResult.denied(
                    "cart_move duration $duration ms is below minimum 100 ms"
                )
            }
        }

        // Rule 4b: Duration limits for cart_turn
        if (name == "cart_turn") {
            val direction = args.optString("direction", "")
            if (direction !in setOf("left", "right")) {
                return ValidationResult.denied("cart_turn direction must be left or right")
            }
            val duration = args.optInt("duration_ms", 0)
            if (duration > maxTurnDurationMs) {
                return ValidationResult.denied(
                    "cart_turn duration $duration ms exceeds maximum $maxTurnDurationMs ms"
                )
            }
            if (duration < 100) {
                return ValidationResult.denied(
                    "cart_turn duration $duration ms is below minimum 100 ms"
                )
            }
        }

        // Rule 5: Speed level bounds (including reverse cap)
        if (name == "cart_move" || name == "cart_turn") {
            val speedLevel = args.optInt("speed_level", 1)
            val direction = args.optString("direction", "forward")

            if (speedLevel < 1) {
                return ValidationResult.denied("speed_level must be at least 1")
            }

            if (direction == "reverse" && speedLevel > reverseMaxSpeedLevel) {
                return ValidationResult.denied(
                    "reverse speed_level $speedLevel exceeds maximum $reverseMaxSpeedLevel"
                )
            }

            if (name == "cart_move" && speedLevel > maxSpeedLevel) {
                return ValidationResult.denied(
                    "speed_level $speedLevel exceeds maximum $maxSpeedLevel"
                )
            }

            // cart_turn is capped at speed_level 2 by schema
            if (name == "cart_turn" && speedLevel > 2) {
                return ValidationResult.denied(
                    "cart_turn speed_level must be 1 or 2"
                )
            }
        }

        // Rule 6: Movement lock
        if (isMovementLocked && isMovementTool(name)) {
            return ValidationResult.denied(
                "Movement is locked. User must unlock movement in the UI first."
            )
        }

        return ValidationResult.allowed()
    }

    /**
     * Check whether the current tool-call round number is within the limit.
     * Should be called once per round before the first tool in that round.
     */
    fun checkToolRoundLimit(round: Int): ValidationResult {
        if (round >= maxToolRounds) {
            return ValidationResult.denied(
                "Tool call round $round exceeds maximum $maxToolRounds — stopping."
            )
        }
        return ValidationResult.allowed()
    }

    // ------------------------------------------------------------------ internal

    /** Set of tool names that affect the cart's physical state. */
    private fun isMovementTool(name: String): Boolean {
        return name in setOf(
            "cart_move", "cart_turn", "cart_run_motor_test"
        )
    }

    /** Tools allowed while the cart is in a fault state. */
    private fun isAllowedDuringFault(name: String): Boolean {
        return name in setOf("cart_get_status", "cart_stop")
    }

    /** Tools that exist in the V1 ToolRegistry. */
    private fun isKnownTool(name: String): Boolean {
        return name in setOf(
            "cart_get_status", "cart_stop", "cart_set_mode",
            "cart_move", "cart_turn"
        )
    }
}
