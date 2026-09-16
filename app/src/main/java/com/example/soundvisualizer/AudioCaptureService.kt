package com.example.soundvisualizer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.soundvisualizer.ai.AiCaptureSampleRatePolicy
import com.example.soundvisualizer.ai.RealtimeAiPipeline
import com.example.soundvisualizer.feedback.HapticNotifier
import com.example.soundvisualizer.language.AppLanguage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaProjection 기반 내부 오디오 캡처 포그라운드 서비스.
 *
 * 캡처 루프는 코루틴 대신 전용 스레드(URGENT_AUDIO 우선순위)에서 돌며,
 * AudioRecord → direct ByteBuffer → JNI 로 복사 없이 넘긴다.
 *
 * 사용자가 끈 게 아닌데 멈추면 [StopAlert] 로 알린다. 화면이 꺼지면 [ScreenOffPause] 에 따라 쉰다.
 */
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    /** 지금 도는 캡처 스레드. 메인 스레드에서만 바꾸고 읽는다. */
    private var captureThread: Thread? = null

    @Volatile
    private var isRecording = false

    /** 캡처 스레드가 프레임마다 읽고, 초기화 스레드가 늦게 채운다. */
    @Volatile
    private var aiPipeline: RealtimeAiPipeline? = null

    /** aiPipeline 부착과 서비스 종료·화면 꺼짐 사이의 경합을 막는다. */
    private val aiLock = Any()

    /** onDestroy 가 지났는지. 늦게 끝난 초기화가 스스로 정리하도록 알린다. aiLock 으로 보호. */
    private var aiDestroyed = false

    /** 화면이 꺼져 AI 와 진동을 쉬는 중인지. 늦게 끝난 초기화가 쉬는 중에 추론을 시작하지 않도록 알린다. aiLock 으로 보호. */
    private var aiPaused = false

    /** 분류 결과에 맞춰 진동을 준다. AI 파이프라인이 붙은 뒤에만 생긴다. aiLock 으로 보호. */
    private var hapticNotifier: HapticNotifier? = null

    /** 실제로 사용 중인 캡처 레이트. onCreate 에서 기기에 맞춰 정해진다. */
    private var sampleRate = 48000

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 멈추는 중인지와 처음 남긴 이유. 뒤따라 오는 실패(프로젝션이 끊겨 read 오류 등)보다 먼저 난 원인이 정확하고,
     * 내려간 뒤 늦게 도착한 콜백은 다음 실행을 건드리면 안 된다. 메인 스레드 전용.
     */
    private val stopLatch = StopLatch()

    /** 화면이 꺼져 쉬는 중인지. 메인 스레드 전용. */
    private val screenPause = ScreenOffPause()

    /** 화면이 켜진 뒤 미뤄 둔 AI 재시작. 취소할 수 있게 들고 있는다. 메인 스레드 전용. ([scheduleAiResume]) */
    private var aiResumeRunnable: Runnable? = null

    private var screenReceiverRegistered = false

    private val projectionCallback = object : MediaProjection.Callback() {
        // 다른 앱이 화면 녹화·공유·전송을 시작했거나(안드로이드는 한 번에 한 앱만 허용한다),
        // 사용자가 시스템 UI 에서 캡처를 끈 경우. 콜백만으로는 둘을 구분할 수 없다.
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user")
            stopEverything(StopReason.ProjectionStopped)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"

        /** 실행 중 알림의 중지 버튼. 사용자가 끈 것으로 본다. */
        const val ACTION_STOP = "com.example.soundvisualizer.action.STOP"
        private const val CHANNEL_ID = "AudioCaptureChannel"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        /** 한 번에 읽는 float 개수 (스테레오 512 프레임 ≈ 11.6ms @44.1kHz). */
        private const val READ_FLOATS = 1024
        private const val BYTES_PER_FLOAT = 4

        /** 캡처 스레드가 끝나기를 기다리는 최대 시간. stop() 으로 read() 가 풀리므로 보통 바로 끝난다. */
        private const val CAPTURE_JOIN_TIMEOUT_MS = 2000L

        /** 화면이 켜진 뒤 AI 를 다시 시작하기까지 미루는 시간. 추론 한 번(수십 ms)보다 넉넉히 길게 잡는다. ([scheduleAiResume]) */
        private const val AI_RESUME_DELAY_MS = 300L

        /** 프로세스 내에서 서비스가 살아있는지 (액티비티 UI 상태 복원용). */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 앱 버튼이나 타일로 끄기를 눌렀는지. [VisualizerController.stop] 이 stopService 앞에 세우고,
         * 다음 실행의 onCreate 가 되돌린다.
         *
         * stopService 로 내리면 [stopEverything] 을 거치지 않아 [stopLatch] 가 비어 있고, onDestroy 까지는
         * 시간이 걸린다. 그사이 늦게 끝난 모델 로딩이 실행 중 알림을 다시 올리면 포그라운드 알림이 치워진 뒤
         * 같은 번호로 올라가 지워지지 않는 알림이 남는다.
         * UI 상태([SettingsManager.isServiceRunning])로는 거를 수 없다. 액티비티가 onResume 에서
         * [isRunning] 으로 다시 세우는데, 내려가는 중에도 onDestroy 전까지는 true 이기 때문이다.
         */
        @Volatile
        var stopRequested: Boolean = false
            private set

        /** 끄기를 눌렀다고 서비스에 알린다. ([stopRequested]) */
        fun markStopRequested() {
            stopRequested = true
        }

        /**
         * 떠 있는 캡처 서비스. [stopForFailure] 가 서비스를 내리기 전에 알리도록 부른다.
         * onCreate 에서 넣고 onDestroy 에서 비우므로 서비스가 내려간 뒤까지 붙잡지 않는다. 메인 스레드 전용.
         */
        @SuppressLint("StaticFieldLeak")
        private var instance: AudioCaptureService? = null

        // stopService 는 Intent 의 대상 컴포넌트로 서비스를 찾으므로 새로 만든 Intent 로 멈추는 게 맞다.
        // Lint(ImplicitSamInstance)는 새 인스턴스라 아무것도 멈추지 못한다고 보지만 오탐이다.
        /**
         * 서비스 밖(오버레이)이 실패해서 캡처를 내린다. 사용자가 끈 게 아니므로 캡처 서비스가 멈추며 알린다.
         *
         * stopService 로 내리면 이유를 넘길 수 없고, onDestroy 까지 기다리면 포그라운드에서 내려온 뒤라 진동이 막힌다.
         * 그래서 떠 있는 서비스에 직접 이유를 넘겨 알린 뒤 내리게 한다.
         * 이유를 인텐트에 실어 startService 로 보내지 않는 이유: 캡처 서비스가 이미 내려가는 중이면
         * startService 가 새 인스턴스를 만들고, 동의 없이 mediaProjection 포그라운드 서비스를 띄우려다 죽는다.
         */
        @MainThread
        @SuppressLint("ImplicitSamInstance")
        fun stopForFailure(context: Context, reason: StopReason) {
            val service = instance
            if (service != null) {
                service.stopEverything(reason)
            } else {
                // 떠 있는 서비스가 없다(onCreate 전이거나 이미 내려갔다). 켜진 적이 없으니 알릴 것도 없고, 내리기만 한다.
                context.stopService(Intent(context, AudioCaptureService::class.java))
            }
        }
    }

    // Android 12 이하에서 알림 문구를 앱 언어로 보여준다. 13 이상은 시스템이 적용한다.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(applicationContext)
        instance = this
        // 지난 실행에서 눌린 끄기는 이번 실행과 상관없다.
        stopRequested = false
        // 이번 실행의 모델 로딩 결과는 아직 모른다. 로딩 중에는 실패로 보이지 않게 둔다.
        SettingsManager.setAiAvailable(true)
        SettingsManager.setCapturePaused(false)
        createNotificationChannel()
        StopAlert.createChannel(this)
        // Android 14+: getMediaProjection() 이전에 mediaProjection 타입 FGS 가 먼저 떠 있어야 한다.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        // 다시 켜졌으니 지난번에 꺼졌다고 알린 알림과 홈 안내는 틀린 정보다.
        StopAlert.cancel(this)
        AudioEngine.reset()
        isRunning = true
        // 실행 상태는 서비스가 직접 알린다. 액티비티가 startForegroundService() 직후에
        // 표시하면 아직 onCreate 가 안 돌아 false 로 덮어써진다.
        SettingsManager.setServiceRunning(true)
        sampleRate = pickSampleRate()

        // 초기화 스레드가 쉬는 중인지 볼 수 있도록 AI 보다 먼저 등록한다.
        registerScreenReceiver()

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
        if (!AiCaptureSampleRatePolicy.isSupportedForAi(rate)) {
            Log.w(TAG, "AI disabled for unsupported capture sample rate: $rate")
            synchronized(aiLock) { onAiPipelineLoadedLocked(null) }
            return
        }
        val appContext = applicationContext
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val pipeline = try {
                RealtimeAiPipeline.create(appContext, rate, channels = 2)
            } catch (t: Throwable) {
                Log.e(TAG, "AI pipeline init failed: ${t.message}", t)
                null
            }
            synchronized(aiLock) { onAiPipelineLoadedLocked(pipeline) }
        }, "SV-AiInit").apply {
            isDaemon = true
            start()
        }
    }

    /** 모델 로딩이 끝났다. aiLock 안에서 부른다. [pipeline] 이 null 이면 로딩에 실패한 것이다. */
    private fun onAiPipelineLoadedLocked(pipeline: RealtimeAiPipeline?) {
        if (aiDestroyed) {
            // 로딩 중에 서비스가 내려갔다. 다음 실행의 상태와 섞이지 않게 아무것도 알리지 않는다.
            pipeline?.close()
            return
        }
        if (pipeline == null) {
            // 캡처와 시각화는 계속한다. 대신 모든 소리가 환경음으로 그려지고 진동 알림이 돌지 않는다는 걸
            // 홈·설정 화면과 실행 중 알림에 알린다. 알리지 않으면 위협음 진동이 켜진 줄 믿게 된다.
            SettingsManager.setAiAvailable(false)
            mainHandler.post { refreshOngoingNotification() }
            return
        }
        aiPipeline = pipeline
        // 결과를 읽는 쪽(AiClassification)은 startAiLocked 가 붙인다. 쉬는 중에 붙여 두면
        // 화면이 켜졌을 때 오버레이가 쉬기 직전 라벨을 읽는다.
        // 화면이 꺼져 쉬는 중이면 켜질 때 resumeAfterScreenOff 가 시작한다.
        if (!aiPaused) startAiLocked(pipeline)
    }

    /**
     * 추론과 진동 알림을 시작하고, 오버레이가 읽을 결과를 [AiClassification] 에 붙인다. aiLock 안에서 부른다.
     * [HapticNotifier] 는 한 번만 시작·정지하는 객체라 켤 때마다 새로 만든다.
     * start() 는 링버퍼·후처리·마지막 결과를 비우므로 쉬기 직전의 소리를 다시 분류하지 않는다.
     * 비우기 직전까지 돌던 추론 한 번이 뒤늦게 덮어쓰지 않도록 [scheduleAiResume] 가 그만큼 기다렸다 부른다.
     *
     * 붙이기는 비운 **뒤**에 한다. 파이프라인이 도는 동안에만 붙어 있어야, 쉬는 동안과 다시 켜기를 기다리는
     * 동안 오버레이가 환경음으로 떨어진다([pauseForScreenOff] 가 뗀다).
     */
    private fun startAiLocked(pipeline: RealtimeAiPipeline) {
        pipeline.start()
        AiClassification.attach { pipeline.lastClassification() }
        if (hapticNotifier != null) return
        // 첫 분류 결과가 나오기 전(null)에는 울리지 않는다.
        hapticNotifier = HapticNotifier(applicationContext) { pipeline.lastClassification()?.coarse }
            .also { it.start() }
    }

    /**
     * AI 가 검증된 48/44.1kHz를 우선한다. 기기 native rate가 그중 하나면 그대로 써서
     * 시스템 리샘플링을 피한다. 고 native rate에서는 검증된 rate를 먼저 요청하고,
     * 둘 다 불가능할 때만 native rate를 시각화 전용 fallback으로 유지한다.
     */
    private fun pickSampleRate(): Int {
        val reported = (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
        var accepted = false
        val selected = AiCaptureSampleRatePolicy.selectCaptureRate(reported) { rate ->
            (AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT) > 0).also {
                if (it) accepted = true
            }
        }
        val message = "capture sample rate: $selected (device reported $reported, " +
            "AI supported=${AiCaptureSampleRatePolicy.isSupportedForAi(selected)})"
        if (accepted) Log.i(TAG, message) else Log.w(TAG, "no candidate accepted; $message")
        return selected
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 내려가기 전에 다시 켜면 onCreate 없이 여기로 온다. 지난 끄기는 이번 실행과 상관없다.
        if (intent?.action != ACTION_STOP) stopRequested = false
        if (intent?.action == ACTION_STOP) {
            stopEverything(StopReason.UserRequested)
            return START_NOT_STICKY
        }
        if (intent != null && audioRecord == null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            if (resultCode != 0 && resultData != null) {
                if (!startAudioCapture(resultCode, resultData)) {
                    stopEverything(StopReason.StartFailed)
                }
            } else {
                stopEverything(StopReason.StartFailed)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAudioCapture(resultCode: Int, resultData: Intent): Boolean {
        // 액티비티가 권한을 받은 뒤에 시작하지만, 그 사이 시스템 설정에서 권한을 끌 수 있다.
        // 프로젝션을 만들기 전에 확인해야 동의만 받고 캡처는 못 하는 상태가 남지 않는다.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            return false
        }

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
            // 기기 미지원, 확인 직후 권한 회수 등
            Log.e(TAG, "AudioRecord build failed", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            record.release()
            return false
        }

        audioRecord = record
        // 동의 직후 화면이 꺼져 쉬는 중이면 녹음은 화면이 켜질 때 시작한다.
        if (screenPause.isPaused) return true
        return startCaptureLoop(record)
    }

    /** 녹음을 시작하고 캡처 스레드를 띄운다. 처음 켤 때와 화면이 다시 켜질 때 쓴다. 녹음이 시작되지 않으면 false. */
    private fun startCaptureLoop(record: AudioRecord): Boolean {
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord.startRecording failed", e)
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord failed to start recording")
            return false
        }
        isRecording = true
        captureThread = Thread({ captureLoop(record) }, "SV-AudioCapture").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * 캡처 루프를 멈추고 스레드가 끝나기를 기다린다. AudioRecord 는 해제하지 않는다.
     *
     * isRecording 을 먼저 내려야 stop() 으로 read() 가 풀리며 내는 오류를 진짜 캡처 오류로 보지 않는다.
     *
     * @return 스레드가 끝났거나 없었으면 true. 끝나지 않았으면 스레드 참조를 남기고 false.
     *         아직 read() 안에 있는데 release() 하면 네이티브에서 해제된 AudioRecord 를 건드려 SIGSEGV 가 나고,
     *         같은 AudioRecord 로 새 스레드를 띄우면 두 스레드가 한 버퍼를 읽는다.
     */
    private fun stopCaptureLoop(): Boolean {
        isRecording = false
        audioRecord?.let { record ->
            try { record.stop() } catch (e: IllegalStateException) { /* already stopped */ }
        }
        val thread = captureThread ?: return true
        try {
            thread.join(CAPTURE_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) return false
        captureThread = null
        return true
    }

    private fun captureLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val loopThread = Thread.currentThread()
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
                // 우리가 멈춘 게 아닌데 캡처가 끊겼다 (오디오 서버 재시작 등).
                // 서비스만 남으면 화면은 "실행 중"인데 시각화는 멈추고, AI 는 링버퍼에 남은
                // 마지막 소리를 계속 다시 분류한다. 전부 내리고 사용자에게 알린다.
                // 종료나 화면 꺼짐으로 멈추는 중에도 read 가 에러를 낼 수 있으니 isRecording 으로 구분한다.
                // 그사이 화면을 껐다 켜서 새 캡처 스레드가 떴다면, 이 스레드의 오래된 오류로 새 캡처를 내리지 않는다.
                if (isRecording) {
                    mainHandler.post {
                        if (isRecording && captureThread === loopThread) stopEverything(StopReason.CaptureError)
                    }
                }
                break
            }
        }
    }

    // ---------------- 화면 꺼짐 일시정지 ----------------

    /**
     * 화면 켜짐·꺼짐 방송을 받는다. 두 방송은 매니페스트에 적어서는 받을 수 없어 실행 중에만 등록한다.
     *
     * 둘 다 시스템만 보낼 수 있는 보호된 방송이라 다른 앱이 흉내 낼 수 없다. 그래도 RECEIVER_NOT_EXPORTED 를 준다.
     * Android 13(API 33) 부터 동적 수신기는 내보냄 여부를 밝히게 됐고(targetSdk 34 부터는 보호되지 않은 방송에서 필수),
     * 이 수신기는 앱 밖에서 보낼 이유가 없다. ContextCompat 은 13 이상에서는 플래그를 그대로 넘기고,
     * 그 아래에서는 앱 서명 권한을 요구하는 방식으로 같은 효과를 낸다. 시스템이 보낸 방송은 그 검사를 통과한다.
     */
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        // 등록 전에 이미 꺼졌다면 꺼짐 방송은 다시 오지 않는다. (동의 직후 바로 전원 버튼을 누른 경우)
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) onScreenOff()
    }

    private fun onScreenOff() {
        // 이미 내려가는 중이면 건드리지 않는다.
        if (stopLatch.isStopping) return
        if (screenPause.onScreenOff(SettingsManager.pauseWhenScreenOff.value)) pauseForScreenOff()
    }

    private fun onScreenOn() {
        if (stopLatch.isStopping) return
        if (screenPause.onScreenOn()) resumeAfterScreenOff()
    }

    /**
     * 화면이 꺼져 쉰다. 결과 읽기 → 진동 → AI → 캡처 순으로 멈추고, 남은 소리 크기를 지운다.
     *
     * MediaProjection 과 AudioRecord 는 놓지 않는다. 놓으면 화면을 켤 때마다 화면 녹화 동의를 다시 받아야 한다.
     * 녹음만 멈춰도 오디오 서버가 앱 몫으로 쥐고 있던 wake lock 을 놓아 CPU 가 잠들 수 있다.
     * 사용자가 끈 것도 실패도 아니므로 멈춤 알림을 띄우지 않는다.
     */
    private fun pauseForScreenOff() {
        Log.i(TAG, "screen off: pausing capture, AI and haptics")
        // 화면을 켰다가 바로 껐으면 미뤄 둔 AI 재시작이 쉬는 중에 깨어난다.
        cancelAiResume()
        // 오버레이가 쉬기로 내려오면 폴링을 멈추게 먼저 알린다.
        SettingsManager.setCapturePaused(true)
        val (pipeline, haptics) = synchronized(aiLock) {
            aiPaused = true
            (aiPipeline to hapticNotifier).also { hapticNotifier = null }
        }
        // 읽는 쪽도 같이 뗀다. 붙여 둔 채로 쉬면 stop() 이 마지막 결과를 지우지 않으므로, 화면을 켠 뒤
        // start() 가 비울 때까지(AI 는 [AI_RESUME_DELAY_MS] 만큼 늦게 켠다) 오버레이가 쉬기 직전 라벨을 읽는다.
        // 위협음 색은 즉시 칠해지므로 첫 소리가 빨갛게 번쩍이고, 숨긴 종류였다면 반대로 보여야 할 소리가 가려진다.
        // aiLock 을 놓은 뒤에 떼야, 잠금 안에서 붙이는 startAiLocked 와 순서가 엇갈리지 않는다.
        AiClassification.detach()
        // 진동부터 멈춘다. 캡처를 멈춘 뒤 남은 결과로 주머니 속에서 한 번 더 울리지 않도록.
        haptics?.stop()
        // 파이프라인은 닫지 않는다. 다시 켤 때 start() 가 링버퍼·후처리·마지막 결과를 비운다.
        // 비우지 않으면 쉬기 직전 1초 남짓한 소리를 켜자마자 다시 분류한다.
        pipeline?.stop()
        if (!stopCaptureLoop()) {
            // 캡처 스레드가 끝나지 않으면 같은 AudioRecord 로 다시 켤 수 없다. 이어갈 수 없으니 알리고 내린다.
            Log.w(TAG, "capture thread did not stop for screen-off pause")
            stopEverything(StopReason.CaptureError)
            return
        }
        // 네이티브에 마지막 소리 크기가 남으면 진동 판단과 오버레이가 그 소리가 계속 나는 것으로 본다.
        AudioEngine.reset()
    }

    /** 화면이 켜져 다시 켠다. 캡처를 먼저 켜고, AI 와 진동은 [scheduleAiResume] 가 조금 뒤에 켠다. */
    private fun resumeAfterScreenOff() {
        Log.i(TAG, "screen on: resuming capture, AI and haptics")
        // 동의 직후 꺼져서 아직 AudioRecord 가 없으면 onStartCommand 가 이어서 시작한다.
        val record = audioRecord
        if (record != null && !startCaptureLoop(record)) {
            // 쉬는 사이 오디오 서버가 재시작되는 등 녹음을 다시 시작할 수 없다. 조용히 멈춘 채 두지 않는다.
            stopEverything(StopReason.CaptureError)
            return
        }
        scheduleAiResume()
        // 그래픽은 기다리지 않고 바로 되살린다. 소리를 보는 것이 이 앱의 본 일이라 늦추면 안 된다.
        // 라벨은 [pauseForScreenOff] 가 떼어 둔 덕분에 다시 붙을 때까지 환경음으로 나온다.
        SettingsManager.setCapturePaused(false)
    }

    /**
     * AI 와 진동 알림만 [AI_RESUME_DELAY_MS] 뒤에 켠다. 캡처와 그래픽은 바로 켠다.
     *
     * 쉴 때 부르는 파이프라인의 stop() 은 이미 돌고 있던 추론 한 번을 기다리지 않는다. 화면을 껐다 바로 켜서
     * 그 추론이 start() 로 비운 뒤에 끝나면, 쉬기 직전의 결과가 되살아나 첫 소리에 위협음 진동이 잘못 울린다.
     * 추론 한 번보다 길게 기다렸다 시작해 그 사이를 비운다. 그때까지 aiPaused 를 세워 두어,
     * 늦게 끝난 모델 로딩도 추론을 먼저 시작하지 않는다.
     *
     * 기다리는 동안 오버레이는 [AiClassification] 이 떨어지는 환경음으로 그린다. 붙이는 것도 [startAiLocked]
     * 가 비운 뒤에 하므로, 이 사이에 쉬기 직전 라벨이 화면에 나오지 않는다.
     */
    private fun scheduleAiResume() {
        cancelAiResume()
        val runnable = Runnable {
            aiResumeRunnable = null
            // 기다리는 사이에 내려갔거나 화면이 다시 꺼졌으면 켜지 않는다.
            if (stopLatch.isStopping || screenPause.isPaused) return@Runnable
            synchronized(aiLock) {
                aiPaused = false
                // 모델이 아직 로딩 중이면 로딩이 끝날 때 시작한다.
                aiPipeline?.let(::startAiLocked)
            }
        }
        aiResumeRunnable = runnable
        mainHandler.postDelayed(runnable, AI_RESUME_DELAY_MS)
    }

    /** 미뤄 둔 AI 재시작을 없앤다. 여러 번 불러도 된다. */
    private fun cancelAiResume() {
        aiResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        aiResumeRunnable = null
    }

    // ---------------- 종료 ----------------

    // stopService 는 Intent 의 대상 컴포넌트로 서비스를 찾으므로 새로 만든 Intent 로 멈추는 게 맞다.
    // Lint(ImplicitSamInstance)는 새 인스턴스라 아무것도 멈추지 못한다고 보지만 오탐이다.
    /**
     * 캡처, 오버레이, 자기 자신을 모두 정리한다. 여러 번 호출해도 안전. 메인 스레드에서 부른다.
     *
     * 처음 멈출 때만 [reason] 으로 알린다. 뒤따라 오는 실패보다 먼저 난 원인이 정확하기 때문이다.
     * 알림은 서비스를 내리기 **전에** 한다. onDestroy 에서는 포그라운드 서비스와 오버레이가 이미 내려가
     * 안드로이드가 이 앱을 백그라운드로 보고 진동을 버릴 수 있다. 게임 화면 위에서 꺼지는, 알려야 할 바로 그 경우다.
     * 앱 버튼이나 타일로 끄면 이 함수를 거치지 않고 stopService 로 바로 내려가므로 알리지 않는다.
     */
    @SuppressLint("ImplicitSamInstance")
    private fun stopEverything(reason: StopReason) {
        if (stopLatch.isDestroyed) return
        // 미뤄 둔 AI 재시작이 남아 있으면 내리는 중에 추론과 진동이 다시 붙는다.
        cancelAiResume()
        if (stopLatch.claimAlert(reason)) {
            stopHapticsBeforeAlert()
            StopAlert.show(this, reason)
        }
        stopService(Intent(this, OverlayService::class.java))
        SettingsManager.setServiceRunning(false)
        stopSelf()
    }

    /**
     * 멈춤 진동을 울리기 전에 진동 알림을 떼어 멈춘다. 진동 알림의 cancel() 이 멈춤 진동까지 끊기 때문이다.
     * aiPaused 를 세워, onDestroy 전에 모델 로딩이 끝나도 진동 알림을 새로 붙이지 않게 한다(추론도 시작하지 않는다).
     */
    private fun stopHapticsBeforeAlert() {
        val haptics = synchronized(aiLock) {
            aiPaused = true
            hapticNotifier.also { hapticNotifier = null }
        }
        haptics?.stop()
    }

    override fun onDestroy() {
        stopLatch.onDestroy()
        isRunning = false
        instance = null
        cancelAiResume()
        SettingsManager.setServiceRunning(false)

        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }

        // 스레드가 실제로 끝났을 때만 해제한다 (stopCaptureLoop 설명 참고).
        if (stopCaptureLoop()) {
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
        val (pipeline, haptics) = synchronized(aiLock) {
            aiDestroyed = true
            (aiPipeline to hapticNotifier).also {
                aiPipeline = null
                hapticNotifier = null
            }
        }
        // 진동부터 멈춘다. 캡처를 끈 뒤에 남은 결과로 한 번 더 울리지 않도록.
        haptics?.stop()
        // 브릿지를 먼저 끊어야 오버레이가 닫힌 파이프라인을 읽지 않는다.
        AiClassification.detach()
        pipeline?.close()

        AudioEngine.reset()
        SettingsManager.setCapturePaused(false)
        // 다음 실행의 로딩 결과와 섞이지 않게 되돌린다. 꺼져 있을 때는 알릴 것이 없다.
        SettingsManager.setAiAvailable(true)
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

    /**
     * 실행 중 알림을 지금 상태(AI 사용 가능 여부)에 맞게 다시 올린다. 메인 스레드에서 부른다.
     * 내려가는 중이면 올리지 않는다. 포그라운드 알림이 치워진 뒤 같은 번호로 올리면 지워지지 않는 알림이 남는다.
     *
     * 앱 버튼이나 타일로 끄면 stopService 로 바로 내려가 onDestroy 전까지 래치가 비어 있다.
     * 그때는 같은 클릭에서 세워 둔 [stopRequested] 로 거른다. 실행 상태(isServiceRunning)는 액티비티도
     * 쓰는 UI 값이라 내려가는 중에 다시 true 로 덮일 수 있어 여기서 쓰지 않는다.
     */
    private fun refreshOngoingNotification() {
        if (stopLatch.isStopping || stopRequested) return
        StopAlert.post(this, CHANNEL_ID, NOTIFICATION_ID, createNotification())
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
        // AI 를 못 불러왔으면 알림창에서도 소리 종류 구분과 진동 알림이 꺼졌다는 걸 알 수 있게 한다.
        val text = getString(
            if (SettingsManager.aiAvailable.value) R.string.notification_text else R.string.notification_text_ai_unavailable
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
