plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP 9 는 Kotlin 컴파일을 직접 하므로 org.jetbrains.kotlin.android 플러그인을 쓰지 않는다.
    // Compose 컴파일러 플러그인의 버전이 곧 Kotlin 버전이다.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
