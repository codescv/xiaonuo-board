import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.xiaonuoboard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.xiaonuoboard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.1.17"
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Keep 1-bit PNG line art byte-exact (no aapt crunching).
        noCompress += listOf("png", "webp")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
        }
    }
}

/*
 * BOOX Onyx SDK transitive dependencies that are statically unreachable from everything this app
 * calls (TouchHelper / RawInputCallback / TouchPoint(List) / EpdController / UpdateMode / Device —
 * verified with a constant-pool class-reference closure over the resolved runtime classpath).
 * They are excluded because they either break the build or only add dead weight:
 *  - com.android.support:* (via easypermissions): duplicate classes with AndroidX core.
 *  - mmkv: 4-ABI native libs (+ a 3rd libc++_shared.so) used only by Onyx's MMKVBuilder.
 *  - onyxsdk-pennative (libneopen_jni.so, ~7 MB stored for 2 ABIs + a 2nd libc++_shared.so) and
 *    onyxsdk-geometry: only used by the Neo brush/shape renderers, never by TouchHelper raw drawing.
 *    onyxsdk-pen's own arm64 libonyx_pen_touch_reader.so + libc++_shared.so remain.
 *  - retrofit / logging-interceptor / zip4j / commons-io / dynamicanimation: Onyx OTA, zip and
 *    scroller utilities; fst's javassist / objenesis / jackson serializers.
 */
configurations.configureEach {
    exclude(group = "com.android.support")
    exclude(group = "pub.devrel", module = "easypermissions")
    exclude(group = "com.tencent", module = "mmkv")
    exclude(group = "com.onyx.android.sdk", module = "onyxsdk-pennative")
    exclude(group = "com.onyx.android.sdk", module = "onyxsdk-geometry")
    exclude(group = "com.squareup.retrofit2", module = "retrofit")
    exclude(group = "com.squareup.okhttp3", module = "logging-interceptor")
    exclude(group = "net.lingala.zip4j", module = "zip4j")
    exclude(group = "commons-io", module = "commons-io")
    exclude(group = "androidx.dynamicanimation", module = "dynamicanimation")
    exclude(group = "org.javassist", module = "javassist")
    exclude(group = "org.objenesis", module = "objenesis")
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.onyxsdk.device)
    implementation(libs.onyxsdk.pen)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.okhttp.mockwebserver)
}
