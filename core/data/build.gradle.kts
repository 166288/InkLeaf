// :core:data —— 持久层（手写 SQLite）+ 仓储 + SAF 导入 + 存档编解码
//
// ★ 与方案 §7.2 的有意偏差：这里不用 Room。
//   原因见 gradle/libs.versions.toml 的 [versions] 尾注：
//   AGP 9 内建 Kotlin 无 KAPT，而 KSP 最新发布版（2.3.12）是针对 Kotlin 2.3.20 的，
//   本工程 Kotlin 被 AGP 钉在 2.4.21 → 不存在可用的注解处理链路。
//   已记入 design/方案设计.md §7.2 的修订记录。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.inkleaf.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:model"))
    // api 而不是 implementation：:core:data 的公开签名里出现 DocumentSession / TextBlock
    // （阅读器要直接拿排版结果），消费方必须能看见这些类型。
    api(project(":core:document"))
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // 设备端路径保留依赖：同一份断言换个 Runner 就能搬回 androidTest。
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
