package com.zerotimes.picocart.agent

/**
 * High-level session manager that wraps [SessionStore] with context assembly
 * helpers for the DeepSeek API.
 *
 * Handles:
 *   - Assembling the full message array (system + developer + status + history + user)
 *   - Appending tool call / tool result messages during tool-call loops
 *   - Checking message count for summary triggers
 */
class SessionManager(
    private val store: SessionStore
) {

    companion object {
        /** System prompt describing the cart assistant role. */
        const val DEFAULT_SYSTEM_PROMPT: String =
            "You are the AI assistant for a lightweight electric tug cart. " +
                    "Your role is to understand the user's intent, invoke safe tools, " +
                    "and explain cart status in plain language."

        /** Developer prompt with safety instructions. */
        const val DEFAULT_DEVELOPER_PROMPT: String =
            "You can only call the tools provided to you. " +
                    "Never attempt to control motors directly. " +
                    "Always call cart_stop if something seems wrong. " +
                    "Keep all movements short and slow. " +
                    "Do not suggest disabling safety features."

        /** Maximum recent messages to include in context. */
        const val DEFAULT_RECENT_LIMIT: Int = 20
    }

    /**
     * Build the full messages array for a /chat/completions request.
     *
     * Order (optimized for context caching):
     *   1. system – stable role prompt
     *   2. system name=safety_rules – stable safety rules
     *   3. system name=cart_status – current hardware state (changes per turn)
     *   4. recent messages from session history
     *   5. user – current input
     */
    fun buildContext(
        sessionId: String,
        userText: String,
        cartStatus: CartStatus?,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        developerPrompt: String = DEFAULT_DEVELOPER_PROMPT,
        recentLimit: Int = DEFAULT_RECENT_LIMIT
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // 1. System prompt
        messages.add(ChatMessage(role = "system", content = systemPrompt))

        // 2. Safety prompt. DeepSeek's Chat Completion schema accepts
        // system/user/assistant/tool roles, so keep project "developer"
        // rules as a named system message for API compatibility.
        messages.add(ChatMessage(role = "system", content = developerPrompt, name = "safety_rules"))

        // 3. Current cart status (as a named system message for clarity)
        if (cartStatus != null) {
            messages.add(
                ChatMessage(
                    role = "system",
                    content = cartStatus.toContentString(),
                    name = "cart_status"
                )
            )
        }

        // 4. Recent conversation history
        val history = store.getRecentMessages(sessionId, limit = recentLimit)
        messages.addAll(history)

        // 5. Current user input
        messages.add(ChatMessage(role = "user", content = userText))

        return messages
    }

    /**
     * Append an assistant tool-call message and a tool-result message to the
     * store, returning the updated message list for the next API call.
     *
     * Used inside the tool-call loop.
     */
    fun appendToolRound(
        sessionId: String,
        currentMessages: List<ChatMessage>,
        assistantToolMessage: ChatMessage,
        toolCall: ToolCall,
        result: ToolResult
    ): List<ChatMessage> {
        // Persist
        store.addAssistantToolCallMessage(sessionId, assistantToolMessage.toolCalls ?: listOf(toolCall))
        store.addToolResultMessage(sessionId, toolCall.id, result.resultJson)

        // Also log to tool_calls table
        store.addToolCallLog(
            sessionId = sessionId,
            toolName = toolCall.function.name,
            argumentsJson = toolCall.function.arguments,
            resultJson = result.resultJson,
            ok = result.ok,
            error = result.error
        )

        // Build updated list: previous messages + assistant tool_call + tool result
        val updated = currentMessages.toMutableList()
        updated.add(assistantToolMessage)
        updated.add(
            ChatMessage(
                role = "tool",
                content = result.resultJson ?: result.error ?: "{}",
                toolCallId = toolCall.id
            )
        )
        return updated
    }

    /**
     * After a successful chat response, persist the assistant's reply.
     */
    fun persistAssistantReply(sessionId: String, response: ChatResponse) {
        if (response.toolCalls.isNotEmpty()) {
            store.addAssistantToolCallMessage(sessionId, response.toolCalls)
        } else if (response.text != null) {
            store.addAssistantMessage(sessionId, response.text)
        }
    }

    /**
     * Check if the session's message count exceeds a threshold that would
     * trigger a summary update.
     */
    fun shouldUpdateSummary(sessionId: String, threshold: Int = 30): Boolean {
        return store.getMessageCount(sessionId) >= threshold
    }
}
