package com.example.soundvisualizer.ai

/**
 * YAMNet 521 클래스 → 3분류 키워드 매핑.
 * 키워드 우선순위: danger > speech > ambient > 기본 ambient.
 * 임시 proxy 매핑은 제한된 라벨에만 적용한다.
 */
object YamnetThreeClassMapper {

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
