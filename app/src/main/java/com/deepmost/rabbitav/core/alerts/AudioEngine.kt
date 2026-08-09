package com.deepmost.rabbitav.core.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Single audio channel for alerts (Section 5.5): SoundPool tones are primary
 * and pre-loaded; TTS is secondary (mapped-hazard callouts) and never blocks
 * the tone path. Higher-priority tones interrupt lower immediately.
 */
@Singleton
class AudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(attributes)
        .build()

    private val soundIds = HashMap<Tone, Int>()
    private val loaded = HashSet<Int>()
    private var currentStream = 0
    private var currentTone: Tone? = null

    @Volatile var volume: Float = 1f

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile var ttsEnabled: Boolean = false
    @Volatile var ttsLocale: Locale = Locale.ENGLISH

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
            else Timber.tag(TAG).e("tone load failed: sample=%d status=%d", sampleId, status)
        }
        for (tone in Tone.entries) {
            try {
                context.assets.openFd(tone.assetFile).use { afd ->
                    soundIds[tone] = soundPool.load(afd, 1)
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "missing tone asset %s", tone.assetFile)
            }
        }
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = ttsLocale
                Timber.tag(TAG).i("TTS ready")
            } else {
                Timber.tag(TAG).w("TTS unavailable (status=%d); tones only", status)
            }
        }
    }

    /** Plays [tone], stopping whatever is currently sounding. */
    fun play(tone: Tone) {
        val id = soundIds[tone] ?: return
        if (id !in loaded) {
            Timber.tag(TAG).w("tone %s not loaded yet", tone)
            return
        }
        stopTone()
        val loop = if (tone.loops) -1 else 0
        currentStream = soundPool.play(id, volume, volume, 1, loop, 1f)
        currentTone = tone
    }

    fun stopTone() {
        if (currentStream != 0) {
            soundPool.stop(currentStream)
            currentStream = 0
        }
        currentTone = null
    }

    val playingTone: Tone? get() = currentTone

    /** Queued speech; used only for hazard callouts, never for FCW. */
    fun speak(text: String) {
        if (!ttsEnabled || !ttsReady || text.isEmpty()) return
        val t = tts ?: return
        try {
            t.language = ttsLocale
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rav-${System.nanoTime()}")
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "TTS speak failed")
        }
    }

    fun shutdown() {
        stopTone()
        soundPool.release()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "RAV-Alert"
    }
}
