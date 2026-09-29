package com.example.soundvisualizer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
