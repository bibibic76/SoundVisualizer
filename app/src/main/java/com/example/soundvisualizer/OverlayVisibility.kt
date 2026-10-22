package com.example.soundvisualizer

/**
 * 오버레이 창이 가려졌는지(화면이 꺼졌거나 잠금 화면) 정한다(#401). 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 가려진 동안 오버레이는 쉰다([VisualizerOverlay]).
 * - 잠금 화면은 다른 앱의 오버레이 창을 숨긴다. Galaxy S25+(Android 17)는 그 동안 오버레이가 새 프레임을 요청하면, 잠금을
 *   풀어도 그 요청을 끝내 주지 않아 오버레이가 멈춘 채 남았다. 창이 다시 보인 뒤에 요청하면 바로 그린다.
 * - 화면이 꺼진 동안에도 프레임 신호를 보내는 버전(Android 10)에서는, 보이지 않는 그림을 계속 계산하던 것도 멈춘다(#400).
 *
 * 다시 보이는 것은 잠금 풀림 방송([onUnlocked])에서다. 잠금 화면이 없는 기기도 화면이 켜지면 이 방송이 온다.
 * - 잠금 상태가 '풀림'으로 바뀐 뒤에도 안드로이드가 창을 다시 보이게 하는 데는 조금 걸린다. 그래서 화면이 켜질 때 잠금이
 *   이미 풀려 있어도 바로 다시 보이게 하지 않는다. S25+ 에서 그렇게 일찍 다시 그리게 하면 멈췄다.
 * - 방송을 놓쳐도 쉰 채 남지 않게, 잠금이 풀리기를 기다리는 동안 잠금을 확인한다([onLockCheck]). 두 번 연속 풀려 있을
 *   때만 다시 보이게 한다. 자동 잠금 유예 시간 안에 화면을 다시 켜면 잠금 화면이 뜨지 않아 방송이 오지 않는데, 그때도 이
 *   확인으로 약 2초 뒤에 다시 보인다.
 * - 잠금이 풀려도 창이 숨김으로 남는 기기가 있어, 다시 보일 때마다 창 배치를 다시 요청한다([OverlayService]).
 *
 * 메인 스레드에서만 부른다.
 */
class OverlayVisibility(covered: Boolean, waitingForUnlock: Boolean) {

    /** 지금 가려져 있는지. */
    var covered: Boolean = covered
        private set

    /** 화면은 켜졌고 잠금이 풀리기를 기다리는 중인지. 이 동안만 잠금을 확인한다. */
    var waitingForUnlock: Boolean = waitingForUnlock
        private set

    /** 잠금을 확인했을 때 연달아 풀려 있던 횟수. */
    private var unlockedChecks = 0

    /** 화면이 꺼졌다. */
    fun onScreenOff() {
        covered = true
        waitingForUnlock = false
        unlockedChecks = 0
    }

    /** 화면이 켜졌다. 잠금 풀림 방송이 올 때까지 가려진 채 기다린다. */
    fun onScreenOn() {
        covered = true
        waitingForUnlock = true
        unlockedChecks = 0
    }

    /**
     * 잠금 풀림 방송이 왔다.
     * @return 다시 보이게 해야 하면 true. 이미 보이는 중이어도 true 다. 잠금 풀림이 오면 창 배치를 다시 요청해야 하기 때문이다.
     */
    fun onUnlocked(): Boolean {
        covered = false
        waitingForUnlock = false
        unlockedChecks = 0
        return true
    }

    /**
     * 잠금이 풀리기를 기다리는 동안의 잠금 확인. 방송을 놓쳤을 때를 위한 것이다.
     * @param keyguardLocked 지금 잠금 화면인지
     * @return 지금 다시 보이게 해야 하면 true. 두 번 연달아 풀려 있어야 true 다.
     */
    fun onLockCheck(keyguardLocked: Boolean): Boolean {
        if (!waitingForUnlock) return false
        if (keyguardLocked) {
            unlockedChecks = 0
            return false
        }
        unlockedChecks++
        if (unlockedChecks < UNLOCKED_CHECKS_TO_SHOW) return false
        return onUnlocked()
    }

    companion object {
        /** 잠금 확인에서 이만큼 연달아 풀려 있어야 다시 보이게 한다. 풀리는 도중에 일찍 다시 그리지 않게 한다. */
        const val UNLOCKED_CHECKS_TO_SHOW = 2

        /**
         * 오버레이를 띄우는 순간의 상태. 화면이 꺼졌으면 가려진 것이고, 켜졌어도 잠금 화면이면 잠금이 풀리기를 기다린다.
         * 실행은 앱 화면에서 누르므로 보통은 잠금이 풀린 채 켜진 상태다.
         */
        fun initial(interactive: Boolean, keyguardLocked: Boolean): OverlayVisibility = when {
            !interactive -> OverlayVisibility(covered = true, waitingForUnlock = false)
            keyguardLocked -> OverlayVisibility(covered = true, waitingForUnlock = true)
            else -> OverlayVisibility(covered = false, waitingForUnlock = false)
        }
    }
}
