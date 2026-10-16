package app.voidmaps.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** A German voice offered in the settings. */
data class VoiceInfo(val name: String, val label: String, val detail: String)

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
    private var voiceName = ""
    private var rate = 1.0f
    private var applied: Pair<String, Float>? = null
    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())

    /** German voices of the installed speech engine (filled once the engine is ready). */
    val voices: StateFlow<List<VoiceInfo>> = _voices

    /** Name of the speech engine, e.g. "Sprachausgabe von Google". */
    var engineLabel: String = ""
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale.GERMANY)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.GERMAN)
        tts.setAudioAttributes(attrs)
        runCatching {
            engineLabel = tts.engines.firstOrNull { it.name == tts.defaultEngine }?.label ?: tts.defaultEngine.orEmpty()
            _voices.value = germanVoices()
        }
        applyVoice()
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

    private fun germanVoices(): List<VoiceInfo> {
        val list = tts.voices.orEmpty().filter { v ->
            v.locale.language == "de" &&
                !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.sortedWith(
            compareBy<android.speech.tts.Voice>({ it.locale.country != "DE" }, { it.isNetworkConnectionRequired }, { -it.quality }, { it.name }),
        )
        return list.mapIndexed { i, v ->
            val region = when (v.locale.country) {
                "DE", "" -> null
                "AT" -> "Österreich"
                "CH" -> "Schweiz"
                else -> v.locale.displayCountry
            }
            val quality = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "sehr hohe Qualität"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "hohe Qualität"
                else -> "normale Qualität"
            }
            val parts = listOfNotNull(
                region,
                if (v.isNetworkConnectionRequired) "online, natürlicher" else "offline",
                quality,
            )
            VoiceInfo(v.name, "Stimme ${i + 1}", parts.joinToString(" · "))
        }
    }

    /** Selects the voice by name ("" = default German voice) and the speaking rate. Cheap when unchanged. */
    fun configure(name: String, speechRate: Float) {
        voiceName = name
        rate = speechRate
        if (ready) applyVoice()
    }

    private fun applyVoice() {
        if (applied == voiceName to rate) return
        applied = voiceName to rate
        runCatching {
            tts.setSpeechRate(rate)
            val v = if (voiceName.isNotBlank()) tts.voices?.firstOrNull { it.name == voiceName } else null
            if (v != null) {
                tts.voice = v
            } else {
                val r = tts.setLanguage(Locale.GERMANY)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.GERMAN)
            }
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
