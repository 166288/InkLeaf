import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:anchoring —— 纯 Kotlin，零 Android 依赖（方案 §4.1 强制约束）
//
// 这是整个产品最应该被单测覆盖的模块：锚点解析的正确性直接决定
// "用户的笔记会不会跑到别的地方去"，而这个模块可以在 JVM 上跑成毫秒级的测试，
// 完全不需要模拟器。把它的 Android 依赖保持在零，是让这件事成立的前提。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
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
