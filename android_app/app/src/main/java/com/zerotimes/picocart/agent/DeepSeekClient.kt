package com.zerotimes.picocart.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight DeepSeek API client using HttpURLConnection and org.json.
 *
 * Supports /chat/completions with tools/tool_calls and response_format.
 * All network I/O is dispatched on [Dispatchers.IO].
 *
 * Usage:
 *   val client = DeepSeekClient(apiKey = "<deepseek-api-key>")
 *   val response = client.chat(messages, tools)
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-v4-flash",
    private val thinkingEnabled: Boolean = false,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000
) {

    data class Config(
        val apiKey: String,
        val baseUrl: String = "https://api.deepseek.com",
        val model: String = "deepseek-v4-flash",
        val thinkingEnabled: Boolean = false
    )

    /**
     * Send a chat completion request. Returns [Result.success] with a
     * [ChatResponse] on HTTP 200, or [Result.failure] on error.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<JSONObject>? = null,
        responseFormat: JSONObject? = null,
        maxTokens: Int? = null,
        temperature: Double? = 0.0
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildRequestBody(messages, tools, responseFormat, maxTokens, temperature)
            val responseJson = executePost("/chat/completions", requestBody)
            val chatResponse = parseChatResponse(responseJson)
            Result.success(chatResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ internal

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<JSONObject>?,
        responseFormat: JSONObject?,
        maxTokens: Int?,
        temperature: Double?
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))

        val messagesArr = JSONArray()
        messages.forEach { messagesArr.put(it.toJson()) }
        body.put("messages", messagesArr)

        if (tools != null && tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            tools.forEach { toolsArr.put(it) }
            body.put("tools", toolsArr)
        }

        if (responseFormat != null) {
            body.put("response_format", responseFormat)
        }

        if (maxTokens != null) body.put("max_tokens", maxTokens)
        if (temperature != null) body.put("temperature", temperature)
        body.put("stream", false)

        return body
    }

    /**
     * Execute an HTTP POST and return the parsed JSON response body.
     */
    private fun executePost(path: String, body: JSONObject): JSONObject {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            val responseText = reader.readText()

            if (responseCode !in 200..299) {
                throw RuntimeException(
                    "API error $responseCode: ${responseText.take(500)}"
                )
            }

            return JSONObject(responseText)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parse DeepSeek /chat/completions response into [ChatResponse].
     */
    private fun parseChatResponse(json: JSONObject): ChatResponse {
        val choices = json.optJSONArray("choices")
        val choice = choices?.optJSONObject(0)
        val message = choice?.optJSONObject("message")

        val content = message?.optionalString("content")
        val finishReason = choice?.optionalString("finish_reason")

        val toolCalls = if (message?.has("tool_calls") == true) {
            val arr = message.getJSONArray("tool_calls")
            (0 until arr.length()).map { ToolCall.fromJson(arr.getJSONObject(it)) }
        } else emptyList()

        val usage = json.optJSONObject("usage")?.let { u ->
            Usage(
                promptTokens = u.optInt("prompt_tokens", 0),
                completionTokens = u.optInt("completion_tokens", 0),
                totalTokens = u.optInt("total_tokens", 0),
                promptCacheHitTokens = u.optInt("prompt_cache_hit_tokens", -1)
                    .takeIf { it >= 0 },
                promptCacheMissTokens = u.optInt("prompt_cache_miss_tokens", -1)
                    .takeIf { it >= 0 }
            )
        }

        return ChatResponse(
            text = content,
            toolCalls = toolCalls,
            usage = usage,
            finishReason = finishReason
        )
    }
}

private fun JSONObject.optionalString(key: String): String? {
    return if (has(key) && !isNull(key)) {
        optString(key).takeIf { it.isNotEmpty() }
    } else {
        null
    }
}
