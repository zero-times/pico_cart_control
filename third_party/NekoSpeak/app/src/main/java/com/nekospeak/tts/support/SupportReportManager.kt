package com.nekospeak.tts.support

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.nekospeak.tts.BuildConfig
import com.nekospeak.tts.data.PrefsManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SupportReportManager {
    private const val REPORT_DIR_NAME = "support_reports"
    private const val MAX_REPORT_COUNT = 10
    private const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L
    private const val TTL_MILLIS = 14L * 24L * 60L * 60L * 1000L

    fun createReport(context: Context): File {
        val appContext = context.applicationContext
        cleanupReports(appContext)

        val reportDir = reportDir(appContext)
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }

        val name = "support_report_${timestampForFile()}.zip"
        val reportFile = File(reportDir, name)

        val metadata = collectMetadata(appContext)
        val prefsSnapshot = collectPrefsSnapshot(appContext)

        ZipOutputStream(FileOutputStream(reportFile)).use { zip ->
            writeTextEntry(zip, "meta.json", mapToJson(metadata))
            writeTextEntry(zip, "prefs_snapshot.json", mapToJson(prefsSnapshot))

            val logFile = SupportLogStore.getLogFile(appContext)
            if (logFile.exists()) {
                writeFileEntry(zip, "events.log", logFile)
            }

            val crashFile = SupportCrashHandler.getCrashFile(appContext)
            if (crashFile.exists()) {
                writeFileEntry(zip, "last_crash.txt", crashFile)
            }
        }

        SupportLogStore.log(appContext, "SupportReport", "Generated report: ${reportFile.name}")
        cleanupReports(appContext)
        return reportFile
    }

    fun cleanupReports(context: Context) {
        val appContext = context.applicationContext
        val dir = reportDir(appContext)
        if (!dir.exists()) {
            return
        }

        val now = System.currentTimeMillis()
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) == "zip" }
            ?.toMutableList()
            ?: mutableListOf()

        files.filter { now - it.lastModified() > TTL_MILLIS }.forEach { it.delete() }

        val fresh = dir.listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) == "zip" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList()
            ?: mutableListOf()

        while (fresh.size > MAX_REPORT_COUNT) {
            val oldest = fresh.removeLast()
            oldest.delete()
        }

        var totalBytes = fresh.sumOf { it.length() }
        if (totalBytes > MAX_TOTAL_BYTES) {
            val oldestFirst = fresh.sortedBy { it.lastModified() }.toMutableList()
            while (totalBytes > MAX_TOTAL_BYTES && oldestFirst.isNotEmpty()) {
                val file = oldestFirst.removeFirst()
                totalBytes -= file.length()
                file.delete()
            }
        }

        SupportLogStore.enforceLogRetention(appContext)
    }

    fun latestReport(context: Context): File? {
        val dir = reportDir(context.applicationContext)
        if (!dir.exists()) {
            return null
        }

        return dir.listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) == "zip" }
            ?.maxByOrNull { it.lastModified() }
    }

    fun collectMetadata(context: Context): Map<String, String> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = PrefsManager(context)

        return linkedMapOf(
            "generated_at" to isoTimestamp(),
            "app_version_name" to BuildConfig.VERSION_NAME,
            "app_version_code" to BuildConfig.VERSION_CODE.toString(),
            "application_id" to BuildConfig.APPLICATION_ID,
            "build_type" to BuildConfig.BUILD_TYPE,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "device" to Build.DEVICE,
            "android_version" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT.toString(),
            "abis" to Build.SUPPORTED_ABIS.joinToString(","),
            "memory_class_mb" to activityManager.memoryClass.toString(),
            "large_memory_class_mb" to activityManager.largeMemoryClass.toString(),
            "usable_storage_bytes" to context.filesDir.usableSpace.toString(),
            "selected_model" to prefs.currentModel,
            "selected_voice" to prefs.currentVoice
        )
    }

    private fun collectPrefsSnapshot(context: Context): Map<String, String> {
        val prefs = PrefsManager(context)
        return linkedMapOf(
            "current_model" to prefs.currentModel,
            "current_voice" to prefs.currentVoice,
            "cpu_threads" to prefs.cpuThreads.toString(),
            "speech_speed" to prefs.speechSpeed.toString(),
            "stream_token_size" to prefs.streamTokenSize.toString(),
            "pocket_temperature" to prefs.pocketTemperature.toString(),
            "pocket_lsd_steps" to prefs.pocketLsdSteps.toString(),
            "pocket_frames_after_eos" to prefs.pocketFramesAfterEos.toString(),
            "pocket_decoding_mode" to prefs.pocketDecodingMode,
            "pocket_decode_chunk_size" to prefs.pocketDecodeChunkSize.toString(),
            "theme" to prefs.appTheme,
            "dark_mode" to prefs.darkMode
        )
    }

    private fun reportDir(context: Context): File = File(context.cacheDir, REPORT_DIR_NAME)

    private fun timestampForFile(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return formatter.format(Date())
    }

    private fun isoTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return formatter.format(Date())
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeFileEntry(zip: ZipOutputStream, name: String, source: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(source).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun mapToJson(map: Map<String, String>): String {
        return buildString {
            append("{\n")
            map.entries.forEachIndexed { index, (key, value) ->
                append("  \"")
                append(escapeJson(key))
                append("\": \"")
                append(escapeJson(value))
                append("\"")
                if (index < map.size - 1) {
                    append(",")
                }
                append("\n")
            }
            append("}\n")
        }
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length + 16) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
