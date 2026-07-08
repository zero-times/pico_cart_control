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
) {
    private enum class Stage {
        WAKE,
        COMMAND,
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var state = MamboVoiceState.OFF
    private var stage = Stage.WAKE
    private var commandDeadlineElapsedMs = 0L

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

                val segment = vad.accept(frame, read) ?: continue
                if (segment.durationMs < MIN_SEGMENT_MS) {
                    continue
                }
                handleSegment(recognizer, segment)
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
        val wakeText = recognizer.transcribeWake(segment.pcmBytes)
        if (!containsWakeWord(wakeText)) {
            publishState(MamboVoiceState.WAITING_WAKE)
            return
        }

        val fullText = recognizer.transcribeCommand(segment.pcmBytes)
        val command = extractWakeCommand(fullText).orEmpty()
        if (command.isNotBlank()) {
            stage = Stage.WAKE
            commandDeadlineElapsedMs = 0L
            publishState(MamboVoiceState.WAITING_WAKE)
            postCommand(command)
            return
        }

        stage = Stage.COMMAND
        commandDeadlineElapsedMs = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS
        publishState(MamboVoiceState.LISTENING_COMMAND)
        postWake()
    }

    private fun handleCommandSegment(recognizer: LocalVoskSpeechRecognizer, segment: VoiceSegment) {
        val text = recognizer.transcribeCommand(segment.pcmBytes)
        val command = (extractWakeCommand(text) ?: compactSpeechText(text))
            .trimStart('，', ',', '。', '.', ' ', '：', ':')
            .trim()

        stage = Stage.WAKE
        commandDeadlineElapsedMs = 0L
        publishState(MamboVoiceState.WAITING_WAKE)

        if (command.isBlank()) {
            postError("没有听清指令，请再叫曼波重试")
        } else {
            postCommand(command)
        }
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

    private fun containsWakeWord(text: String): Boolean {
        val compact = compactSpeechText(text)
        return WAKE_WORDS.any { compact.contains(it) }
    }

    private fun extractWakeCommand(text: String): String? {
        val compact = compactSpeechText(text)
        val match = WAKE_WORDS
            .mapNotNull { wake ->
                val index = compact.indexOf(wake)
                if (index >= 0) index to wake.length else null
            }
            .minByOrNull { it.first }
            ?: return null
        return compact
            .substring(match.first + match.second)
            .trimStart('，', ',', '。', '.', ' ', '：', ':')
            .trim()
    }

    private fun compactSpeechText(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "").trim()

    private companion object {
        const val FRAME_MS = 32
        const val FRAME_SAMPLES = LocalVoskSpeechRecognizer.SAMPLE_RATE * FRAME_MS / 1_000
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        const val MIN_SEGMENT_MS = 420L
        const val MAX_SEGMENT_MS = 7_000
        const val COMMAND_TIMEOUT_MS = 7_000L
        val WAKE_WORDS = listOf("曼波", "漫波", "mambo")
    }
}
