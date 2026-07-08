package com.zerotimes.picocart.speech

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

internal data class VoiceSegment(
    val pcmBytes: ByteArray,
    val durationMs: Long,
)

internal class VoiceActivityDetector(
    private val sampleRate: Int,
    private val frameMs: Int,
    private val maxSegmentMs: Int,
) {
    private val preRollFrames = ArrayDeque<ShortArray>()
    private val segmentFrames = mutableListOf<ShortArray>()
    private var inSpeech = false
    private var voicedFrames = 0
    private var silenceFrames = 0
    private var noiseRms = INITIAL_NOISE_RMS
    private var segmentFrameCount = 0

    fun reset() {
        preRollFrames.clear()
        segmentFrames.clear()
        inSpeech = false
        voicedFrames = 0
        silenceFrames = 0
        noiseRms = INITIAL_NOISE_RMS
        segmentFrameCount = 0
    }

    fun accept(frame: ShortArray, size: Int): VoiceSegment? {
        val copy = frame.copyOf(size)
        val rms = copy.rms()
        val speech = rms > max(MIN_SPEECH_RMS, noiseRms * SPEECH_RATIO)

        if (!inSpeech) {
            if (!speech) {
                noiseRms = (noiseRms * NOISE_DECAY) + (rms * (1.0 - NOISE_DECAY))
            }
            keepPreRoll(copy)
            voicedFrames = if (speech) voicedFrames + 1 else 0
            if (voicedFrames >= START_VOICED_FRAMES) {
                inSpeech = true
                silenceFrames = 0
                segmentFrameCount = 0
                segmentFrames.clear()
                segmentFrames.addAll(preRollFrames)
                segmentFrames.add(copy)
                segmentFrameCount = segmentFrames.size
                preRollFrames.clear()
            }
            return null
        }

        segmentFrames.add(copy)
        segmentFrameCount += 1
        silenceFrames = if (speech) 0 else silenceFrames + 1
        val exceededMax = segmentDurationMs() >= maxSegmentMs
        if (silenceFrames < END_SILENCE_FRAMES && !exceededMax) {
            return null
        }

        return finishSegment()
    }

    private fun finishSegment(): VoiceSegment {
        val frames = segmentFrames.toList()
        val durationMs = segmentDurationMs()
        segmentFrames.clear()
        inSpeech = false
        voicedFrames = 0
        silenceFrames = 0
        segmentFrameCount = 0
        return VoiceSegment(frames.toPcmBytes(), durationMs)
    }

    private fun keepPreRoll(frame: ShortArray) {
        preRollFrames.addLast(frame)
        while (preRollFrames.size > PRE_ROLL_FRAMES) {
            preRollFrames.removeFirst()
        }
    }

    private fun segmentDurationMs(): Long = segmentFrameCount.toLong() * frameMs

    private fun ShortArray.rms(): Double {
        if (isEmpty()) return 0.0
        var sum = 0.0
        for (sample in this) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / size)
    }

    private fun List<ShortArray>.toPcmBytes(): ByteArray {
        val output = ByteArrayOutputStream(sumOf { it.size * BYTES_PER_SAMPLE })
        for (frame in this) {
            for (sample in frame) {
                output.write(sample.toInt() and 0xFF)
                output.write((sample.toInt() shr 8) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val PRE_ROLL_FRAMES = 8
        const val START_VOICED_FRAMES = 3
        const val END_SILENCE_FRAMES = 18
        const val INITIAL_NOISE_RMS = 120.0
        const val MIN_SPEECH_RMS = 420.0
        const val SPEECH_RATIO = 3.2
        const val NOISE_DECAY = 0.96
    }
}
