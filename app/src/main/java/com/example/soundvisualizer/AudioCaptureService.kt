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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.soundvisualizer.ai.AiCaptureSampleRatePolicy
import com.example.soundvisualizer.ai.RealtimeAiPipeline
import com.example.soundvisualizer.feedback.HapticNotifier
import com.example.soundvisualizer.language.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaProjection 기반 내부 오디오 캡처 포그라운드 서비스.
 *
 * 캡처 루프는 코루틴 대신 전용 스레드(URGENT_AUDIO 우선순위)에서 돌며,
 * AudioRecord → direct ByteBuffer → JNI 로 복사 없이 넘긴다.
 *
 * 사용자가 끈 게 아닌데 멈추면 [StopAlert] 로 알린다. 화면이 꺼지면 [ScreenOffPause] 에 따라 쉰다.
 * 재생 중인 소리를 아무것도 받지 못하면 [BlockedCaptureNotice] 에 따라 알린다.
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

    /** 재생 여부·볼륨·통화 모드·출력 기기를 묻는다. onCreate 에서 한 번 받아 둔다. */
    private var audioManager: AudioManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 멈추는 중인지와 처음 남긴 이유. 뒤따라 오는 실패(프로젝션이 끊겨 read 오류 등)보다 먼저 난 원인이 정확하고,
     * 내려간 뒤 늦게 도착한 콜백은 다음 실행을 건드리면 안 된다. 메인 스레드 전용.
     */
    private val stopLatch = StopLatch()

    /** 화면이 꺼져 쉬는 중인지. 메인 스레드 전용. */
    private val screenPause = ScreenOffPause()

    /** 재생 중인 앱의 소리를 아무것도 받지 못하는지. 메인 스레드 전용. ([blockedCheck]) */
    private val blockedNotice = BlockedCaptureNotice()

    /** [AudioEngine.takePeakSinceLastCheck] 가 채우는 `[피크, 버퍼 수]`. 틱마다 새로 만들지 않는다. 메인 스레드 전용. */
    private val checkSample = FloatArray(2)

    /** 화면이 켜진 뒤 미뤄 둔 AI 재시작. 취소할 수 있게 들고 있는다. 메인 스레드 전용. ([scheduleAiResume]) */
    private var aiResumeRunnable: Runnable? = null

    private var screenReceiverRegistered = false

    /** 알림의 모드 표시를 맞추는 데만 쓴다. 메인 스레드에서 돌고 onDestroy 에서 취소한다. */
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

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

        /**
         * 모드 칩의 PendingIntent 요청 번호가 시작하는 자리. 모드마다 [NotificationCommand.EXTRA_MODE_ORDINAL] 만 다른 인텐트를 쓰는데,
         * PendingIntent 는 요청 번호가 같으면 같은 것으로 보고 하나만 남긴다. 그러면 칩 넷이 모두 같은 모드를 켠다.
         * 아래 0·1 번(중지·앱 열기)과 겹치지 않게 띄워 둔다.
         */
        private const val REQUEST_MODE_BASE = 10

        /**
         * 모드 칩의 뷰 번호. [VisualMode] 의 차례와 짝을 이룬다. 모드를 더하면 여기와
         * [R.layout.notification_modes] 에도 칩을 더해야 한다. 어긋나면 VisualModeOrdinalTest 가 알려 준다.
         */
        val MODE_CHIP_IDS = intArrayOf(
            R.id.notification_mode_0,
            R.id.notification_mode_1,
            R.id.notification_mode_2,
            R.id.notification_mode_3
        )
        private const val CHANNEL_ID = "AudioCaptureChannel"

        /** 실행 중 알림의 번호. 서비스보다 오래 남은 알림을 지우는지 계측 테스트가 같은 번호로 확인한다. */
        internal const val NOTIFICATION_ID = 1
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        /** 한 번에 읽는 float 개수 (스테레오 512 프레임 ≈ 11.6ms @44.1kHz). */
        private const val READ_FLOATS = 1024
        private const val BYTES_PER_FLOAT = 4

        /** 캡처 스레드가 끝나기를 기다리는 최대 시간. stop() 으로 read() 가 풀리므로 보통 바로 끝난다. */
        private const val CAPTURE_JOIN_TIMEOUT_MS = 2000L

        /** 화면이 켜진 뒤 AI 를 다시 시작하기까지 미루는 시간. 추론 한 번(수십 ms)보다 넉넉히 길게 잡는다. ([scheduleAiResume]) */
        private const val AI_RESUME_DELAY_MS = 300L

        /**
         * 받지 못하고 있는지 다시 살펴보는 간격. ([blockedCheck])
         *
         * 진동 판단(100ms)만큼 자주 볼 이유가 없다. 판단이 뒤집히는 데 15초가 걸리고, 이 틱은 진동과 달리
         * 시스템에 재생 중인 소리를 묻는다. 0.5초면 소리가 다시 들어왔을 때 안내를 내리는 것도 사람이
         * 눈치채지 못할 만큼 빠르다. 그사이 놓치는 소리는 없다. 네이티브가 구간 전체의 피크를 모아 둔다
         * ([AudioEngine.takePeakSinceLastCheck]).
         */
        private const val BLOCKED_CHECK_MS = 500L

        /**
         * 우리가 받기로 한 소리의 용도. 캡처 설정과 재생 중 판단([isCapturedMediaPlaying])이 같은 목록을 본다.
         *
         * 둘이 어긋나면 우리가 애초에 받지도 않는 소리(어시스턴트 응답, 내비게이션 안내)를 "재생 중" 으로
         * 세고, 화면에 보이는 멀쩡한 앱을 탓하게 된다. 그래서 목록을 한 곳에만 둔다.
         */
        private val CAPTURED_USAGES: Set<Int> = setOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN
        )

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

        /**
         * 실행 중 알림의 버튼이 보낸 명령을 떠 있는 서비스에 전한다. ([NotificationActionReceiver])
         *
         * 떠 있는 서비스가 없는데 버튼이 눌렸다면 그 알림은 서비스보다 오래 남은 것이다. 서비스를 새로 띄우지 않고
         * 알림만 지운다. 어떤 버튼이었든 마찬가지다.
         */
        @MainThread
        fun handleNotificationCommand(context: Context, command: NotificationCommand) {
            val service = instance
            if (service == null) {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                return
            }
            when (command) {
                NotificationCommand.Stop -> service.stopEverything(StopReason.UserRequested)
                // 캡처도 AI 도 건드리지 않는다. 오버레이는 프레임마다 모드를 읽으므로 바로 바뀐다.
                is NotificationCommand.SetMode -> SettingsManager.setVisualMode(command.mode)
                NotificationCommand.Ignore -> Unit
            }
        }
    }

    // Android 12 이하에서 알림 문구를 앱 언어로 보여준다. 13 이상은 시스템이 적용한다.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /**
     * 사용자에게 보이는 문구를 꺼내는 컨텍스트(#187).
     *
     * Android 12 이하는 [attachBaseContext] 에서 한 번만 언어가 입혀지므로, 시각화를 켜 둔 채 언어를 바꾸면
     * 서비스는 계속 떠난 언어로 문구를 꺼낸다. 실행 중 알림과 꺼짐 알림이 그 언어로 남는다.
     * 그래서 언어가 바뀌면([AppLanguage.changes]) 다시 만든다. 13 이상은 신호가 오지 않아 이 값이 그대로다.
     */
    @Volatile
    private var uiContext: Context = this

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(applicationContext)
        instance = this
        // 지난 실행에서 눌린 끄기는 이번 실행과 상관없다.
        stopRequested = false
        // 이번 실행의 모델 로딩 결과는 아직 모른다. 로딩 중에는 실패로 보이지 않게 둔다.
        SettingsManager.setAiAvailable(true)
        SettingsManager.setCapturePaused(false)
        SettingsManager.setCaptureBlocked(false)
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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        observeVisualMode()
        observeAppLanguage()

        // 초기화 스레드가 쉬는 중인지 볼 수 있도록 AI 보다 먼저 등록한다.
        registerScreenReceiver()

        // AI 는 캡처가 실제로 열린 뒤에 시작한다. 어느 레이트로 열릴지는 열어 봐야 알고
        // ([openAudioRecord]), AI 는 그 레이트에 맞춰 만들어야 한다.
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
                RealtimeAiPipeline.create(
                    appContext,
                    rate,
                    channels = 2,
                    diagnosticConfigProvider = SettingsManager::activeAiDiagnosticConfig
                )
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
     * 후보 레이트를 차례로 **실제로 열어 보고** 처음 열린 것을 쓴다. [sampleRate] 도 여기서 정해진다.
     *
     * AI 가 검증한 48/44.1kHz 를 우선한다. 기기가 보고한 레이트가 그중 하나면 그대로 써서 시스템
     * 리샘플링을 피하고, 고 레이트 기기에서는 검증된 레이트를 먼저 요청한다. 둘 다 열리지 않을 때만
     * 기기 레이트를 시각화 전용으로 쓴다 ([AiCaptureSampleRatePolicy]).
     *
     * **한 번 실패했다고 포기하지 않는다.** `getMinBufferSize` 는 "이 조합이 말이 된다" 만 알려줄 뿐,
     * 그 레이트로 `AudioRecord` 가 실제로 열린다는 보장이 아니다. 예전에는 기기가 보고한 레이트를 첫
     * 후보로 써서 실패 가능성이 낮았지만, 지금은 AI 가 검증한 레이트를 먼저 요청한다(#69). 여기서
     * 물러나지 않으면, 예전이라면 "AI 만 사용 불가" 로 끝났을 기기에서 시각화까지 켜지지 않는다.
     *
     * 마이크 권한은 부르는 쪽이 확인한다. 예전에는 확인과 생성이 한 함수에 있어 Lint 가 짝을 볼 수
     * 있었는데, 여기로 떼어 내면서 보이지 않게 됐다. 필요한 권한을 표시해 Lint 가 호출부의 확인을
     * 다시 짝지을 수 있게 한다.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openAudioRecord(config: AudioPlaybackCaptureConfiguration): AudioRecord? {
        val reported = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
        val candidates = AiCaptureSampleRatePolicy.orderedCaptureCandidates(reported)

        for (rate in candidates) {
            val minBufferSize = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize <= 0) {
                Log.w(TAG, "sample rate $rate: getMinBufferSize returned $minBufferSize")
                continue
            }
            val bufferSize = maxOf(minBufferSize * 2, READ_FLOATS * BYTES_PER_FLOAT * 4)
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(rate)
                .setChannelMask(CHANNEL_CONFIG)
                .build()
            val record = try {
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } catch (e: Exception) {
                // 기기 미지원, 확인 직후 권한 회수 등
                Log.w(TAG, "sample rate $rate: AudioRecord build failed", e)
                continue
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "sample rate $rate: AudioRecord not initialized")
                record.release()
                continue
            }

            sampleRate = rate
            val fallback = if (rate == candidates.first()) "" else ", fell back from ${candidates.first()}"
            Log.i(
                TAG,
                "capture format requested[sr=$rate chMask=$CHANNEL_CONFIG encoding=$AUDIO_FORMAT] " +
                    "actual[sr=${record.sampleRate} ch=${record.channelCount} encoding=${record.audioFormat}] " +
                    "(device reported $reported, " +
                    "AI supported=${AiCaptureSampleRatePolicy.isSupportedForAi(rate)}$fallback)"
            )
            return record
        }

        Log.e(TAG, "no sample rate could open AudioRecord (tried ${candidates.joinToString()})")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 알림 버튼은 이제 NotificationActionReceiver 가 받는다. 시작 요청에는 동작 이름이 없으므로, 이름이 있으면
        // 서비스로 보내던 옛 버전의 알림이 남아 있다가 눌린 것이다. 캡처를 열지 않고, 그 때문에 새로 떴으면 조용히 내린다.
        // 시작 요청으로 읽으면 동의 결과가 없어 "켜지 못했다" 고 잘못 알린다.
        if (intent?.action != null) {
            if (audioRecord == null) stopEverything(StopReason.UserRequested)
            return START_NOT_STICKY
        }
        // 내려가기 전에 다시 켜면 onCreate 없이 여기로 온다. 지난 끄기는 이번 실행과 상관없다.
        stopRequested = false
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

        // 받을 소리의 용도는 [CAPTURED_USAGES] 한 곳에만 둔다. 확인 틱이 같은 목록으로 "재생 중" 을 센다.
        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
        CAPTURED_USAGES.forEach { configBuilder.addMatchingUsage(it) }
        val config = configBuilder.build()

        val record = openAudioRecord(config) ?: return false

        audioRecord = record
        // AI 분류는 시각화 경로와 독립적으로 돈다. 초기화에 실패해도 캡처는 계속한다.
        // 열린 레이트가 정해진 뒤에 시작해야 AI 가 같은 레이트로 만들어진다.
        startAiPipelineAsync()
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
        startBlockedCheck()
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
        // 받지 못하는 게 당연한 상태가 되므로 안내할 것도 없다.
        stopBlockedCheck()
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
                // 쉬는 오버레이를 소리가 난 이 버퍼에서 바로 깨운다(#170). 원자 변수를 읽고, 오버레이가 쉬는 중이면
                // 소리 크기를 한 번 더 읽는다. 할당은 없다.
                OverlayWake.onBuffer()
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

    // ---------------- 보호된 소리 안내 ----------------

    /**
     * 캡처가 도는 동안 [BLOCKED_CHECK_MS] 마다 "폰은 소리를 내는데 우리에게는 아무것도 들어오지 않는"
     * 상태인지 본다.
     *
     * 캡처 스레드는 버퍼마다 원자 연산 두 번만 더 한다([AudioEngine.takePeakSinceLastCheck]). URGENT_AUDIO
     * 스레드에서 할당이나 잠금이 생기지 않게, 모아 둔 값을 메인 스레드가 읽어 가는 쪽으로 만들었다.
     * 새 스레드도 필요 없다.
     */
    private val blockedCheck = object : Runnable {
        override fun run() {
            // 내려가는 중이거나 이미 멈춘 캡처라면 더 볼 것이 없다. 다시 예약하지도 않는다.
            if (!isRecording || stopLatch.isStopping) return
            updateBlockedNotice()
            mainHandler.postDelayed(this, BLOCKED_CHECK_MS)
        }
    }

    /** 캡처가 돌기 시작했다. 두 번 불러도 틱은 하나만 돈다. */
    private fun startBlockedCheck() {
        blockedNotice.reset()
        // 지난번 캡처가 남긴 피크·버퍼 수를 버린다. 남겨 두면 첫 틱이 방금 받은 소리로 착각한다.
        AudioEngine.takePeakSinceLastCheck(checkSample)
        mainHandler.removeCallbacks(blockedCheck)
        mainHandler.postDelayed(blockedCheck, BLOCKED_CHECK_MS)
    }

    /** 확인을 멈추고 안내를 내린다. 여러 번 불러도 된다. */
    private fun stopBlockedCheck() {
        mainHandler.removeCallbacks(blockedCheck)
        if (blockedNotice.reset()) {
            SettingsManager.setCaptureBlocked(false)
            refreshOngoingNotification()
        }
    }

    /**
     * 한 틱. 판단이 바뀐 때만 홈 화면과 실행 중 알림을 건드린다.
     *
     * 싼 질문부터 한다. 이 틱은 오버레이가 프레임을 그리는 바로 그 메인 스레드에서 돌기 때문이다.
     * 네이티브에서 읽는 것은 공짜에 가깝고, 소리가 들어왔거나 버퍼가 끊겼으면 그것만으로 판단이 끝나
     * 시스템에는 아예 묻지 않는다. 아무것도 재생하지 않는 흔한 상태에서는 한 번만 묻는다.
     */
    private fun updateBlockedNotice() {
        AudioEngine.takePeakSinceLastCheck(checkSample)
        val peak = checkSample[0]
        val buffers = checkSample[1].toInt()
        val manager = audioManager
        var canJudge = false
        var mediaPlaying = false
        // 소리가 들어왔거나 버퍼가 한 개도 오지 않았으면 그것만으로 판단이 끝난다(안내를 내리는 쪽).
        // 화면이 꺼져 쉬는 중이면 우리가 받지 않기로 한 것이다([ScreenOffPause]). 어느 쪽도 아닐 때만 묻는다.
        if (manager != null && buffers > 0 && !blockedNotice.hasSound(peak) && !screenPause.isPaused) {
            mediaPlaying = isCapturedMediaPlaying(manager)
            // 재생 중이 아니면 "낼 소리가 없다" 는 뜻이라 그대로 판단에 쓴다(떠 있던 안내를 내리는 쪽).
            // 볼륨·통화 같은 나머지 조건은 재생 중일 때만 물어보면 된다.
            canJudge = !mediaPlaying || canJudgeBlocked(manager)
        }
        val changed = blockedNotice.onTick(SystemClock.elapsedRealtime(), canJudge, mediaPlaying, peak, buffers)
        if (!changed) return
        Log.i(TAG, "blocked capture notice: ${blockedNotice.isBlocked}")
        SettingsManager.setCaptureBlocked(blockedNotice.isBlocked)
        refreshOngoingNotification()
    }

    /**
     * 우리가 받기로 한 소리(미디어·게임)를 지금 누가 내고 있는지. ([CAPTURED_USAGES])
     *
     * `isMusicActive` 는 STREAM_MUSIC 으로 나가는 모든 소리에 true 라서, 우리가 애초에 받지 않는
     * 어시스턴트 응답이나 내비게이션 안내가 길게 이어지기만 해도 "재생 중인데 못 받는다" 가 된다.
     * 그러면 그때 화면에 떠 있던 멀쩡한 앱이 누명을 쓴다. 재생 중인 소리를 용도별로 보면 그 일이 없다.
     * 재생이 끝난 뒤에도 잠시 true 로 남는 `isMusicActive` 의 여유분도 함께 사라진다.
     *
     * 시스템은 우리 같은 일반 앱에 재생 정보를 가려서 준다. 용도까지 가려지는 기기가 있다면 그 소리는
     * 용도 없음(`USAGE_UNKNOWN`)으로 보이는데, 그것도 우리가 받는 용도라 지금까지와 같게 동작한다.
     */
    private fun isCapturedMediaPlaying(manager: AudioManager): Boolean =
        manager.activePlaybackConfigurations.any { it.audioAttributes.usage in CAPTURED_USAGES }

    /**
     * 지금 "소리는 나는데 받지 못한다" 고 말해도 되는 상태인지. 하나라도 걸리면 판단하지 않는다.
     *
     * 무음에는 앱이 막은 것 말고도 이유가 많고, 틀린 안내는 멀쩡한 앱을 탓하게 만든다.
     * 여기서 거르는 것들은 모두 "무음이 당연하거나, 무음의 원인을 우리가 알 수 없는" 경우다.
     *
     * 출력 기기는 보지 않는다. 캡처는 소리가 기기로 나가기 전의 믹스를 받으므로 이어폰을 꽂든 워치가
     * 붙어 있든 우리가 받는 소리는 같다. 연결만 되어 있어도 거르면, 보청기나 워치를 늘 차고 있는
     * 사용자에게는 안내가 영영 뜨지 않는다. 정작 이 안내가 가장 필요한 사람들이다.
     */
    private fun canJudgeBlocked(manager: AudioManager): Boolean {
        // 미디어 볼륨 0. 이때는 막힌 앱이든 아니든 믹스가 통째로 0 이라 아무것도 가려낼 수 없다.
        // 사용자가 스스로 소리를 껐으니 왜 조용한지도 알고 있다.
        if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return false
        // 통화·음성 채팅 중. 미디어가 눌리거나 통화 쪽으로 빠지고, 통화 소리는 애초에 캡처 대상이 아니다.
        if (manager.mode != AudioManager.MODE_NORMAL) return false
        return true
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
            StopAlert.show(uiContext, reason)
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
        // 내려가는 중에 모드가 바뀌어도 알림을 다시 올리지 않는다.
        serviceScope.cancel()
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
        audioManager = null
        SettingsManager.setCapturePaused(false)
        SettingsManager.setCaptureBlocked(false)
        // 다음 실행의 로딩 결과와 섞이지 않게 되돌린다. 꺼져 있을 때는 알릴 것이 없다.
        SettingsManager.setAiAvailable(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            uiContext.getString(R.string.notification_channel_name),
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

    /**
     * 모드가 바뀌면 알림의 모드 표시를 맞춘다. 앱 설정에서 바꾸든 알림 버튼으로 바꾸든 같은 곳을 지난다.
     *
     * 지금 값은 이미 알림에 들어 있으므로 첫 값은 흘린다.
     */
    /** 언어가 바뀌면 문구용 컨텍스트를 다시 만들고 실행 중 알림을 새 언어로 올린다(#187). 지금 언어는 이미 반영돼 있으니 첫 값은 흘린다. */
    private fun observeAppLanguage() {
        serviceScope.launch {
            AppLanguage.changes.drop(1).collect {
                uiContext = AppLanguage.wrap(applicationContext)
                refreshOngoingNotification()
            }
        }
    }

    private fun observeVisualMode() {
        serviceScope.launch {
            SettingsManager.visualMode.drop(1).collect { refreshOngoingNotification() }
        }
    }

    /**
     * 알림 버튼이 보낼 인텐트. 서비스가 아니라 [NotificationActionReceiver] 로 보낸다.
     *
     * 누른 사람이 기다리고 있으므로 포그라운드 방송으로 보낸다. 백그라운드 방송은 시스템이 바쁘면 몇 초씩
     * 밀릴 수 있어, 칩을 눌러도 모드가 늦게 바뀐다.
     */
    private fun notificationActionIntent(action: String): Intent =
        Intent(this, NotificationActionReceiver::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    private fun createNotification(): Notification {
        val stopIntent = PendingIntent.getBroadcast(
            this,
            0,
            notificationActionIntent(NotificationCommand.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 알림창만 보고도 왜 화면에 아무것도 없는지 알 수 있게 한다.
        // 받는 소리가 아예 없으면 분류할 소리도 없으므로, 둘 다 해당할 때는 받지 못한다는 쪽만 말한다.
        // AI 안내는 그때 숨겨도 소리가 다시 들어오면 나온다.
        val text = uiContext.getString(
            when {
                SettingsManager.isCaptureBlocked.value -> R.string.notification_text_capture_blocked
                !SettingsManager.aiAvailable.value -> R.string.notification_text_ai_unavailable
                else -> R.string.notification_text
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(uiContext.getString(R.string.notification_title))
            .setContentText(text)
            // 접은 알림은 시스템이 그리므로 위 setContentText 가 그대로 보인다.
            // 펼치면 아래 본문이 그 자리를 대신하면서 모드 칩이 함께 나온다.
            .setCustomBigContentView(buildModeChooser(text))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            // 접힌 상태에서는 칩이 보이지 않으므로, 펼치지 않고도 지금 모드를 알 수 있게 제목 옆에 이름을 둔다.
            .setSubText(uiContext.getString(SettingsManager.visualMode.value.labelRes))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, uiContext.getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 펼친 알림의 본문. 상태 한 줄과 모드를 고르는 칩 네 개를 담는다([R.layout.notification_modes]).
     *
     * 지금 켜져 있는 모드는 색을 채워 표시하고, 화면을 읽어 주는 기능에는 "선택됨"을 덧붙인다.
     * 색만으로 알리면 색을 구별하기 어려운 사람에게는 어느 것이 켜져 있는지 전해지지 않는다.
     *
     * Android 12 이상에서는 칩이 라디오 버튼이라([R.layout.notification_modes] 의 v31 판)
     * 고른 칩이 바뀌는 것을 알림을 그리는 쪽이 직접 처리한다. 그래서 누른 즉시 표시가 옮겨간다.
     * 그 아래 버전은 우리가 배경과 글자색을 넣어 주고, 알림을 다시 올릴 때 표시가 옮겨간다.
     */
    private fun buildModeChooser(statusText: String): RemoteViews {
        val views = RemoteViews(packageName, R.layout.notification_modes)
        views.setTextViewText(R.id.notification_status, statusText)

        val current = SettingsManager.visualMode.value
        for ((index, mode) in VisualMode.values().withIndex()) {
            val chip = MODE_CHIP_IDS[index]
            val label = uiContext.getString(mode.labelRes)
            val selected = mode == current
            val chipIntent = notificationActionIntent(NotificationCommand.ACTION_SET_MODE)
                .putExtra(NotificationCommand.EXTRA_MODE_ORDINAL, mode.ordinal)
            views.setTextViewText(chip, label)
            views.setContentDescription(
                chip,
                if (selected) uiContext.getString(R.string.notification_mode_selected, label) else label
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 색은 state_checked 로 정해져 있으므로 켜짐만 알려 주면 된다.
                views.setCompoundButtonChecked(chip, selected)
                // 한 칩이 켜지면 켜져 있던 칩이 꺼지는 것도 함께 알려 온다. 어느 쪽인지는 알림을 그리는 쪽이
                // EXTRA_CHECKED 로 채워 주므로, 그 값을 받으려면 인텐트를 고칠 수 있게 둬야 한다.
                // 갈 곳을 지정한 인텐트라 바뀔 수 있는 것은 이 값뿐이다.
                views.setOnCheckedChangeResponse(
                    chip,
                    RemoteViews.RemoteResponse.fromPendingIntent(
                        PendingIntent.getBroadcast(
                            this,
                            REQUEST_MODE_BASE + mode.ordinal,
                            chipIntent,
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                )
            } else {
                val intent = PendingIntent.getBroadcast(
                    this,
                    REQUEST_MODE_BASE + mode.ordinal,
                    chipIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setInt(
                    chip,
                    "setBackgroundResource",
                    if (selected) R.drawable.notification_mode_chip_on else R.drawable.notification_mode_chip_off
                )
                // 고른 칩만 색을 지정하면, 알림을 고쳐 달 때 전에 골랐던 칩에 색이 남을 수 있다. 넷 다 정해 준다.
                views.setTextColor(
                    chip,
                    ContextCompat.getColor(
                        this,
                        if (selected) R.color.notification_chip_text_on else R.color.notification_chip_text_off
                    )
                )
                views.setOnClickPendingIntent(chip, intent)
            }
        }
        return views
    }
}
