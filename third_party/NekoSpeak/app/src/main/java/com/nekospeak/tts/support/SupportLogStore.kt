package com.nekospeak.tts.support

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SupportLogStore {
    private const val TAG = "SupportLogStore"
    private const val MAX_LOG_BYTES = 1_048_576L

    fun log(context: Context, source: String, message: String, throwable: Throwable? = null) {
        val time = timestamp()
        val thread = Thread.currentThread().name
        val baseLine = "$time [$thread] $source: $message"
        appendLine(context, baseLine)

        if (throwable != null) {
            val stack = StringWriter().also { sw ->
                throwable.printStackTrace(PrintWriter(sw))
            }.toString()
            appendLine(context, stack)
        }
    }

    fun getLogFile(context: Context): File {
        val dir = File(context.filesDir, "support")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "events.log")
    }

    fun enforceLogRetention(context: Context) {
        val file = getLogFile(context)
        trimIfNeeded(file)
    }

    private fun appendLine(context: Context, line: String) {
        try {
            val file = getLogFile(context)
            trimIfNeeded(file)
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to append support log", t)
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) {
            return
        }

        try {
            val bytes = file.readBytes()
            val keep = (MAX_LOG_BYTES / 2L).toInt().coerceAtLeast(1)
            val start = (bytes.size - keep).coerceAtLeast(0)
            val slice = bytes.copyOfRange(start, bytes.size)
            file.writeBytes(slice)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to trim support log", t)
        }
    }

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return formatter.format(Date())
    }
}
