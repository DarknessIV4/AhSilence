package com.bted.ahsilence.data.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.bted.ahsilence.core.logging.AppLogger
import com.bted.ahsilence.domain.port.AudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapter implementing [AudioEngine] by delegating the entire real-time hot
 * path (capture -> FxLMS adapt -> emit) to a native Oboe engine over JNI.
 *
 * Compared to the previous pure-Kotlin implementation, this class does
 * almost nothing itself. Its only two jobs are:
 *  1. A one-time coarse frequency estimate via FFT -- kept here in
 *     Kotlin/IO because it runs BEFORE the real-time loop starts and is
 *     not latency-critical.
 *  2. Marshalling UI parameter changes down to the native engine.
 * Every per-sample DSP operation now happens in C++, driven directly by
 * Oboe's own high-priority audio callback thread -- there is no longer a
 * Kotlin coroutine in the hot path at all, which is what removes the
 * 20-50ms of JVM/AudioTrack scheduling latency this class used to have.
 *
 * NOTE: this adds a native lifecycle that the old class didn't have.
 * Call [release] when whatever owns this adapter is torn down (e.g. from
 * a ViewModel's onCleared()), or the native AncEngine instance will leak.
 */
class NativeAudioDSP : AudioEngine {

    private companion object {
        init { System.loadLibrary("ancengine") }
    }

    // Requested/preferred rate -- Oboe negotiates the device's actual native
    // rate internally; ideally source this from
    // AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE) rather than a
    // hardcoded value, but correctness here doesn't depend on guessing right
    // since AncEngine re-queries the real negotiated rate after opening.
    private val preferredSampleRate = com.bted.ahsilence.core.constants.AudioConstants.SAMPLE_RATE
    private val fftBufferSize = com.bted.ahsilence.core.constants.AudioConstants.FFT_BUFFER_SIZE
    private val fftEngine = FastFourier(fftBufferSize, preferredSampleRate)

    private var nativeHandle: Long = 0L

    @SuppressLint("MissingPermission") // Handled at the UI/Manifest layer
    override suspend fun captureAndAnalyzeEnv(durationSeconds: Int): Float = withContext(Dispatchers.IO) {
        ensureEngineCreated()

        val minBufferSize = AudioRecord.getMinBufferSize(
            preferredSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            AppLogger.e("AudioRecord: Invalid buffer size for sample rate $preferredSampleRate")
            return@withContext 0f
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                preferredSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize.coerceAtLeast(fftBufferSize * 2)
            )
        } catch (e: Exception) {
            AppLogger.e("Failed to create AudioRecord: ${e.message}")
            return@withContext 0f
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.e("AudioRecord is uninitialized. Mic may be in use or permission not yet granted.")
            try { record.release() } catch (_: Exception) {}
            return@withContext 0f
        }

        val readBuffer = ShortArray(fftBufferSize)
        var detectedFrequency = 0f

        try {
            AppLogger.d("Engine: Mic engaged for room analysis ($durationSeconds s)...")
            record.startRecording()

            val startTimeMs = System.currentTimeMillis()
            val totalDurationMs = durationSeconds * 1000L
            var zeroByteCount = 0

            while (System.currentTimeMillis() - startTimeMs < totalDurationMs) {
                var read = 0
                while (read < fftBufferSize) {
                    val result = record.read(readBuffer, read, fftBufferSize - read)
                    if (result < 0) {
                        AppLogger.e("CRITICAL: AudioRecord hardware failed with OS error code: $result")
                        break
                    } else if (result == 0) {
                        zeroByteCount++
                        if (zeroByteCount > com.bted.ahsilence.core.constants.AudioConstants.ZERO_READ_ESCAPE_THRESHOLD) {
                            AppLogger.e("CRITICAL: Mic is engaged but returning 0 bytes. OS is blocking the stream.")
                            break
                        }
                    } else {
                        read += result
                        zeroByteCount = 0
                    }
                }

                if (read == fftBufferSize) {
                    detectedFrequency = fftEngine.extractDominantFrequency(readBuffer)
                }

                if (zeroByteCount > com.bted.ahsilence.core.constants.AudioConstants.ZERO_READ_ESCAPE_THRESHOLD) break
            }
        } catch (e: Exception) {
            AppLogger.e("Exception during audio capture: ${e.message}")
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {}
            try {
                record.release()
            } catch (_: Exception) {}
        }

        AppLogger.d("Engine: FFT complete. Handing $detectedFrequency Hz to the native tracker.")
        nativeSetFrequency(nativeHandle, detectedFrequency)
        return@withContext detectedFrequency
    }

    override fun startAntiNoiseEmission() {
        ensureEngineCreated()
        val started = nativeStart(nativeHandle)
        AppLogger.d("Engine: native Oboe duplex stream started = $started")
    }

    override fun updateParameters(amplitudePercentage: Float, phaseDegrees: Float) {
        if (nativeHandle != 0L) {
            nativeUpdateParameters(nativeHandle, amplitudePercentage, phaseDegrees)
        }
    }

    override fun stop() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
        }
    }

    /** Frees the native AncEngine. See the class-level NOTE above. */
    override fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun ensureEngineCreated() {
        if (nativeHandle == 0L) {
            nativeHandle = nativeCreate(preferredSampleRate)
        }
    }

    // --- JNI bridge -----------------------------------------------------
    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetFrequency(handle: Long, frequencyHz: Float)
    private external fun nativeUpdateParameters(handle: Long, gainPercent: Float, phaseDegrees: Float)
    private external fun nativeDestroy(handle: Long)
}
