package com.example.soundvisualizer.feedback

/**
 * 설정 화면의 진동 방식 칸이 외부 사운드 모드에서 무엇을 보여 주고 무엇을 저장할지(#290, #354). 안드로이드에 의존하지 않아
 * JVM 에서 테스트한다.
 *
 * 외부 사운드 모드에서는 종류마다 상한([HapticSettings.externalCap])까지만 고를 수 있고, 상한보다 빠르게 저장된 종류는
 * 상한으로 보이고 울린다. 저장값은 바꾸지 않으므로 외부 사운드 모드를 끄면 정해 둔 대로다. 그 사이에 고를 수 있는 다른
 * 방식을 고르면 그 방식이 저장되어 그대로 남는다.
 */
internal object HapticModeChoice {

    /** [label] 줄에 보이고 미리 울릴 설정. 외부 사운드 모드에서는 실제로 울릴 설정([HapticSettings.inExternalSound])이다. */
    fun shown(stored: HapticSettings, label: String, external: Boolean): HapticSettings =
        if (external) stored.inExternalSound(label) else stored

    /** [label] 줄에서 이 방식을 고를 수 있는지. 외부 사운드 모드에서는 그 종류의 상한보다 빠른 방식을 고를 수 없다. */
    fun selectable(mode: HapticMode, label: String, external: Boolean): Boolean =
        !external || mode <= HapticSettings.externalCap(label)

    /**
     * [tapped] 를 눌렀을 때 저장할 설정. 저장하지 않으면 null 이다. 고를 수 없는 방식이거나 이미 보이는 방식을 다시 누른
     * 경우다. 그래야 상한으로 보이는 더 빠른 방식(예: '느림'으로 보이는 대화음의 '빠름')을 눌러도 정해 둔 방식이 남는다.
     * 미리보기는 저장과 상관없이 울린다.
     */
    fun toStore(stored: HapticSettings, tapped: HapticMode, label: String, external: Boolean): HapticSettings? {
        if (!selectable(tapped, label, external)) return null
        if (tapped == shown(stored, label, external).mode) return null
        return stored.copy(mode = tapped)
    }

    /**
     * 상한 안내(상한이 꺼짐인 환경음은 진동하지 않는다는 안내)를 보일지. 외부 사운드 모드에서는 저장된 방식과 상관없이
     * 보인다. 상한보다 빠르게 저장된 줄에만 보이면, 기본값(위협음 '중간')에서 더 빠른 방식을 고르려는 사람은 칸이 왜 흐린지
     * 알 수 없다.
     */
    fun showsNote(external: Boolean): Boolean = external
}
