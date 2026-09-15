package com.example.soundvisualizer.ai

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** 스케줄러가 삼킨 예외처럼 로그로만 드러나는 실패를 테스트에서 확인하기 위한 헬퍼. */
object LogcatReader {

    fun clear() {
        shell("logcat -c").close()
    }

    /** [tag] 로 남은 WARN 이상 로그를 줄 단위로 돌려준다. */
    fun linesFor(tag: String): List<String> {
        val fd = shell("logcat -d -s $tag:W")
        val text = ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return text.lines()
    }

    private fun shell(command: String): ParcelFileDescriptor =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
}
