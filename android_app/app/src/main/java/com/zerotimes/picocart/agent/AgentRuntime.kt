package com.zerotimes.picocart.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AgentRuntimeEvent(
    val label: String,
    val detail: String,
    val ok: Boolean? = null,
)

data class AgentTurnResult(
    val text: String,
    val speakText: String? = null,
    val usage: Usage? = null,
)

class AgentRuntime(
    private val store: SessionStore,
    private val hardware: CartHardware,
    private val safetyGuard: SafetyGuard = SafetyGuard(maxToolRounds = MAX_TOOL_ROUNDS),
) {
    suspend fun runTurn(
        sessionId: String,
        userText: String,
        apiKey: String,
        cartStatus: CartStatus,
        movementUnlocked: Boolean,
        onEvent: suspend (AgentRuntimeEvent) -> Unit = {},
    ): AgentTurnResult = withContext(Dispatchers.IO) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            return@withContext AgentTurnResult("请输入要让助手处理的内容。")
        }
        if (apiKey.isBlank()) {
            return@withContext AgentTurnResult("请先填写 DeepSeek API Key。")
        }

        store.addUserMessage(sessionId, trimmed)
        store.addCartStatusLog(cartStatus)

        val client = DeepSeekClient(apiKey = apiKey.trim())
        val tools = ToolRegistry.availableTools(
            mode = if (cartStatus.fault != null) "fault" else cartStatus.mode,
        )
        var messages = buildMessages(sessionId, cartStatus)
        var lastUsage: Usage? = null

        repeat(MAX_TOOL_ROUNDS) { round ->
            val roundCheck = safetyGuard.checkToolRoundLimit(round)
            if (!roundCheck.ok) {
                hardware.stop()
                val text = "工具调用轮次过多，已停车并停止本轮操作。"
                store.addAssistantMessage(sessionId, text)
                onEvent(AgentRuntimeEvent("safety", roundCheck.reason ?: text, ok = false))
                return@withContext AgentTurnResult(text = text, speakText = "工具调用太多了，我已停车。", usage = lastUsage)
            }

            val response = client.chat(
                messages = messages,
                tools = tools,
                maxTokens = 900,
                temperature = 0.1,
            ).getOrElse { error ->
                val text = "DeepSeek 请求失败：${error.message ?: error::class.java.simpleName}"
                store.addAssistantMessage(sessionId, text)
                return@withContext AgentTurnResult(text = text, speakText = "DeepSeek 请求失败，请检查网络或密钥。", usage = lastUsage)
            }

            lastUsage = response.usage

            if (response.toolCalls.isEmpty()) {
                val parsed = parseMamboResponse(response.text?.takeIf { it.isNotBlank() } ?: "本轮没有返回文本。")
                store.addAssistantMessage(sessionId, parsed.visibleText)
                return@withContext AgentTurnResult(parsed.visibleText, parsed.speakText, lastUsage)
            }

            val assistantMessage = ChatMessage(
                role = "assistant",
                content = response.text,
                toolCalls = response.toolCalls,
            )
            store.addAssistantToolCallMessage(sessionId, response.toolCalls)
            messages = messages + assistantMessage

            response.toolCalls.forEach { call ->
                val validation = safetyGuard.validate(
                    toolCall = call,
                    cartStatus = cartStatus,
                    isMovementLocked = !movementUnlocked,
                )
                val result = if (validation.ok) {
                    onEvent(AgentRuntimeEvent("tool", call.function.name, ok = null))
                    hardwareAwareExecute(call)
                } else {
                    ToolResult.error(validation.reason ?: "工具调用被安全策略拒绝")
                }

                store.addToolCallLog(
                    sessionId = sessionId,
                    toolName = call.function.name,
                    argumentsJson = call.function.arguments,
                    resultJson = result.asMessageContent(),
                    ok = result.ok,
                    error = result.error,
                )
                store.addToolResultMessage(sessionId, call.id, result.asMessageContent())
                onEvent(
                    AgentRuntimeEvent(
                        label = call.function.name,
                        detail = result.error ?: result.resultJson ?: "{}",
                        ok = result.ok,
                    ),
                )
                messages = messages + ChatMessage(
                    role = "tool",
                    content = result.asMessageContent(),
                    toolCallId = call.id,
                )
            }
        }

        hardware.stop()
        val text = "工具调用轮次过多，已停车并停止本轮操作。"
        store.addAssistantMessage(sessionId, text)
        AgentTurnResult(text = text, speakText = "工具调用太多了，我已停车。", usage = lastUsage)
    }

    private suspend fun hardwareAwareExecute(call: ToolCall): ToolResult {
        return CartToolExecutor(hardware).execute(call)
    }

    private fun buildMessages(
        sessionId: String,
        cartStatus: CartStatus,
    ): List<ChatMessage> {
        return listOf(
            ChatMessage(
                role = "system",
                content = "你叫曼波，是轻便取快递牵引小车的车载助手。你有稳定、谨慎、简洁的角色属性：先确认安全，再调用工具，最后用中文解释状态。",
            ),
            ChatMessage(
                role = "system",
                name = "mambo_voice_contract",
                content = "最终给用户的普通响应照常输出；另外必须单独提供一段适合朗读的短句，格式严格为 <mambo_say>这里写曼波要朗读的话</mambo_say>。朗读段不超过 60 个汉字，不包含 JSON、工具名或尖括号。",
            ),
            ChatMessage(
                role = "system",
                name = "safety_rules",
                content = "不能调用裸 PWM，不能解除物理急停，所有移动必须短时限速。遇到急停、fault、状态不明或工具失败时，先调用 cart_stop 或解释拒绝原因。",
            ),
            ChatMessage(
                role = "system",
                name = "cart_capabilities",
                content = "硬件：Android App 通过 BLE UART 给 Pico 发送安全命令；Pico 保留看门狗、缓启动、急停和传感器异常停车。可用工具只有 cart_get_status/cart_stop/cart_set_mode/cart_move/cart_turn。",
            ),
            ChatMessage(
                role = "system",
                name = "cart_status",
                content = cartStatus.toJson().toString(),
            ),
        ) + store.getRecentMessages(sessionId, limit = 20)
    }

    private fun parseMamboResponse(text: String): MamboParsedResponse {
        val match = MAMBO_SAY_REGEX.find(text)
        val speak = match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val visible = text.replace(MAMBO_SAY_REGEX, "").trim()
        val cleanVisible = visible.ifBlank { speak ?: text.trim() }
        val cleanSpeak = speak ?: cleanVisible.take(120)
        return MamboParsedResponse(cleanVisible, cleanSpeak)
    }

    private fun ToolResult.asMessageContent(): String {
        return if (ok) {
            resultJson ?: JSONObject().put("ok", true).toString()
        } else {
            JSONObject()
                .put("ok", false)
                .put("error", error ?: "unknown tool error")
                .toString()
        }
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 4
        val MAMBO_SAY_REGEX = Regex("<mambo_say>(.*?)</mambo_say>", RegexOption.DOT_MATCHES_ALL)
    }
}

private data class MamboParsedResponse(
    val visibleText: String,
    val speakText: String,
)
