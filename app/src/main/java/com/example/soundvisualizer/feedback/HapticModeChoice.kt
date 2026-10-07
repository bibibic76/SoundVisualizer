package com.example.soundvisualizer.feedback

/**
 * 설정 화면의 진동 방식 칸이 외부 사운드 모드에서 무엇을 보여 주고 무엇을 저장할지(#290). 안드로이드에 의존하지 않아
 * JVM 에서 테스트한다.
 *
 * 외부 사운드 모드에서는 '연속'을 고를 수 없고, '연속'으로 저장된 종류는 '빠름'으로 보이고 울린다. 저장값은 바꾸지
 * 않으므로 외부 사운드 모드를 끄면 다시 '연속'이다. 그 사이에 다른 방식을 고르면 그 방식이 저장되어 그대로 남는다.
 */
internal object HapticModeChoice {

    /** 화면에 보이고 미리 울릴 설정. */
    fun shown(stored: HapticSettings, external: Boolean): HapticSettings =
        if (external) stored.inExternalSound() else stored

    /** 이 방식을 고를 수 있는지. 외부 사운드 모드의 '연속'만 고를 수 없다. */
    fun selectable(mode: HapticMode, external: Boolean): Boolean =
        !(external && mode == HapticMode.Continuous)

    /**
     * [tapped] 를 눌렀을 때 저장할 설정. 저장하지 않으면 null 이다. 고를 수 없는 방식이거나 이미 보이는 방식을 다시 누른
     * 경우다. 그래야 '빠름'으로 보이는 '연속'을 눌러도 '연속'이 남는다. 미리보기는 저장과 상관없이 울린다.
     */
    fun toStore(stored: HapticSettings, tapped: HapticMode, external: Boolean): HapticSettings? {
        if (!selectable(tapped, external)) return null
        if (tapped == shown(stored, external).mode) return null
        return stored.copy(mode = tapped)
    }

    /**
     * '연속'을 고를 수 없고 '빠름'으로 울린다는 안내를 보일지. 외부 사운드 모드에서는 저장된 방식과 상관없이 보인다.
     * '연속'으로 저장된 줄만 보이면, 기본값(위협음 '중간')에서 '연속'을 고르려는 사람은 칸이 왜 흐린지 알 수 없다.
     */
    fun showsNote(external: Boolean): Boolean = external
}
