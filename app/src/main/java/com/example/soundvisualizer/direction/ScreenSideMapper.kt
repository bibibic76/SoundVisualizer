package com.example.soundvisualizer.direction

import android.view.Surface
import kotlin.math.abs

/** 소리가 화면의 어느 쪽에서 오는지. */
enum class ScreenSide { Left, Center, Right }

/**
 * 두 마이크의 시간차([ArrivalDelayEstimator])를 화면의 왼쪽·가운데·오른쪽으로 바꾼다(#248).
 * 안드로이드에 의존하지 않아 JVM 에서 테스트한다(`Surface` 상수는 컴파일할 때 숫자로 들어간다).
 *
 * 두 마이크는 폰의 긴 축에 놓여 있다.
 * - **세로로 들면** 그 축이 화면의 위아래라 좌우를 가릴 수 없다. 늘 가운데다.
 * - **가로로 들면** 축이 화면의 좌우가 된다. `Display.getRotation()` 은 그린 화면을 돌린 각이라 폰을 돌린 방향과
 *   반대다. `ROTATION_90` 은 폰을 반시계로 돌린 것이라 폰의 위쪽 끝이 화면 왼쪽에 오고, `ROTATION_270` 은 오른쪽에 온다.
 *
 * 위쪽 마이크가 어느 채널로 들어오는지는 기기마다 다를 수 있어 [topChannel] 로 받는다. 기기 측정에서
 * `AudioRecord.getActiveMicrophones()` 의 위치와 채널 배치로 정한다.
 *
 * 앞·뒤는 가릴 수 없다. 축을 둘러싼 원뿔 위의 방향은 모두 같은 시간차를 낸다. 좌우 표시에는 상관없다.
 */
object ScreenSideMapper {

    /**
     * @param lagSamples 채널 1 이 채널 0 보다 늦게 받은 샘플 수([ArrivalDelayEstimator.Estimate.lagSamples])
     * @param topChannel 위쪽 마이크가 들어오는 채널(0 또는 1)
     * @param rotation `Display.getRotation()` 값
     * @param deadZoneSamples 시간차가 이보다 작으면 정면에 가까워 가운데로 둔다
     */
    fun side(lagSamples: Float, topChannel: Int, rotation: Int, deadZoneSamples: Float): ScreenSide {
        require(topChannel == 0 || topChannel == 1) { "채널은 0 또는 1: $topChannel" }
        if (rotation != Surface.ROTATION_90 && rotation != Surface.ROTATION_270) return ScreenSide.Center
        if (abs(lagSamples) < deadZoneSamples) return ScreenSide.Center
        // 양수면 채널 0 쪽 마이크에 먼저 닿았다.
        val nearTop = if (topChannel == 0) lagSamples > 0f else lagSamples < 0f
        val topOnLeft = rotation == Surface.ROTATION_90
        return if (nearTop == topOnLeft) ScreenSide.Left else ScreenSide.Right
    }
}
