package com.example.soundvisualizer.ai

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** 스케줄러가 삼킨 예외처럼 로그로만 드러나는 실패를 테스트에서 확인하기 위한 헬퍼. */
object LogcatReader {

    /**
     * executeShellCommand 는 비동기다. 돌려받은 fd 를 읽지 않고 닫아 버리면 `logcat -c` 가
     * 테스트가 한참 진행된 뒤에 끝나면서, 정작 테스트가 보려던 줄까지 지워 버린다.
     * 읽기 경로와 똑같이 끝까지 읽어 명령이 끝난 것을 확인한다 (#49).
     */
    fun clear() {
        drain(shell("logcat -c"))
    }

    /** [tag] 로 남은 WARN 이상 로그를 줄 단위로 돌려준다. */
    fun linesFor(tag: String): List<String> = drain(shell("logcat -d -s $tag:W")).lines()

    private fun drain(fd: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun shell(command: String): ParcelFileDescriptor =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
}
