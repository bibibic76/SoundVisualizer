package com.example.soundvisualizer

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.tutorial.TutorialPage
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 튜토리얼: 처음 열면 뜨고, 닫으면 다시 뜨지 않으며, 홈의 ‘튜토리얼 보기’로 다시 볼 수 있다.
 *
 * [SettingsManager] 는 프로세스마다 한 번만 프리퍼런스를 읽으므로, 본 적이 있는지는 액티비티를 열기 전에 직접 세운다.
 * 처음 설치인지 판단하는 규칙은 JVM 테스트(TutorialSettingTest)가 본다. 끝나면 테스트 전의 값으로 돌려 둔다.
 * 사람이 쓰는 폰에서 돌려도 그 폰의 상태를 바꿔 놓지 않고, 뒤에 도는 계측 테스트가 튜토리얼에 가리지 않게 한다.
 *
 * 그림의 애니메이션은 테스트에서 돌지 않는다(끝없는 애니메이션은 테스트가 멈춰 둔다). 멈춘 장면만 보고 지나간다.
 */
@RunWith(AndroidJUnit4::class)
class TutorialInstrumentedTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var seenBefore = true

    private fun text(id: Int) = context.getString(id)

    private fun setSeen(seen: Boolean) = instrumentation.runOnMainSync { SettingsManager.setTutorialSeen(seen) }

    @Before
    fun rememberState() {
        instrumentation.runOnMainSync {
            SettingsManager.init(context)
            seenBefore = SettingsManager.tutorialSeen.value
        }
    }

    @After
    fun restoreState() {
        setSeen(seenBefore)
    }

    @Test
    fun firstLaunchShowsTutorialInsteadOfTabs() {
        setSeen(false)
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(text(R.string.tutorial_sound_title)).assertIsDisplayed()
            // 튜토리얼 뒤에 탭 화면을 겹쳐 그리지 않는다. 화면 읽어주기가 가려진 홈을 읽으면 안 된다.
            rule.onNodeWithText(text(R.string.tab_home)).assertDoesNotExist()

            rule.onNodeWithText(text(R.string.tutorial_skip)).performClick()

            rule.onNodeWithText(text(R.string.tab_home)).assertIsDisplayed()
            assertTrue("건너뛰었는데 본 것으로 저장되지 않았다", SettingsManager.tutorialSeen.value)
        }
        // 다시 열면 뜨지 않는다.
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(text(R.string.tab_home)).assertIsDisplayed()
            rule.onNodeWithText(text(R.string.tutorial_sound_title)).assertDoesNotExist()
        }
    }

    @Test
    fun soundStartsOffTogglesAndIsOffAgainWhenReopened() {
        // 튜토리얼 소리(#327)는 처음에 꺼져 있다. 테스트에서는 그림이 움직이지 않으므로 켜도 소리는 나지 않는다.
        setSeen(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(text(R.string.home_tutorial)).performScrollTo().performClick()
            rule.onNodeWithContentDescription(text(R.string.tutorial_sound_on)).performClick()
            rule.onNodeWithContentDescription(text(R.string.tutorial_sound_off)).assertIsDisplayed()

            // 닫았다 다시 열면 다시 꺼진 채로 시작한다.
            rule.onNodeWithText(text(R.string.tutorial_skip)).performClick()
            rule.onNodeWithText(text(R.string.home_tutorial)).performScrollTo().performClick()
            rule.onNodeWithContentDescription(text(R.string.tutorial_sound_on)).assertIsDisplayed()
        }
    }

    @Test
    fun homeButtonReopensTutorialAndLastPageCloses() {
        setSeen(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(text(R.string.home_tutorial)).performScrollTo().performClick()
            rule.onNodeWithText(text(R.string.tutorial_sound_title)).assertIsDisplayed()

            // 다음으로 끝까지 넘긴다.
            repeat(TutorialPage.entries.size - 1) {
                rule.onNodeWithText(text(R.string.tutorial_next)).performClick()
            }
            rule.onNodeWithText(text(R.string.tutorial_start_title)).assertIsDisplayed()
            // 마지막 쪽에서는 아래 확인이 닫기를 맡으므로 건너뛰기는 숨어 있고 누를 수도 없다.
            rule.onNodeWithText(text(R.string.tutorial_skip)).assertIsNotEnabled()

            rule.onNodeWithText(text(R.string.tutorial_done)).performClick()
            rule.onNodeWithText(text(R.string.tab_home)).assertIsDisplayed()
        }
    }

    @Test
    fun doubleTapOnDoneDoesNotReopenTutorial() {
        // 튜토리얼이 사라지는 동안 탭 화면이 투명한 채 맨 위에서 누름을 받는다. ‘확인’을 빠르게 두 번 누르면 둘째 번이
        // 그 자리의 ‘튜토리얼 보기’에 닿아 방금 닫은 튜토리얼이 다시 열렸다(#232).
        setSeen(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(text(R.string.home_tutorial)).performScrollTo().performClick()
            repeat(TutorialPage.entries.size - 1) {
                rule.onNodeWithText(text(R.string.tutorial_next)).performClick()
            }
            val done = rule.onNodeWithText(text(R.string.tutorial_done)).fetchSemanticsNode().boundsInRoot.center

            // 둘째 번이 전환(0.3초) 한가운데 닿도록 시계를 멈추고 0.1초만 흘린다. 그사이 실제 시간은 0.7초 흘린다.
            // 느린 기기에서는 화면이 멈춰 전환이 그만큼 늦어진다. 실제 시계로 막으면 이때 튜토리얼이 다시 열렸다(#249).
            rule.mainClock.autoAdvance = false
            rule.onRoot().performTouchInput { click(done) }
            rule.mainClock.advanceTimeBy(100)
            SystemClock.sleep(700)
            rule.onRoot().performTouchInput { click(done) }
            rule.mainClock.autoAdvance = true

            rule.onNodeWithText(text(R.string.tab_home)).assertIsDisplayed()
            rule.onNodeWithText(text(R.string.tutorial_start_title)).assertDoesNotExist()
        }
    }

    @Test
    fun tapDuringCloseDoesNotReachHomeSwitch() {
        // 튜토리얼이 사라지는 동안의 누름이 홈의 외부 사운드 모드 스위치를 바꾸지 않는다(#264). 가로 화면에서는 ‘확인’
        // 자리에 그 스위치가 있어, 두 번 누르면 동의 없이 켜지는 마이크 모드로 바뀌었다. 여기서는 자리와 상관없이
        // 전환 한가운데에 스위치를 직접 누른다.
        setSeen(true)
        var modeBefore = false
        instrumentation.runOnMainSync {
            modeBefore = SettingsManager.externalSoundMode.value
            SettingsManager.setExternalSoundMode(false)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                // 탭 화면은 튜토리얼이 닫히며 새로 만들어져 맨 위부터 보인다. 스위치 자리를 그 상태에서 재 둔다.
                val switch = rule.onNodeWithText(text(R.string.home_external_mode)).fetchSemanticsNode().boundsInRoot.center
                rule.onNodeWithText(text(R.string.home_tutorial)).performScrollTo().performClick()
                repeat(TutorialPage.entries.size - 1) {
                    rule.onNodeWithText(text(R.string.tutorial_next)).performClick()
                }
                // 시계를 멈추기 전에 잰다. 멈춘 뒤에는 마지막 쪽으로 넘어가는 전환이 끝나지 않아 ‘확인’이 아직 없다.
                val done = rule.onNodeWithText(text(R.string.tutorial_done)).fetchSemanticsNode().boundsInRoot.center

                rule.mainClock.autoAdvance = false
                rule.onRoot().performTouchInput { click(done) }
                rule.mainClock.advanceTimeBy(100)
                rule.onRoot().performTouchInput { click(switch) }
                rule.mainClock.autoAdvance = true

                rule.onNodeWithText(text(R.string.tab_home)).assertIsDisplayed()
                var mode = true
                instrumentation.runOnMainSync { mode = SettingsManager.externalSoundMode.value }
                assertFalse("튜토리얼이 사라지는 동안의 누름이 외부 사운드 모드를 켰다", mode)
            }
        } finally {
            instrumentation.runOnMainSync { SettingsManager.setExternalSoundMode(modeBefore) }
        }
    }

    @Test
    fun backGoesToPreviousPageThenCloses() {
        setSeen(false)
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText(text(R.string.tutorial_next)).performClick()
            rule.onNodeWithText(text(R.string.tutorial_direction_title)).assertIsDisplayed()

            // 위의 ‘이전’ 버튼
            rule.onNodeWithContentDescription(text(R.string.tutorial_back)).performClick()
            rule.onNodeWithText(text(R.string.tutorial_sound_title)).assertIsDisplayed()

            // 시스템 뒤로 가기
            rule.onNodeWithText(text(R.string.tutorial_next)).performClick()
            rule.onNodeWithText(text(R.string.tutorial_direction_title)).assertIsDisplayed()
            Espresso.pressBack()
            rule.onNodeWithText(text(R.string.tutorial_sound_title)).assertIsDisplayed()
            assertFalse("앞 쪽으로 돌아가기만 했는데 본 것으로 저장했다", SettingsManager.tutorialSeen.value)

            // 첫 쪽에서의 뒤로 가기는 건너뛰기와 같다. 앱을 끄지 않고 탭 화면으로 간다.
            Espresso.pressBack()
            rule.onNodeWithText(text(R.string.tab_home)).assertIsDisplayed()
            assertTrue(SettingsManager.tutorialSeen.value)
        }
    }

    @Test
    fun tutorialStaysOpenOnSamePageWhenRecreated() {
        // 화면 회전이나 언어 변경으로 액티비티가 다시 만들어져도 보던 쪽이 그대로 남는다.
        setSeen(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.onNodeWithText(text(R.string.tutorial_next)).performClick()
            rule.onNodeWithText(text(R.string.tutorial_direction_title)).assertIsDisplayed()

            scenario.recreate()

            rule.onNodeWithText(text(R.string.tutorial_direction_title)).assertIsDisplayed()
            rule.onNodeWithText(text(R.string.tab_home)).assertDoesNotExist()
        }
    }

    @Test
    fun reopenedTutorialStaysOpenWhenRecreated() {
        // 홈에서 다시 연 튜토리얼도 다시 만들어질 때 닫히지 않는다(본 적은 이미 있다).
        setSeen(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.onNodeWithText(text(R.string.home_tutorial)).performScrollTo().performClick()
            rule.onNodeWithText(text(R.string.tutorial_sound_title)).assertIsDisplayed()

            scenario.recreate()

            rule.onNodeWithText(text(R.string.tutorial_sound_title)).assertIsDisplayed()
        }
    }
}
