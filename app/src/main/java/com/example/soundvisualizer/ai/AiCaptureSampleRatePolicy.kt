package com.example.soundvisualizer.ai

/**
 * Sample-rate contract shared by capture selection and the realtime AI pipeline.
 *
 * 44.1/48kHz are the production downsampling rates. 16kHz is also accepted so
 * direct/passthrough inputs keep the YAMNet contract. Higher native rates remain
 * available as a capture-only fallback, but must not enter the fixed AI ring or
 * resampler without a separately validated anti-aliasing design.
 */
object AiCaptureSampleRatePolicy {
    const val DEFAULT_CAPTURE_SAMPLE_RATE = 48000

    private val preferredCaptureRates = intArrayOf(48000, 44100)

    fun isSupportedForAi(sampleRate: Int): Boolean {
        return sampleRate == 16000 || sampleRate == 44100 || sampleRate == 48000
    }

    /**
     * Prefer a reported native rate only when AI supports it. Otherwise try the
     * validated rates before retaining the native rate as a capture-only fallback.
     */
    fun orderedCaptureCandidates(reportedSampleRate: Int?): IntArray {
        val reported = reportedSampleRate?.takeIf { it > 0 }
        val candidates = ArrayList<Int>(preferredCaptureRates.size + 1)

        if (reported != null && isSupportedForAi(reported)) candidates.add(reported)
        for (rate in preferredCaptureRates) {
            if (!candidates.contains(rate)) candidates.add(rate)
        }
        if (reported != null && !candidates.contains(reported)) candidates.add(reported)

        return candidates.toIntArray()
    }

    fun selectCaptureRate(
        reportedSampleRate: Int?,
        isAccepted: (Int) -> Boolean
    ): Int {
        return orderedCaptureCandidates(reportedSampleRate).firstOrNull(isAccepted)
            ?: DEFAULT_CAPTURE_SAMPLE_RATE
    }
}
