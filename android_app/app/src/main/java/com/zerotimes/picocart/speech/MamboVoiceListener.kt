package com.zerotimes.picocart.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

enum class MamboVoiceState {
    OFF,
    LOADING_MODEL,
    WAITING_WAKE,
    LISTENING_COMMAND,
}

class MamboVoiceListener(
    context: Context,
    private val onWake: () -> Unit,
    private val onCommand: (String) -> Unit,
    private val onStateChanged: (MamboVoiceState) -> Unit,
    private val onErrorMessage: (String) -> Unit,
    private val onDebugMessage: (String) -> Unit,
) {
    private enum class Stage {
        WAKE,
        COMMAND,
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wakeWordDetector = WakeWordDetector()
    private var job: Job? = null
    private var state = MamboVoiceState.OFF
    private var stage = Stage.WAKE
    private var commandDeadlineElapsedMs = 0L
    private var lastDebugElapsedMs = 0L

    @Volatile
    private var enabled = false

    @Volatile
    private var suspendedUntilElapsedMs = 0L

    fun setEnabled(value: Boolean) {
        if (enabled == value && (value || state == MamboVoiceState.OFF)) {
            return
        }
        enabled = value
        if (enabled) {
            start()
        } else {
            stop()
        }
    }

    fun suspendFor(durationMs: Long) {
        if (durationMs <= 0L) return
        suspendedUntilElapsedMs = max(
            suspendedUntilElapsedMs,
            SystemClock.elapsedRealtime() + durationMs,
        )
    }

    fun stop() {
        enabled = false
        commandDeadlineElapsedMs = 0L
        job?.cancel()
        job = null
        publishState(MamboVoiceState.OFF)
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun start() {
        if (job?.isActive == true) {
            return
        }
        stage = Stage.WAKE
        wakeWordDetector.reset()
        commandDeadlineElapsedMs = 0L
        job = scope.launch {
            publishState(MamboVoiceState.LOADING_MODEL)
            val recognizer = LocalVoskSpeechRecognizer.create(appContext).getOrElse {
                enabled = false
                publishState(MamboVoiceState.OFF)
                postError(it.message ?: "本地语音模型加载失败")
                return@launch
            }

            recognizer.use {
                runCatching { runAudioLoop(it) }
                    .onFailure { error ->
                        if (enabled) {
                            enabled = false
                            publishState(MamboVoiceState.OFF)
                            postError(error.message ?: "本地语音监听启动失败")
                        }
                    }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runAudioLoop(recognizer: LocalVoskSpeechRecognizer) {
        val audioRecord = createAudioRecord()
        val vad = VoiceActivityDetector(
            sampleRate = LocalVoskSpeechRecognizer.SAMPLE_RATE,
            frameMs = FRAME_MS,
            maxSegmentMs = MAX_SEGMENT_MS,
        )
        try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("麦克风未进入录音状态")
            }

            publishState(MamboVoiceState.WAITING_WAKE)
            val frame = ShortArray(FRAME_SAMPLES)
            while (enabled && currentCoroutineContext().isActive) {
                val read = audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (now < suspendedUntilElapsedMs) {
                    vad.reset()
                    continue
                }
                handleCommandTimeout(now, vad)

                for (event in vad.accept(frame, read)) {
                    when (event) {
                        is VadEvent.Debug -> postDebug(event.log.format())
                        is VadEvent.Segment -> handleSegment(recognizer, event.segment)
                    }
                }
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            LocalVoskSpeechRecognizer.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("当前设备不支持 16kHz 单声道 PCM 录音")
        }
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(LocalVoskSpeechRecognizer.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(max(minBuffer, FRAME_BYTES * 8))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("麦克风初始化失败")
        }
        return record
    }

    private fun handleCommandTimeout(now: Long, vad: VoiceActivityDetector) {
        if (stage != Stage.COMMAND || commandDeadlineElapsedMs == 0L || now <= commandDeadlineElapsedMs) {
            return
        }
        stage = Stage.WAKE
        commandDeadlineElapsedMs = 0L
        vad.reset()
        publishState(MamboVoiceState.WAITING_WAKE)
        postError("没有听到指令，请再叫曼波重试")
    }

    private fun handleSegment(recognizer: LocalVoskSpeechRecognizer, segment: VoiceSegment) {
        when (stage) {
            Stage.WAKE -> handleWakeSegment(recognizer, segment)
            Stage.COMMAND -> handleCommandSegment(recognizer, segment)
        }
    }

    private fun handleWakeSegment(recognizer: LocalVoskSpeechRecognizer, segment: VoiceSegment) {
        postDebug("送入唤醒识别：duration=${segment.durationMs}ms，vad=${segment.vadConfidence.formatScore()}")
        val wakeText = recognizer.transcribeWake(segment.pcmBytes)
        val result = wakeWordDetector.detect(
            rawText = wakeText,
            vadConfidence = segment.vadConfidence,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        postWakeCandidate(result)
        if (!result.matched) {
            publishState(MamboVoiceState.WAITING_WAKE)
            return
        }

        stage = Stage.COMMAND
        commandDeadlineElapsedMs = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS
        publishState(MamboVoiceState.LISTENING_COMMAND)
        postWake()
    }

    private fun handleCommandSegment(recognizer: LocalVoskSpeechRecognizer, segment: VoiceSegment) {
        postDebug("送入命令识别：duration=${segment.durationMs}ms，vad=${segment.vadConfidence.formatScore()}")
        val grammarText = recognizer.transcribeCommandGrammar(segment.pcmBytes)
        val freeText = recognizer.transcribeCommand(segment.pcmBytes)
        postDebug("命令识别明细：grammar=${grammarText.forLog()}，free=${freeText.forLog()}")
        val command = chooseCommandText(grammarText = grammarText, freeText = freeText)
        postCommandCandidate(command)

        stage = Stage.WAKE
        commandDeadlineElapsedMs = 0L
        publishState(MamboVoiceState.WAITING_WAKE)

        if (command.isBlank()) {
            postError("没有听清指令，可以说：自检、读电量、停车。")
        } else {
            postCommand(command)
        }
    }

    private fun chooseCommandText(grammarText: String, freeText: String): String {
        val grammarCommand = normalizeCommandText(grammarText)
        val freeCommand = normalizeCommandText(freeText)
        if (freeCommand.isBlank()) {
            return grammarCommand
        }
        if (freeCommand in COMMAND_ALIASES.keys || freeCommand in CANONICAL_COMMANDS) {
            return COMMAND_ALIASES[freeCommand] ?: freeCommand
        }
        return freeCommand
    }

    private fun publishState(next: MamboVoiceState) {
        if (state == next) {
            return
        }
        state = next
        mainHandler.post { onStateChanged(next) }
    }

    private fun postWake() {
        mainHandler.post { onWake() }
    }

    private fun postCommand(command: String) {
        mainHandler.post { onCommand(command) }
    }

    private fun postError(message: String) {
        mainHandler.post { onErrorMessage(message) }
    }

    private fun postDebug(message: String) {
        mainHandler.post { onDebugMessage(message) }
    }

    private fun postWakeCandidate(result: WakeDetectionResult) {
        val compact = compactSpeechText(result.normalizedText)
        if (compact.isBlank()) {
            postDebugThrottled("检测到语音段，但本地模型未识别出文字")
            return
        }
        postDebug(
            "唤醒候选：$compact，score=${result.wakeScore.formatScore()}，目标=${result.phrase}",
        )
    }

    private fun postCommandCandidate(text: String) {
        val compact = compactSpeechText(text)
        if (compact.isBlank()) {
            postDebug("命令候选：未识别")
            return
        }
        postDebug("命令候选：$compact")
    }

    private fun postDebugThrottled(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugElapsedMs < DEBUG_THROTTLE_MS) {
            return
        }
        lastDebugElapsedMs = now
        postDebug(message)
    }

    private fun normalizeCommandText(text: String): String {
        val compact = compactSpeechText(text)
        if (compact.isBlank() || compact == "unk") {
            return ""
        }
        return COMMAND_ALIASES[compact] ?: compact
    }

    private fun compactSpeechText(text: String): String =
        text
            .lowercase(Locale.ROOT)
            .replace(Regex("\\[unk\\]"), "unk")
            .replace(Regex("[\\s，,。.:：！？!?、]+"), "")
            .trim()

    private fun Double.formatScore(): String = String.format(Locale.US, "%.2f", this)

    private fun String.forLog(): String =
        compactSpeechText(this).ifBlank { "未识别" }

    private companion object {
        const val FRAME_MS = 32
        const val FRAME_SAMPLES = LocalVoskSpeechRecognizer.SAMPLE_RATE * FRAME_MS / 1_000
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        const val MAX_SEGMENT_MS = 5_000
        const val COMMAND_TIMEOUT_MS = 7_000L
        const val DEBUG_THROTTLE_MS = 2_500L
        val CANONICAL_COMMANDS = setOf(
            "停车",
            "急停",
            "前进",
            "后退",
            "左转",
            "右转",
            "读取状态",
            "进入调试模式",
            "退出调试模式",
            "测试左轮",
            "测试右轮",
            "开始牵引",
            "停止牵引",
            "自检",
            "确认",
            "取消",
        )
        val COMMAND_ALIASES = mapOf(
            "停止" to "停车",
            "读状态" to "读取状态",
            "读一下状态" to "读取状态",
            "读电量" to "读取状态",
            "自我检查" to "自检",
        )
    }
}
