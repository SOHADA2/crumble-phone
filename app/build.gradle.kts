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
    //   debug 키라 비밀번호는 안드로이드 관례값 그대로다. 저장소가 공개라 이 키도 공개돼 있다.
    //
    //   ⚠️ **이건 이제 '로컬 개발 설치' 전용이다**(2026-09-09부터).
    //      배포판은 위의 `release` 서명(GitHub Secrets 의 전용 키)으로 나간다 —
    //      그래서 이 debug 키가 공개돼 있어도 **배포판을 사칭할 수는 없다.**
    //      여기 남겨 두는 이유는 `dev.ps1 install` 이 컴퓨터를 옮겨 다녀도 같은 서명을 쓰게 하려는 것뿐이다.
    signingConfigs {
        // 배포용 서명. **키는 저장소에 없다** — CI 가 GitHub Secrets(SIGNING_KEYSTORE_B64)에서
        // 파일로 풀고 아래 환경변수로 알려 준다. 로컬에서 시험하려면 같은 이름의 환경변수를 넣으면 된다.
        // ⚠️ 이 키를 잃어버리면 **설치된 앱을 다시는 업데이트할 수 없다**(지우고 새로 깔아야 한다).
        //    사본은 저장소 밖에 보관한다 — 바탕화면 '크럼블_폰봇_서명키' 폴더.
        create("release") {
            val ksPath = System.getenv("SIGNING_STORE_FILE")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storeType = "PKCS12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
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
        release {
            // ⚠️ 난독화는 켜지 않는다 — ML Kit 이 리플렉션을 써서 이름이 바뀌면 깨진다.
            isMinifyEnabled = false
            isDebuggable = false
            val rel = signingConfigs.getByName("release")
            // 키가 없으면(로컬 개발) 서명 없이 만든다 — 배포는 CI 만 한다.
            if (rel.storeFile != null) signingConfig = rel
        }
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
    // 쿠키 이름은 한글이라 별도 인식기가 필요하다. 기본(라틴) 인식기는 한글을 아예 못 읽는다.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
}
