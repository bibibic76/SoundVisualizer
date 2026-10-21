package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiLatencyTrace

/** Separate versioned stream; existing classification CSV schema stays intact. */
object AiLatencyCsv {
    const val HEADER = "schema,stage,at_boot_ns,session_id,detail,pipeline_session_id,result_id,pipeline_started_boot_ns,capture_session_id,first_frame,end_frame_exclusive,sample_rate,read_return_boot_ns,input_may_be_filtered,snapshot_begin_boot_ns,snapshot_end_boot_ns,preprocessed_boot_ns,inferred_boot_ns,confirmed_boot_ns,raw_coarse,meets_threshold,candidate_streak,confirmed_coarse,mapping_signature,raw_confidence,effective_threshold,candidate_coarse"
    private fun field(value: Any?): String = "\"${value?.toString().orEmpty().replace("\"", "\"\"")}\""
    fun row(event: AiLatencyTrace.Event): String {
        val s = event.stamp
        return listOf(1, event.stage, event.atNs, event.sessionId, event.detail,
            s?.pipelineSessionId, s?.resultId, s?.pipelineStartedNs, s?.captureSessionId,
            s?.firstFrame, s?.endFrameExclusive, s?.sampleRate, s?.readCompletedNs,
            s?.inputMayBeFiltered, s?.snapshotBeginNs, s?.snapshotEndNs, s?.preprocessedNs,
            s?.inferredNs, s?.confirmedNs, s?.rawCoarse, s?.meetsThreshold, s?.candidateStreak,
            s?.confirmedCoarse, s?.mappingSignature, s?.rawConfidence, s?.effectiveThreshold,
            s?.candidateCoarse).joinToString(",", transform = ::field)
    }
}
