plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.sohada.crumblephone"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sohada.crumblephone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-spike"
    }
    buildTypes {
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
