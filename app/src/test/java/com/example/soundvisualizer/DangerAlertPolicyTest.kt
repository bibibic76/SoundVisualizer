package com.example.soundvisualizer

import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 잠금 화면 위협음 알림을 언제 띄우는지(#310)와 어느 소리 이름을 적는지(#356). 확인 틱은 500ms 마다 돈다. */
class DangerAlertPolicyTest {

    private val danger = AiClassification.DANGER
    private val loud = 0.2f
    private val quiet = 0f

    /** [from] 부터 [toMs] 까지 500ms 틱을 돌려 알린 시각을 모은다. */
    private fun run(
        policy: DangerAlertPolicy,
        from: Long,
        toMs: Long,
        label: (Long) -> String = { danger },
        peak: (Long) -> Float = { loud },
        enabled: Boolean = true,
        locked: (Long) -> Boolean = { true }
    ): List<Long> {
        val fired = mutableListOf<Long>()
        var t = from
        while (t <= toMs) {
            val now = t
            if (policy.onTick(now, label(now), peak(now), enabled) { locked(now) }) fired += now
            t += 500
        }
        return fired
    }

    @Test
    fun `잠겨 있을 때 위협음이 이어지면 한 번만 알린다`() {
        assertEquals(listOf(0L), run(DangerAlertPolicy(), 0, 20_000))
    }

    @Test
    fun `쓰는 중에는 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, locked = { false }))
    }

    @Test
    fun `위협음이 이어지는 중에 잠그면 그때 알린다`() {
        assertEquals(listOf(4_000L), run(DangerAlertPolicy(), 0, 10_000, locked = { it >= 4_000 }))
    }

    @Test
    fun `소리가 없는데 위협음 라벨만 남아 있으면 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, peak = { quiet }))
    }

    @Test
    fun `위협음이 아니면 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, label = { AiClassification.SPEECH }))
    }

    @Test
    fun `위협음 표시를 꺼 두었거나 AI 가 동작하지 않으면 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, enabled = false))
    }

    @Test
    fun `알린 뒤 30초 안의 새 사건은 알리지 않고 그 뒤에는 알린다`() {
        // 0~2초 위협음, 10~12초 위협음(쿨다운 안), 40~42초 위협음(쿨다운 뒤)
        val on = { t: Long -> t in 0..2_000 || t in 10_000..12_000 || t in 40_000..42_000 }
        val fired = run(DangerAlertPolicy(), 0, 45_000, peak = { if (on(it)) loud else quiet })
        assertEquals(listOf(0L, 40_000L), fired)
    }

    @Test
    fun `짧게 끊겼다 이어지는 위협음은 같은 사건이다`() {
        val policy = DangerAlertPolicy(cooldownMs = 0)
        // 2초 끊김은 사건 간격(3초)보다 짧다.
        val on = { t: Long -> t !in 3_000..4_500 }
        assertEquals(listOf(0L), run(policy, 0, 10_000, peak = { if (on(it)) loud else quiet }))
    }

    @Test
    fun `잠금 여부는 알릴 차례일 때만 묻는다`() {
        val policy = DangerAlertPolicy()
        var asked = 0
        policy.onTick(0, AiClassification.SPEECH, loud, true) { asked++; true }
        policy.onTick(500, danger, quiet, true) { asked++; true }
        assertEquals("위협음이 아니거나 조용하면 묻지 않는다", 0, asked)
        assertTrue(policy.onTick(1_000, danger, loud, true) { asked++; true })
        policy.onTick(1_500, danger, loud, true) { asked++; true }
        assertEquals("알린 사건 동안에는 다시 묻지 않는다", 1, asked)
    }

    @Test
    fun `다시 시작하면 지난 실행의 쿨다운을 잊는다`() {
        val policy = DangerAlertPolicy()
        assertTrue(policy.onTick(0, danger, loud, true) { true })
        policy.reset()
        assertTrue(policy.onTick(1_000, danger, loud, true) { true })
        assertFalse(policy.onTick(1_500, danger, loud, true) { true })
    }

    // ---------------- 알림에 적을 소리(#356) ----------------

    /** 사용자가 바꾸지 않은 종류. */
    private val defaults = { name: String -> YamnetThreeClassMapper.mapDisplayNameToCoarse(name) }

    /** [name] 하나만 [type] 으로 바꾼 종류. 분류 탭에서 바꾼 것과 같다. */
    private fun changed(name: String, type: String) = { n: String -> if (n == name) type else defaults(n) }

    /** 실제 클래스 번호를 단 상위 후보. 확률 순으로 넘긴다. */
    private fun top(vararg hits: Pair<String, Float>) = hits.map { (name, probability) ->
        YamnetCoarseClassifier.TopClassHit(requireNotNull(SoundCatalog.indexOf(entries, name)) { name }, name, probability)
    }

    /** 알림이 적을 소리의 모델 이름. 서비스처럼 확정 이름의 번호를 소리 목록에서 찾고, 고른 번호를 이름으로 되돌린다. */
    private fun named(
        display: String,
        top5: List<YamnetCoarseClassifier.TopClassHit>,
        typeOf: (String) -> String = defaults
    ): String? = DangerAlertPolicy.alertSoundIndex(display, top5, typeOf) { SoundCatalog.indexOf(entries, it) }
        ?.let { entries[it].name }

    @Test
    fun `확정 이름이 위협음이면 최근 프레임에서 더 높은 다른 위협음보다 그 이름을 적는다`() {
        val top5 = top(
            "Alarm" to 0.40f, "Siren" to 0.30f, "Civil defense siren" to 0.10f, "Music" to 0.06f, "Vehicle" to 0.03f
        )
        assertEquals("Siren", named("Siren", top5))
    }

    @Test
    fun `구급차 사이렌 녹음은 최근 프레임에 사이렌이 없어도 확정 이름 Siren 을 적는다`() {
        // 10/2 개발자 CSV 의 한 줄. 라벨은 히스테리시스로 위협음에 남아 있고 확정 이름은 Siren 인데, 이 프레임의 상위 5개에는
        // 사이렌이 없다. 상위 후보만 보던 규칙은 #344 뒤의 매핑에서 Train whistle 을 적었다.
        val top5 = top(
            "Rail transport" to 0.08554f, "Train" to 0.07835f, "Steam whistle" to 0.06803f,
            "Steam" to 0.06653f, "Train whistle" to 0.06104f
        )
        assertEquals("Siren", named("Siren", top5))
    }

    @Test
    fun `총소리 녹음은 하한 아래의 Smash, crash 를 적지 않는다`() {
        // 10/2 개발자 CSV 의 한 줄. 상위 후보만 보던 규칙은 마지막 후보 Smash, crash(0.028)를 적었다.
        val top5 = top(
            "Outside, rural or natural" to 0.09392f, "Animal" to 0.06532f, "Speech" to 0.06459f,
            "Outside, urban or manmade" to 0.05752f, "Smash, crash" to 0.02784f
        )
        assertEquals("Gunshot, gunfire", named("Gunshot, gunfire", top5))
        assertNull("확정 이름이 위협음이 아니면 이름 없이 알린다", named("Outside, rural or natural", top5))
    }

    @Test
    fun `투표로 위협음이 된 프레임은 상위 후보에서 하한 이상인 첫 위협음을 적는다`() {
        // 1위 Vehicle(환경음)이 확정 이름으로 남았지만, 2·3위 위협음의 합이 더 커서 위협음이 됐다. 둘 다 강한 단서가 아니라
        // 안전 신호로 올라오지 않는다.
        val top5 = top(
            "Vehicle" to 0.30f, "Train whistle" to 0.20f, "Reversing beeps" to 0.15f, "Car" to 0.10f, "Engine" to 0.05f
        )
        assertEquals("Train whistle", named("Vehicle", top5))
    }

    @Test
    fun `하한과 같은 확률의 위협음은 적고 그보다 낮으면 적지 않는다`() {
        val min = DangerAlertPolicy.CANDIDATE_MIN_PROBABILITY
        assertEquals(0.05f, min)
        assertEquals("Bang", named("Vehicle", top("Vehicle" to 0.50f, "Car" to 0.20f, "Bang" to min)))
        assertNull(named("Vehicle", top("Vehicle" to 0.50f, "Car" to 0.20f, "Bang" to 0.049f)))
    }

    @Test
    fun `확정 이름이 비어 있으면 상위 후보만 본다`() {
        assertEquals("Siren", named("", top("Music" to 0.40f, "Siren" to 0.20f)))
        assertNull(named("", top("Music" to 0.40f, "Speech" to 0.20f)))
        assertNull(named("", emptyList()))
    }

    @Test
    fun `위협음에서 뺀 확정 이름으로는 알리지 않는다`() {
        val sirenOff = changed("Siren", AiClassification.AMBIENT)
        // 민방위 사이렌 녹음의 실제 상위 후보(에뮬레이터, #325). 1위 Siren, 2위 Alarm.
        val top5 = top("Siren" to 0.50f, "Alarm" to 0.40f, "Civil defense siren" to 0.30f, "Music" to 0.20f)
        assertEquals("Siren", named("Siren", top5))
        assertEquals("위협음인 다른 후보를 적는다", "Alarm", named("Siren", top5, sirenOff))
        assertNull("그런 후보가 없으면 이름 없이 알린다", named("Siren", top("Siren" to 0.60f, "Music" to 0.20f), sirenOff))
    }

    @Test
    fun `위협음으로 새로 지정한 소리의 이름으로 알린다`() {
        val doorbellOn = changed("Doorbell", AiClassification.DANGER)
        val top5 = top("Music" to 0.40f, "Doorbell" to 0.30f)
        assertNull("기본 종류로는 위협음이 없다", named("Music", top5))
        assertEquals("상위 후보로", "Doorbell", named("Music", top5, doorbellOn))
        assertEquals(
            "확정 이름으로",
            "Doorbell", named("Doorbell", top("Ding-dong" to 0.30f, "Music" to 0.20f), doorbellOn)
        )
    }

    @Test
    fun `확정 이름의 번호를 찾아 그 번호를 돌려준다`() {
        val top5 = top("Alarm" to 0.40f)
        assertEquals(390, DangerAlertPolicy.alertSoundIndex("Siren", top5, defaults) { SoundCatalog.indexOf(entries, it) })
        assertNull(
            "번호를 모르면 다른 소리를 적지 않는다",
            DangerAlertPolicy.alertSoundIndex("Siren", top5, defaults) { null }
        )
    }

    companion object {
        /** 모델의 소리 목록. 알림은 이 번호로 앱 언어의 이름을 꺼낸다. */
        private lateinit var entries: List<SoundEntry>

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val csv = DangerAlertPolicyTest::class.java.classLoader
                ?.getResourceAsStream("ai/yamnet_class_map.csv")
                ?: File("src/main/assets/ai/yamnet_class_map.csv").inputStream()
            entries = SoundCatalog.fromNames(YamnetCoarseClassifier.loadClassNames(csv))
        }
    }
}
