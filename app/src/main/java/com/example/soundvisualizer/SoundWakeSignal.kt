package com.example.soundvisualizer

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 쉬는(idle) 오버레이를 소리가 나는 순간 깨운다(#170).
 *
 * 쉬는 동안 33ms 마다 소리를 확인하면 그리는 것이 없어도 앱이 초당 200번 가까이 깨어났다. Compose 루프 안의
 * `delay` 는 따로 타이머 스레드를 거치고, 돌아올 때 핸들러 메시지와 vsync 콜백을 둘 다 요청하기 때문이다.
 * 캡처 스레드는 어차피 버퍼마다 소리 크기를 알고 있으므로, 조용한 동안에는 오버레이가 여기서 잠들고
 * 캡처 스레드가 깨운다. 33ms 주기를 기다리지 않으니 소리에 반응하는 시점도 빨라진다.
 *
 * 순서는 이렇다. 오버레이가 [arm] 을 부르고 소리를 확인한 뒤 조용하면 [awaitLoud] 로 잠든다. 그사이 캡처
 * 스레드가 [onBuffer] 에서 큰 소리를 보면 깨운다. **[arm] 뒤에 새로 들어온 버퍼만 센다.** 마지막 소리 크기만
 * 보고 깨우면, 캡처가 멈춰 그 값이 크게 남아 있을 때 오버레이가 잠들지 못하고 메인 스레드에서 쉬지 않고 돈다.
 *
 * 기다리는 쪽은 하나(오버레이)뿐이다. 캡처 스레드가 버퍼마다 더 하는 일은 원자 변수 읽기이고, 오버레이가 쉬는
 * 중이면 소리 크기 읽기가 한 번 더다. 할당은 없다.
 *
 * @param level 가장 최근 버퍼의 소리 크기(0..1). 캡처 스레드에서 불린다.
 * @param threshold 이 값보다 크면 깨운다.
 */
internal class SoundWakeSignal(
    private val level: () -> Float,
    private val threshold: Float
) {
    private val waiter = AtomicReference<CancellableContinuation<Unit>?>(null)

    /** 마지막 [arm] 뒤에 큰 소리가 들어왔는지. */
    @Volatile
    private var loudSinceArm = false

    /** 지금부터 들어오는 소리만 센다. 오버레이가 소리를 확인하기 **직전에** 부른다. 메인 스레드 전용. */
    fun arm() {
        loudSinceArm = false
    }

    /** 캡처 스레드가 버퍼를 넘긴 직후 부른다. */
    fun onBuffer() {
        // 이미 알렸고 기다리는 쪽도 없다. 오버레이가 그리는 동안은 대부분 여기서 끝난다.
        if (loudSinceArm && waiter.get() == null) return
        if (level() <= threshold) return
        loudSinceArm = true
        waiter.getAndSet(null)?.resume(Unit)
    }

    /**
     * 마지막 [arm] 뒤에 큰 소리가 들어올 때까지 잔다. 이미 들어왔으면 바로 돌아온다.
     * 취소되면(오버레이가 사라지면) 기다리던 자리를 비운다.
     */
    suspend fun awaitLoud() {
        suspendCancellableCoroutine { cont ->
            waiter.set(cont)
            cont.invokeOnCancellation { waiter.compareAndSet(cont, null) }
            // 등록하기 전에 들어온 버퍼는 캡처 스레드가 기다리는 쪽을 보지 못했다. 등록한 뒤에 보아야 놓치지 않는다.
            // 이미 들어왔으면 여기서 바로 깨우고, 그러면 잠들지 않고 돌아간다. 캡처 스레드와 여기서 둘 다 깨우려 해도
            // getAndSet 으로 꺼낸 쪽 하나만 깨운다.
            if (loudSinceArm) waiter.getAndSet(null)?.resume(Unit)
        }
    }
}

/** 오버레이([VisualizerOverlay])가 기다리고 캡처 서비스([AudioCaptureService])가 깨우는 신호. */
internal val OverlayWake = SoundWakeSignal({ AudioEngine.currentLevel() }, VisualizerEngine.WAKE_THRESHOLD)
