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
        "manual_max",
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
        "ramp" to "0.018",
        "manual_max" to "0.25",
        "left_motor_gain" to "1.00",
        "right_motor_gain" to "1.00",
        "left_force_gain" to "1.00",
        "right_force_gain" to "1.00",
        "tow_left_comp" to "0",
        "tow_right_comp" to "0",
    )

    fun parameterLabel(key: String): String = when (key) {
        "tow_left_comp" -> "牵引左通道补偿（原始拉力）"
        "tow_right_comp" -> "牵引右通道补偿（原始拉力）"
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
}
