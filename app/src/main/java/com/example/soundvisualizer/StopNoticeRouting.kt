package com.example.soundvisualizer

/**
 * 뜻하지 않게 꺼졌다는 안내를 보여주려고 홈 탭으로 옮길지 정한다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 안내는 홈 탭에만 그린다. 알림을 꺼 둔 사용자에게는 이 안내가 무엇이 꺼졌는지 아는 유일한 통로라,
 * 설정·도움말 탭에 두고 나갔다 돌아온 사람도 한 번은 보게 해야 한다.
 *
 * 다만 돌아올 때마다 옮기면 안내를 닫기 전까지 보던 탭에 남지 못하고, 설정 탭에서 언어를 바꾸거나
 * 화면을 돌려 액티비티가 다시 만들어질 때도 홈으로 끌려 나온다. 그래서 **안내 하나에 한 번만** 옮긴다.
 *
 * "안내 하나"는 [SettingsManager.lastUnexpectedStopSeq] 번호로 구분한다. 안내를 닫았는지, 다시 켜서 지워졌는지를
 * 액티비티가 지켜보지 않아도 되고, 같은 이유로 다시 꺼져도 새 안내로 친다.
 *
 * 메인 스레드에서만 부른다.
 */
class StopNoticeRouting(routedSeq: Int = NONE) {

    /** 마지막으로 홈 탭으로 옮긴 안내 번호. 화면이 다시 만들어져도 유지되도록 저장한다. */
    var routedSeq: Int = routedSeq
        private set

    /**
     * [noticeSeq] 안내 때문에 지금 홈 탭으로 옮겨야 하는지. 안내가 없으면 null 을 넘긴다.
     * 한 번 옮긴 안내는 다시 옮기지 않는다.
     */
    fun shouldShowOnHome(noticeSeq: Int?): Boolean {
        if (noticeSeq == null || noticeSeq == routedSeq) return false
        routedSeq = noticeSeq
        return true
    }

    /**
     * 빠른 설정 타일을 길게 눌러 설정 탭으로 들어온 경우. 지금 안내는 옮긴 것으로 쳐 둔다.
     *
     * 사용자가 설정을 열러 온 것이므로 그 안내로는 다시 끌어내지 않는다. 다음에 또 꺼지면 새 번호가 되어 그때 옮긴다.
     */
    fun skipCurrent(noticeSeq: Int) {
        routedSeq = noticeSeq
    }

    companion object {
        /** 아직 아무 안내도 옮기지 않음. 안내 번호는 0 부터 올라간다. */
        const val NONE = -1
    }
}
