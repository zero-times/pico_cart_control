package com.zerotimes.picocart.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Represents a message in the chat conversation, compatible with DeepSeek API format.
 *
 * Maps to the "messages" array in /chat/completions requests.
 * Supports system, developer, user, assistant, and tool roles.
 */
data class ChatMessage(
    val role: String,
    val content: String?,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("role", role)
        if (content != null) obj.put("content", content) else obj.put("content", JSONObject.NULL)
        if (name != null) obj.put("name", name)
        if (toolCallId != null) obj.put("tool_call_id", toolCallId)
        if (toolCalls != null && toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            toolCalls.forEach { arr.put(it.toJson()) }
            obj.put("tool_calls", arr)
        }
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): ChatMessage {
            val role = json.optString("role", "")
            val content = if (json.has("content") && !json.isNull("content")) {
                json.optString("content")
            } else null
            val name = json.optionalString("name")
            val toolCallId = json.optionalString("tool_call_id")
            val toolCalls = if (json.has("tool_calls")) {
                val arr = json.getJSONArray("tool_calls")
                (0 until arr.length()).map { ToolCall.fromJson(arr.getJSONObject(it)) }
            } else null
            return ChatMessage(role, content, name, toolCallId, toolCalls)
        }
    }
}

/**
 * Represents a tool call returned by the model, or being sent back as part of an assistant message.
 */
data class ToolCall(
    val id: String = "call_" + UUID.randomUUID().toString().take(8),
    val type: String = "function",
    val function: ToolCallFunction
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("type", type)
        obj.put("function", function.toJson())
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): ToolCall {
            val id = json.optString("id", "")
            val type = json.optString("type", "function")
            val func = if (json.has("function")) {
                ToolCallFunction.fromJson(json.getJSONObject("function"))
            } else {
                ToolCallFunction("", "{}")
            }
            return ToolCall(id, type, func)
        }
    }
}

/**
 * The function name and arguments within a ToolCall.
 */
data class ToolCallFunction(
    val name: String,
    val arguments: String
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("arguments", arguments)
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): ToolCallFunction {
            val name = json.optString("name", "")
            val arguments = json.optString("arguments", "{}")
            return ToolCallFunction(name, arguments)
        }
    }
}

/**
 * Result from executing a tool call.
 */
data class ToolResult(
    val ok: Boolean,
    val resultJson: String? = null,
    val error: String? = null
) {
    companion object {
        fun success(data: Map<String, Any?> = emptyMap()): ToolResult {
            return ToolResult(true, JSONObject(data).toString())
        }

        fun error(message: String): ToolResult {
            return ToolResult(false, error = message)
        }
    }
}

/**
 * Parsed response from the DeepSeek /chat/completions endpoint.
 */
data class ChatResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val usage: Usage?,
    val finishReason: String?
)

/**
 * Token usage from the API response, including cache metrics.
 */
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val promptCacheHitTokens: Int? = null,
    val promptCacheMissTokens: Int? = null
)

/**
 * Cached cart hardware state, as reported by Pico over serial.
 */
data class CartStatus(
    val mode: String = "idle",
    val batteryV: Double? = null,
    val estop: Boolean = false,
    val fault: String? = null,
    val leftPwm: Double? = null,
    val rightPwm: Double? = null,
    val leftForce: Double? = null,
    val rightForce: Double? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("mode", mode)
        if (batteryV != null) obj.put("battery_v", batteryV)
        obj.put("estop", estop)
        if (fault != null) obj.put("fault", fault)
        if (leftPwm != null) obj.put("left_pwm", leftPwm)
        if (rightPwm != null) obj.put("right_pwm", rightPwm)
        if (leftForce != null) obj.put("left_force", leftForce)
        if (rightForce != null) obj.put("right_force", rightForce)
        return obj
    }

    fun toContentString(): String {
        val parts = mutableListOf("mode=$mode")
        if (batteryV != null) parts.add("battery_v=${"%.1f".format(batteryV)}")
        parts.add("estop=$estop")
        if (fault != null) parts.add("fault=$fault")
        if (leftPwm != null) parts.add("left_pwm=${"%.3f".format(leftPwm)}")
        if (rightPwm != null) parts.add("right_pwm=${"%.3f".format(rightPwm)}")
        return parts.joinToString(", ")
    }

    companion object {
        fun fromJson(json: JSONObject): CartStatus {
            return CartStatus(
                mode = json.optString("mode", "idle"),
                batteryV = json.optDouble("battery_v", Double.NaN).takeIf { !it.isNaN() },
                estop = json.optBoolean("estop", false),
                fault = json.optionalString("fault"),
                leftPwm = json.optDouble("left_pwm", Double.NaN).takeIf { !it.isNaN() },
                rightPwm = json.optDouble("right_pwm", Double.NaN).takeIf { !it.isNaN() },
                leftForce = json.optDouble("left_force", Double.NaN).takeIf { !it.isNaN() },
                rightForce = json.optDouble("right_force", Double.NaN).takeIf { !it.isNaN() }
            )
        }
    }
}

private fun JSONObject.optionalString(key: String): String? {
    return if (has(key) && !isNull(key)) {
        optString(key).takeIf { it.isNotEmpty() }
    } else {
        null
    }
}

/**
 * A persisted session metadata row.
 */
data class Session(
    val id: String,
    val title: String,
    val summary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A persisted tool call log row.
 */
data class ToolCallLog(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String? = null,
    val ok: Boolean = false,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A persisted cart status snapshot row.
 */
data class CartStatusLog(
    val id: String,
    val batteryV: Double? = null,
    val estop: Boolean = false,
    val mode: String = "idle",
    val fault: String? = null,
    val statusJson: String,
    val createdAt: Long = System.currentTimeMillis()
)
