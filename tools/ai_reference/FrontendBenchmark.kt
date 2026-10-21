package com.example.soundvisualizer.ai

import java.lang.management.ManagementFactory
import kotlin.math.sin
import kotlin.system.measureNanoTime

/** Host JVM microbenchmark; timings do not predict Android end-to-end latency or battery. */
fun main(args: Array<String>) {
    val iterations = args.firstOrNull()?.toInt() ?: 500
    require(iterations > 0)
    val frontend = QualcommSourceAudioPreprocessor()
    val mono = FloatArray(15_600) { (0.4 * sin(it * 0.13) + 0.1 * sin(it * 0.037)).toFloat() }
    val capture = FloatArray(46_800) { (0.4 * sin(it * 0.13 / 3)).toFloat() }
    val resampled = FloatArray(15_600)
    val cpu = ManagementFactory.getThreadMXBean()
    var checksum = 0.0
    fun sample(name: String, action: () -> Unit) {
        repeat(200) { action() }
        val times = LongArray(iterations)
        val cpuStart = cpu.currentThreadCpuTime
        for (i in times.indices) times[i] = measureNanoTime { action() }
        val cpuNs = cpu.currentThreadCpuTime - cpuStart
        times.sort()
        println("$name n=$iterations medianMs=${times[iterations / 2] / 1e6} " +
            "p95Ms=${times[((iterations - 1) * 0.95).toInt()] / 1e6} " +
            "threadCpuMsPerTick=${cpuNs / iterations / 1e6}")
    }
    sample("resample48k") {
        CaptureAudioMath.resampleMonoFloatTo16kCustom(capture, capture.size, 48_000, resampled)
        checksum += resampled[100]
    }
    sample("logmel") { checksum += frontend.computeLogMelSpectrogram(mono)[100] }
    sample("frontend48k") {
        CaptureAudioMath.resampleMonoFloatTo16kCustom(capture, capture.size, 48_000, resampled)
        checksum += frontend.computeLogMelSpectrogram(resampled)[100]
    }
    println("checksum=$checksum java=${System.getProperty("java.version")} arch=${System.getProperty("os.arch")}")
}
