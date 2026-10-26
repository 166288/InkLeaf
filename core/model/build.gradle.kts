import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:model —— 纯 Kotlin，零 Android 依赖（方案 §4.1 强制约束）
//
// 为什么必须是纯 Kotlin JVM 模块而不是 Android 库：
//   它承载"坐标模型 / 锚点链 / 序列化格式"，是未来唯一需要跨平台共享、
//   也唯一会因为 Android API 污染而难以剥离的部分。
//   保持纯 Kotlin 的直接收益是它可以在 JVM 上 100% 单测，不需要模拟器。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
