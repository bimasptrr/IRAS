package com.example.iras.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceCoach(context: Context) {

    enum class Status { INIT, READY, NO_INDONESIAN, ERROR }

    private val _status = MutableStateFlow(Status.INIT)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var engine: TextToSpeech? = null

    // Instruksi yang menunggu giliran. Hanya satu slot supaya instruksi tidak
    // menumpuk; yang terbaru menggantikan yang lama.
    private var pending: VoiceCue? = null
    private var speaking = false
    private var activeUtteranceId: String? = null
    private var utteranceCounter = 0
    private var utteranceStartedAt = 0L
    private var utteranceEstimateMs = 0L

    init {
        engine = TextToSpeech(context.applicationContext) { code -> handleInit(code) }
    }

    private fun handleInit(code: Int) {
        val e = engine
        if (e == null) {
            // Callback init bisa dipanggil sinkron di dalam constructor, sebelum
            // assignment engine selesai, jadi dicoba lagi satu tick berikutnya.
            Handler(Looper.getMainLooper()).post { handleInit(code) }
            return
        }
        if (code != TextToSpeech.SUCCESS) {
            _status.value = Status.ERROR
            return
        }
        val result = e.setLanguage(Locale("id", "ID"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            _status.value = Status.NO_INDONESIAN
            return
        }
        e.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        e.setSpeechRate(1.05f)
        e.setOnUtteranceProgressListener(progressListener)
        _status.value = Status.READY
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            if (utteranceId == activeUtteranceId) speaking = true
        }

        override fun onDone(utteranceId: String?) = onFinished(utteranceId)

        override fun onError(utteranceId: String?) = onFinished(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = onFinished(utteranceId)
    }

    private fun onFinished(utteranceId: String?) {
        if (utteranceId != activeUtteranceId) return
        speaking = false
        activeUtteranceId = null
        val next = pending ?: return
        pending = null
        speakNow(next)
    }

    fun speak(text: String, urgent: Boolean = false) {
        speak(VoiceCue(text, urgent))
    }

    fun speak(cue: VoiceCue) {
        if (_status.value != Status.READY) return
        if (engine == null) return

        if (cue.interrupt) {
            // Peringatan boleh memotong suara yang sedang berjalan.
            pending = null
            speakNow(cue)
            return
        }

        if (speaking) {
            if (isStalled()) {
                // Callback selesai tidak pernah datang, anggap suara sudah selesai.
                speaking = false
                activeUtteranceId = null
                pending = null
                speakNow(cue)
            } else {
                pending = mergePending(pending, cue)
            }
            return
        }

        val waiting = pending
        pending = null
        speakNow(if (waiting != null && waiting.interrupt) waiting else cue)
    }

    /** Peringatan tidak ditimpa instruksi biasa yang prioritasnya lebih rendah. */
    private fun mergePending(current: VoiceCue?, incoming: VoiceCue): VoiceCue {
        if (current == null) return incoming
        return if (current.interrupt && !incoming.interrupt) current else incoming
    }

    private fun speakNow(cue: VoiceCue) {
        val e = engine ?: return
        utteranceCounter++
        val id = "iras-$utteranceCounter"
        activeUtteranceId = id
        speaking = true
        utteranceStartedAt = SystemClock.elapsedRealtime()
        utteranceEstimateMs = cue.text.length * MS_PER_CHAR
        val mode = if (cue.interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        e.speak(cue.text, mode, null, id)
    }

    private fun isStalled(): Boolean {
        if (utteranceEstimateMs <= 0L) return true
        return SystemClock.elapsedRealtime() - utteranceStartedAt > utteranceEstimateMs + STALL_GRACE_MS
    }

    fun stop() {
        pending = null
        speaking = false
        activeUtteranceId = null
        engine?.stop()
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
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

    private companion object {
        // ~60 ms per karakter untuk TTS bahasa Indonesia pada rate 1.05.
        const val MS_PER_CHAR = 70L
        const val STALL_GRACE_MS = 1_500L
    }
}
