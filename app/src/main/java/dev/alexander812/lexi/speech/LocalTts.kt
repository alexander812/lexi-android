package dev.alexander812.lexi.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors

class LocalTts(private val storage: VoiceStorage) {

    private val executor = Executors.newSingleThreadExecutor()
    private var instance: OfflineTts? = null
    private var loadedLang: String? = null
    private var track: AudioTrack? = null
    private var generation = 0

    fun speak(entry: VoiceEntry, text: String, onOutcome: (SpeakOutcome) -> Unit) {
        val version = synchronized(this) {
            generation += 1
            generation
        }
        executor.execute {
            try {
                val tts = ensureLoaded(entry)
                if (!isCurrent(version)) return@execute
                stopPlayback()
                val audio = tts.generate(text, 0, DEFAULT_SPEED)
                if (!isCurrent(version)) return@execute
                play(audio.samples, audio.sampleRate)
                Log.i(TAG, "local speak queued: ${entry.lang}, ${text.length} chars")
                onOutcome(SpeakOutcome.Success("local-${entry.id}"))
            } catch (error: Exception) {
                Log.w(TAG, "local speak failed: ${entry.lang}", error)
                onOutcome(SpeakOutcome.Failure("local_speak_failed"))
            }
        }
    }

    @Synchronized
    fun stop() {
        generation += 1
        stopPlayback()
    }

    @Synchronized
    fun unload(lang: String) {
        if (loadedLang != lang) return
        generation += 1
        stopPlayback()
        instance?.release()
        instance = null
        loadedLang = null
    }

    @Synchronized
    fun release() {
        generation += 1
        stopPlayback()
        instance?.release()
        instance = null
        loadedLang = null
        executor.shutdownNow()
    }

    @Synchronized
    private fun isCurrent(version: Int): Boolean = version == generation

    @Synchronized
    private fun ensureLoaded(entry: VoiceEntry): OfflineTts {
        val existing = instance
        if (existing != null && loadedLang == entry.lang) return existing
        existing?.release()
        val dir = storage.voiceDir(entry.id).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/${entry.id}.onnx",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = THREADS,
            ),
        )
        val created = OfflineTts(config = config)
        instance = created
        loadedLang = entry.lang
        return created
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSize = maxOf(minBuffer, samples.size * Float.SIZE_BYTES)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        val audioTrack = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        synchronized(this) { track = audioTrack }
        audioTrack.setNotificationMarkerPosition(samples.size)
        audioTrack.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    synchronized(this@LocalTts) {
                        if (this@LocalTts.track === track) {
                            track.release()
                            this@LocalTts.track = null
                        }
                    }
                }

                override fun onPeriodicNotification(track: AudioTrack) = Unit
            },
        )
        audioTrack.play()
        audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    @Synchronized
    private fun stopPlayback() {
        val current = track ?: return
        track = null
        runCatching {
            current.pause()
            current.flush()
            current.release()
        }
    }

    companion object {
        private const val TAG = "LexiVoice"
        private const val THREADS = 2
        private const val DEFAULT_SPEED = 1.0f
    }
}
