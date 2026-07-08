package com.zerotimes.picocart.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidSpeechEngine(context: Context) : SpeechEngine, TextToSpeech.OnInitListener {
    private var ready = false
    private val pending = ArrayDeque<String>()
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.CHINA
            while (pending.isNotEmpty()) {
                speak(pending.removeFirst())
            }
        }
    }

    override fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!ready) {
            pending.add(clean)
            return
        }
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "pico-cart-${System.nanoTime()}")
    }

    override fun shutdown() {
        pending.clear()
        tts.stop()
        tts.shutdown()
    }
}
