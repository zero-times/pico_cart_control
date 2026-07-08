package com.zerotimes.picocart.speech

import kotlin.math.max
import kotlin.math.min

internal data class WakeDetectionResult(
    val matched: Boolean,
    val phrase: String,
    val rawText: String,
    val normalizedText: String,
    val wakeScore: Double,
    val textScore: Double,
    val pinyinScore: Double,
)

internal class WakeWordDetector {
    private var lastSoftHitElapsedMs = 0L

    fun reset() {
        lastSoftHitElapsedMs = 0L
    }

    fun detect(rawText: String, vadConfidence: Double, nowElapsedMs: Long): WakeDetectionResult {
        val normalized = normalizeChinese(rawText)
        val inputPinyin = normalized.toPinyin()
        val scored = WAKE_CANDIDATES
            .map { candidate ->
                val textScore = textSimilarity(normalized, candidate.phrase)
                val pinyinScore = pinyinSimilarity(inputPinyin, candidate.pinyin)
                val wakeScore = textScore * 0.35 + pinyinScore * 0.50 + vadConfidence * 0.15
                candidate to Triple(textScore, pinyinScore, wakeScore)
            }
            .maxByOrNull { it.second.third }

        val candidate = scored?.first ?: WAKE_CANDIDATES.first()
        val textScore = scored?.second?.first ?: 0.0
        val pinyinScore = scored?.second?.second ?: 0.0
        val wakeScore = scored?.second?.third ?: 0.0
        val softHit = wakeScore >= SOFT_WAKE_THRESHOLD
        val repeatedSoftHit = softHit &&
            lastSoftHitElapsedMs > 0L &&
            nowElapsedMs - lastSoftHitElapsedMs <= SOFT_WAKE_WINDOW_MS
        val matched = wakeScore >= HARD_WAKE_THRESHOLD || repeatedSoftHit
        if (softHit) {
            lastSoftHitElapsedMs = nowElapsedMs
        }

        return WakeDetectionResult(
            matched = matched,
            phrase = candidate.phrase,
            rawText = rawText,
            normalizedText = normalized,
            wakeScore = wakeScore.coerceIn(0.0, 1.0),
            textScore = textScore.coerceIn(0.0, 1.0),
            pinyinScore = pinyinScore.coerceIn(0.0, 1.0),
        )
    }

    private fun textSimilarity(input: String, target: String): Double {
        if (input.isBlank()) return 0.0
        if (input.contains(target)) return 1.0
        if (target.contains(input) && input.length >= MIN_PARTIAL_TEXT_LENGTH) {
            return (input.length.toDouble() / target.length.toDouble()) * 0.72
        }
        val lcs = longestCommonSubsequence(input, target)
        return (2.0 * lcs.toDouble()) / (input.length + target.length).toDouble()
    }

    private fun pinyinSimilarity(input: List<String>, target: List<String>): Double {
        if (input.isEmpty()) return 0.0
        val substitutionDistance = weightedEditDistance(input, target)
        val editScore = 1.0 - substitutionDistance / max(input.size, target.size).toDouble()
        val lcsScore = weightedPinyinLcs(input, target) / target.size.toDouble()
        return max(editScore, lcsScore).coerceIn(0.0, 1.0)
    }

    private fun weightedEditDistance(input: List<String>, target: List<String>): Double {
        val rows = input.size + 1
        val cols = target.size + 1
        val dp = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) dp[i][0] = i.toDouble()
        for (j in 0 until cols) dp[0][j] = j.toDouble()
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val substitutionCost = 1.0 - syllableSimilarity(input[i - 1], target[j - 1])
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1.0, dp[i][j - 1] + 1.0),
                    dp[i - 1][j - 1] + substitutionCost,
                )
            }
        }
        return dp[input.size][target.size]
    }

    private fun weightedPinyinLcs(input: List<String>, target: List<String>): Double {
        val dp = Array(input.size + 1) { DoubleArray(target.size + 1) }
        for (i in 1..input.size) {
            for (j in 1..target.size) {
                val matchScore = syllableSimilarity(input[i - 1], target[j - 1])
                dp[i][j] = max(
                    max(dp[i - 1][j], dp[i][j - 1]),
                    dp[i - 1][j - 1] + matchScore,
                )
            }
        }
        return dp[input.size][target.size]
    }

    private fun syllableSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (PINYIN_GROUPS.any { left in it && right in it }) return 0.86
        if (left.firstOrNull() == right.firstOrNull()) return 0.48
        return 0.0
    }

    private fun longestCommonSubsequence(left: String, right: String): Int {
        val dp = Array(left.length + 1) { IntArray(right.length + 1) }
        for (i in 1..left.length) {
            for (j in 1..right.length) {
                dp[i][j] = if (left[i - 1] == right[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    max(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[left.length][right.length]
    }

    private fun normalizeChinese(text: String): String =
        text
            .lowercase()
            .replace(Regex("\\[unk\\]"), "")
            .replace(Regex("[^\\p{IsHan}a-z0-9]+"), "")
            .trim()

    private fun String.toPinyin(): List<String> {
        if (isBlank()) return emptyList()
        if (all { it in 'a'..'z' || it in '0'..'9' }) {
            return listOf(this)
        }
        return mapNotNull { PINYIN_BY_CHAR[it] }
    }

    private data class WakeCandidate(
        val phrase: String,
        val pinyin: List<String>,
    )

    private companion object {
        const val HARD_WAKE_THRESHOLD = 0.75
        const val SOFT_WAKE_THRESHOLD = 0.55
        const val SOFT_WAKE_WINDOW_MS = 1_500L
        const val MIN_PARTIAL_TEXT_LENGTH = 3

        val WAKE_CANDIDATES = listOf(
            WakeCandidate("曼波", listOf("man", "bo")),
            WakeCandidate("漫步", listOf("man", "bu")),
            WakeCandidate("慢不", listOf("man", "bu")),
            WakeCandidate("兰博", listOf("lan", "bo")),
            WakeCandidate("蓝波", listOf("lan", "bo")),
            WakeCandidate("你好曼波", listOf("ni", "hao", "man", "bo")),
            WakeCandidate("曼波小车", listOf("man", "bo", "xiao", "che")),
            WakeCandidate("曼波曼波", listOf("man", "bo", "man", "bo")),
        )

        val PINYIN_GROUPS = listOf(
            setOf("ni", "li"),
            setOf("hao", "ao"),
            setOf("man", "nan", "lan", "wan", "mang", "ma"),
            setOf("bo", "bu", "po"),
            setOf("xiao", "shao"),
            setOf("che", "ce", "ze"),
            setOf("mambo", "manbo"),
        )

        val PINYIN_BY_CHAR = mapOf(
            '你' to "ni",
            '泥' to "ni",
            '里' to "li",
            '好' to "hao",
            '号' to "hao",
            '曼' to "man",
            '漫' to "man",
            '慢' to "man",
            '蛮' to "man",
            '满' to "man",
            '南' to "nan",
            '男' to "nan",
            '蓝' to "lan",
            '兰' to "lan",
            '拦' to "lan",
            '万' to "wan",
            '忙' to "mang",
            '芒' to "mang",
            '马' to "ma",
            '波' to "bo",
            '播' to "bo",
            '博' to "bo",
            '不' to "bu",
            '步' to "bu",
            '部' to "bu",
            '小' to "xiao",
            '晓' to "xiao",
            '车' to "che",
            '撤' to "che",
            '测' to "ce",
            '册' to "ce",
            '这' to "ze",
        )
    }
}
