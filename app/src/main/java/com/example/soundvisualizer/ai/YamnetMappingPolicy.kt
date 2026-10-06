package com.example.soundvisualizer.ai

/** Immutable, exact-English-label snapshot shared by all stages of one inference. */
class YamnetMappingPolicy private constructor(private val overrides: Map<String, String>) {
    val overrideCount: Int get() = overrides.size

    // Full canonical contents, not a process-dependent hash: CSV can reproduce the mapping.
    val signature: String = if (overrides.isEmpty()) "default" else
        overrides.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }

    fun coarse(name: String): String =
        overrides[name] ?: YamnetThreeClassMapper.mapDisplayNameToCoarse(name)

    /** Demoted labels cannot re-enter through keyword shortcuts. New Danger labels only vote. */
    fun allowsSafetyCue(name: String): Boolean =
        overrides[name]?.let { it == "danger" } ?: true

    fun hasCriticalDangerCue(display: String, top5: List<YamnetCoarseClassifier.TopClassHit>): Boolean =
        (allowsSafetyCue(display) && AiPostProcessor.isCriticalDangerKeyword(display)) ||
            top5.any { allowsSafetyCue(it.name) && AiPostProcessor.isCriticalDangerKeyword(it.name) }

    companion object {
        val DEFAULT = YamnetMappingPolicy(emptyMap())

        fun from(overrides: Map<String, String>, classNames: List<String>): YamnetMappingPolicy {
            val names = classNames.toHashSet()
            val valid = overrides.filter { (name, coarse) ->
                name in names && coarse in setOf("ambient", "speech", "danger") &&
                    coarse != YamnetThreeClassMapper.mapDisplayNameToCoarse(name)
            }
            return if (valid.isEmpty()) DEFAULT else YamnetMappingPolicy(valid)
        }
    }
}
