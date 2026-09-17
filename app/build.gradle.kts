plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.soundvisualizer"
    // 어떤 API 로 컴파일할지만 정한다. 최신 androidx(core 1.19, Compose 1.12)가 37 이상을 요구한다.
    // 앱의 동작 규칙은 targetSdk, 설치할 수 있는 폰은 minSdk 가 정한다.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.soundvisualizer"
        // AudioPlaybackCapture(내부 오디오 캡처)는 Android 10(API 29) 이상에서만 동작한다.
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_LD=lld"
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            }
        }
    }

    // 팀 배포용 릴리스 키는 저장소에 두지 않는다. 경로와 비밀번호를 환경 변수(CI 는 Secret)로만 받는다.
    // SV_RELEASE_KEYSTORE 가 비어 있으면 "키가 없는 환경"으로 보고 아래 buildTypes 에서 디버그 키로 물러난다.
    val releaseKeystore = providers.environmentVariable("SV_RELEASE_KEYSTORE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
    // 경로만 있고 파일이나 비밀번호가 빠진 상태는 실수다. 조용히 다른 키로 서명하면
    // 그 APK 가 기존 앱 위에 덮어 설치되지 않는데, 그걸 폰에서야 알게 되면 늦다. 그래서 여기서 멈춘다.
    // (키를 아예 설정하지 않은 사람은 이 검사에 걸리지 않는다.)
    if (releaseKeystore != null) {
        require(releaseKeystore.isFile) {
            "SV_RELEASE_KEYSTORE 가 가리키는 파일이 없습니다: $releaseKeystore"
        }
        for (name in listOf("SV_RELEASE_KEYSTORE_PASSWORD", "SV_RELEASE_KEY_ALIAS", "SV_RELEASE_KEY_PASSWORD")) {
            require(!providers.environmentVariable(name).orNull.isNullOrBlank()) {
                "SV_RELEASE_KEYSTORE 를 설정했으면 $name 도 함께 설정해야 합니다."
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            // CI 는 팀 공용 디버그 키를 풀어 두고 그 경로를 SV_DEBUG_KEYSTORE 로 알려준다. 그래야 CI·릴리스 APK 가
            // 같은 서명이 되어 기존 앱 위에 덮어 설치된다. GitHub 서버에서는 ~/.android/debug.keystore 에 풀어 둬도
            // 빌드 도구가 그 파일을 쓰지 않고 새 키를 만들어서, 기본 위치에 기대지 않고 경로를 직접 넘긴다.
            // 환경 변수가 없는 로컬 빌드는 지금처럼 각자의 기본 디버그 키로 서명된다.
            providers.environmentVariable("SV_DEBUG_KEYSTORE").orNull?.let { storeFile = file(it) }
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("SV_RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SV_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SV_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // 릴리스 키가 없으면 디버그 키로 서명한다. 서명을 아예 빼면 APK 가 설치되지 않아서,
            // 키가 없는 사람은 R8 을 켠 빌드가 폰에서 실제로 도는지 확인할 방법이 사라진다.
            // 그렇게 만든 APK 가 배포로 새지는 않는다. release.yml 은 릴리스 Secret 이 있을 때만
            // 릴리스 APK 를 붙이고, 없으면 지금까지처럼 디버그 APK 를 붙인다.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // dex 가 24MB 까지 커진 가장 큰 이유가 축소를 끈 것이었다. R8 로 쓰지 않는 코드와 리소스를 지운다.
            // 지워지면 안 되는 것(우리 JNI 진입점, ONNX 런타임이 네이티브에서 이름으로 찾는 클래스)은
            // proguard-rules.pro 에 이유와 함께 적어 뒀다. 그 파일을 고치면 릴리스 APK 로 AI 분류를 다시 확인한다.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Kotlin 의 바이트코드 버전도 여기(targetCompatibility)를 따른다. AGP 9 부터 kotlinOptions 블록이 없다.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // .so 를 압축하지 않고 저장해야 AGP 가 16KB 경계에 정렬해 넣는다.
        // (assets 의 onnx/data 무압축은 아래 androidResources.noCompress 가 담당한다.)
        jniLibs {
            useLegacyPackaging = false
        }
    }

    splits {
        // ABI 마다 APK 를 따로 만든다. 폰에는 app-arm64-v8a-*.apk 만 보내면 된다.
        // .so 를 압축하지 않고 넣으므로(위 useLegacyPackaging) ABI 하나가 APK 크기에 그대로 더해지는데,
        // 하나로 합치면 폰에 필요 없는 에뮬레이터용 x86_64 ONNX 런타임까지 따라간다.
        // 32비트 ABI 는 넣지 않는다. 16KB 페이지 기기는 전부 64비트이고, 32비트 ONNX 런타임은 4KB 로만 정렬돼 있다.
        // (ABI 분할과 ndk.abiFilters 는 함께 쓸 수 없어서 ABI 목록은 여기서만 정한다.)
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    bundle {
        // 앱 안에서 언어를 바꾸므로, App Bundle 로 올리더라도 폰 언어 말고 다른 언어의 문구가 빠지지 않게 한다.
        language {
            enableSplit = false
        }
    }

    androidResources {
        // Avoid aapt compression of ONNX external-data companion files
        noCompress += listOf("onnx", "data")
        // res 의 values-* 폴더로 지원 언어 목록(locale config)을 만들어 매니페스트에 넣는다.
        // Android 13 이상의 폰 설정 "앱 언어"에 이 목록이 뜬다. 기본 values 의 언어는 res/resources.properties 에 적는다.
        generateLocaleConfig = true
    }

    lint {
        // 이미 있던 문제는 기준선에 기록해 두고, 새로 생긴 문제만 잡는다.
        // 기준선에 있는 문제를 고쳤으면 lint-baseline.xml 을 지우고 lintDebug 를 한 번 돌려 다시 만든다.
        baseline = file("lint-baseline.xml")
        // 오류만 빌드를 실패시킨다. 경고는 CI 실행 화면에 개수와 위치로만 보인다.
        abortOnError = true
        warningsAsErrors = false
        // 영어·한국어 말고 다른 언어는 번역이 늦어도 영어로 보이므로 경고로만 둔다.
        // 영어(values)와 한국어(values-ko)가 빠짐없는지는 StringResourcesTest 가 막는다.
        warning += "MissingTranslation"
        // XML·HTML 보고서(build/reports/lint-results-debug.*)는 AGP 9 부터 설정 없이 늘 만들어진다. CI 가 그 파일을 읽는다.
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 펼치기·들어가기 화살표(Icons.Default·Icons.AutoMirrored). material3 1.4 부터 따라오지 않아 직접 적는다.
    implementation("androidx.compose.material:material-icons-core")

    // ONNX Runtime Android — loads yamnet.onnx with its yamnet.data external weights as-is
    // 버전을 올리면 추론 결과가 달라질 수 있어 AI 담당이 정한다(#140).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the Android stub is not mocked by default)
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
