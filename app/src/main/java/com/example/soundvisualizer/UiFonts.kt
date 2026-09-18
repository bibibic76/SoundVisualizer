package com.example.soundvisualizer

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/**
 * 앱에 넣은 고정폭 글꼴. 글자 수로 줄을 맞추는 곳(개발자 모드 HUD, 라이선스 고지, 제보용 기기 정보)에 쓴다.
 *
 * [FontFamily.Monospace] 를 쓰지 않는 이유: 삼성 One UI 는 시스템 글꼴을 `monospace` 계열까지 덮어써서
 * 고정폭이 아니게 그린다. Galaxy S25+ 에서 `lvl 0.00` 의 글자 간격이 11.5px 에서 19px 까지 들쭉날쭉했다.
 * 같은 폰의 `/system/fonts/DroidSansMono.ttf` 는 AOSP 원본과 똑같은 파일인데도 그랬다(#162).
 * 앱에 들고 다니는 글꼴은 시스템이 덮어쓰지 않는다.
 *
 * 파일은 AOSP `frameworks/base/data/fonts/DroidSansMono.ttf` 를 고치지 않고 그대로 넣었다
 * (SHA-256 `db19a1fd…c862`). 라이선스는 Apache 2.0 이고 NOTICE 에 적어 두었다.
 */
internal val AppMonospace = FontFamily(Font(R.font.droid_sans_mono))
