package com.example.soundvisualizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.example.soundvisualizer.ai.RealtimeAiPipeline
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaProjection 기반 내부 오디오 캡처 포그라운드 서비스.
 *
 * 캡처 루프는 코루틴 대신 전용 스레드(URGENT_AUDIO 우선순위)에서 돌며,
 * AudioRecord → direct ByteBuffer → JNI 로 복사 없이 넘긴다.
 */
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var isRecording = false

    /** 캡처 스레드가 프레임마다 읽고, 초기화 스레드가 늦게 채운다. */
    @Volatile
    private var aiPipeline: RealtimeAiPipeline? = null

    /** aiPipeline 부착과 서비스 종료 사이의 경합을 막는다. */
    private val aiLock = Any()

    /** onDestroy 가 지났는지. 늦게 끝난 초기화가 스스로 정리하도록 알린다. */
    private var aiDestroyed = false

    /** 실제로 사용 중인 캡처 레이트. onCreate 에서 기기에 맞춰 정해진다. */
    private var sampleRate = 48000

    private val projectionCallback = object : MediaProjection.Callback() {
        // 사용자가 상태바/시스템 UI 에서 캡처를 중단한 경우
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user")
            stopEverything()
        }
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val ACTION_STOP = "com.example.soundvisualizer.action.STOP"
        private const val CHANNEL_ID = "AudioCaptureChannel"
        private const val NOTIFICATION_ID = 1
        /** 기기 출력 레이트를 못 읽었을 때의 순서. 요즘 기기는 대부분 48kHz 가 네이티브다. */
        private val SAMPLE_RATE_CANDIDATES = intArrayOf(48000, 44100)
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        /** 한 번에 읽는 float 개수 (스테레오 512 프레임 ≈ 11.6ms @44.1kHz). */
        private const val READ_FLOATS = 1024
        private const val BYTES_PER_FLOAT = 4

        /** 프로세스 내에서 서비스가 살아있는지 (액티비티 UI 상태 복원용). */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(applicationContext)
        createNotificationChannel()
        // Android 14+: getMediaProjection() 이전에 mediaProjection 타입 FGS 가 먼저 떠 있어야 한다.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        AudioEngine.reset()
        isRunning = true
        // 실행 상태는 서비스가 직접 알린다. 액티비티가 startForegroundService() 직후에
        // 표시하면 아직 onCreate 가 안 돌아 false 로 덮어써진다.
        SettingsManager.setServiceRunning(true)
        sampleRate = pickSampleRate()

        // AI 분류는 시각화 경로와 독립적으로 돈다. 초기화 실패해도 캡처는 계속한다.
        startAiPipelineAsync()
    }

    /**
     * 모델 로딩을 메인 스레드에서 하지 않는다.
     *
     * 첫 실행에는 15MB 짜리 외부 가중치를 filesDir 로 복사하고 ONNX 세션 두 개를
     * 그래프 최적화까지 걸어 만든다. onCreate 를 붙잡으면 그동안 화면이 멈추고
     * 실제 캡처 시작(onStartCommand)까지 밀린다.
     *
     * 로딩이 끝나기 전에는 [AiClassification] 이 환경음을 돌려주므로 시각화는 그대로 돈다.
     * 로딩 중에 서비스가 내려가면 늦게 만들어진 파이프라인이 스스로 닫힌다.
     */
    private fun startAiPipelineAsync() {
        val rate = sampleRate
        val appContext = applicationContext
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val pipeline = try {
                RealtimeAiPipeline.create(appContext, rate, channels = 2)
            } catch (t: Throwable) {
                Log.e(TAG, "AI pipeline init failed: ${t.message}", t)
                null
            }
            if (pipeline != null) {
                synchronized(aiLock) {
                    if (aiDestroyed) {
                        pipeline.close()
                    } else {
                        pipeline.start()
                        aiPipeline = pipeline
                        AiClassification.attach { pipeline.lastClassification() }
                    }
                }
            }
        }, "SV-AiInit").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 기기 출력 레이트를 우선 쓴다. 다른 값을 요청하면 캡처 경로에 리샘플러가 끼어
     * 지연이 늘고 일부 기기에서 초기화가 실패한다.
     */
    private fun pickSampleRate(): Int {
        val reported = (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
        val candidates = if (reported != null) {
            intArrayOf(reported) + SAMPLE_RATE_CANDIDATES.filter { it != reported }
        } else {
            SAMPLE_RATE_CANDIDATES
        }
        for (rate in candidates) {
            if (AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT) > 0) {
                Log.i(TAG, "capture sample rate: $rate (device reported $reported)")
                return rate
            }
        }
        Log.w(TAG, "no candidate sample rate accepted; falling back to 48000")
        return 48000
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (intent != null && audioRecord == null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            if (resultCode != 0 && resultData != null) {
                if (!startAudioCapture(resultCode, resultData)) {
                    stopEverything()
                }
            } else {
                stopEverything()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAudioCapture(resultCode: Int, resultData: Intent): Boolean {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        } ?: return false

        // 콜백은 프로젝션을 사용하기 전에 등록해야 한다 (Android 14 요구사항).
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(sampleRate)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBufferSize")
            return false
        }
        val bufferSize = maxOf(minBufferSize * 2, READ_FLOATS * BYTES_PER_FLOAT * 4)

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            // RECORD_AUDIO 미허용 / 기기 미지원 등
            Log.e(TAG, "AudioRecord build failed", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            record.release()
            return false
        }

        audioRecord = record
        isRecording = true
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord failed to start recording")
            return false
        }

        captureThread = Thread({ captureLoop(record) }, "SV-AudioCapture").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun captureLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // direct buffer: AudioRecord 가 직접 채우고 JNI 가 주소로 읽는다 (Kotlin 힙 복사 0회, GC 0회).
        val buffer = ByteBuffer.allocateDirect(READ_FLOATS * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
        // AI 경로는 FloatArray 를 받으므로 뷰와 스크래치를 한 번만 만들어 재사용한다.
        val floatView = buffer.asFloatBuffer()
        val aiScratch = FloatArray(READ_FLOATS)
        while (isRecording) {
            val bytes = record.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
            if (bytes > 0) {
                val floats = bytes / BYTES_PER_FLOAT
                AudioEngine.pushAudioBuffer(buffer, floats)
                // AI 는 캡처 스레드에서 추론하지 않는다. 링버퍼로 복사만 하고 즉시 반환된다.
                aiPipeline?.let { pipeline ->
                    floatView.position(0)
                    floatView.get(aiScratch, 0, floats)
                    pipeline.ingestInterleavedPcm(aiScratch, floats)
                }
            } else if (bytes < 0) {
                Log.w(TAG, "AudioRecord.read error $bytes, stopping capture loop")
                break
            }
        }
    }

    /** 캡처, 오버레이, 자기 자신을 모두 정리한다. 여러 번 호출해도 안전. */
    private fun stopEverything() {
        stopService(Intent(this, OverlayService::class.java))
        SettingsManager.setServiceRunning(false)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        SettingsManager.setServiceRunning(false)
        isRecording = false

        // read() 블로킹을 풀기 위해 먼저 stop, 그 다음 스레드 종료를 기다린 뒤 해제한다.
        audioRecord?.let { record ->
            try { record.stop() } catch (e: IllegalStateException) { /* already stopped */ }
        }
        // 스레드가 실제로 끝났는지 확인한다. 아직 read() 안에 있는데 release() 하면
        // 네이티브에서 해제된 AudioRecord 를 건드려 SIGSEGV 가 난다.
        var terminated = true
        captureThread?.let { t ->
            try { t.join(2000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            terminated = !t.isAlive
        }
        captureThread = null

        if (terminated) {
            audioRecord?.release()
        } else {
            // 크래시보다는 누수가 낫다. 프로세스가 살아있는 동안만 남는다.
            Log.w(TAG, "capture thread still alive; leaking AudioRecord to avoid use-after-free")
        }
        audioRecord = null

        mediaProjection?.let { p ->
            p.unregisterCallback(projectionCallback)
            p.stop()
        }
        mediaProjection = null

        // 캡처 스레드가 멈춘 뒤에 AI 파이프라인을 닫는다 (ingest 가 더 들어오지 않도록).
        // aiDestroyed 를 먼저 세워야 아직 로딩 중인 초기화가 붙지 않고 스스로 닫는다.
        val pipeline = synchronized(aiLock) {
            aiDestroyed = true
            aiPipeline.also { aiPipeline = null }
        }
        // 브릿지를 먼저 끊어야 오버레이가 닫힌 파이프라인을 읽지 않는다.
        AiClassification.detach()
        pipeline?.close()

        AudioEngine.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
