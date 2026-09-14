plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.soundvisualizer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.soundvisualizer"
        // AudioPlaybackCapture(내부 오디오 캡처)는 Android 10(API 29) 이상에서만 동작한다.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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

    androidResources {
        // Avoid aapt compression of ONNX external-data companion files
        noCompress += listOf("onnx", "data")
    }

    lint {
        // 이미 있던 문제는 기준선에 기록해 두고, 새로 생긴 문제만 잡는다.
        // 기준선에 있는 문제를 고쳤으면 lint-baseline.xml 을 지우고 lintDebug 를 한 번 돌려 다시 만든다.
        baseline = file("lint-baseline.xml")
        // 오류만 빌드를 실패시킨다. 경고는 CI 실행 화면에 개수와 위치로만 보인다.
        abortOnError = true
        warningsAsErrors = false
        xmlReport = true
        htmlReport = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // ONNX Runtime Android — loads yamnet.onnx with its yamnet.data external weights as-is
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the Android stub is not mocked by default)
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
