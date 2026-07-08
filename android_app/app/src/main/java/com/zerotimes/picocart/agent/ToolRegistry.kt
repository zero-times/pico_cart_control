package com.zerotimes.picocart.agent

import org.json.JSONObject

/**
 * Central registry of all available tool definitions for DeepSeek Tool Calls.
 *
 * Each tool is defined as a JSON Schema compatible with the DeepSeek API
 * /chat/completions `tools` parameter.
 *
 * V1 tools (all cart_*):
 *   cart_get_status, cart_stop, cart_set_mode, cart_move, cart_turn
 */
object ToolRegistry {

    /**
     * Return all defined tools regardless of state.
     */
    fun allTools(): List<JSONObject> = listOf(
        cartGetStatus(),
        cartStop(),
        cartSetMode(),
        cartMove(),
        cartTurn()
    )

    /**
     * Return tools available in the given [mode].
     *
     * In "fault" mode only read-only and stop tools are surfaced.
     * In all other modes all V1 tools are available.
     */
    fun availableTools(mode: String = "idle"): List<JSONObject> {
        return when (mode) {
            "fault" -> listOf(cartGetStatus(), cartStop())
            else -> allTools()
        }
    }

    // ------------------------------------------------------------------
    // Individual tool definitions
    // ------------------------------------------------------------------

    fun cartGetStatus(): JSONObject = JSONObject(
        """
        {
            "type": "function",
            "function": {
                "name": "cart_get_status",
                "description": "Read current cart status: battery voltage, estop state, mode, sensor values, and motor PWM.",
                "parameters": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": false
                }
            }
        }
        """.trimIndent()
    )

    fun cartStop(): JSONObject = JSONObject(
        """
        {
            "type": "function",
            "function": {
                "name": "cart_stop",
                "description": "Immediately stop the cart. This is the safe action whenever something is uncertain or an error occurs.",
                "parameters": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": false
                }
            }
        }
        """.trimIndent()
    )

    fun cartSetMode(): JSONObject = JSONObject(
        """
        {
            "type": "function",
            "function": {
                "name": "cart_set_mode",
                "description": "Switch the cart operating mode. Modes: manual (direct human control), debug (testing and calibration), tow (towing mode), assist (AI-assisted driving).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "mode": {
                            "type": "string",
                            "enum": ["manual", "debug", "tow", "assist"]
                        }
                    },
                    "required": ["mode"],
                    "additionalProperties": false
                }
            }
        }
        """.trimIndent()
    )

    fun cartMove(): JSONObject = JSONObject(
        """
        {
            "type": "function",
            "function": {
                "name": "cart_move",
                "description": "Move the cart forward or reverse for a short, limited duration. The Pico still applies speed limits and watchdog safety.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "direction": {
                            "type": "string",
                            "enum": ["forward", "reverse"]
                        },
                        "speed_level": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 3
                        },
                        "duration_ms": {
                            "type": "integer",
                            "minimum": 100,
                            "maximum": 800
                        }
                    },
                    "required": ["direction", "speed_level", "duration_ms"],
                    "additionalProperties": false
                }
            }
        }
        """.trimIndent()
    )

    fun cartTurn(): JSONObject = JSONObject(
        """
        {
            "type": "function",
            "function": {
                "name": "cart_turn",
                "description": "Turn the cart left or right in place for a short duration.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "direction": {
                            "type": "string",
                            "enum": ["left", "right"]
                        },
                        "speed_level": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 2
                        },
                        "duration_ms": {
                            "type": "integer",
                            "minimum": 100,
                            "maximum": 500
                        }
                    },
                    "required": ["direction", "speed_level", "duration_ms"],
                    "additionalProperties": false
                }
            }
        }
        """.trimIndent()
    )
}
