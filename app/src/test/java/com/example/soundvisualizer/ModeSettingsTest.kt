package com.example.soundvisualizer

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SettingsManager] 가 모드 설정 키 앞에 붙이는 이름. init 과 update 함수에 적힌 것과 같다. */
private val MODE_PREFIXES = listOf("wave", "pad", "circle", "outline")

/**
 * 모드 설정의 기본값을 고정한다.
 *
 * 이 값들은 그냥 취향이 아니라 슬라이더의 의미와 맞물려 있다. 예를 들어 최대 진하기가
 * 0 이면 크기 고정 모드가 완전히 투명해져서 아무것도 보이지 않고, 민감도는 화면에
 * 보이는 값(내부 계수의 4배)이라 바꾸면 반응 속도가 통째로 달라진다.
 *
 * [SettingsManager] 의 모드 설정 읽기·쓰기([SettingsManager.loadMode], [SettingsManager.putMode])는
 * 메모리에만 두는 가짜 프리퍼런스로 기기 없이 돌린다. 앱 전체의 설정 복원(init)은 Context 가
 * 필요해서 여기서 다루지 않는다.
 */
class ModeSettingsTest {

    @Test
    fun `기본값이 고정되어 있다`() {
        val d = ModeSettings()
        assertEquals("크기", 50f, d.intensity, 0f)
        assertEquals("속도", 20f, d.speed, 0f)
        assertEquals("진하기", 50f, d.opacity, 0f)
        assertEquals("민감도", 15f, d.sensitivity, 0f)
        assertEquals("반지름", 40f, d.circleRadius, 0f)
        assertEquals("고정 크기", 30f, d.opacityFixedSize, 0f)
        assertEquals("최대 진하기", 100f, d.opacityFixedMaxOpacity, 0f)
        assertEquals("광원 강도", 0f, d.glowIntensity, 0f)
        assertTrue("공간 리플은 기본 켜짐", d.useRippleDelay)
        assertFalse("광원은 기본 꺼짐", d.isGlowMode)
        assertFalse("크기 고정은 기본 꺼짐", d.intensityAsOpacity)
    }

    @Test
    fun `최대 진하기 기본값이 0 이면 크기 고정 모드가 보이지 않는다`() {
        // 알파 = 최대진하기/100 * 볼륨비율. 0 이면 소리가 아무리 커도 0 이다.
        assertTrue(
            "최대 진하기 기본값이 0 이면 크기 고정 모드에서 화면이 비어 보인다",
            ModeSettings().opacityFixedMaxOpacity > 0f
        )
    }

    @Test
    fun `진하기 기본값은 절반쯤 보이는 값이다`() {
        val opacity = ModeSettings().opacity
        assertTrue("진하기 기본값($opacity)이 화면에 보이는 범위여야 한다", opacity in 1f..100f)
    }

    @Test
    fun `copy 는 지정한 값만 바꾼다`() {
        val base = ModeSettings()
        val changed = base.copy(intensity = 77f)
        assertEquals(77f, changed.intensity, 0f)
        assertEquals(base.speed, changed.speed, 0f)
        assertEquals(base.opacity, changed.opacity, 0f)
        assertEquals(base.sensitivity, changed.sensitivity, 0f)
    }

    @Test
    fun `copy 후 수정이 원본에 번지지 않는다`() {
        // SettingsManager 의 update 가 copy().apply { } 로 도는데,
        // ModeSettings 가 var 필드를 가진 data class 라 얕은 복사가 맞게 동작해야 한다.
        val base = ModeSettings()
        val originalIntensity = base.intensity
        base.copy().apply { intensity = 99f }
        assertEquals("원본이 함께 바뀌었다", originalIntensity, base.intensity, 0f)
    }

    // ---------------------------------------------------------------
    // 저장·복원 (SettingsManager)
    // ---------------------------------------------------------------

    @Test
    fun `저장값이 없는 새 설치는 ModeSettings 기본값을 그대로 받는다`() {
        // 복원 코드에 기본값을 따로 적어두면 데이터 클래스만 고쳤을 때 여기서 어긋난다.
        val empty = MemoryPrefs()
        for (prefix in MODE_PREFIXES) {
            assertEquals(prefix, ModeSettings(), SettingsManager.loadMode(empty, prefix))
        }
    }

    @Test
    fun `저장한 모드 설정을 모든 항목 그대로 다시 읽는다`() {
        // 실수 항목은 기본값과도 서로와도 다른 값이라 키가 틀리거나 뒤바뀌면 복원값이 달라진다.
        // 켜고 끄는 항목은 값이 둘뿐이므로, 두 번째 묶음에서 크기 고정만 되돌려 광원과 값을 엇갈리게 한다.
        val changed = ModeSettings(
            intensity = 71f,
            speed = 62f,
            opacity = 53f,
            circleRadius = 44f,
            useRippleDelay = false,
            sensitivity = 35f,
            isGlowMode = true,
            glowIntensity = 26f,
            intensityAsOpacity = true,
            opacityFixedSize = 17f,
            opacityFixedMaxOpacity = 88f
        )
        val d = ModeSettings()
        val sameAsDefault = listOf(
            "크기" to (changed.intensity == d.intensity),
            "속도" to (changed.speed == d.speed),
            "진하기" to (changed.opacity == d.opacity),
            "반지름" to (changed.circleRadius == d.circleRadius),
            "공간 리플" to (changed.useRippleDelay == d.useRippleDelay),
            "민감도" to (changed.sensitivity == d.sensitivity),
            "광원" to (changed.isGlowMode == d.isGlowMode),
            "광원 강도" to (changed.glowIntensity == d.glowIntensity),
            "크기 고정" to (changed.intensityAsOpacity == d.intensityAsOpacity),
            "고정 크기" to (changed.opacityFixedSize == d.opacityFixedSize),
            "최대 진하기" to (changed.opacityFixedMaxOpacity == d.opacityFixedMaxOpacity)
        ).filter { it.second }.map { it.first }
        assertTrue("기본값과 같은 항목은 저장 키가 틀려도 잡히지 않는다: $sameAsDefault", sameAsDefault.isEmpty())

        for (saved in listOf(changed, changed.copy(intensityAsOpacity = false))) {
            for (prefix in MODE_PREFIXES) {
                val prefs = MemoryPrefs()
                SettingsManager.putMode(prefs.edit(), prefix, saved)

                assertEquals(prefix, saved, SettingsManager.loadMode(prefs, prefix))
                // 다른 모드 이름으로는 읽히지 않는다.
                MODE_PREFIXES.filter { it != prefix }.forEach { other ->
                    assertEquals("$prefix 에 저장하고 $other 로 읽음", d, SettingsManager.loadMode(prefs, other))
                }
            }
        }
    }
}

/**
 * 메모리에만 두는 SharedPreferences. 에디터는 값을 바로 적는다
 * (실제 구현의 apply·commit 시점은 여기서 검사할 대상이 아니다).
 */
private class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    private inner class MemoryEditor : SharedPreferences.Editor {
        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            values[key] = value
            return this
        }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, value: MutableSet<String>?) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            values.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            return this
        }

        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}
