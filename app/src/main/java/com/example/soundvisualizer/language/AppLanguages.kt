package com.example.soundvisualizer.language

/**
 * 앱이 지원하는 언어 하나.
 *
 * @param tag 앱 언어로 저장하고 적용하는 BCP 47 태그 (예: "zh-CN")
 * @param resourceDir 이 언어의 strings.xml 이 있는 res 아래 폴더 이름. 영어는 기본 폴더 "values" 다.
 * @param endonym 언어 선택 창에 보이는 그 언어 스스로의 이름. 앱 언어와 상관없이 번역하지 않는다.
 */
data class SupportedLanguage(
    val tag: String,
    val resourceDir: String,
    val endonym: String
)

/**
 * 지원 언어 목록. 설정의 언어 선택 창이 이 순서대로 보여준다.
 *
 * 안드로이드 코드를 쓰지 않아서 JVM 유닛 테스트(StringResourcesTest)가 그대로 읽는다.
 * 언어를 추가할 때는 res/values-xx/strings.xml 과 함께 여기에도 넣는다. 둘이 어긋나면 테스트가 실패한다.
 */
object AppLanguages {

    /** 기본 폴더(values)의 언어. res/resources.properties 의 unqualifiedResLocale 과 같은 언어다. */
    const val DEFAULT_TAG = "en"

    val all: List<SupportedLanguage> = listOf(
        SupportedLanguage("en", "values", "English"),
        SupportedLanguage("ko", "values-ko", "한국어"),
        SupportedLanguage("ja", "values-ja", "日本語"),
        SupportedLanguage("zh-CN", "values-zh-rCN", "简体中文"),
        SupportedLanguage("zh-TW", "values-zh-rTW", "繁體中文"),
        SupportedLanguage("es", "values-es", "Español"),
        SupportedLanguage("pt-BR", "values-pt-rBR", "Português (Brasil)"),
        SupportedLanguage("fr", "values-fr", "Français"),
        SupportedLanguage("de", "values-de", "Deutsch"),
        SupportedLanguage("ru", "values-ru", "Русский"),
        SupportedLanguage("it", "values-it", "Italiano"),
        SupportedLanguage("vi", "values-vi", "Tiếng Việt"),
        SupportedLanguage("th", "values-th", "ไทย"),
        // 안드로이드 리소스 폴더는 인도네시아어에 옛 코드 in 을 쓴다.
        SupportedLanguage("id", "values-in", "Bahasa Indonesia"),
        SupportedLanguage("tr", "values-tr", "Türkçe"),
        SupportedLanguage("pl", "values-pl", "Polski"),
        SupportedLanguage("hi", "values-hi", "हिन्दी"),
        SupportedLanguage("ar", "values-ar", "العربية")
    )

    /**
     * 기기나 폰 설정에서 온 언어 태그를 지원 언어 중 하나로 맞춘다. 맞는 언어가 없거나 태그가 비었으면 null.
     *
     * - 대소문자와 구분자("-", "_")는 가리지 않는다.
     * - 중국어는 번체 문자(Hant)이거나 대만·홍콩·마카오면 번체, 그 밖에는 간체로 본다. 간체 문자(Hans)가 적혀 있으면 간체다.
     * - 그 밖의 언어는 언어 부분만 같으면 같은 언어로 본다. (en-US → en, pt-PT → pt-BR, in → id)
     */
    fun match(tag: String?): SupportedLanguage? {
        if (tag.isNullOrBlank()) return null
        val parts = tag.trim().split('-', '_').filter { it.isNotEmpty() }.map { it.lowercase() }
        if (parts.isEmpty()) return null
        val language = LEGACY_CODES[parts[0]] ?: parts[0]
        val rest = parts.drop(1)

        if (language == "zh") {
            val traditional = "hans" !in rest && ("hant" in rest || rest.any { it in TRADITIONAL_CHINESE_REGIONS })
            return byTag(if (traditional) "zh-TW" else "zh-CN")
        }
        return all.firstOrNull { it.tag.substringBefore('-').lowercase() == language }
    }

    /** 목록에 있는 태그면 그 언어, 없으면 null. 대소문자는 가리지 않는다. */
    fun byTag(tag: String): SupportedLanguage? = all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    /** 자바 Locale 이 아직 쓰는 옛 언어 코드. */
    private val LEGACY_CODES = mapOf("in" to "id", "iw" to "he", "ji" to "yi")

    private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")
}
