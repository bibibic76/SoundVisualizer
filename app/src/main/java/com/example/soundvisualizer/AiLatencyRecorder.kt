package com.example.soundvisualizer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.soundvisualizer.ai.AiLatencyTrace
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Paired with the existing developer recording toggle. File I/O only on its own worker. */
object AiLatencyRecorder {
    private var recording: AiLatencyTrace.Recording? = null
    private const val MAX_BYTES = 20L * 1024 * 1024

    @Synchronized fun start(context: Context, stamp: String) {
        if (recording != null) return
        val app = context.applicationContext
        val mine = AiLatencyTrace.begin()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        recording = mine
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-latency-record").apply { isDaemon = true } }
        executor.execute {
            var bytes = 0L
            var writer: java.io.BufferedWriter? = null
            try {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, AiDebugRecorder.DIR_NAME)
                    while (mine.active.get() || mine.queue.isNotEmpty()) {
                        val event = mine.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        if (writer == null) {
                            dir.mkdirs()
                            val file = File.createTempFile("latency-$stamp-${mine.id}-", ".csv", dir)
                            writer = file.bufferedWriter()
                            writer!!.appendLine(AiLatencyCsv.HEADER)
                            writer!!.appendLine(AiLatencyCsv.row(AiLatencyTrace.Event("recording_start", startedNs,
                                detail = "version=${BuildConfig.VERSION_NAME};sdk=${android.os.Build.VERSION.SDK_INT}")))
                        }
                        val out = writer!!
                        val row = AiLatencyCsv.row(event)
                        out.appendLine(row)
                        bytes += row.toByteArray(Charsets.UTF_8).size + 1
                        out.flush()
                        if (bytes >= MAX_BYTES) {
                            AiLatencyTrace.end(mine)
                            mine.dropped.addAndGet(mine.queue.size.toLong())
                            mine.queue.clear()
                            break
                        }
                    }
                    writer?.appendLine(AiLatencyCsv.row(AiLatencyTrace.Event("recording_end",
                        SystemClock.elapsedRealtimeNanos(), detail = "dropped=${mine.dropped.get()};limit=${bytes >= MAX_BYTES}")))
            } catch (e: Exception) {
                Log.w("AiLatencyRecorder", "Latency recording stopped", e)
            } finally {
                try { writer?.close() } catch (e: Exception) { Log.w("AiLatencyRecorder", "Closing trace", e) }
                AiLatencyTrace.end(mine)
                synchronized(this) { if (recording === mine) recording = null }
            }
        }
        executor.shutdown()
    }

    @Synchronized fun stop() {
        recording?.let(AiLatencyTrace::end)
        recording = null
    }
}
