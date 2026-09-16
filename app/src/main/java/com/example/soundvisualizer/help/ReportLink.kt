package com.example.soundvisualizer.help

import java.net.URLEncoder

/**
 * 제보용 GitHub 이슈 링크와 환경 정보 문구를 만든다.
 *
 * 기기 API 없이 문자열만 다루므로 유닛 테스트로 고정한다. 넣는 값은 [environment] 의 인자뿐이라
 * 오디오나 개인정보가 섞일 수 없다. 환경 정보의 이름표는 번역하지 않는다. 제보를 읽는 쪽이
 * 어떤 언어로 온 제보든 같은 형태로 읽을 수 있어야 한다.
 */
object ReportLink {

    const val ISSUES_URL = "https://github.com/bibibic76/SoundVisualizer/issues/new"

    /** 이슈 본문 끝에 붙는 네 줄. 사용자가 쓴 내용과 구분되게 앞에 빈 줄을 두고 붙인다. */
    fun environment(
        appVersion: String,
        androidRelease: String,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        language: String
    ): String = listOf(
        "App version: $appVersion",
        "Android: $androidRelease (API $sdkInt)",
        "Device: $manufacturer $model",
        "App language: $language"
    ).joinToString(separator = "\n")

    /**
     * 제목과 본문을 미리 채운 새 이슈 주소.
     *
     * GitHub 은 title/body 쿼리로 새 이슈 화면을 채워 준다. 값은 모두 인코딩하므로
     * 사용자가 쓸 자리에 줄바꿈이나 한글이 있어도 주소가 깨지지 않는다.
     */
    fun issueUrl(title: String, bodyPrompt: String, environment: String): String =
        "$ISSUES_URL?title=${encode(title)}&body=${encode(body(bodyPrompt, environment))}"

    /** 사용자가 쓸 자리와 환경 정보를 합친 제보 본문. GitHub 이슈와 메일이 같은 본문을 쓴다. */
    fun body(bodyPrompt: String, environment: String): String =
        if (bodyPrompt.isEmpty()) environment else "$bodyPrompt\n\n$environment"

    /**
     * 제목과 본문을 채운 mailto 주소.
     *
     * Gmail 은 인텐트로 따로 넘긴 제목·본문을 무시하고 이 주소만 읽는다(기기에서 확인).
     * mailto 쿼리에서는 `+` 가 공백이 아니라 글자 그대로라서 공백을 %20 으로 바꾼다.
     */
    fun mailtoUri(address: String, subject: String, bodyPrompt: String, environment: String): String =
        "mailto:$address?subject=${encodeForMailto(subject)}&body=${encodeForMailto(body(bodyPrompt, environment))}"

    private fun encodeForMailto(value: String): String = encode(value).replace("+", "%20")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
