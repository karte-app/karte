plugins {
    id("com.android.application")
}

android {
    namespace = "com.karte.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.karte.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Pleos Connect 에뮬레이터(Apple Silicon: arm64-v8a, Windows/Linux: x86_64)와 실차 대상 ABI만 포함
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // CarPropertyManager(차속), CarUxRestrictionsManager(주행 규제)
    useLibrary("android.car")

    signingConfigs {
        create("release") {
            // 공모전 제출용 서명 키입니다. 실제 배포에는 사용하지 않습니다.
            storeFile = rootProject.file("keystore/karte-contest.jks")
            storePassword = "karte-contest"
            keyAlias = "karte"
            keyPassword = "karte-contest"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    testImplementation("junit:junit:4.13.2")
}
