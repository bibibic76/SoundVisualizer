package com.example.soundvisualizer

/**
 * "다른 앱 위에 표시" 권한을 켜러 설정 화면으로 보낸 사용자를 위해, 돌아왔을 때 눌렀던 실행을 이어갈지 기억한다.
 * 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 이어가지 않으면 허용하고 돌아와도 "실행 대기 중"이라 실행을 한 번 더 눌러야 한다. 권한 화면을 처음 본
 * 사용자가 그만두기 쉬운 자리다.
 *
 * 다만 [MainActivity.onResume] 은 앱을 열 때마다, 화면을 돌릴 때마다, 타일이나 다른 앱에서 돌아올 때마다
 * 불린다. 사용자가 부탁하지도 않은 실행이 그때마다 켜지면 안 되므로, **사용자가 실행을 눌러 설정 화면으로
 * 보내진 경우에만** 세우고 한 번 쓰면 지운다. 화면 회전으로 다시 만들어져도 살아남게 저장한다.
 *
 * 메인 스레드에서만 부른다.
 */
class PendingStart(pending: Boolean = false) {

    /** 권한 설정 화면에 보내 놓고 이어갈 실행을 기다리는 중인지. */
    var isPending: Boolean = pending
        private set

    /** 사용자가 실행을 눌러 권한 설정 화면으로 보냈다. */
    fun awaitPermission() {
        isPending = true
    }

    /**
     * 사용자가 앱 화면으로 돌아왔다.
     *
     * @param granted 지금 오버레이 권한이 허용돼 있는지
     * @return 기다리던 실행을 지금 이어가야 하면 true
     */
    fun consumeOnResume(granted: Boolean): Boolean {
        if (!isPending) return false
        // 허용하지 않고 돌아왔으면 그만둔 것으로 본다. 그대로 두면 한참 뒤에 다른 일로 앱을 열었을 때
        // 뜬금없이 화면 녹화 동의 창이 뜬다.
        isPending = false
        return granted
    }

    /** 실행을 그만뒀다(실행 종료를 누름). 기다리던 실행도 버린다. */
    fun cancel() {
        isPending = false
    }
}
