package com.example.soundvisualizer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 분류 탭의 색인(#368). 지금 앱 언어로 한 번 만들어 두고(앱 언어 하나의 것만 둔다), 찾는 결과는 탭이 그릴 때마다
 * 만들던 때와 같아야 한다.
 *
 * 색인은 프로세스에 하나라 테스트마다 다른 언어 키를 쓴다. 키는 앱에서는 LocaleList.toLanguageTags() 의 값이다.
 * 리소스 대신 실제 문구 파일(values, values-ko)을 읽어 앱처럼 풀어 넘긴다.
 */
class SoundIndexTest {

    private val entries = SoundGroupFixtures.entries()
    private val english = SoundGroupFixtures.strings("values")

    /** 새로 지은 묶음 이름의 문구 id 와 문구 이름. */
    private val titleNames = SoundGroup.entries.filter { it.titleRes != 0 }.associate { it.titleRes to "sound_group_${it.id}" }

    /** [dir] 언어의 문구. 앱처럼 그 언어에 없는 문구는 기본 문구(영어)로 풀린다. */
    private fun text(dir: String): (String) -> String {
        val local = SoundGroupFixtures.strings(dir)
        return { name -> local[name] ?: english.getValue(name) }
    }

    /** 리소스의 sound_names 배열처럼 [dir] 언어의 소리 이름을 번호 순서로 늘어놓는다. */
    private fun labels(dir: String): List<String> = text(dir).let { t -> entries.indices.map { t("sound_name_$it") } }

    /** 앱(SoundCatalog.loadIndex)이 넘기는 것과 같은 값으로 [dir] 언어의 색인을 만든다. */
    private fun build(key: String, dir: String, labels: List<String> = labels(dir)): SoundIndex {
        val t = text(dir)
        return SoundCatalog.buildIndex(
            key,
            entries,
            labels,
            titleLocal = { t(titleNames.getValue(it)) },
            titleEnglish = { english.getValue(titleNames.getValue(it)) }
        )
    }

    /**
     * #368 전의 분류 탭이 그릴 때마다 만들던 값. 그때의 ClassifyTab 을 그대로 옮겼다(이름 배열, 소리마다의 찾기 값,
     * 묶음, 영어로 맞춘 리소스로 만든 묶음 이름 값).
     */
    private inner class Before(dir: String, array: Array<String>) {
        private val t = text(dir)
        val labelOf = { entry: SoundEntry -> array.getOrElse(entry.index) { entry.name } }
        val searchKeys = entries.map { SoundCatalog.searchKey(labelOf(it), it.name) }
        val sections = SoundGroups.sections(entries)
        val titleKeys = SoundGroups.titleKeys(
            local = { t(titleNames.getValue(it)) },
            english = { english.getValue(titleNames.getValue(it)) }
        )
    }

    @Test
    fun `색인은 언어마다 한 번만 만들고 다시 쓴다`() {
        val key = "ko-KR"
        var builds = 0
        val first = SoundCatalog.index(key) { builds++; build(key, "values-ko") }
        assertSame(first, SoundCatalog.cachedIndexOrNull(key))
        // 탭에 다시 들어오거나 페이저가 탭을 미리 만들 때다.
        repeat(3) {
            assertSame(first, SoundCatalog.index(key) { builds++; build(key, "values-ko") })
        }
        assertEquals("이름 521개를 읽고 정규화를 1,042번 하는 일은 한 번이다", 1, builds)
    }

    @Test
    fun `언어가 다르면 색인을 따로 만들고 다른 언어의 것을 쓰지 않는다`() {
        val ko = SoundCatalog.index("ko") { build("ko", "values-ko") }
        assertNull("영어로는 아직 만들지 않았다", SoundCatalog.cachedIndexOrNull("en-US"))
        var builds = 0
        val en = SoundCatalog.index("en-US") { builds++; build("en-US", "values") }
        assertEquals(1, builds)
        assertNotSame(ko, en)
        assertSame(en, SoundCatalog.cachedIndexOrNull("en-US"))

        val siren = entries.first { it.name == "Siren" }
        assertEquals("사이렌", ko.labelOf(siren))
        assertEquals("Siren", en.labelOf(siren))
        val kitchen = SoundGroup.KITCHEN
        assertTrue("한국어 색인은 한국어 묶음 이름으로 찾는다", SoundCatalog.normalize("부엌") in ko.titleKeys.getValue(kitchen))
        assertTrue("영어 색인에는 한국어 묶음 이름이 없다", SoundCatalog.normalize("부엌") !in en.titleKeys.getValue(kitchen))
    }

    @Test
    fun `색인으로 찾은 결과는 탭이 그릴 때마다 만들던 때와 같다`() {
        // SoundGroupSearchTest 와 SoundCatalogTest 가 보는 찾는 말들이다. 한국어·영어 소리 이름, 두 언어의 묶음 이름,
        // 문장 부호와 띄어쓰기, 한글을 치는 중의 모양, 맞지 않는 말.
        val queries = listOf(
            "", "  ", "사이렌", "노크", "경적", "초인종", "부엌", "부엌 요리", "부엌·요리", "경적 자동차", "TV 라디오",
            "tv radio", "kitchen", "Kitchen and cooking", "knock", "Siren", "SIRENE", "firetruck",
            "fire engine fire truck siren", "Fire engine, fire truck (siren)", "civil defense", "door",
            "ㅅ", "상", "사일", "홪", "화재 경봉", "닭 울", "노크 소리knock", "비rain", "zzzz"
        )
        val overridesList = listOf(
            emptyMap(),
            mapOf("Doorbell" to AiClassification.DANGER, "Siren" to AiClassification.AMBIENT)
        )
        // 이름 배열이 소리 수보다 짧은 경우(모델이 바뀐 직후 등)도 예전처럼 모델 이름으로 찾는다.
        val cases = listOf(
            Triple("values-ko", "ko", labels("values-ko")),
            Triple("values", "en", labels("values")),
            Triple("values-ko", "ko-short", labels("values-ko").take(100))
        )
        for ((dir, key, labels) in cases) {
            val before = Before(dir, labels.toTypedArray())
            val index = build(key, dir, labels)
            assertSame(entries, index.entries)
            assertEquals(key, entries.map(before.labelOf), entries.map(index::labelOf))
            assertEquals(key, before.searchKeys, index.searchKeys)
            assertEquals(key, before.sections, index.sections)
            assertEquals(key, before.titleKeys, index.titleKeys)
            for (overrides in overridesList) {
                for (filter in SoundFilter.entries) {
                    for (query in queries) {
                        assertEquals(
                            "$key \"$query\" $filter $overrides",
                            SoundGroups.filter(before.sections, before.searchKeys, before.titleKeys, overrides, query, filter),
                            SoundGroups.filter(index.sections, index.searchKeys, index.titleKeys, overrides, query, filter)
                        )
                    }
                }
            }
        }

        // 위의 비교가 빈 결과끼리만 맞춘 것이 아니다.
        val ko = build("ko", "values-ko")
        fun groups(query: String) = SoundGroups.filter(ko.sections, ko.searchKeys, ko.titleKeys, emptyMap(), query, SoundFilter.ALL)
            .flatMap { section -> section.cards.mapNotNull { it.card.group } }
        assertEquals(listOf(SoundGroup.KITCHEN), groups("부엌 요리"))
        assertEquals(listOf(SoundGroup.KITCHEN), groups("Kitchen and cooking"))
        assertEquals(listOf(SoundGroup.BACKGROUND), groups("tv radio"))
        assertEquals(listOf(SoundGroup.SIRENS), groups("fire engine fire truck siren"))
    }

    @Test
    fun `만드는 중에 탭이 버려져도 다 만든 색인이 남고 다시 만든 탭은 그것을 기다려 쓴다`() = runBlocking {
        val key = "ko-KR,en-US"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builds = AtomicInteger()
        // 탭이 하는 대로 탭의 코루틴이 Dispatchers.Default 로 넘어가 색인을 만든다. 페이저가 미리 만든 탭이다.
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.Default) {
                SoundCatalog.index(key) {
                    builds.incrementAndGet()
                    started.countDown()
                    // 기다림이 실패해도 테스트가 멈추지 않게 끝을 둔다.
                    release.await(30, TimeUnit.SECONDS)
                    build(key, "values-ko")
                }
            }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        // 만드는 중에 옆으로 넘겨 탭이 버려진다.
        first.cancel()

        // 곧 탭이 다시 만들어져 색인을 찾는다. 앞의 것이 끝나기를 기다려야 한다(따로 만들기 시작하면 안 된다).
        var second: SoundIndex? = null
        val waiter = thread(isDaemon = true) {
            second = SoundCatalog.index(key) { builds.incrementAndGet(); build(key, "values-ko") }
        }
        // 확인이 실패해도 앞의 것을 풀어 준다. 풀지 않으면 앞의 것이 자물쇠를 쥔 채 남아 runBlocking 이 끝나지 않는다.
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (waiter.state != Thread.State.BLOCKED && builds.get() < 2) {
                assertTrue("뒤의 탭이 색인을 찾으러 오지 않았다", System.nanoTime() < deadline)
                Thread.sleep(1)
            }
        } finally {
            release.countDown()
        }
        first.join()
        waiter.join(TimeUnit.SECONDS.toMillis(30))

        assertTrue(first.isCancelled)
        val cached = SoundCatalog.cachedIndexOrNull(key)
        assertNotNull("취소된 쪽이 만든 색인을 버렸다", cached)
        assertSame("다시 만든 탭은 앞에서 만든 색인을 쓴다", cached, second)
        assertEquals("색인은 한 번만 만든다", 1, builds.get())
    }
}
