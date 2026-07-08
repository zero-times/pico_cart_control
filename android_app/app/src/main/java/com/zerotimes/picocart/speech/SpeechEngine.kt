package com.zerotimes.picocart.speech

interface SpeechEngine {
    fun speak(text: String)
    fun shutdown()
}
