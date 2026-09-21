package com.example.soundvisualizer

import android.content.Context
import android.util.Log
import com.example.soundvisualizer.ai.AiClassificationResult
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 개발자 모드의 **결과 기록**을 파일로 남긴다 (#193). 줄을 만드는 규칙은 [AiDebugLogWriter] 에 있다.
 *
 * 켜면 `getExternalFilesDir()/ai-log/ai-<날짜-시각>.csv` 를 새로 열고, 끄면 닫는다. 받아 보는 법:
 * `adb pull /sdcard/Android/data/com.example.soundvisualizer/files/ai-log/`
 *
 * **디스크는 따로 둔 스레드에서만 만진다.** 부르는 곳은 오버레이의 HUD 루프(메인 스레드)라서,
 * 거기서 파일을 열거나 쓰면 그리는 프레임이 밀린다. 그래서 [offer] 는 큐에 넣고 바로 돌아온다.
 * 큐가 차면 가장 오래된 것을 버린다 — 기록이 밀리는 것보다 화면이 밀리는 것이 나쁘다.
 */
object AiDebugRecorder {

    private const val TAG = "AiDebugRecorder"

    /** 파일을 두는 폴더 이름. adb pull 로 폴더째 받는다. */
    const val DIR_NAME = "ai-log"

    /**
     * 큐에 담아 두는 줄 수. 넘치면 오래된 것을 버린다.
     *
     * 초당 네 줄이니 200 이면 50초 분량이다. 디스크가 그만큼 밀리는 일은 없고, 있다면 그 기기에서는
     * 기록 자체가 뜻이 없다.
     */
    private const val QUEUE_CAPACITY = 200

    /** 몇 줄마다 디스크에 밀어 넣는지. 앱이 갑자기 죽어도 이만큼만 잃는다. */
    private const val FLUSH_EVERY = 20

    private val queue = LinkedBlockingQueue<Row>(QUEUE_CAPACITY)

    /** 쓰는 스레드. 켤 때 만들고 끌 때 없앤다. */
    private var worker: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var running = false

    /** 지금 쓰고 있는 파일. 껐다 켜면 새 파일이 된다. 켜지 않았으면 null. */
    @Volatile
    var currentFile: File? = null
        private set

    /** 큐가 차서 버린 줄 수. 0 이 아니면 기기가 따라오지 못한 것이다. */
    @Volatile
    var dropped: Int = 0
        private set

    private data class Row(
        val result: AiClassificationResult,
        val nowMs: Long,
        val level: Float,
        val shown: Boolean
    )

    /**
     * 기록을 시작한다. 이미 돌고 있으면 아무것도 하지 않는다(설정을 두 번 켜도 파일이 갈리지 않게).
     *
     * 파일을 만들지 못하면 시작하지 않고 로그만 남긴다. 기록이 안 되는 것이 앱이 죽는 것보다 낫다.
     */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        val file = createFile(context) ?: return
        val writer = try {
            file.bufferedWriter()
        } catch (e: IOException) {
            Log.e(TAG, "기록 파일을 열지 못했다: ${file.absolutePath}", e)
            return
        }
        queue.clear()
        dropped = 0
        currentFile = file
        running = true
        val log = AiDebugLogWriter(writer)
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ai-debug-record").apply { isDaemon = true }
        }.also { executor ->
            executor.execute {
                try {
                    var sinceFlush = 0
                    while (running || queue.isNotEmpty()) {
                        // 끌 때 이 스레드가 큐에서 영원히 기다리지 않도록 시간 제한을 둔다.
                        val row = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        if (log.write(row.result, row.nowMs, row.level, row.shown)) {
                            sinceFlush++
                            if (sinceFlush >= FLUSH_EVERY) {
                                writer.flush()
                                sinceFlush = 0
                            }
                        }
                        if (log.stopped) {
                            Log.w(TAG, "기록 상한에 닿아 멈춘다: ${log.rows}줄, ${currentFile?.name}")
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: IOException) {
                    Log.e(TAG, "기록을 쓰다 실패했다", e)
                } finally {
                    try {
                        writer.flush()
                        writer.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "기록 파일을 닫다 실패했다", e)
                    }
                    Log.i(TAG, "기록 끝: ${log.rows}줄, 버린 줄 $dropped, ${currentFile?.name}")
                }
            }
        }
        Log.i(TAG, "기록 시작: ${file.absolutePath}")
    }

    /** 기록을 멈추고 파일을 닫는다. 남은 큐는 쓰는 스레드가 비우고 나간다. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        worker?.shutdown()
        worker = null
    }

    /**
     * 결과 하나를 큐에 넣는다. 켜 두지 않았으면 아무것도 하지 않는다.
     *
     * 메인 스레드에서 불러도 되도록 여기서는 디스크를 만지지 않는다.
     */
    fun offer(result: AiClassificationResult, nowMs: Long, level: Float, shown: Boolean) {
        if (!running) return
        if (!queue.offer(Row(result, nowMs, level, shown))) {
            // 가장 오래된 것을 버리고 새것을 넣는다. 최근 것이 궁금한 도구라 뒤를 살린다.
            queue.poll()
            dropped++
            queue.offer(Row(result, nowMs, level, shown))
        }
    }

    private fun createFile(context: Context): File? {
        // 앱 전용 외부 폴더라 adb pull 로 바로 받을 수 있다. 없는 기기(외부 저장소가 빠진 경우)에서는
        // 내부 폴더로 물러난다 — 그때는 run-as 로 꺼내야 한다.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "기록 폴더를 만들지 못했다: ${dir.absolutePath}")
            return null
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "ai-$stamp.csv")
    }
}
