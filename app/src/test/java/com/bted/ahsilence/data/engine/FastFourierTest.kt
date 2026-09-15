package com.bted.ahsilence.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FastFourierTest {

    @Test
    fun testExtractDominantFrequency_detects100HzTone() {
        val sampleRate = 48000
        val bufferSize = 8192
        val targetFreq = 100.0 // 100 Hz

        val fft = FastFourier(bufferSize, sampleRate)
        val pcmData = ShortArray(bufferSize)

        for (i in 0 until bufferSize) {
            val t = i.toDouble() / sampleRate
            val sample = sin(2.0 * PI * targetFreq * t)
            pcmData[i] = (sample * 30000.0).toInt().toShort()
        }

        val detectedFreq = fft.extractDominantFrequency(pcmData)

        // Frequency resolution: 48000 / 8192 ≈ 5.86 Hz
        assertEquals(targetFreq, detectedFreq.toDouble(), 6.0)
    }

    @Test
    fun testExtractDominantFrequency_detects250HzTone() {
        val sampleRate = 48000
        val bufferSize = 8192
        val targetFreq = 250.0

        val fft = FastFourier(bufferSize, sampleRate)
        val pcmData = ShortArray(bufferSize)

        for (i in 0 until bufferSize) {
            val t = i.toDouble() / sampleRate
            val sample = sin(2.0 * PI * targetFreq * t)
            pcmData[i] = (sample * 30000.0).toInt().toShort()
        }

        val detectedFreq = fft.extractDominantFrequency(pcmData)

        assertEquals(targetFreq, detectedFreq.toDouble(), 6.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNonPowerOfTwo_throwsException() {
        FastFourier(500, 48000)
    }
}
