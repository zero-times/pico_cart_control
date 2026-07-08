package com.zerotimes.picocart.speech

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.zerotimes.picocart.logging.PersistentDebugLogStore

class LocalCachedSpeechEngine(context: Context) : SpeechEngine {
    private val appContext = context.applicationContext
    private val fallback = AndroidSpeechEngine(appContext)
    private val logs = PersistentDebugLogStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPlayer: MediaPlayer? = null
    private var currentDescriptor: AssetFileDescriptor? = null

    override fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val assetPath = CACHED_PHRASES[clean.normalizedSpeechKey()]
        if (assetPath == null) {
            logs.appendVoice("local_tts fallback=text_to_speech text=${clean.preview()}")
            fallback.speak(clean)
            return
        }
        logs.appendVoice("local_tts cache_hit asset=$assetPath text=${clean.preview()}")
        playAsset(assetPath, clean)
    }

    override fun shutdown() {
        mainHandler.post {
            releaseCurrentPlayer()
        }
        fallback.shutdown()
        logs.close()
    }

    private fun playAsset(assetPath: String, fallbackText: String) {
        mainHandler.post {
            var descriptor: AssetFileDescriptor? = null
            runCatching {
                releaseCurrentPlayer()
                descriptor = appContext.assets.openFd(assetPath)
                val openedDescriptor = checkNotNull(descriptor)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(
                        openedDescriptor.fileDescriptor,
                        openedDescriptor.startOffset,
                        openedDescriptor.length,
                    )
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener {
                        releaseDescriptor()
                        it.release()
                        if (currentPlayer === it) currentPlayer = null
                    }
                    setOnErrorListener { mediaPlayer, _, _ ->
                        releaseDescriptor()
                        mediaPlayer.release()
                        if (currentPlayer === mediaPlayer) currentPlayer = null
                        logs.appendVoice("local_tts playback_error fallback=text_to_speech text=${fallbackText.preview()}")
                        fallback.speak(fallbackText)
                        true
                    }
                    prepareAsync()
                }
                currentPlayer = player
                currentDescriptor = descriptor
                descriptor = null
            }.onFailure {
                descriptor?.close()
                logs.appendVoice("local_tts playback_exception fallback=text_to_speech reason=${it.message?.take(120)}")
                fallback.speak(fallbackText)
            }
        }
    }

    private fun releaseCurrentPlayer() {
        currentPlayer?.release()
        currentPlayer = null
        releaseDescriptor()
    }

    private fun releaseDescriptor() {
        currentDescriptor?.close()
        currentDescriptor = null
    }

    private fun String.normalizedSpeechKey(): String =
        lowercase()
            .replace(Regex("[\\s，,。.:：！？!?、；;]+"), "")
            .trim()

    private fun String.preview(): String =
        replace("\r", " ")
            .replace("\n", " ")
            .take(LOG_TEXT_PREVIEW_LENGTH)

    private companion object {
        const val LOG_TEXT_PREVIEW_LENGTH = 42
        val CACHED_PHRASES = mapOf(
            "我在" to "mambo_voice/i_am_here.mp3",
            "自检完成" to "mambo_voice/self_check_done.mp3",
            "自检完毕" to "mambo_voice/self_check_done.mp3",
            "已停车" to "mambo_voice/stopped.mp3",
            "停车完成" to "mambo_voice/stopped.mp3",
            "干完了" to "mambo_voice/done.mp3",
            "完成" to "mambo_voice/done.mp3",
            "命令已执行" to "mambo_voice/done.mp3",
        )
    }
}
