// 注意：这里必须显式 import，不能写成 java.util.Properties()。
// 在 `android { }` 块里 `java` 会被解析成别的接收者（不是包名），
// 于是整段签名配置报 "Unresolved reference: util" —— 这类错误只在
// 真正配置工程时暴露，`--dry-run` 也看不出来。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // 注意：这里**不**应用 org.jetbrains.kotlin.android。
    // AGP 9.0 起 Kotlin 支持已内建（built-in Kotlin，默认开启），
    // 再应用该插件会直接构建失败。KGP 会随 AGP 进入 classpath，
    // 版本由 :core:model / :core:anchoring 上的 kotlin-jvm(2.4.21) 提升到 2.4.21。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.inkleaf.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    /**
     * release 签名。
     *
     * 材料在仓库根目录的 `keystore.properties`（已被 .gitignore 挡住，绝不入库），
     * 格式：
     * ```
     * storeFile=/absolute/path/to/inkleaf-release.jks
     * storePassword=...
     * keyAlias=...
     * keyPassword=...
     * ```
     * 文件不存在时**不**创建 signingConfig：这台机器上没有密钥的人照样能
     * `assembleDebug` 与跑测试，只有 `assembleRelease` 会因为没有签名而产出未签名包。
     * 反过来，把密钥硬编码进构建脚本会让它进 git 历史 —— 那是不可撤销的泄露。
     */
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.inkleaf"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 9
        // 这个字符串会被写进全库存档的 manifest（"这份档案是谁导出的"），
        // 也会显示在设置页底部。M0 起就没再动过，第一次上模拟器才看见它还停在 m2。
        // M13 曾改成 0.2.0-m13；M16 起不再带里程碑后缀 —— 它要出现在用户能看到的地方，
        // 而"m16"对用户没有意义（里程碑编号是工程内部的记法，看 `design/` 目录）。
        // M17 起 0.4.0：这一版用户能看见的东西多了（主题、网格、书写模式）。
        // M25 起 1.0.0：收尾批次做完（层级目录、批注刻度、套索、荧光笔、
        // 手写检索、书写时翻页），功能闭环了，可以按正式版发。
        // 1.1.0：M26–M30 —— 书写跟手性、音量键翻页、拖动进度条、字体设置、
        // 首页与开书提速（会话缓存）、书架多选删除。都是用户能直接感觉到的变化，
        // 没有破坏性数据结构变更（不改笔迹编码、不改库表），所以是次版本号。
        // 1.2.0：M31–M37 —— 滚动模式（纵向虚拟化）、正文贴边、排版三档、
        // 磨砂顶栏、翻页动效；以及一次包体积腰斩（63 MB → 32 MB，dex 里
        // 三分之一的图标换了本地实现）。同样是次版本号，库表与笔迹编码没动。
        versionName = "1.2.0"

        // 设备端测试（M7 起有了模拟器）。在此之前这个工程从没跑过
        // connectedAndroidTest —— 所有"能写能画"的结论都是 Robolectric 级的。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 仍不开混淆：先保证功能闭环可复现，R8 配置放到 M6（方案 §12）。
            // 没有设备可验证"混淆之后还能不能读回笔迹"，而笔迹的 protobuf 编码
            // 恰恰是反射敏感的一类代码 —— 开一个验证不了的优化，比不开更危险。
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // 全库存档的 manifest 里要写 App 版本号（"这份档案是谁导出的"），
        // 用它比手写一个常量靠谱：版本升级时不会忘记同步。
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric 需要 res/ 与 assets/ 才能把 App 当成真实 App 启动
            isIncludeAndroidResources = true
        }
    }
}

// 不写 kotlin { compilerOptions { jvmTarget = ... } }：
// 内建 Kotlin 的 jvmTarget 默认取 android.compileOptions.targetCompatibility（= 17）。
// 少一个旋钮就少一处可能与 Java 目标版本不一致的地方。

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    // 只用 core 那一批常用图标（Add / Search / Settings / 箭头 / 撤销…），
    // 12 个 extended 独有的图标抄在本模块的 InkIcons.kt 里。
    // 换回 material-icons-extended 会让 dex 多出约 15 MB（全量 5000+ 图标），
    // 详见 design/M37-APK 为什么这么大.md。
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:document"))
    implementation(project(":core:data"))
    // 笔迹层：采集 / 解析 / 绘制。它只依赖一个 InkSink 接口，
    // 具体的仓储实现由本模块注入（见 ReaderScreen 里的 RepositoryInkSink）。
    implementation(project(":core:ink"))

    debugImplementation(libs.compose.ui.tooling)

    // 应用层自己的测试：验证"导入 → 排版 → 落笔 → 回读 → 撤销"这条**整链路**。
    // 这些组件各自都有单元测试，但"它们拼起来还能跑"只能在这里验证 ——
    // 而本机没有设备，Robolectric 是唯一能跑整链路的地方。
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.core)

    // 设备端测试：注入真实 MotionEvent、跑真实 Skia、连真实 SQLite。
    // 它要回答的是 Robolectric 回答不了的那一问："在真机上点一下，会发生什么"。
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
