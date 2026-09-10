package com.zerotimes.picocart.protocol

import java.util.Locale

data class ParsedLine(
    val type: String,
    val raw: String,
    val fields: Map<String, String>,
) {
    operator fun get(key: String): String? = fields[key.lowercase(Locale.US)]
}

object PicoProtocol {
    val serviceHints = listOf(
        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
        "0000FFE0-0000-1000-8000-00805F9B34FB",
        "0000FFF0-0000-1000-8000-00805F9B34FB",
    )

    val characteristicHints = listOf(
        "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
        "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
        "0000FFE1-0000-1000-8000-00805F9B34FB",
        "0000FFF1-0000-1000-8000-00805F9B34FB",
    )

    val paramKeys = listOf(
        "max_pwm",
        "min_pwm",
        "start_raw",
        "full_raw",
        "steer_gain",
        "ramp",
        "decel_ramp",
        "manual_max",
        "timeout_ms",
        "reverse_neutral_ms",
        "tow_idle_ms",
        "left_motor_gain",
        "right_motor_gain",
        "left_force_gain",
        "right_force_gain",
        "tow_left_comp",
        "tow_right_comp",
    )

    val defaultParamInputs = linkedMapOf(
        "max_pwm" to "0.45",
        "min_pwm" to "0.14",
        "start_raw" to "25000",
        "full_raw" to "180000",
        "steer_gain" to "0.75",
        "ramp" to "0.012",
        "decel_ramp" to "0.018",
        "manual_max" to "0.25",
        "timeout_ms" to "1200",
        "reverse_neutral_ms" to "120",
        "tow_idle_ms" to "300000",
        "left_motor_gain" to "1.00",
        "right_motor_gain" to "1.00",
        "left_force_gain" to "1.00",
        "right_force_gain" to "1.00",
        "tow_left_comp" to "0",
        "tow_right_comp" to "0",
    )

    fun parameterLabel(key: String): String = when (key) {
        "ramp" -> "启动斜坡（每 25ms PWM 增量）"
        "decel_ramp" -> "停车斜坡（每 25ms PWM 减量）"
        "timeout_ms" -> "手动控制保活超时（毫秒）"
        "reverse_neutral_ms" -> "反向零位等待（毫秒）"
        "tow_idle_ms" -> "牵引无拉力退出超时（毫秒，默认 5 分钟）"
        "left_motor_gain" -> "左轮增益"
        "right_motor_gain" -> "右轮增益"
        "left_force_gain" -> "左拉力增益"
        "right_force_gain" -> "右拉力增益"
        "start_raw" -> "启动拉力阈值"
        "full_raw" -> "满功率拉力阈值"
        "tow_left_comp" -> "牵引左通道补偿（原始量，勿与增益重复叠加）"
        "tow_right_comp" -> "牵引右通道补偿（原始量，勿与增益重复叠加）"
        else -> key
    }

    fun parseLine(line: String): ParsedLine {
        val raw = line.trim()
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        val type = parts.firstOrNull().orEmpty()
        val fields = parts.drop(1).mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) {
                null
            } else {
                part.substring(0, index).lowercase(Locale.US) to part.substring(index + 1)
            }
        }.toMap()
        return ParsedLine(type = type, raw = raw, fields = fields)
    }

    fun uuidScore(uuid: String?, hints: List<String>): Int {
        val normalized = normalizeUuid(uuid)
        var score = 0
        hints.forEachIndexed { index, hint ->
            val exact = normalizeUuid(hint)
            val shortId = exact.substring(4, 8)
            score += when {
                normalized == exact -> 120 - index * 5
                normalized.contains(shortId) -> 70 - index * 4
                else -> 0
            }
        }
        return score
    }

    fun normalizeUuid(uuid: String?): String = uuid.orEmpty().uppercase(Locale.US)

    const val bundledFirmwareFile = "pico_firmware/main.py"
    const val otaChunkBytes = 96

    fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val delta = leftParts.getOrElse(index) { 0 } - rightParts.getOrElse(index) { 0 }
            if (delta != 0) return if (delta > 0) 1 else -1
        }
        return 0
    }

    private fun versionParts(value: String): List<Int> {
        return value.trim().lowercase(Locale.US)
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
    }

    fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor 0xEDB88320.toInt()
                } else {
                    crc ushr 1
                }
            }
        }
        return crc xor 0xFFFFFFFF.toInt()
    }

    fun crc32Hex(data: ByteArray): String = "%08x".format(Locale.US, crc32(data).toLong() and 0xFFFFFFFFL)

    fun extractFirmwareVersion(source: String): String? {
        val match = Regex("""FIRMWARE_VERSION\s*=\s*["']([^"']+)["']""").find(source)
        return match?.groupValues?.get(1)
    }
}
