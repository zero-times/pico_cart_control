package com.zerotimes.picocart.agent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SQLite-backed session store using SQLiteOpenHelper.
 *
 * Maintains four tables conforming to the V1 guide spec:
 *   sessions      – session metadata
 *   messages      – per-session chat history
 *   tool_calls    – per-session tool execution log
 *   cart_status_log – time-series cart status snapshots
 *
 * All public methods are thread-safe for single-writer access via
 * the underlying SQLiteDatabase locking.
 */
class SessionStore(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val DB_NAME = "agent_host.db"
        private const val DB_VERSION = 1

        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_TOOL_CALLS = "tool_calls"
        private const val TABLE_CART_STATUS_LOG = "cart_status_log"
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SESSIONS (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                summary TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT,
                name TEXT,
                tool_call_id TEXT,
                tool_calls_json TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_TOOL_CALLS (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                arguments_json TEXT NOT NULL,
                result_json TEXT,
                ok INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_CART_STATUS_LOG (
                id TEXT PRIMARY KEY,
                battery_v REAL,
                estop INTEGER NOT NULL DEFAULT 0,
                mode TEXT NOT NULL DEFAULT 'idle',
                fault TEXT,
                status_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CART_STATUS_LOG")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TOOL_CALLS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        onCreate(db)
    }

    // ------------------------------------------------------------------ sessions

    fun createSession(title: String = ""): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insert(TABLE_SESSIONS, null, values)
        return id
    }

    fun getSession(sessionId: String): Session? {
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, null, "id = ?", arrayOf(sessionId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToSession(it) else null
        }
    }

    fun getAllSessions(): List<Session> {
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, null, null, null, null, null, "updated_at DESC"
        )
        val list = mutableListOf<Session>()
        cursor.use {
            while (it.moveToNext()) list.add(cursorToSession(it))
        }
        return list
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        val values = ContentValues().apply {
            put("title", title)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_SESSIONS, values, "id = ?", arrayOf(sessionId))
    }

    fun updateSessionSummary(sessionId: String, summary: String) {
        val values = ContentValues().apply {
            put("summary", summary)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_SESSIONS, values, "id = ?", arrayOf(sessionId))
    }

    fun deleteSession(sessionId: String) {
        writableDatabase.delete(TABLE_MESSAGES, "session_id = ?", arrayOf(sessionId))
        writableDatabase.delete(TABLE_TOOL_CALLS, "session_id = ?", arrayOf(sessionId))
        writableDatabase.delete(TABLE_SESSIONS, "id = ?", arrayOf(sessionId))
    }

    // ------------------------------------------------------------------ messages

    fun addMessage(
        sessionId: String,
        role: String,
        content: String?,
        name: String? = null,
        toolCallId: String? = null,
        toolCalls: List<ToolCall>? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val toolCallsJson = if (toolCalls != null && toolCalls.isNotEmpty()) {
            JSONArray().apply {
                toolCalls.forEach { put(it.toJson()) }
            }.toString()
        } else null

        val values = ContentValues().apply {
            put("id", id)
            put("session_id", sessionId)
            put("role", role)
            if (content != null) put("content", content) else putNull("content")
            if (name != null) put("name", name)
            if (toolCallId != null) put("tool_call_id", toolCallId)
            if (toolCallsJson != null) put("tool_calls_json", toolCallsJson)
            put("created_at", now)
        }
        writableDatabase.insert(TABLE_MESSAGES, null, values)
        touchSession(sessionId)
        return id
    }

    /**
     * Convenience: insert a user message.
     */
    fun addUserMessage(sessionId: String, text: String): String {
        return addMessage(sessionId, "user", text)
    }

    /**
     * Convenience: insert an assistant text message.
     */
    fun addAssistantMessage(sessionId: String, text: String): String {
        return addMessage(sessionId, "assistant", text)
    }

    /**
     * Convenience: insert an assistant message containing tool_calls.
     */
    fun addAssistantToolCallMessage(sessionId: String, toolCalls: List<ToolCall>): String {
        return addMessage(sessionId, "assistant", content = null, toolCalls = toolCalls)
    }

    /**
     * Convenience: insert a tool-result message.
     */
    fun addToolResultMessage(sessionId: String, toolCallId: String, resultJson: String?): String {
        return addMessage(sessionId, "tool", resultJson, toolCallId = toolCallId)
    }

    /**
     * Returns the most recent N messages for a session, ordered oldest-first
     * (ready for API submission).
     */
    fun getRecentMessages(sessionId: String, limit: Int = 20): List<ChatMessage> {
        val cursor = readableDatabase.query(
            TABLE_MESSAGES, null, "session_id = ?", arrayOf(sessionId),
            null, null, "created_at DESC", limit.toString()
        )
        val messages = mutableListOf<ChatMessage>()
        cursor.use {
            while (it.moveToNext()) messages.add(cursorToChatMessage(it))
        }
        return messages.asReversed()
    }

    fun getMessageCount(sessionId: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE session_id = ?",
            arrayOf(sessionId)
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ------------------------------------------------------------------ tool call logs

    fun addToolCallLog(
        sessionId: String,
        toolName: String,
        argumentsJson: String,
        resultJson: String? = null,
        ok: Boolean = false,
        error: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", id)
            put("session_id", sessionId)
            put("tool_name", toolName)
            put("arguments_json", argumentsJson)
            if (resultJson != null) put("result_json", resultJson) else putNull("result_json")
            put("ok", if (ok) 1 else 0)
            if (error != null) put("error", error) else putNull("error")
            put("created_at", now)
        }
        writableDatabase.insert(TABLE_TOOL_CALLS, null, values)
        return id
    }

    fun getRecentToolCallLogs(sessionId: String, limit: Int = 10): List<ToolCallLog> {
        val cursor = readableDatabase.query(
            TABLE_TOOL_CALLS, null, "session_id = ?", arrayOf(sessionId),
            null, null, "created_at DESC", limit.toString()
        )
        val logs = mutableListOf<ToolCallLog>()
        cursor.use {
            while (it.moveToNext()) logs.add(cursorToToolCallLog(it))
        }
        return logs
    }

    // ------------------------------------------------------------------ cart status log

    fun addCartStatusLog(status: CartStatus): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val statusJson = status.toJson().toString()
        val values = ContentValues().apply {
            put("id", id)
            if (status.batteryV != null) put("battery_v", status.batteryV) else putNull("battery_v")
            put("estop", if (status.estop) 1 else 0)
            put("mode", status.mode)
            if (status.fault != null) put("fault", status.fault) else putNull("fault")
            put("status_json", statusJson)
            put("created_at", now)
        }
        writableDatabase.insert(TABLE_CART_STATUS_LOG, null, values)
        return id
    }

    fun getLatestCartStatus(): CartStatus? {
        val cursor = readableDatabase.query(
            TABLE_CART_STATUS_LOG, null, null, null, null, null,
            "created_at DESC", "1"
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(it.getColumnIndexOrThrow("status_json"))
                CartStatus.fromJson(JSONObject(json))
            } else null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun touchSession(sessionId: String) {
        val values = ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_SESSIONS, values, "id = ?", arrayOf(sessionId))
    }

    private fun cursorToSession(cursor: Cursor): Session {
        return Session(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")).orEmpty(),
            summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }

    private fun cursorToChatMessage(cursor: Cursor): ChatMessage {
        val role = cursor.getString(cursor.getColumnIndexOrThrow("role"))
        val contentIdx = cursor.getColumnIndexOrThrow("content")
        val content = if (cursor.isNull(contentIdx)) null else cursor.getString(contentIdx)
        val nameIdx = cursor.getColumnIndexOrThrow("name")
        val name = if (cursor.isNull(nameIdx)) null else cursor.getString(nameIdx)
        val toolCallIdIdx = cursor.getColumnIndexOrThrow("tool_call_id")
        val toolCallId = if (cursor.isNull(toolCallIdIdx)) null else cursor.getString(toolCallIdIdx)
        val toolCallsJsonIdx = cursor.getColumnIndexOrThrow("tool_calls_json")
        val toolCalls = if (cursor.isNull(toolCallsJsonIdx)) null else {
            val arr = JSONArray(cursor.getString(toolCallsJsonIdx))
            (0 until arr.length()).map { ToolCall.fromJson(arr.getJSONObject(it)) }
        }
        return ChatMessage(role, content, name, toolCallId, toolCalls)
    }

    private fun cursorToToolCallLog(cursor: Cursor): ToolCallLog {
        return ToolCallLog(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
            toolName = cursor.getString(cursor.getColumnIndexOrThrow("tool_name")),
            argumentsJson = cursor.getString(cursor.getColumnIndexOrThrow("arguments_json")),
            resultJson = cursor.getString(cursor.getColumnIndexOrThrow("result_json")),
            ok = cursor.getInt(cursor.getColumnIndexOrThrow("ok")) != 0,
            error = cursor.getString(cursor.getColumnIndexOrThrow("error")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        )
    }
}
