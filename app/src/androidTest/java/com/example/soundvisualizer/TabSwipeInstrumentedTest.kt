package com.example.soundvisualizer

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 탭 화면도 쪽 너비의 10%([PAGE_SNAP_THRESHOLD])만 끌고 놓으면 다음 탭으로 넘어간다(#335, #337). 페이저의 기본값(절반)이나
 * 30% 이면 짧게 끌고 놓을 때 앞 탭으로 튕겨 돌아갔다.
 *
 * 빠르게 연달아 넘겨도 넘어가던 탭이 앞 탭으로 끌려 되돌아가지 않아야 한다(#339). 튜토리얼에서는 늘 되돌아가던
 * 손동작인데, 탭 화면은 고치기 전에도 통과했다. 손을 뗀 곳이 두 탭의 가운데 바로 앞일 때만 걸리는 드문 경우였다.
 *
 * 튜토리얼이 탭 화면 대신 뜨지 않게, 본 적이 있는 것으로 세우고 끝나면 테스트 전의 값으로 돌려 둔다.
 */
@RunWith(AndroidJUnit4::class)
class TabSwipeInstrumentedTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var seenBefore = true

    @Before
    fun rememberState() {
        instrumentation.runOnMainSync {
            SettingsManager.init(context)
            seenBefore = SettingsManager.tutorialSeen.value
            SettingsManager.setTutorialSeen(true)
        }
    }

    @After
    fun restoreState() {
        instrumentation.runOnMainSync { SettingsManager.setTutorialSeen(seenBefore) }
    }

    @Test
    fun rapidFlicksDoNotBounceBack() {
        // 빠르게 연달아 넘기면 앞 넘김이 끝나기 전에 다음 넘김이 온다. 그때 넘어가던 탭이 도중에 앞 탭으로 끌려
        // 되돌아갔다(#339). 4분의 1을 0.08초에 튕기고, 0.15초 뒤에 다시 튕긴다. 홈에서 두 번이면 분류 탭이다.
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(context.getString(R.string.tab_home)).assertIsSelected()
            rule.mainClock.autoAdvance = false
            repeat(2) {
                rule.onRoot().performTouchInput {
                    val y = height * 0.55f
                    swipe(Offset(width * 0.75f, y), Offset(width * 0.5f, y), durationMillis = 80)
                }
                rule.mainClock.advanceTimeBy(150)
            }
            rule.mainClock.advanceTimeBy(2_000)
            rule.mainClock.autoAdvance = true
            rule.onNodeWithText(context.getString(R.string.tab_classify)).assertIsSelected()
        }
    }

    @Test
    fun shortSlowDragGoesToTheNextTab() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(context.getString(R.string.tab_home)).assertIsSelected()
            // 20%를 1초에 걸쳐 끈다. 빠르게 튕긴 것으로 보지 않을 만큼 느리다. 홈 탭 가운데에는 가로로 끄는 조작이 없다.
            rule.onRoot().performTouchInput {
                val y = height * 0.55f
                swipe(Offset(width * 0.7f, y), Offset(width * 0.5f, y), durationMillis = 1_000)
            }
            rule.waitForIdle()
            rule.onNodeWithText(context.getString(R.string.tab_settings)).assertIsSelected()
        }
    }
}
