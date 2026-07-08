package com.zerotimes.picocart.speech

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

internal data class VoiceSegment(
    val pcmBytes: ByteArray,
    val durationMs: Long,
    val vadConfidence: Double,
)

internal data class VadDebugLog(
    val state: String,
    val noiseFloor: Double,
    val currentRms: Double,
    val startThreshold: Double,
    val endThreshold: Double,
    val detail: String,
) {
    fun format(): String = String.format(
        Locale.US,
        "VAD %s：noise_floor=%.0f，current_rms=%.0f，start_threshold=%.0f，end_threshold=%.0f，%s",
        state,
        noiseFloor,
        currentRms,
        startThreshold,
        endThreshold,
        detail,
    )
}

internal sealed class VadEvent {
    data class Debug(val log: VadDebugLog) : VadEvent()
    data class Segment(val segment: VoiceSegment) : VadEvent()
}

internal class VoiceActivityDetector(
    private val sampleRate: Int,
    private val frameMs: Int,
    private val maxSegmentMs: Int,
) {
    private enum class State {
        CALIBRATING,
        IDLE,
        POSSIBLE_SPEECH,
        SPEECH_STARTED,
        COOLDOWN,
    }

    private val preRollFrames = ArrayDeque<ShortArray>()
    private val segmentFrames = mutableListOf<ShortArray>()
    private var state = State.CALIBRATING
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var calibrationFrames = 0
    private var calibrationRmsSum = 0.0
    private var idleHighFrames = 0
    private var possibleHighFrames = 0
    private var silenceFrames = 0
    private var belowStartFrames = 0
    private var cooldownFrames = 0
    private var segmentFrameCount = 0
    private var speechFrameCount = 0
    private var segmentPeakRms = 0.0
    private var calibrationAnnounced = false

    private val calibrationFrameTarget = framesFor(CALIBRATION_MS)
    private val preRollFrameLimit = framesFor(PRE_ROLL_MS)
    private val possibleStartFrames = framesFor(POSSIBLE_START_MS)
    private val speechStartFrames = framesFor(SPEECH_START_MS)
    private val possibleTimeoutFrames = framesFor(POSSIBLE_TIMEOUT_MS)
    private val endSilenceFrames = framesFor(END_SILENCE_MS)
    private val softEndFrames = framesFor(SOFT_END_MS)
    private val minValidSpeechFrames = framesFor(MIN_VALID_SPEECH_MS)
    private val cooldownFrameTarget = framesFor(NOISE_REJECT_COOLDOWN_MS)

    fun reset() {
        clearSpeechBuffers()
        if (state == State.CALIBRATING) {
            calibrationFrames = 0
            calibrationRmsSum = 0.0
            calibrationAnnounced = false
            noiseFloor = INITIAL_NOISE_FLOOR
        } else {
            state = State.IDLE
        }
    }

    fun accept(frame: ShortArray, size: Int): List<VadEvent> {
        val copy = frame.copyOf(size)
        val rms = copy.rms()
        val events = mutableListOf<VadEvent>()
        when (state) {
            State.CALIBRATING -> handleCalibrating(copy, rms, events)
            State.IDLE -> handleIdle(copy, rms, events)
            State.POSSIBLE_SPEECH -> handlePossibleSpeech(copy, rms, events)
            State.SPEECH_STARTED -> handleSpeechStarted(copy, rms, events)
            State.COOLDOWN -> handleCooldown(rms, events)
        }
        return events
    }

    private fun handleCalibrating(frame: ShortArray, rms: Double, events: MutableList<VadEvent>) {
        if (!calibrationAnnounced) {
            calibrationAnnounced = true
            events += debug("CALIBRATING", rms, "采集 2 秒底噪")
        }
        calibrationRmsSum += rms
        calibrationFrames += 1
        if (calibrationFrames < calibrationFrameTarget) {
            return
        }
        noiseFloor = (calibrationRmsSum / calibrationFrames.toDouble()).coerceAtLeast(MIN_NOISE_FLOOR)
        state = State.IDLE
        keepPreRoll(frame)
        events += debug("IDLE", rms, "底噪校准完成")
    }

    private fun handleIdle(frame: ShortArray, rms: Double, events: MutableList<VadEvent>) {
        keepPreRoll(frame)
        if (rms < endThreshold()) {
            adaptNoiseFloor(rms)
        }
        if (rms >= startThreshold()) {
            idleHighFrames += 1
        } else {
            idleHighFrames = 0
            return
        }
        if (idleHighFrames < possibleStartFrames) {
            return
        }
        state = State.POSSIBLE_SPEECH
        possibleHighFrames = idleHighFrames
        segmentFrames.clear()
        segmentFrames.addAll(preRollFrames)
        segmentFrameCount = segmentFrames.size
        speechFrameCount = idleHighFrames
        segmentPeakRms = rms
        preRollFrames.clear()
        events += debug("POSSIBLE_SPEECH", rms, "连续声音超过起始阈值")
    }

    private fun handlePossibleSpeech(frame: ShortArray, rms: Double, events: MutableList<VadEvent>) {
        addSegmentFrame(frame, rms)
        if (rms >= startThreshold()) {
            possibleHighFrames += 1
        }
        if (possibleHighFrames >= speechStartFrames) {
            state = State.SPEECH_STARTED
            silenceFrames = 0
            belowStartFrames = 0
            events += debug("SPEECH_STARTED", rms, "确认语音开始")
            return
        }
        val possibleTimedOut = speechFrameCount >= possibleTimeoutFrames
        if (rms < endThreshold() || possibleTimedOut) {
            rejectNoise(rms, "短促噪声，已丢弃", events)
        }
    }

    private fun handleSpeechStarted(frame: ShortArray, rms: Double, events: MutableList<VadEvent>) {
        addSegmentFrame(frame, rms)
        silenceFrames = if (rms < endThreshold()) silenceFrames + 1 else 0
        belowStartFrames = if (rms < startThreshold()) belowStartFrames + 1 else 0
        val endedBySilence = silenceFrames >= endSilenceFrames
        val endedBySoftDrop = belowStartFrames >= softEndFrames
        val exceededMax = segmentDurationMs() >= maxSegmentMs
        if (!endedBySilence && !endedBySoftDrop && !exceededMax) {
            return
        }
        if (speechFrameCount < minValidSpeechFrames) {
            rejectNoise(rms, "语音短于 500ms，已丢弃", events)
            return
        }
        val endDetail = when {
            endedBySilence -> "静音超过 800ms，送入识别"
            endedBySoftDrop -> "能量回落到起始阈值以下，送入识别"
            else -> "达到最长录音，送入识别"
        }
        events += debug("SPEECH_ENDED", rms, endDetail)
        events += VadEvent.Segment(finishSegment())
        state = State.IDLE
        clearSpeechBuffers()
        events += debug("IDLE", rms, "等待下一段语音")
    }

    private fun handleCooldown(rms: Double, events: MutableList<VadEvent>) {
        cooldownFrames -= 1
        if (cooldownFrames > 0) {
            return
        }
        state = State.IDLE
        clearSpeechBuffers()
        events += debug("IDLE", rms, "噪声冷却结束")
    }

    private fun rejectNoise(rms: Double, detail: String, events: MutableList<VadEvent>) {
        state = State.COOLDOWN
        cooldownFrames = cooldownFrameTarget
        clearSpeechBuffers()
        events += debug("REJECTED_NOISE", rms, "$detail，冷却 1 秒")
    }

    private fun addSegmentFrame(frame: ShortArray, rms: Double) {
        segmentFrames.add(frame)
        segmentFrameCount += 1
        speechFrameCount += 1
        segmentPeakRms = max(segmentPeakRms, rms)
    }

    private fun finishSegment(): VoiceSegment {
        val frames = segmentFrames.toList()
        val durationMs = segmentDurationMs()
        val confidence = ((segmentPeakRms - noiseFloor) / max(startThreshold() - noiseFloor, 1.0))
            .coerceIn(0.0, 1.0)
        return VoiceSegment(frames.toPcmBytes(), durationMs, confidence)
    }

    private fun clearSpeechBuffers() {
        preRollFrames.clear()
        segmentFrames.clear()
        idleHighFrames = 0
        possibleHighFrames = 0
        silenceFrames = 0
        belowStartFrames = 0
        cooldownFrames = 0
        segmentFrameCount = 0
        speechFrameCount = 0
        segmentPeakRms = 0.0
    }

    private fun keepPreRoll(frame: ShortArray) {
        preRollFrames.addLast(frame)
        while (preRollFrames.size > preRollFrameLimit) {
            preRollFrames.removeFirst()
        }
    }

    private fun adaptNoiseFloor(rms: Double) {
        noiseFloor = ((noiseFloor * NOISE_DECAY) + (rms * (1.0 - NOISE_DECAY)))
            .coerceAtLeast(MIN_NOISE_FLOOR)
    }

    private fun debug(state: String, rms: Double, detail: String): VadEvent.Debug =
        VadEvent.Debug(
            VadDebugLog(
                state = state,
                noiseFloor = noiseFloor,
                currentRms = rms,
                startThreshold = startThreshold(),
                endThreshold = endThreshold(),
                detail = detail,
            ),
        )

    private fun startThreshold(): Double = noiseFloor * START_THRESHOLD_RATIO

    private fun endThreshold(): Double = noiseFloor * END_THRESHOLD_RATIO

    private fun segmentDurationMs(): Long = segmentFrameCount.toLong() * frameMs

    private fun framesFor(durationMs: Int): Int =
        ((durationMs + frameMs - 1) / frameMs).coerceAtLeast(1)

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
        const val CALIBRATION_MS = 2_000
        const val PRE_ROLL_MS = 500
        const val POSSIBLE_START_MS = 64
        const val SPEECH_START_MS = 160
        const val POSSIBLE_TIMEOUT_MS = 260
        const val MIN_VALID_SPEECH_MS = 500
        const val END_SILENCE_MS = 800
        const val SOFT_END_MS = 480
        const val NOISE_REJECT_COOLDOWN_MS = 1_000
        const val INITIAL_NOISE_FLOOR = 80.0
        const val MIN_NOISE_FLOOR = 50.0
        const val START_THRESHOLD_RATIO = 3.0
        const val END_THRESHOLD_RATIO = 1.6
        const val NOISE_DECAY = 0.995
    }
}
