package com.example.soundvisualizer.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AiFrontendSwitcherTest {

    @Test
    fun `선택된 frontend만 만들고 같은 선택에서는 재사용한다`() {
        var currentCreates = 0
        var qualcommCreates = 0
        val switcher = AiFrontendSwitcher(
            currentFactory = {
                currentCreates++
                LogMelFrontend { floatArrayOf(1f) }
            },
            qualcommSourceFactory = {
                qualcommCreates++
                LogMelFrontend { floatArrayOf(2f) }
            }
        )

        assertEquals("생성만 해서는 작업 버퍼를 만들지 않는다", 0, currentCreates)
        assertEquals(0, qualcommCreates)

        assertArrayEquals(floatArrayOf(1f), switcher.compute(AiFrontendMode.CURRENT, floatArrayOf()), 0f)
        assertArrayEquals(floatArrayOf(1f), switcher.compute(AiFrontendMode.CURRENT, floatArrayOf()), 0f)
        assertEquals("같은 선택은 한 인스턴스를 재사용한다", 1, currentCreates)
        assertEquals("선택하지 않은 frontend는 만들지 않는다", 0, qualcommCreates)
    }

    @Test
    fun `선택이 바뀌면 새 frontend 하나로 교체한다`() {
        var currentCreates = 0
        var qualcommCreates = 0
        val switcher = AiFrontendSwitcher(
            currentFactory = {
                currentCreates++
                LogMelFrontend { floatArrayOf(1f) }
            },
            qualcommSourceFactory = {
                qualcommCreates++
                LogMelFrontend { floatArrayOf(2f) }
            }
        )

        assertArrayEquals(floatArrayOf(1f), switcher.compute(AiFrontendMode.CURRENT, floatArrayOf()), 0f)
        assertArrayEquals(
            floatArrayOf(2f),
            switcher.compute(AiFrontendMode.QUALCOMM_SOURCE, floatArrayOf()),
            0f
        )
        assertArrayEquals(floatArrayOf(1f), switcher.compute(AiFrontendMode.CURRENT, floatArrayOf()), 0f)

        assertEquals("돌아오면 버렸던 current 구현을 새로 만든다", 2, currentCreates)
        assertEquals(1, qualcommCreates)
    }
}
