package com.example.soundvisualizer.ai

/**
 * AI-only capture mono ring (reference capture ring semantics).
 * Independent of the visualizer's native audio path.
 *
 * Thread-safe: ingest from AudioRecord loop; snapshot from AI coroutine.
 */
class AiAudioBuffer(
    private val captureSampleRate: Int = DEFAULT_CAPTURE_SAMPLE_RATE,
    private val channels: Int = 2
) {
    companion object {
        const val DEFAULT_CAPTURE_SAMPLE_RATE = 44100
        const val RING_SECONDS = 2
        /** MaxRingSize — power of two for mask indexing. */
        const val MAX_RING_SIZE = 131072
        private const val RING_MASK = MAX_RING_SIZE - 1
    }

    private val lock = Any()
    private val ring = FloatArray(MAX_RING_SIZE)
    private var head = 0
    private var tail = 0
    private var count = 0

    /** Scratch for stereo→mono without per-read allocation of mono output. */
    private val monoScratch = FloatArray(MAX_RING_SIZE / 2)

    val sampleRate: Int get() = captureSampleRate
    val availableSamples: Int get() = synchronized(lock) { count }

    fun reset() {
        synchronized(lock) {
            head = 0
            tail = 0
            count = 0
        }
    }

    /**
     * Copy interleaved PCM into the mono ring immediately (safe with reused AudioRecord buffers).
     * @param floatCount number of float samples read (stereo: frames*2)
     */
    fun ingestInterleaved(pcm: FloatArray, floatCount: Int) {
        if (floatCount <= 0) return
        val frames = CaptureAudioMath.downmixInterleavedToMono(
            interleaved = pcm,
            floatCount = floatCount,
            channels = channels,
            dest = monoScratch,
            destOffset = 0
        )
        if (frames <= 0) return
        appendMono(monoScratch, frames)
    }

    /** Test/helper: ingest already-mono capture-rate samples. */
    fun ingestMono(mono: FloatArray, length: Int = mono.size) {
        if (length <= 0) return
        appendMono(mono, length)
    }

    private fun appendMono(monoChunk: FloatArray, length: Int) {
        val cap = maxOf(
            DEFAULT_CAPTURE_SAMPLE_RATE * RING_SECONDS,
            captureSampleRate * RING_SECONDS
        )
        synchronized(lock) {
            for (i in 0 until length) {
                ring[tail] = monoChunk[i]
                tail = (tail + 1) and RING_MASK
                if (count < MAX_RING_SIZE) {
                    count++
                } else {
                    head = (head + 1) and RING_MASK
                }
            }
            while (count > cap) {
                head = (head + 1) and RING_MASK
                count--
            }
        }
    }

    /**
     * CopyRingTailRightPadded: latest [take] samples right-aligned; left zeros if short.
     */
    fun copyTailRightPadded(destination: FloatArray, countRequested: Int): Int {
        require(destination.size >= countRequested)
        synchronized(lock) {
            for (i in 0 until countRequested) destination[i] = 0f
            val take = minOf(count, countRequested)
            val dstStart = countRequested - take
            if (take > 0) {
                val ringStart = (tail - take) and RING_MASK
                for (i in 0 until take) {
                    destination[dstStart + i] = ring[(ringStart + i) and RING_MASK]
                }
            }
            return count
        }
    }

    fun hasEnoughForYamnetWindow(): Boolean {
        val need = CaptureAudioMath.captureSamplesForOneYamnetWindow(captureSampleRate)
        return synchronized(lock) { count >= need }
    }
}
