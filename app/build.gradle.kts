plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }

if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    error("Set all Android release signing environment variables or leave them all unset.")
}

if (releaseSigningConfigured && !file(requireNotNull(releaseStoreFile)).isFile) {
    error("The Android release keystore file does not exist.")
}

val versionCodeValue = providers.gradleProperty("VERSION_CODE").orNull?.toInt()
    ?: 1
val versionNameValue = providers.gradleProperty("VERSION_NAME").orNull ?: "1.0"

android {
    namespace = "com.myproductivity.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myproductivity.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = versionNameValue
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Encrypts the API URL + token at rest (Android Keystore backed)
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
}

val copyDailyTrackerDebugApk = tasks.register("copyDailyTrackerDebugApk") {
    val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val dailyTrackerApk = layout.buildDirectory.file("outputs/daily-tracker/Daily Tracker.apk")
    inputs.file(debugApk)
    outputs.file(dailyTrackerApk)
    doLast {
        debugApk.get().asFile.copyTo(dailyTrackerApk.get().asFile, overwrite = true)
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        check(releaseSigningConfigured) {
            "Configure all Android release signing environment variables before building a Play Store bundle."
        }
    }
}

tasks.configureEach {
    if (name == "bundleRelease") {
        dependsOn(verifyReleaseSigning)
    }
    if (name == "assembleDebug") {
        finalizedBy(copyDailyTrackerDebugApk)
    }
}
