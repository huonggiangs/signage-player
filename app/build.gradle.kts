plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.anio.signage"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.anio.signage"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.1"
    }

    // Khóa ký CỐ ĐỊNH (app/anio-signage.jks): mọi bản build đều cùng chữ ký nên cài đè lên bản cũ được và ANDROID_ID không đổi.
    // Trước 1.1.1 CI ký bằng khóa debug ngẫu nhiên mỗi lần chạy -> không bao giờ cài đè được. KHÔNG đổi/xóa tệp này.
    signingConfigs {
        create("anio") {
            storeFile = file("anio-signage.jks")
            storePassword = "anio-signage-2026"
            keyAlias = "anio-signage"
            keyPassword = "anio-signage-2026"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("anio")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("anio")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
    packaging { resources { excludes += "META-INF/versions/**" } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
