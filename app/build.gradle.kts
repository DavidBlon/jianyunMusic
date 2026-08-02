import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    FileInputStream(localPropertiesFile).use { localProperties.load(it) }
}

val paidMusicSourceFiles = rootProject.projectDir
    .listFiles()
    .orEmpty()
    .filter { file ->
        file.isFile &&
            file.name.startsWith("lx-music-source-paid-") &&
            file.extension.equals("js", ignoreCase = true)
    }
val paidMusicSourceFile = paidMusicSourceFiles.singleOrNull()
if (paidMusicSourceFiles.size > 1) {
    logger.warn(
        "Multiple lx-music-source-paid-*.js files found; ignoring them. " +
            "Keep one file or configure paidMusicApiUrl explicitly."
    )
}
val paidMusicSourceScript = paidMusicSourceFile?.readText(Charsets.UTF_8).orEmpty()

fun paidMusicScriptConstant(name: String): String? {
    if (paidMusicSourceScript.isBlank()) return null
    val pattern = Regex(
        """(?m)^\s*const\s+${Regex.escape(name)}\s*=\s*["']([^"']*)["']"""
    )
    return pattern.find(paidMusicSourceScript)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

fun buildConfigString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}

val paidMusicApiUrl = nonBlank(providers.gradleProperty("paidMusicApiUrl").orNull)
    ?: nonBlank(providers.environmentVariable("PAID_MUSIC_API_URL").orNull)
    ?: nonBlank(localProperties.getProperty("paidMusicApiUrl"))
    ?: paidMusicScriptConstant("API_URL")
    ?: "https://source.shiqianjiang.cn/api/music"

val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.ncm.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ncm.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.4.0"

        buildConfigField("String", "API_BASE_URL", "\"https://music.163.com/\"")
        buildConfigField("String", "PAID_MUSIC_API_URL", buildConfigString(paidMusicApiUrl))
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lifecycle ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Audio playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Gson
    implementation("com.google.code.gson:gson:2.11.0")

    // Weekly recommendation: Room + serialization
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Weekly recommendation: unit-test support
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.14.1")

    // Markdown rendering for legal and informational content
    implementation("io.noties.markwon:core:4.6.2")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
}
