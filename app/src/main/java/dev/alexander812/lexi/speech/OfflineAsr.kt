package dev.alexander812.lexi.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.sqrt

class OfflineAsr(private val storage: AsrStorage) {

    private val executor = Executors.newSingleThreadExecutor()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerKey: String? = null

    @Volatile
    private var cancelled = false

    fun listen(lang: String, onOutcome: (RecognizeOutcome) -> Unit) {
        cancelled = false
        executor.execute {
            val outcome = try {
                val samples = capture()
                if (cancelled) {
                    RecognizeOutcome.Failure(REASON_CANCELLED)
                } else if (samples == null) {
                    RecognizeOutcome.Failure(REASON_NO_SPEECH)
                } else {
                    val text = decode(samples, lang)
                    when {
                        cancelled -> RecognizeOutcome.Failure(REASON_CANCELLED)
                        text.isBlank() -> RecognizeOutcome.Failure(REASON_NO_SPEECH)
                        else -> RecognizeOutcome.Success(text, null, emptyList())
                    }
                }
            } catch (error: SecurityException) {
                Log.w(TAG, "offline asr permission denied", error)
                RecognizeOutcome.Failure(REASON_PERMISSION)
            } catch (error: Throwable) {
                Log.w(TAG, "offline asr failed", error)
                RecognizeOutcome.Failure(REASON_FAILED)
            }
            onOutcome(outcome)
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun unload() {
        synchronized(this) {
            recognizer?.release()
            recognizer = null
        }
    }

    fun release() {
        unload()
        executor.shutdownNow()
    }

    private fun decode(samples: FloatArray, lang: String): String {
        val entry = resolveEntry(lang)
        Log.i(TAG, "decoding ${samples.size} samples on $lang with ${entry.id}")
        val instance = ensureRecognizer(entry, lang)
        val stream = instance.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            instance.decode(stream)
            return instance.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun resolveEntry(lang: String): AsrEntry {
        val candidates = preferredAsrEntries(lang).ifEmpty { ASR_ENTRIES }

        return candidates.firstOrNull { storage.isInstalled(it) } ?: candidates.first()
    }

    @Synchronized
    private fun ensureRecognizer(entry: AsrEntry, lang: String): OfflineRecognizer {
        val key = "${entry.id}:$lang"
        val cached = recognizer

        if (cached != null && recognizerKey == key) return cached

        cached?.release()
        recognizer = null

        val dir = storage.modelDir(entry).absolutePath
        val tokens = storage.tokensFile(entry).absolutePath
        val modelConfig = when (entry.engine) {
            AsrEngine.NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = storage.file(entry, entry.modelFile.orEmpty()).absolutePath,
                ),
                tokens = tokens,
                numThreads = THREADS,
                debug = false,
                modelType = "nemo_ctc",
            )
            AsrEngine.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = storage.file(entry, entry.encoderFile.orEmpty()).absolutePath,
                    decoder = storage.file(entry, entry.decoderFile.orEmpty()).absolutePath,
                    language = lang,
                    task = "transcribe",
                ),
                tokens = tokens,
                numThreads = THREADS,
                debug = false,
            )
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )

        Log.i(TAG, "loading offline model from $dir for $lang")
        val created = OfflineRecognizer(config = config)
        Log.i(TAG, "offline model loaded")
        recognizer = created
        recognizerKey = key

        return created
    }

    private fun capture(): FloatArray? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IOException("audio_unavailable")

        val bufferSize = maxOf(minBuffer, CHUNK_SAMPLES * Short.SIZE_BYTES * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("audio_unavailable")
        }

        val samples = ArrayList<Short>(SAMPLE_RATE * MAX_SECONDS)
        val history = ArrayDeque<ShortArray>()
        val chunk = ShortArray(CHUNK_SAMPLES)
        var started = false
        var speechChunks = 0
        var silenceChunks = 0
        val beganAt = System.currentTimeMillis()

        try {
            record.startRecording()

            while (!cancelled) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) break

                val current = chunk.copyOf(read)
                val level = rms(current)

                if (!started) {
                    history.addLast(current)
                    if (history.size > HISTORY_CHUNKS) history.removeFirst()

                    if (level >= SPEECH_RMS) {
                        started = true
                        speechChunks = 0
                        history.forEach { samples.addAll(it.toList()) }
                        history.clear()
                    } else if (System.currentTimeMillis() - beganAt > START_TIMEOUT_MS) {
                        return null
                    }
                } else {
                    samples.addAll(current.toList())

                    if (level < SPEECH_RMS) {
                        silenceChunks += 1
                        if (silenceChunks >= SILENCE_CHUNKS) break
                    } else {
                        silenceChunks = 0
                        speechChunks += 1
                    }

                    if (System.currentTimeMillis() - beganAt > MAX_DURATION_MS) break
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        if (cancelled || !started || speechChunks < MIN_SPEECH_CHUNKS) return null

        val floats = FloatArray(samples.size)
        for (index in samples.indices) {
            floats[index] = samples[index] / 32768f
        }

        return floats
    }

    private fun rms(chunk: ShortArray): Float {
        if (chunk.isEmpty()) return 0f

        var sum = 0.0
        for (value in chunk) {
            val sample = value / 32768.0
            sum += sample * sample
        }

        return sqrt(sum / chunk.size).toFloat()
    }

    companion object {
        private const val TAG = "LexiAsr"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val THREADS = 2
        private const val CHUNK_SAMPLES = 1600
        private const val HISTORY_CHUNKS = 3
        private const val MIN_SPEECH_CHUNKS = 2
        private const val SILENCE_CHUNKS = 7
        private const val SPEECH_RMS = 0.02f
        private const val START_TIMEOUT_MS = 5000L
        private const val MAX_DURATION_MS = 12000L
        private const val MAX_SECONDS = 12
        private const val REASON_CANCELLED = "cancelled"
        private const val REASON_NO_SPEECH = "no_speech"
        private const val REASON_PERMISSION = "permission_denied"
        private const val REASON_FAILED = "recognition_failed"
    }
}
