plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.julien.feature_recording"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Media infrastructure (time mapping, frame assembly, MediaCodec).
    // 必须是 api：RecordingSessionDependencies.timeline 的类型来自 core-media，
    // 用 implementation 会让模块的公开入口对调用方不可用（调用方报
    // "Cannot access class ... RecordingTimeline"）。
    api(project(":core-media"))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Logging
    implementation(libs.timber)

    // Insta360 SDK.
    // 必须是 api：公开 API 里出现了 WorkWrapper（MediaExporter.listLocalWorks、
    // RecordingViewModel.listCameraWorks 的返回类型），用 implementation 同样会让
    // 调用方无法使用这些方法。
    api(libs.inskmp.camera)
    api(libs.inskmp.media)

    // Testing
    testImplementation(libs.junit)
}
