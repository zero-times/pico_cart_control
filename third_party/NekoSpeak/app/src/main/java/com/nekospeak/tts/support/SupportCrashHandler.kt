package com.nekospeak.tts.support

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object SupportCrashHandler {
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) {
            return
        }

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(appContext, thread, throwable)
                SupportLogStore.log(appContext, "CrashHandler", "Uncaught exception", throwable)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun getCrashFile(context: Context): File {
        val dir = File(context.filesDir, "support")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "last_crash.txt")
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val file = getCrashFile(context)
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val body = buildString {
            appendLine("timestamp: $stamp")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(sw.toString())
        }

        file.writeText(body)
    }
}
