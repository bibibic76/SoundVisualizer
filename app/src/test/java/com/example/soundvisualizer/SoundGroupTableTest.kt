package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 분류 탭의 묶음 표(assets/sound_groups.tsv, #349).
 *
 * 표는 사람이 검토한 데이터라 저장소에 만드는 스크립트가 없다. 지켜야 할 규칙은 여기서 막는다.
 * 묶음마다의 기본 종류는 일부러 보지 않는다. 그것은 AI 의 매핑(가람 님 담당)이 정하고, 매핑이 바뀌어도 화면이 그때그때
 * 나눈다(SoundGroupsTest). 매핑을 고치는 PR 이 이 테스트에 걸리면 안 된다.
 */
class SoundGroupTableTest {

    private val text = SoundGroupFixtures.tableFile.readText(Charsets.UTF_8)

    /** 파일의 줄. 윈도에서는 git 이 CRLF 로 꺼내므로 줄 끝을 가리지 않고 나눈다. */
    private val lines = text.lines().filter { it.isNotEmpty() }
    private val header = lines.takeWhile { it.startsWith("#") }
    private val rows = lines.drop(header.size)
    private val table = SoundGroupFixtures.table

    private fun group(name: String): SoundGroup = table.getValue(name).group
    private fun members(group: SoundGroup) = SoundGroupFixtures.members(group)

    @Test
    fun `머리글에 라이선스와 출처와 바꾼 점을 적는다`() {
        // 온톨로지를 읽고 만든 표라 CC BY-SA 4.0 의 각색물로 본다. 출처, 라이선스와 링크, 바꾼 점을 파일 안에 적는다.
        assertEquals("# SPDX-License-Identifier: CC-BY-SA-4.0", lines.first())
        val joined = header.joinToString("\n")
        listOf(
            "AudioSet Ontology",
            "Google Inc.",
            "https://github.com/audioset/ontology",
            "d417d32",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "the 521 YAMNet sound names were regrouped into everyday groups by SoundVisualizer contributors"
        ).forEach { assertTrue("머리글에 \"$it\" 가 없다", it in joined) }
        assertTrue("# 줄은 맨 위에만 둔다", rows.none { it.startsWith("#") })
    }

    @Test
    fun `모델의 소리 521가지가 한 번씩 들어 있다`() {
        assertEquals(521, rows.size)
        assertTrue("줄마다 묶음과 소리 이름 사이에 탭이 하나다", rows.all { row -> row.count { it == '\t' } == 1 })
        val names = rows.map { it.substringAfter('\t') }
        val twice = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("두 번 나온 소리(한 소리는 한 묶음에만 둔다): $twice", twice.isEmpty())
        assertEquals("모델의 클래스 목록과 같아야 한다", SoundGroupFixtures.names.toSet(), names.toSet())
    }

    @Test
    fun `묶음 이름은 모두 SoundGroup 항목이고 항목은 모두 쓰인다`() {
        val ids = rows.map { it.substringBefore('\t') }.toSet()
        assertEquals(SoundGroup.entries.map { it.id }.toSet(), ids)
        SoundGroup.entries.forEach { assertEquals(it, SoundGroup.fromId(it.id)) }
    }

    @Test
    fun `한 묶음의 줄은 붙어 있고 SoundGroup 순서를 따른다`() {
        val runs = mutableListOf<String>()
        rows.map { it.substringBefore('\t') }.forEach { if (runs.lastOrNull() != it) runs += it }
        assertEquals("한 묶음의 줄이 흩어져 있다: $runs", runs.toSet().size, runs.size)
        assertEquals(SoundGroup.entries.map { it.id }, runs)
    }

    @Test
    fun `읽은 자리는 표의 줄 순서다`() {
        assertEquals(rows.map { it.substringAfter('\t') }, table.keys.toList())
        assertEquals(rows.indices.toList(), table.values.map { it.order })
        assertEquals(rows.map { it.substringBefore('\t') }, table.values.map { it.group.id })
    }

    @Test
    fun `줄 끝이 CRLF 여도 같게 읽고 머리글과 빈 줄은 건너뛴다`() {
        val lf = text.replace("\r\n", "\n")
        assertEquals(table, SoundGroups.parseTable(lf.byteInputStream()))
        assertEquals(table, SoundGroups.parseTable(lf.replace("\n", "\r\n").byteInputStream()))

        val sample = "# SPDX-License-Identifier: CC-BY-SA-4.0\r\n#\r\nsirens\tSiren\r\n\r\n# 가운데 줄\r\nalarms\tAlarm\r\n"
        assertEquals(
            mapOf("Siren" to SoundGroupRow(SoundGroup.SIRENS, 0), "Alarm" to SoundGroupRow(SoundGroup.ALARMS, 1)),
            SoundGroups.parseTable(sample.byteInputStream())
        )
    }

    @Test
    fun `모르는 묶음과 망가진 줄은 건너뛰고 먼저 나온 줄을 쓴다`() {
        // 그런 소리는 표에 없는 것이 되어 제 이름의 카드로 보인다. 탭이 깨지지 않는 쪽이다.
        val sample = "sirens\tSiren\nnope\tAlarm\nno tab here\nalarms\tSiren\nalarms\t\nalarms\tFire alarm\n"
        assertEquals(
            mapOf("Siren" to SoundGroupRow(SoundGroup.SIRENS, 0), "Fire alarm" to SoundGroupRow(SoundGroup.ALARMS, 1)),
            SoundGroups.parseTable(sample.byteInputStream())
        )
    }

    @Test
    fun `묶음 이름은 새로 지은 문구이거나 그 묶음의 소리 이름이다`() {
        for (group in SoundGroup.entries) {
            val sound = group.titleSound
            assertTrue("$group 은 이름이 하나여야 한다", (group.titleRes != 0) != (sound != null))
            if (sound != null) {
                assertEquals("$group 의 이름인 소리가 그 묶음에 없다", group, table[sound]?.group)
            } else {
                assertEquals(
                    "$group 의 이름은 R.string.sound_group_${group.id} 다",
                    R.string::class.java.getField("sound_group_${group.id}").getInt(null),
                    group.titleRes
                )
            }
        }
        assertEquals(
            "새로 지은 묶음 이름은 14개다. 나머지는 이미 번역된 소리 이름을 쓴다",
            setOf(
                SoundGroup.HORNS, SoundGroup.VEHICLE_WARNINGS, SoundGroup.BREAKING, SoundGroup.DISTRESS,
                SoundGroup.SIGNALS, SoundGroup.VOICES, SoundGroup.WATER, SoundGroup.KITCHEN, SoundGroup.APPLIANCES,
                SoundGroup.TOOLS, SoundGroup.NATURE, SoundGroup.BODY, SoundGroup.OBJECTS, SoundGroup.BACKGROUND
            ),
            SoundGroup.entries.filter { it.titleRes != 0 }.toSet()
        )
    }

    @Test
    fun `사이렌 5개와 일반 경보음 3개는 따로 한 묶음씩이다`() {
        assertEquals(
            listOf("Siren", "Ambulance (siren)", "Fire engine, fire truck (siren)", "Police car (siren)", "Civil defense siren"),
            members(SoundGroup.SIRENS)
        )
        assertEquals(listOf("Alarm", "Smoke detector, smoke alarm", "Fire alarm"), members(SoundGroup.ALARMS))
    }

    @Test
    fun `자동차 경적과 트럭 경적과 자동차 경보음은 한 묶음이다`() {
        assertEquals(listOf("Vehicle horn, car horn, honking", "Air horn, truck horn", "Car alarm"), members(SoundGroup.HORNS))
    }

    @Test
    fun `초인종은 노크와 한 묶음이고 사이렌이나 전화와 섞이지 않는다`() {
        assertEquals(listOf("Doorbell", "Ding-dong", "Knock", "Tap"), members(SoundGroup.DOORBELL))
        assertNotEquals(group("Doorbell"), group("Siren"))
        assertNotEquals(group("Doorbell"), group("Telephone"))
    }

    @Test
    fun `함께 들리는 비슷한 소리는 같은 묶음이다`() {
        assertEquals(group("Singing"), group("Choir"))
        assertEquals(group("Baby cry, infant cry"), group("Whimper"))
        assertEquals(group("Music"), group("Musical instrument"))
        assertEquals(group("Music"), group("Guitar"))
        assertEquals(group("Bell"), group("Cowbell"))
    }

    @Test
    fun `기본 종류가 바뀐 소리에 맞춰 묶음을 옮겼다`() {
        // #344 로 위협음이 된 차량 경고음은 경적 바로 뒤에, 쾅 닫는 소리는 유리 깨짐 바로 뒤에 저 혼자 묶음으로 둔다.
        // 어느 구역에 보이는지는 매핑이 정하므로 여기서는 표와 순서만 본다.
        assertEquals(
            listOf(
                "Reversing beeps", "Bicycle bell", "Tire squeal", "Skidding", "Emergency vehicle", "Train horn",
                "Train whistle", "Foghorn"
            ),
            members(SoundGroup.VEHICLE_WARNINGS)
        )
        assertEquals(SoundGroup.HORNS.ordinal + 1, SoundGroup.VEHICLE_WARNINGS.ordinal)
        assertEquals(listOf("Slam"), members(SoundGroup.SLAM))
        assertEquals("전기톱처럼 제 이름이 묶음 이름이다", "Slam", SoundGroup.SLAM.titleSound)
        assertEquals(SoundGroup.BREAKING.ordinal + 1, SoundGroup.SLAM.ordinal)
        assertFalse("Slam" in members(SoundGroup.DOOR))
        // 환경음으로 남은 짧은 경적은 탈것 끝으로, 환경음이 된 싱잉볼은 음악 끝으로 옮겼다.
        assertEquals("Toot", members(SoundGroup.VEHICLE).last())
        assertEquals("Singing bowl", members(SoundGroup.MUSIC).last())
        assertFalse("Singing bowl" in members(SoundGroup.SINGING))
    }

    @Test
    fun `부모가 여럿인 소리 36개는 정해 둔 묶음에 있다`() {
        val moved = MULTI_PARENT.filter { (name, group) -> table[name]?.group != group }
        assertTrue("규칙 D 로 정한 자리에서 옮겼다. 일부러 옮긴 것이면 이 목록도 고친다: $moved", moved.isEmpty())
        assertEquals(36, MULTI_PARENT.size)
    }

    private companion object {
        /**
         * AudioSet 온톨로지(ontology.json, 커밋 d417d32, SHA-256 9c685f44…d15d)에서 부모가 둘 이상인 소리 36개와 그 묶음.
         *
         * 이런 소리는 어느 부모를 따를지 정해야 했다(규칙 D). 28개는 규칙대로, 8개는 뜻으로 정했다(Knock·Tap 은 초인종,
         * Bicycle bell 은 차량 경고음, Air horn 은 경적, Rattle·Hiss 는 물건 소리, Cowbell 은 종, Wind noise (microphone) 는
         * 배경 소리). #344 로 Emergency vehicle 등이 위협음이 된 뒤로는 기계적인 규칙이 사이렌 셋과 자동차 경적·경보음을
         * 차량 경고음 쪽으로 보내지만, 묶음은 그대로 둔다. 온톨로지 파일은 저장소에 두지 않아 목록을 여기에 적는다.
         * 하나를 옮기면 이 목록도 일부러 고쳐야 한다.
         */
        val MULTI_PARENT = mapOf(
            "Children shouting" to SoundGroup.DISTRESS,
            "Choir" to SoundGroup.SINGING,
            "Chant" to SoundGroup.VOICES,
            "Clapping" to SoundGroup.VOICES,
            "Hubbub, speech noise, speech babble" to SoundGroup.CROWD,
            "Howl" to SoundGroup.DOG,
            "Growling" to SoundGroup.DOG,
            "Hiss" to SoundGroup.OBJECTS,
            "Clip-clop" to SoundGroup.ANIMAL,
            "Cowbell" to SoundGroup.BELL,
            "Bleat" to SoundGroup.ANIMAL,
            "Chirp, tweet" to SoundGroup.ANIMAL,
            "Buzz" to SoundGroup.ANIMAL,
            "Rattle" to SoundGroup.OBJECTS,
            "Bell" to SoundGroup.BELL,
            "Bicycle bell" to SoundGroup.VEHICLE_WARNINGS,
            "Beatboxing" to SoundGroup.MUSIC,
            "Wind noise (microphone)" to SoundGroup.BACKGROUND,
            "Crackle" to SoundGroup.FIRE,
            "Vehicle horn, car horn, honking" to SoundGroup.HORNS,
            "Car alarm" to SoundGroup.HORNS,
            "Air horn, truck horn" to SoundGroup.HORNS,
            "Police car (siren)" to SoundGroup.SIRENS,
            "Ambulance (siren)" to SoundGroup.SIRENS,
            "Fire engine, fire truck (siren)" to SoundGroup.SIRENS,
            "Jet engine" to SoundGroup.VEHICLE,
            "Dental drill, dentist's drill" to SoundGroup.TOOLS,
            "Doorbell" to SoundGroup.DOORBELL,
            "Knock" to SoundGroup.DOORBELL,
            "Tap" to SoundGroup.DOORBELL,
            "Squeak" to SoundGroup.DOOR,
            "Tick" to SoundGroup.APPLIANCES,
            "Crack" to SoundGroup.OBJECTS,
            "Squish" to SoundGroup.WATER,
            "Whoosh, swoosh, swish" to SoundGroup.OBJECTS,
            "Thump, thud" to SoundGroup.OBJECTS
        )
    }
}
