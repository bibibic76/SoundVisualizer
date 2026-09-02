$baseDir = $PWD.Path

# 1. Create directories
$dirs = @(
    "app/src/main/java/com/example/soundvisualizer/ui/theme",
    "app/src/main/res/values",
    "app/src/main/res/drawable",
    "app/src/main/res/mipmap-anydpi-v26",
    "gradle/wrapper"
)
foreach ($dir in $dirs) {
    $target = Join-Path $baseDir $dir
    if (-not (Test-Path $target)) {
        New-Item -ItemType Directory -Path $target -Force | Out-Null
    }
}

# 2. settings.gradle.kts
$settingsGradle = @"
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SoundVisualizer"
include(":app")
"@
Set-Content -Path (Join-Path $baseDir "settings.gradle.kts") -Value $settingsGradle -Encoding UTF8

# 3. build.gradle.kts (Project)
$buildGradleProject = @"
plugins {
    id("com.android.application") version "8.1.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}
"@
Set-Content -Path (Join-Path $baseDir "build.gradle.kts") -Value $buildGradleProject -Encoding UTF8

# 4. gradle.properties
$gradleProperties = @"
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
"@
Set-Content -Path (Join-Path $baseDir "gradle.properties") -Value $gradleProperties -Encoding UTF8

# 5. app/build.gradle.kts
$buildGradleApp = @"
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.soundvisualizer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.soundvisualizer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
"@
Set-Content -Path (Join-Path $baseDir "app/build.gradle.kts") -Value $buildGradleApp -Encoding UTF8

# 6. app/src/main/AndroidManifest.xml
$manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.SoundVisualizer"
        tools:targetApi="31">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.SoundVisualizer">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
"@
Set-Content -Path (Join-Path $baseDir "app/src/main/AndroidManifest.xml") -Value $manifest -Encoding UTF8

# 7. app/src/main/res/values/strings.xml
$strings = @"
<resources>
    <string name="app_name">SoundVisualizer</string>
</resources>
"@
Set-Content -Path (Join-Path $baseDir "app/src/main/res/values/strings.xml") -Value $strings -Encoding UTF8

# 8. app/src/main/res/values/themes.xml
$themes = @"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.SoundVisualizer" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
"@
Set-Content -Path (Join-Path $baseDir "app/src/main/res/values/themes.xml") -Value $themes -Encoding UTF8

# 9. xml folders (dummy for manifest errors)
$xmlDir = Join-Path $baseDir "app/src/main/res/xml"
New-Item -ItemType Directory -Path $xmlDir -Force | Out-Null
$backupRules = @"
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="sharedpref" path="."/>
</full-backup-content>
"@
Set-Content -Path (Join-Path $xmlDir "backup_rules.xml") -Value $backupRules -Encoding UTF8

$dataExtraction = @"
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <include domain="sharedpref" path="."/>
    </cloud-backup>
</data-extraction-rules>
"@
Set-Content -Path (Join-Path $xmlDir "data_extraction_rules.xml") -Value $dataExtraction -Encoding UTF8

# 10. MainActivity.kt
$mainActivity = @"
package com.example.soundvisualizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.soundvisualizer.ui.theme.SoundVisualizerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoundVisualizerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Sound Visualizer")
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello `$name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SoundVisualizerTheme {
        Greeting("Android")
    }
}
"@
Set-Content -Path (Join-Path $baseDir "app/src/main/java/com/example/soundvisualizer/MainActivity.kt") -Value $mainActivity -Encoding UTF8

# 11. Theme files
$colorKt = @"
package com.example.soundvisualizer.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
"@
Set-Content -Path (Join-Path $baseDir "app/src/main/java/com/example/soundvisualizer/ui/theme/Color.kt") -Value $colorKt -Encoding UTF8

$typeKt = @"
package com.example.soundvisualizer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)
"@
Set-Content -Path (Join-Path $baseDir "app/src/main/java/com/example/soundvisualizer/ui/theme/Type.kt") -Value $typeKt -Encoding UTF8

$themeKt = @"
package com.example.soundvisualizer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun SoundVisualizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
"@
Set-Content -Path (Join-Path $baseDir "app/src/main/java/com/example/soundvisualizer/ui/theme/Theme.kt") -Value $themeKt -Encoding UTF8

Write-Host "Project skeleton created successfully."
