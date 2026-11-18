plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ที่อยู่ของ backend
//   ค่าเริ่มต้น  -> http://localhost:3000/ ใช้คู่กับ `adb reverse tcp:3000 tcp:3000`
//                  (รัน scripts/adb-reverse.ps1) ได้ทั้ง MuMu, emulator และมือถือที่เสียบ USB
//                  และ localhost เป็นที่อยู่เดียวที่ redirect URI ของ KMITL SSO รับ
//   เปลี่ยนที่อยู่ -> ใส่ apiBaseUrl=http://<ที่อยู่>:3000/ ใน ~/.gradle/gradle.properties
//                  หรือรัน gradlew ด้วย -PapiBaseUrl=...
val apiBaseUrl = providers.gradleProperty("apiBaseUrl").orElse("http://localhost:3000/")

// token สำหรับปุ่ม "เข้าแบบทดสอบ" ในบิลด์ debug เท่านั้น (ใช้ดู UI ก่อนทำหน้าเข้าสู่ระบบจริงในเฟส 3)
//   สร้างด้วย `bun scripts/dev-token.ts` ใน backend แล้วรัน gradlew -PdevToken=<token>
//   ค่าเริ่มต้นว่าง = ไม่มีปุ่ม · ห้ามใส่ใน gradle.properties ที่ commit
val devToken = providers.gradleProperty("devToken").orElse("")

android {
    namespace = "com.example.campusmarkettek"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.campusmarkettek"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get()}\"")
        buildConfigField("String", "DEV_TOKEN", "\"${devToken.get()}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle + Navigation
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // WorkManager + Coroutines
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // รูปภาพ / กล้อง / Animation
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.lottie.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
