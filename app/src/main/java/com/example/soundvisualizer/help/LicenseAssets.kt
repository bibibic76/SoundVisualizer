package com.example.soundvisualizer.help

/**
 * 앱에 넣은 오픈소스 고지. 저장소 루트의 NOTICE, LICENSE 를 app/src/main/assets/licenses 에 복사한 파일이다.
 * 원본을 고치면 여기도 복사해야 하고, 둘이 다르면 LicenseAssetsTest 가 실패한다.
 *
 * 테스트가 Compose 코드를 불러오지 않도록 도움말 화면과 따로 둔다.
 */
internal val LICENSE_ASSETS = listOf("licenses/NOTICE.txt", "licenses/LICENSE.txt")

/**
 * 고지 파일을 창에 보여 줄 글로 바꾼다. 줄 끝을 `\n` 으로 맞추고 구분선을 창 폭에 맞춘다(#167).
 *
 * 저장소에는 LF 로 들어 있지만 Windows 에서 받으면(`core.autocrlf`) 작업 폴더가 CRLF 가 되고,
 * 그 폴더에서 빌드한 APK 에 `\r` 이 그대로 들어간다. `\r` 은 한 칸을 차지해서, 창 폭에 꽉 맞춘
 * 구분선 뒤에서 넘쳐 빈 줄을 만들었다.
 */
internal fun licenseDisplayText(raw: String, columns: Int): String =
    fitRulers(raw.replace("\r\n", "\n"), columns)

/**
 * 줄 전체가 `=` 인 구분선을 [columns] 글자까지 줄인다. 다른 줄은 손대지 않는다. 줄 끝은 `\n` 이어야 한다.
 *
 * NOTICE 는 80칸에 맞춰 적혀 있는데 고지 창은 폰에서 한 줄에 35~47자만 들어가서, 구분선 하나가
 * `=` 로 가득 찬 두세 줄로 꺾였다(#167). 창이 80칸보다 넓으면 원래 길이 그대로 둔다.
 * 파일 자체는 고치지 않는다. 저장소에서 읽는 사람에게는 80칸이 맞다.
 */
internal fun fitRulers(text: String, columns: Int): String {
    if (columns < 1) return text
    return text.split('\n').joinToString("\n") { line ->
        if (line.length >= MIN_RULER_LENGTH && line.all { it == '=' } && line.length > columns) {
            "=".repeat(columns)
        } else {
            line
        }
    }
}

/** 이보다 짧은 `=` 줄은 구분선이 아니라 내용으로 본다. */
private const val MIN_RULER_LENGTH = 10
