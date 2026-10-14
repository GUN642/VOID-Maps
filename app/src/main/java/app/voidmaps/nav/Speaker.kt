package app.voidmaps.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** German voice announcements; ducks music while speaking. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val tts = TextToSpeech(app, this)
    private var ready = false
    private val pendingCount = AtomicInteger(0)
    private val queued = ArrayList<String>()
    private var counter = 0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale.GERMANY)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.GERMAN)
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
        ready = true
        synchronized(queued) {
            queued.forEach { speak(it) }
            queued.clear()
        }
    }

    private fun finished() {
        if (pendingCount.decrementAndGet() <= 0) {
            pendingCount.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun speak(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) {
            synchronized(queued) { queued += text }
            return
        }
        if (pendingCount.get() == 0) audio.requestAudioFocus(focus)
        if (interrupt) pendingCount.set(0)
        pendingCount.incrementAndGet()
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "void-${counter++}")
    }

    fun stop() {
        tts.stop()
        pendingCount.set(0)
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
