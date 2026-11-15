// 根构建脚本：只声明插件与跨模块的测试策略，不在这里放任何依赖
// （依赖一律走 gradle/libs.versions.toml）
//
// 注意这里**没有** org.jetbrains.kotlin.android：AGP 9.0 起 Kotlin 支持内建，
// 应用该插件会直接构建失败。Android 模块不再声明 Kotlin 插件。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ── Robolectric 的共享测试策略：只作用于真正跑 Robolectric 的两个模块 ─────────
//
// 放在根脚本里而不是各模块里，是因为两个坑都是"踩一次要花半小时"的坑，
// 复制粘贴必然会漏掉其中一个。
//
// 坑一：android-all 运行时 jar（SDK 36 约 200MB）默认在**测试运行时**才去
//       Maven Central 下载，不是构建时。本机实测该通道 118KB/s（约 29 分钟），
//       而且这 29 分钟里没有任何输出 —— 与"测试卡死"在现象上无法区分
//       （开发过程中确实因此白等了一次 30 分钟的构建超时）。
//       因此钉死离线目录：robolectric.offline + robolectric.dependency.dir，
//       jar 由 tools/fetch-robolectric-deps.ps1 从阿里云镜像预取。
//
// 坑二：JDK 17 起 jdk.internal.* 被强封装，而 Robolectric 的
//       FileDescriptorInterceptor 需要读 FileDescriptor 里的 fd
//       （ApplicationSharedMemory 初始化时会用到）。不补 --add-opens 的话，
//       失败点在 AndroidTestEnvironment.setUpApplicationState，
//       现象是**每一个用例都失败**，栈里全是 Robolectric 内部帧，
//       非常容易被误判成"自己的代码有问题"。
val robolectricModules = setOf(":core:data", ":core:document", ":core:ink", ":app")
val robolectricDepsDir = rootProject.layout.buildDirectory.dir("robolectric-deps").get().asFile

/**
 * 是否强制走**本地预取**的 android-all jar。
 *
 * 默认 true（本机唯一能稳定跑测试的方式，理由见上面的"坑一"）。
 * CI 上反过来：runner 没有那份预取目录，但它的出口能直连 Maven Central，
 * 让 Robolectric 按需下载反而是最省事的。用 `-Pinkleaf.robolectricOffline=false`
 * 切换 —— 一个开关而不是两套构建脚本，因为"测试怎么跑"必须只有一处定义。
 */
val robolectricOffline =
    (findProperty("inkleaf.robolectricOffline") as String?)?.toBoolean() ?: true

subprojects {
    if (path !in robolectricModules) return@subprojects

    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.offline", robolectricOffline.toString())
        systemProperty("robolectric.dependency.dir", robolectricDepsDir.absolutePath)

        jvmArgs(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.util=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        )

        // 只有走离线模式时才检查预取目录。CI 用 -Pinkleaf.robolectricOffline=false
        // 让 Robolectric 自己下载，那里本来就没有（也不该有）这份预取 jar。
        // 少这个 if 的话，CI 会在 doFirst 里被 check() 拦下，报一个与本机完全相同
        // 的"缺少 android-all jar"，而解决方式是相反的（那边不需要预取）。
        if (robolectricOffline) {
            doFirst {
                check(robolectricDepsDir.listFiles()?.any { it.name.startsWith("android-all") } == true) {
                    "缺少 Robolectric 的 android-all jar（$robolectricDepsDir）。\n" +
                        "不预取的话测试会去联网下载：实测约 29 分钟，且期间没有任何输出。\n" +
                        "请先执行： powershell -File tools/fetch-robolectric-deps.ps1\n" +
                        "（CI 上没有这份目录，请改用 -Pinkleaf.robolectricOffline=false）"
                }
            }
        }
    }
}
