package com.zerotimes.picocart.logging

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class PersistentDebugLogStore(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()
    private val timeLock = Any()
    private val lineTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logDir = (appContext.getExternalFilesDir(null) ?: appContext.filesDir)
        .resolve(LOG_DIR_NAME)
    private val appLogFile = logDir.resolve(APP_LOG_FILE_NAME)
    private val voiceLogFile = logDir.resolve(VOICE_LOG_FILE_NAME)

    val appLogPath: String
        get() = appLogFile.absolutePath

    val voiceLogPath: String
        get() = voiceLogFile.absolutePath

    fun appendApp(message: String) {
        append(appLogFile, "app", message)
    }

    fun appendHardware(message: String) {
        append(appLogFile, "hardware", message)
    }

    fun appendAgent(role: String, message: String, ok: Boolean?) {
        val status = ok?.let { if (it) " ok=1" else " ok=0" }.orEmpty()
        append(appLogFile, "agent/$role", "$message$status")
    }

    fun appendVoice(message: String) {
        append(voiceLogFile, "voice", message)
    }

    fun close() {
        runBlocking {
            withTimeoutOrNull(CLOSE_FLUSH_TIMEOUT_MS) {
                scope.coroutineContext[Job]?.children?.toList().orEmpty().joinAll()
            }
        }
        scope.cancel()
    }

    private fun append(file: File, category: String, message: String) {
        val line = buildLine(category, message)
        scope.launch {
            runCatching {
                synchronized(fileLock) {
                    logDir.mkdirs()
                    rotateIfNeeded(file)
                    file.appendText(line, Charsets.UTF_8)
                }
            }
        }
    }

    private fun buildLine(category: String, message: String): String {
        val timestamp = synchronized(timeLock) { lineTimeFormat.format(Date()) }
        val compactCategory = category.replace(Regex("[^A-Za-z0-9_./-]"), "_")
        val compactMessage = message
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return "$timestamp [$compactCategory] $compactMessage\n"
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) {
            return
        }
        val oldest = file.rotated(BACKUP_COUNT)
        if (oldest.exists()) {
            oldest.delete()
        }
        for (index in BACKUP_COUNT - 1 downTo 1) {
            val source = file.rotated(index)
            if (source.exists()) {
                source.renameTo(file.rotated(index + 1))
            }
        }
        file.renameTo(file.rotated(1))
    }

    private fun File.rotated(index: Int): File =
        File(parentFile, "$name.$index")

    private companion object {
        const val LOG_DIR_NAME = "logs"
        const val APP_LOG_FILE_NAME = "pico_cart_debug.log"
        const val VOICE_LOG_FILE_NAME = "mambo_voice_debug.log"
        const val MAX_LOG_BYTES = 2L * 1024L * 1024L
        const val BACKUP_COUNT = 3
        const val CLOSE_FLUSH_TIMEOUT_MS = 350L
    }
}
