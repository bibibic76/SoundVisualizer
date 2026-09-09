package com.example.soundvisualizer.ai

/**
 * YAMNet 521 클래스 → 3분류 키워드 매핑.
 * 키워드 우선순위: danger > speech > ambient > 기본 ambient.
 * 이상해 보이는 proxy mapping도 레퍼런스와 동일하게 유지한다.
 */
object YamnetThreeClassMapper {

    fun translateToKorean(displayName: String?): String {
        if (displayName.isNullOrEmpty()) return "알 수 없음"
        val s = displayName.lowercase()

        if ("gunshot" in s || "gunfire" in s) return "총소리"
        if ("machine gun" in s) return "기관총"
        if ("explosion" in s || "폭발" in s) return "폭발음"
        if ("artillery" in s || "fusillade" in s || "cap gun" in s) return "총소리"

        if ("footstep" in s || "footsteps" in s) return "발소리"
        if ("siren" in s || "civil defense" in s) return "사이렌"
        if ("horn" in s) return "경적"
        if ("car" in s || "truck" in s || "bus" in s || "motorcycle" in s) return "자동차"
        if ("helicopter" in s) return "헬리콥터"
        if ("engine" in s || "idling" in s || "accelerating" in s) return "엔진소리"
        if ("alarm" in s || "smoke detector" in s) return "사이렌"
        if ("police car" in s || "ambulance" in s || "fire engine" in s || "fire truck" in s) {
            return "사이렌"
        }

        if (
            "speech" in s || "conversation" in s || "narration" in s ||
            "speaking" in s || "babbling" in s
        ) {
            return "사람 목소리"
        }
        if (
            "shout" in s || "screaming" in s || "yell" in s ||
            "laughter" in s || "crying" in s || "sobbing" in s
        ) {
            return "사람 소리"
        }
        if ("music" in s) return "음악"
        if ("wind" in s || "rustling leaves" in s) return "바람 소리"
        if ("rain" in s || "water" in s || "ocean" in s || "waves" in s) return "비/물 소리"
        if (
            "animal" in s || "dog" in s || "cat" in s ||
            "bird" in s || "bark" in s || "meow" in s
        ) {
            return "동물 소리"
        }
        if ("door" in s || "knock" in s || "slam" in s) return "문 소리"

        return displayName
    }

    fun isGenericSoundEffectLabel(displayName: String?): Boolean {
        if (displayName.isNullOrEmpty()) return false
        return "sound effect" in displayName.lowercase()
    }

    fun mapDisplayNameToCoarse(displayName: String?): String {
        if (displayName.isNullOrEmpty()) return "ambient"
        val s = displayName.lowercase()
        if (matchesDanger(s)) return "danger"
        if (matchesSpeech(s)) return "speech"
        if (matchesAmbient(s)) return "ambient"
        return "ambient"
    }

    private fun matchesTemporaryGunshotProxyDanger(s: String): Boolean {
        if ("plop" in s) return true
        if ("gargling" in s) return true
        if (s == "rain" || "raindrop" in s || "rain on surface" in s) return true
        if ("waterfall" in s) return true
        return false
    }

    private fun matchesDanger(s: String): Boolean {
        if (matchesTemporaryGunshotProxyDanger(s)) return true
        if ("footstep" in s || "footsteps" in s) return true
        if (
            "gunshot" in s || "gunfire" in s || "machine gun" in s ||
            "artillery" in s || "fusillade" in s || "cap gun" in s
        ) {
            return true
        }
        if ("explosion" in s || "fireworks" in s || "firecracker" in s) return true
        if ("civil defense siren" in s) return true
        if ("police car" in s && "siren" in s) return true
        if ("ambulance" in s && "siren" in s) return true
        if ("fire engine" in s || "fire truck" in s) return true
        if ("siren" in s && "telephone" !in s) return true
        if ("smoke detector" in s || "fire alarm" in s) return true
        if (s == "alarm" || "car alarm" in s) return true
        return false
    }

    private fun matchesSpeech(s: String): Boolean {
        if ("speech" in s || "conversation" in s || "narration" in s) return true
        if ("speaking" in s || "babbling" in s) return true
        if ("shout" in s || "whisper" in s || "screaming" in s) return true
        if ("laughter" in s || "crying" in s || "sobbing" in s) return true
        if ("singing" in s || "choir" in s || "rapping" in s) return true
        if ("crowd" in s || "chatter" in s || "hubbub" in s) return true
        if ("children playing" in s) return true
        return false
    }

    private fun matchesAmbient(s: String): Boolean {
        if ("wind" in s || "rustling leaves" in s) return true
        if ("traffic" in s) return true
        if ("music" in s || s.endsWith(" music")) return true
        if ("environmental noise" in s || "field recording" in s) return true
        if ("ocean" in s || "waves" in s || "rain" in s || "thunder" in s) return true
        if ("white noise" in s || "pink noise" in s || "static" in s) return true
        return false
    }
}
