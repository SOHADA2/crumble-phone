plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// 빌드 번호. CI 는 GitHub Actions 의 실행 번호를 넣어 준다(계속 커진다).
// 손으로 빌드하면 1 이라 CI 판보다 낮다 — 그래야 개발 중 빌드가 배포판을 덮어쓰지 않는다.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "com.sohada.crumblephone"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sohada.crumblephone"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // ★ 서명 키를 저장소에 고정해 둔다. 이게 없으면 AGP 가 빌드할 때마다
    //   ~/.android/debug.keystore 를 새로 만드는데, CI 러너는 매번 새 기계라 **매 빌드 서명이 달라진다.**
    //   서명이 다르면 안드로이드가 덮어쓰기 설치를 거부한다("앱이 설치되지 않았습니다") →
    //   앱 안 자동 업데이트가 아예 성립하지 않는다.
    //   debug 키라 비밀번호는 안드로이드 관례값 그대로다. 숨길 것이 없으므로 저장소에 넣는다
    //   (릴리스 저장소는 공개지만 이 키는 비공개인 소스 저장소에만 있다).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 글자 읽기: 모델을 APK 에 넣지 않는 Play 서비스판(앱 크기를 키우지 않는다)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
