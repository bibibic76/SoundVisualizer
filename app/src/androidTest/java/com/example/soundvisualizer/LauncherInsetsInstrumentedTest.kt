package com.example.soundvisualizer

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * 앱 화면은 시스템 바 밑까지 그려진다(Android 15 이상은 targetSdk 35 부터 강제, 옛 버전은 enableEdgeToEdge).
 * 그래도 탭 이름이 상태 표시줄이나 카메라 구멍에 가리지 않는지 본다.
 *
 * 여백 처리(LauncherApp 의 safeDrawingPadding)를 빼면, 상태 표시줄이 24dp 보다 높은 기기(Android 15 이상 대부분)에서
 * 탭 이름이 시계와 겹쳐 이 테스트가 실패한다(#138).
 */
@RunWith(AndroidJUnit4::class)
class LauncherInsetsInstrumentedTest {

    /**
     * 튜토리얼을 본 것으로 두고 연다. 새로 설치한 테스트 기기에서는 튜토리얼이 탭 화면 대신 뜨기 때문이다.
     * 액티비티가 뜨기 전에 정해야 하므로 액티비티를 여는 규칙보다 바깥에 두고, 끝나면 원래 값으로 돌려 둔다.
     */
    private val tutorialSeen = object : ExternalResource() {
        private var before = true

        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.init(InstrumentationRegistry.getInstrumentation().targetContext)
                before = SettingsManager.tutorialSeen.value
                SettingsManager.setTutorialSeen(true)
            }
        }

        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { SettingsManager.setTutorialSeen(before) }
        }
    }

    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tutorialSeen).around(rule)

    @Test
    fun tabNamesStartBelowStatusBarAndCutout() {
        rule.waitForIdle()
        val activity = rule.activity
        val rootInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        assertNotNull("창의 인셋을 읽지 못했습니다", rootInsets)
        val top = rootInsets!!.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ).top

        for (tab in listOf(R.string.tab_home, R.string.tab_settings, R.string.tab_help)) {
            val name = activity.getString(tab)
            // 탭은 글자와 밑줄을 묶은 한 덩어리라, 그 덩어리의 위쪽 끝이 바 아래에 있어야 한다. 좌표는 창 기준 px 이다.
            val tabTop = rule.onNodeWithText(name).fetchSemanticsNode().boundsInWindow.top
            assertTrue(
                "탭 \"$name\" 의 위쪽($tabTop px)이 상태 표시줄·카메라 구멍($top px)에 가립니다",
                tabTop >= top
            )
        }
    }
}
