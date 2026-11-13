package com.example.iras.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceCoach(context: Context) {

    enum class Status { INIT, READY, NO_INDONESIAN, ERROR }

    private val _status = MutableStateFlow(Status.INIT)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { code ->
            val engine = tts
            if (code != TextToSpeech.SUCCESS || engine == null) {
                _status.value = Status.ERROR
                return@TextToSpeech
            }
            val result = engine.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                _status.value = Status.NO_INDONESIAN
            } else {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setSpeechRate(1.05f)
                _status.value = Status.READY
            }
        }
    }

    fun speak(text: String) {
        if (_status.value != Status.READY) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iras-cue")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    fun openVoiceInstall(context: Context) {
        try {
            context.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}