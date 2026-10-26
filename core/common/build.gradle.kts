// :core:common —— 单位换算 / 调度器 / 日志 / 小工具
//
// 说明：Android 模块的 Kotlin 源码放在 src/main/java 下（不是 src/main/kotlin）。
//   理由：AGP 9 的内建 Kotlin 保证 src/main/java 可以被 Kotlin 编译器处理
//   （Android Studio 的 Compose 模板本身就往这里放 .kt），而 src/main/kotlin
//   是否需要额外注册取决于插件行为。这里选"一定成立"的那个，
//   避免出现"模块编译通过但一个源文件都没被编译"这种静默失败。
//   （:core:model / :core:anchoring 用 kotlin-jvm 插件，走标准 src/main/kotlin。）
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.inkleaf.core.common"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
