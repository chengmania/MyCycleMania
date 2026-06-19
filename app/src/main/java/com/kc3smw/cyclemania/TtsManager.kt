package com.kc3smw.cyclemania

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    var isEnabled = true
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            isReady = true
        } else {
            Log.w("TtsManager", "TTS init failed: $status")
        }
    }

    fun speak(text: String) {
        if (!isEnabled || !isReady) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
