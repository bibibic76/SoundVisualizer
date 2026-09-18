package com.example.soundvisualizer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.concurrent.thread

/**
 * 쉬는 오버레이를 깨우는 소리 신호(#170). 오버레이는 [SoundWakeSignal.arm] → 소리 확인 → [SoundWakeSignal.awaitLoud]
 * 순서로 쉬고, 캡처 스레드는 버퍼마다 [SoundWakeSignal.onBuffer] 를 부른다.
 */
class SoundWakeSignalTest {

    private var level = 0f
    private var levelReads = 0
    private val signal = SoundWakeSignal({ levelReads++; level }, threshold = 0.01f)

    /** 캡처 스레드처럼 다른 스레드에서 버퍼 하나를 넘긴다. */
    private fun bufferFromCaptureThread(value: Float) {
        thread {
            level = value
            signal.onBuffer()
        }.join()
    }

    @Test
    fun `조용한 버퍼로는 깨지 않는다`() = runBlocking {
        signal.arm()
        val woke = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { signal.awaitLoud() }
        bufferFromCaptureThread(0.005f)
        assertNull("조용한데 깨어났다", withTimeoutOrNull(200) { woke.await() })
        woke.cancel()
    }

    @Test
    fun `큰 소리 버퍼가 오면 깨운다`() = runBlocking {
        signal.arm()
        val woke = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { signal.awaitLoud() }
        bufferFromCaptureThread(0.5f)
        assertNotNull("큰 소리에 깨어나지 않았다", withTimeoutOrNull(2_000) { woke.await() })
    }

    @Test
    fun `확인과 잠드는 사이에 온 소리로는 잠들지 않는다`() = runBlocking {
        signal.arm()
        // 오버레이가 소리를 확인한 뒤 잠들기 전에 캡처 스레드가 큰 소리를 넘겼다. 기다리는 쪽이 아직 없다.
        bufferFromCaptureThread(0.5f)
        assertNotNull("그사이 온 소리를 놓쳤다", withTimeoutOrNull(2_000) { signal.awaitLoud() })
    }

    @Test
    fun `arm 전에 온 소리로는 깨지 않는다`() = runBlocking {
        // 큰 소리 뒤 캡처가 멈춰 마지막 소리 크기가 큰 채로 남았다. 새 버퍼가 없으니 잠들어야 한다.
        // 남은 값만 보고 깨우면 오버레이가 잠들지 못하고 메인 스레드에서 쉬지 않고 돈다.
        bufferFromCaptureThread(0.5f)
        signal.arm()
        val woke = async(Dispatchers.Default) { signal.awaitLoud() }
        assertNull("멈춘 캡처의 남은 소리 크기로 깨어났다", withTimeoutOrNull(200) { woke.await() })
        woke.cancel()
    }

    @Test
    fun `기다리다 취소돼도 다음 잠은 평소대로 깨운다`() = runBlocking {
        signal.arm()
        val first = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { signal.awaitLoud() }
        first.cancel()
        bufferFromCaptureThread(0.5f) // 취소된 쪽을 깨우려다 죽지 않는다

        signal.arm()
        level = 0f
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { signal.awaitLoud() }
        bufferFromCaptureThread(0.5f)
        assertNotNull(withTimeoutOrNull(2_000) { second.await() })
    }

    @Test
    fun `이미 알렸고 기다리는 쪽이 없으면 버퍼마다 소리 크기를 읽지 않는다`() {
        // 오버레이가 그리는 동안(arm 을 부르지 않는 동안) 캡처 스레드가 버퍼마다 하는 일을 늘리지 않는다.
        signal.arm()
        bufferFromCaptureThread(0.5f)
        val readsAfterFirstLoud = levelReads
        repeat(10) { bufferFromCaptureThread(0.5f) }
        assertEquals(readsAfterFirstLoud, levelReads)
    }
}
