package com.example.soundvisualizer.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 진동 알림이 읽는 소리 크기의 계약(#174).
 *
 * 0.1초마다 가장 최근 버퍼(11.6ms) 하나만 보면 시간의 12% 남짓만 들여다보게 되어, 총소리 한 발처럼 짧은 소리가
 * 확인과 확인 사이에 들어왔다 사라진다. 그러면 화면에는 그려지는데 진동만 빠진다. 구간 전체의 최대값을 읽어야 한다.
 *
 * [HapticNotifier] 는 Context 와 진동 모터가 있어야 해서 JVM 에서 만들 수 없다. 그래서 어느 값을 읽는지를
 * 소스에서 확인한다. 이 저장소가 테마·문구 계약을 파일로 확인하는 것과 같은 방식이다.
 */
class HapticLevelSourceTest {

    // 유닛 테스트는 app 모듈 폴더에서 돈다.
    private val source =
        File("src/main/java/com/example/soundvisualizer/feedback/HapticNotifier.kt").readText(Charsets.UTF_8)
            .split('\n')
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
            .joinToString("\n")

    @Test
    fun `진동은 구간 전체의 최대 피크를 읽는다`() {
        assertTrue(
            "HapticNotifier 가 AudioEngine.takeHapticPeak() 을 읽지 않는다",
            source.contains("AudioEngine.takeHapticPeak()")
        )
    }

    @Test
    fun `진동은 가장 최근 버퍼 하나만 보는 값을 읽지 않는다`() {
        assertFalse(
            "HapticNotifier 가 currentLevel() 을 읽는다. 그 값은 버퍼 하나뿐이라 짧은 소리를 놓친다",
            source.contains("AudioEngine.currentLevel()")
        )
    }
}
