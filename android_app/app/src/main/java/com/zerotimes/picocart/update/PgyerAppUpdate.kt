package com.zerotimes.picocart.update

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class PgyerAppRelease(
    val hasNewVersion: Boolean,
    val forceUpdate: Boolean,
    val versionName: String,
    val versionCode: Int?,
    val buildNumber: Int?,
    val downloadUrl: String,
    val installPageUrl: String,
    val updateDescription: String,
    val fileSizeBytes: Long?,
) {
    val displayVersion: String
        get() = buildString {
            append(versionName.ifBlank { "未知版本" })
            versionCode?.let { append(" ($it)") }
            buildNumber?.let { append(" · #$it") }
        }
}

class PgyerAppUpdateClient(
    private val apiKey: String,
    private val appKey: String,
    private val apiBase: String = DEFAULT_API_BASE,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 20_000,
) {
    fun check(currentVersionName: String, currentVersionCode: Int): PgyerAppRelease {
        require(apiKey.isNotBlank()) { "missing Pgyer API key" }
        require(appKey.isNotBlank()) { "missing Pgyer app key" }
        val body = linkedMapOf(
            "_api_key" to apiKey,
            "appKey" to appKey,
            "buildVersion" to currentVersionName,
            "buildVersionNo" to currentVersionCode.toString(),
        )
        val payload = postForm("$apiBase/apiv2/app/check", body)
        val code = payload.intField("code") ?: -1
        if (code != 0) {
            throw IllegalStateException(payload.stringField("message").ifBlank { "Pgyer check failed" })
        }
        return parseCheck(payload.objectField("data"))
    }

    private fun postForm(url: String, fields: Map<String, String>): Map<String, Any?> {
        val encoded = fields.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, UTF8) + "=" + URLEncoder.encode(value, UTF8)
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { output ->
                output.write(encoded.toByteArray(StandardCharsets.UTF_8))
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val raw = stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return parseObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_API_BASE = "https://api.pgyer.com"
        private const val UTF8 = "UTF-8"

        fun parseCheck(raw: String): PgyerAppRelease = parseCheck(parseObject(raw))

        fun parseCheck(data: Map<String, Any?>): PgyerAppRelease {
            val downloadUrl = data.stringField("downloadURL").ifBlank { data.stringField("downloadUrl") }
            val shortcut = data.stringField("buildShortcutUrl")
            val installPage = when {
                shortcut.startsWith("http") -> shortcut
                shortcut.isNotBlank() -> "https://www.pgyer.com/$shortcut"
                else -> data.stringField("appURl").ifBlank { data.stringField("appURL") }
            }
            return PgyerAppRelease(
                hasNewVersion = data.boolField("buildHaveNewVersion"),
                forceUpdate = data.boolField("needForceUpdate"),
                versionName = data.stringField("buildVersion"),
                versionCode = data.intField("buildVersionNo"),
                buildNumber = data.intField("buildBuildVersion"),
                downloadUrl = downloadUrl,
                installPageUrl = installPage,
                updateDescription = data.stringField("buildUpdateDescription").ifBlank {
                    data.stringField("buildDescription")
                },
                fileSizeBytes = data.longField("buildFileSize"),
            )
        }

        fun formatFileSize(bytes: Long?): String {
            if (bytes == null || bytes <= 0L) return ""
            val mb = bytes / (1024.0 * 1024.0)
            return "%.1f MB".format(Locale.US, mb)
        }

        fun parseObject(raw: String): Map<String, Any?> {
            val parser = LightJsonParser(raw)
            val value = parser.parseValue()
            parser.skipWhitespace()
            @Suppress("UNCHECKED_CAST")
            return value as? Map<String, Any?> ?: emptyMap()
        }

        private fun Map<String, Any?>.stringField(key: String): String {
            val value = this[key] ?: return ""
            return when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> ""
            }
        }

        private fun Map<String, Any?>.boolField(key: String): Boolean {
            return when (val value = this[key]) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.equals("true", ignoreCase = true) || value == "1"
                else -> false
            }
        }

        private fun Map<String, Any?>.intField(key: String): Int? {
            return when (val value = this[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }

        private fun Map<String, Any?>.longField(key: String): Long? {
            return when (val value = this[key]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        }

        private fun Map<String, Any?>.objectField(key: String): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return this[key] as? Map<String, Any?> ?: emptyMap()
        }
    }
}

private class LightJsonParser(private val raw: String) {
    private var index = 0

    fun parseValue(): Any? {
        skipWhitespace()
        if (index >= raw.length) return null
        return when (val current = raw[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalStateException("unexpected json char '$current'")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val values = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            index += 1
            return values
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index += 1
                '}' -> {
                    index += 1
                    return values
                }
                else -> throw IllegalStateException("expected comma or object end")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val values = mutableListOf<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            index += 1
            return values
        }
        while (true) {
            values += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index += 1
                ']' -> {
                    index += 1
                    return values
                }
                else -> throw IllegalStateException("expected comma or array end")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < raw.length) {
            when (val current = raw[index++]) {
                '"' -> return builder.toString()
                '\\' -> {
                    val escaped = raw.getOrNull(index++) ?: throw IllegalStateException("unterminated escape")
                    builder.append(
                        when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = raw.substring(index, (index + 4).coerceAtMost(raw.length))
                                index += 4
                                hex.toInt(16).toChar()
                            }
                            else -> escaped
                        },
                    )
                }
                else -> builder.append(current)
            }
        }
        throw IllegalStateException("unterminated string")
    }

    private fun parseBoolean(): Boolean {
        return when {
            raw.startsWith("true", index) -> {
                index += 4
                true
            }
            raw.startsWith("false", index) -> {
                index += 5
                false
            }
            else -> throw IllegalStateException("invalid boolean")
        }
    }

    private fun parseNull(): Any? {
        if (!raw.startsWith("null", index)) throw IllegalStateException("invalid null")
        index += 4
        return null
    }

    private fun parseNumber(): Number {
        val start = index
        if (peek() == '-') index += 1
        while (peek()?.isDigit() == true) index += 1
        var decimal = false
        if (peek() == '.') {
            decimal = true
            index += 1
            while (peek()?.isDigit() == true) index += 1
        }
        if (peek() == 'e' || peek() == 'E') {
            decimal = true
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            while (peek()?.isDigit() == true) index += 1
        }
        val token = raw.substring(start, index)
        return if (decimal) token.toDouble() else token.toLong()
    }

    fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) index += 1
    }

    private fun expect(char: Char) {
        if (peek() != char) throw IllegalStateException("expected '$char'")
        index += 1
    }

    private fun peek(): Char? = raw.getOrNull(index)
}
