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

    init {
        require(AiCaptureSampleRatePolicy.isSupportedForAi(captureSampleRate)) {
            "Unsupported AI capture sample rate: $captureSampleRate"
        }
    }

    private val lock = Any()
    private val ring = FloatArray(MAX_RING_SIZE)
    private var head = 0
    private var tail = 0
    private var count = 0
    private val pcmTrace = AiPcmTrace()
    private var tracedFrames = 0L
    private var totalFrames = 0L
    private var generation = 0L
    internal data class Cursor(val generation: Long, val frames: Long)
    internal fun cursor(): Cursor = synchronized(lock) { Cursor(generation, totalFrames) }

    /** Absolute stream range for the optional worker; never waits for model execution. */
    internal fun copyRange(epoch: Long, first: Long, size: Int, destination: FloatArray): Boolean = synchronized(lock) {
        require(size <= destination.size)
        if (epoch != generation || first + size > totalFrames || maxOf(first, 0) < totalFrames - count) return@synchronized false
        for (i in 0 until size) {
            val frame = first + i
            destination[i] = if (frame < 0) 0f else ring[((tail.toLong() - (totalFrames - frame)).toInt()) and RING_MASK]
        }
        true
    }

    /** Scratch for stereo→mono without per-read allocation of mono output. */
    private val monoScratch = FloatArray(MAX_RING_SIZE / 2)

    val sampleRate: Int get() = captureSampleRate
    val channelCount: Int get() = channels
    val availableSamples: Int get() = synchronized(lock) { count }

    fun reset() {
        synchronized(lock) {
            head = 0
            tail = 0
            count = 0
            totalFrames = 0
            generation++
            pcmTrace.clear()
            tracedFrames = 0
        }
    }

    /**
     * Copy interleaved PCM into the mono ring immediately (safe with reused AudioRecord buffers).
     * @param floatCount number of float samples read (stereo: frames*2)
     */
    fun ingestInterleaved(pcm: FloatArray, floatCount: Int,
                          captureSessionId: Long = 0, endFrameExclusive: Long = 0,
                          readCompletedNs: Long = 0, inputMayBeFiltered: Boolean = false) {
        if (floatCount <= 0) return
        val frames = CaptureAudioMath.downmixInterleavedToMono(
            interleaved = pcm,
            floatCount = floatCount,
            channels = channels,
            dest = monoScratch,
            destOffset = 0
        )
        if (frames <= 0) return
        appendMono(monoScratch, frames, captureSessionId, endFrameExclusive, readCompletedNs, inputMayBeFiltered)
    }

    /** Test/helper: ingest already-mono capture-rate samples. */
    fun ingestMono(mono: FloatArray, length: Int = mono.size) {
        if (length <= 0) return
        appendMono(mono, length)
    }

    private fun appendMono(monoChunk: FloatArray, length: Int, captureSessionId: Long = 0,
                           endFrameExclusive: Long = 0, readCompletedNs: Long = 0,
                           inputMayBeFiltered: Boolean = false) {
        val cap = maxOf(
            DEFAULT_CAPTURE_SAMPLE_RATE * RING_SECONDS,
            captureSampleRate * RING_SECONDS
        )
        synchronized(lock) {
            totalFrames += length
            val recordingId = if (readCompletedNs > 0) AiLatencyTrace.recordingId else 0
            val contiguous = recordingId != 0L && recordingId == pcmTrace.recordingId &&
                captureSessionId != 0L && captureSessionId == pcmTrace.captureSessionId &&
                endFrameExclusive - length == pcmTrace.endFrameExclusive
            tracedFrames = if (contiguous) tracedFrames + length else if (recordingId != 0L) length.toLong() else 0L
            pcmTrace.recordingId = recordingId
            pcmTrace.captureSessionId = captureSessionId
            pcmTrace.endFrameExclusive = endFrameExclusive
            pcmTrace.readCompletedNs = readCompletedNs
            pcmTrace.inputMayBeFiltered = inputMayBeFiltered
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
    fun copyTailRightPadded(destination: FloatArray, countRequested: Int, trace: AiPcmTrace? = null): Int {
        require(destination.size >= countRequested)
        synchronized(lock) {
            if (trace != null) {
                trace.recordingId = pcmTrace.recordingId
                trace.captureSessionId = if (tracedFrames >= countRequested) pcmTrace.captureSessionId else 0
                trace.endFrameExclusive = if (tracedFrames >= countRequested) pcmTrace.endFrameExclusive else 0
                trace.readCompletedNs = pcmTrace.readCompletedNs
                trace.inputMayBeFiltered = pcmTrace.inputMayBeFiltered
            }
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
