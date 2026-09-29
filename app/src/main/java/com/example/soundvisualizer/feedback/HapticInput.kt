package com.example.soundvisualizer.feedback

/**
 * 진동 알림이 틱마다 읽는 소리 특징. 실제로는 네이티브 누적값([NativeHapticInput])이고, 설정의 미리보기와 개발자
 * '진동 시험' 은 흉내 낸 소리([SyntheticHapticInput])를 같은 경로로 넣는다. 그래서 미리보기가 실제와 똑같이 울린다.
 */
interface HapticInput {
    /** [out] (크기 [FRAME_SIZE] 이상) 에 `[PEAK, RMS, TONE, BUFFERS]` 를 채우고 비운다. 호출당 할당 없음. */
    fun takeFrame(out: FloatArray)

    companion object {
        const val PEAK = 0
        const val RMS = 1
        const val TONE = 2
        const val BUFFERS = 3
        const val FRAME_SIZE = 4
    }
}

/** 흉내 낸 소리 버퍼가 오는 방식. 실제 캡처는 대개 버퍼 하나씩 오지만, 기기에 따라 몇 개씩 몰려 온다. */
enum class Delivery {
    /** 10.67ms 마다 버퍼 하나 */
    SMOOTH,

    /** 42.67ms 마다 버퍼 네 개 */
    BURSTY
}

/**
 * [scene] 을 실시간처럼 흘려보낸다. 첫 [takeFrame] 에서 시계를 맞추고, 그 뒤 [clockMs] 가 지날 때마다 도착했어야 할
 * 버퍼를 만들어 네이티브와 같은 계산([HapticFeatureMirror])으로 모은다. 장면이 끝나면 조용한 버퍼를 보낸다.
 * 소리는 내지 않는다.
 */
class SyntheticHapticInput(
    scene: HapticScene,
    private val clockMs: () -> Long,
    private val delivery: Delivery = Delivery.SMOOTH
) : HapticInput {
    private val source = scene.open()
    private val buf = FloatArray(HapticFeatureMirror.FLOATS_PER_BUFFER)
    private val one = FloatArray(3)
    private val acc = FloatArray(HapticInput.FRAME_SIZE)
    private var t0 = Long.MIN_VALUE
    private var delivered = 0L

    override fun takeFrame(out: FloatArray) {
        val now = clockMs()
        if (t0 == Long.MIN_VALUE) t0 = now
        val elapsed = now - t0
        acc.fill(0f)
        while (deliveryTimeMs(delivered) <= elapsed) {
            source.next(buf)
            HapticFeatureMirror.analyze(buf, buf.size, one)
            HapticFeatureMirror.fold(acc, one)
            delivered++
        }
        acc.copyInto(out, 0, 0, HapticInput.FRAME_SIZE)
    }

    private fun deliveryTimeMs(index: Long): Double = when (delivery) {
        Delivery.SMOOTH -> (index + 1) * HapticFeatureMirror.BUFFER_MS
        Delivery.BURSTY -> ((index / 4) + 1) * 4 * HapticFeatureMirror.BUFFER_MS
    }
}
