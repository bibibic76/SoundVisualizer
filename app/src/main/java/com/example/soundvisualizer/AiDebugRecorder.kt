package com.example.soundvisualizer

import android.content.Context
import android.util.Log
import com.example.soundvisualizer.ai.AiClassificationResult
import java.io.File
import java.io.IOException
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 개발자 모드의 **결과 기록**을 파일로 남긴다 (#193). 줄을 만드는 규칙은 [AiDebugLogWriter] 에 있다.
 *
 * 켜면 `getExternalFilesDir()/ai-log/ai-<날짜-시각>.csv` 를 새로 열고, 끄면 닫는다. 받아 보는 법:
 * `adb pull /sdcard/Android/data/com.example.soundvisualizer/files/ai-log/`
 *
 * **디스크는 쓰는 스레드에서만 만진다.** 부르는 곳은 오버레이의 HUD 루프(메인 스레드)라서, 거기서
 * 폴더를 확인하거나 파일을 열거나 쓰면 그리는 프레임이 밀린다. 그래서 [offer] 는 큐에 넣고 바로
 * 돌아오고, 파일 경로를 정하는 일까지 쓰는 스레드가 한다(#199, #208).
 *
 * **켠 회차마다 큐를 따로 둔다.** [stop] 은 쓰는 스레드가 끝나기를 기다리지 않는다 — 메인 스레드를
 * 잡을 수 없기 때문이다. 그래서 껐다 곧바로 켜면 옛 스레드가 아직 살아 있는데, 큐가 하나면 두
 * 스레드가 그것을 나눠 먹어 줄이 두 파일로 갈렸다(#208). 회차마다 큐가 따로면 옛 스레드는 자기 줄만
 * 비우고 나가고(끌 때 남은 줄을 잃지 않는다), 새 줄은 새 스레드만 본다.
 */
object AiDebugRecorder {

    private const val TAG = "AiDebugRecorder"

    /** 파일을 두는 폴더 이름. adb pull 로 폴더째 받는다. */
    const val DIR_NAME = "ai-log"

    /**
     * 한 회차의 큐에 담아 두는 줄 수. 넘치면 오래된 것을 버린다.
     *
     * 초당 네 줄이니 200 이면 50초 분량이다. 디스크가 그만큼 밀리는 일은 없고, 있다면 그 기기에서는
     * 기록 자체가 뜻이 없다.
     */
    private const val QUEUE_CAPACITY = 200

    /** 켠 한 회차. 큐와 "아직 켜져 있는지" 를 함께 들고 있어 회차끼리 섞이지 않는다. */
    private class Session(val stamp: String) {
        val queue = LinkedBlockingQueue<Row>(QUEUE_CAPACITY)
        val active = AtomicBoolean(true)

        @Volatile
        var dropped: Int = 0
    }

    private class Row(
        val result: AiClassificationResult,
        val nowMs: Long,
        val level: Float,
        val shown: Boolean
    )

    /** 지금 켜져 있는 회차. 꺼져 있으면 null. */
    @Volatile
    private var session: Session? = null

    private var worker: java.util.concurrent.ExecutorService? = null

    /** 지금 쓰고 있는 파일. 경로는 쓰는 스레드가 정하므로 [start] 직후에는 아직 null 이다. */
    @Volatile
    var currentFile: File? = null
        private set

    /** 큐가 차서 버린 줄 수. 0 이 아니면 기기가 따라오지 못한 것이다. */
    val dropped: Int
        get() = session?.dropped ?: 0

    /**
     * 기록을 시작한다. 이미 돌고 있으면 아무것도 하지 않는다(설정을 두 번 켜도 파일이 갈리지 않게).
     *
     * **여기서는 디스크를 만지지 않는다.** 파일 이름에 쓸 시각만 정하고, 폴더 해석·만들기와 파일 열기는
     * 쓰는 스레드가 한다. 그래서 줄이 한 번도 오지 않으면 파일도 만들어지지 않는다 — 켜 보고 바로 끈
     * 기기에 빈 파일이 남지 않는 편이 낫다.
     *
     * 파일을 열지 못하면 쓰는 스레드가 로그를 남기고 그 회차를 닫는다. 기록이 안 되는 것이 앱이 죽는 것보다 낫다.
     */
    @Synchronized
    fun start(context: Context) {
        if (session != null) return
        val app = context.applicationContext
        val mine = Session(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
        session = mine
        currentFile = null
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ai-debug-record").apply { isDaemon = true }
        }.also { executor -> executor.execute { write(app, mine) } }
        Log.i(TAG, "기록 시작: ai-${mine.stamp}.csv (경로는 쓰는 스레드가 정한다)")
    }

    /**
     * 기록을 멈추고 파일을 닫는다.
     *
     * 쓰는 스레드가 끝나기를 **기다리지 않는다.** 부르는 곳이 메인 스레드라, 여기서 기다리면 그리는
     * 프레임을 큐 대기 시간만큼 잡는다. 그 스레드는 자기 큐에 남은 줄을 비우고 스스로 나간다.
     */
    @Synchronized
    fun stop() {
        val leaving = session ?: return
        leaving.active.set(false)
        session = null
        // currentFile 은 "지금 쓰고 있는 파일" 이다. 멈춘 뒤에도 남겨 두면 끈 상태와 켠 상태를 가릴 수 없다.
        currentFile = null
        worker?.shutdown()
        worker = null
    }

    /**
     * 결과 하나를 지금 회차의 큐에 넣는다. 켜 두지 않았으면 아무것도 하지 않는다.
     *
     * 메인 스레드에서 불러도 되도록 여기서는 디스크를 만지지 않는다.
     */
    fun offer(result: AiClassificationResult, nowMs: Long, level: Float, shown: Boolean) {
        val current = session ?: return
        val row = Row(result, nowMs, level, shown)
        if (!current.queue.offer(row)) {
            // 가장 오래된 것을 버리고 새것을 넣는다. 최근 것이 궁금한 도구라 뒤를 살린다.
            current.queue.poll()
            current.dropped++
            current.queue.offer(row)
        }
    }

    /** 쓰는 스레드의 본체. 자기 회차의 큐만 본다. */
    private fun write(app: Context, mine: Session) {
        // 폴더 해석까지 이 스레드에서 한다. getExternalFilesDir 은 경로 계산만 하지 않고 대상 폴더를
        // 확인하고(stat) 없으면 만든다 — 메인 스레드에서 할 일이 아니다(#208).
        val file = File(File(app.getExternalFilesDir(null) ?: app.filesDir, DIR_NAME), "ai-${mine.stamp}.csv")
        if (session === mine) {
            currentFile = file
            Log.i(TAG, "기록 파일: ${file.absolutePath}")
        }

        // 파일은 **첫 줄이 올 때** 만든다.
        var writer: Writer? = null
        var log: AiDebugLogWriter? = null
        try {
            while (mine.active.get() || mine.queue.isNotEmpty()) {
                // 끌 때 이 스레드가 큐에서 영원히 기다리지 않도록 시간 제한을 둔다.
                val row = mine.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (writer == null) {
                    writer = openWriter(file) ?: run {
                        closeSession(mine)
                        return
                    }
                    log = AiDebugLogWriter(writer)
                }
                // 줄마다 디스크에 밀어 넣는다. 모아 두면 돌아가는 중에 adb pull 로 받은 파일에 최근 몇
                // 초가 비어, 그 구간을 "결과가 없었다" 로 읽게 된다. 초당 네 번 쓰는 비용은 60fps 로
                // 그리는 것 옆에서 없는 셈이다.
                if (log!!.write(row.result, row.nowMs, row.level, row.shown)) writer.flush()
                if (log!!.stopped) {
                    // 상태를 사실에 맞춘다. 닫지 않으면 오버레이가 아무도 비우지 않는 큐에 계속 줄을 넣는다.
                    Log.w(TAG, "기록 상한에 닿아 멈춘다: ${log!!.rows}줄, ${file.name}")
                    closeSession(mine)
                    break
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            Log.e(TAG, "기록을 쓰다 실패했다", e)
        } finally {
            try {
                writer?.flush()
                writer?.close()
            } catch (e: IOException) {
                Log.e(TAG, "기록 파일을 닫다 실패했다", e)
            }
            val what = if (writer == null) "파일을 만들지 않았다" else "${log?.rows ?: 0}줄, ${file.name}"
            Log.i(TAG, "기록 끝: $what, 버린 줄 ${mine.dropped}")
        }
    }

    /**
     * 쓰는 쪽이 스스로 그만둘 때(상한·열기 실패) 회차를 닫는다.
     *
     * 이미 다른 회차가 켜져 있으면 건드리지 않는다. 남의 세션을 끄면 켜 둔 사용자의 기록이 조용히 멈춘다.
     */
    private fun closeSession(mine: Session) {
        mine.active.set(false)
        synchronized(this) {
            if (session === mine) {
                session = null
                currentFile = null
            }
        }
    }

    /** 폴더를 만들고 파일을 연다. 쓰는 스레드에서만 부른다. */
    private fun openWriter(file: File): Writer? {
        val dir = file.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "기록 폴더를 만들지 못했다: ${dir.absolutePath}")
            return null
        }
        return try {
            file.bufferedWriter()
        } catch (e: IOException) {
            Log.e(TAG, "기록 파일을 열지 못했다: ${file.absolutePath}", e)
            null
        }
    }
}
