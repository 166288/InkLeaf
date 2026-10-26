// :core:document —— 文档抽象与分格式实现（方案 §4.2 / §5）
//
// ★ 与方案 §4.2 的一处有意修订：分页器从"Compose TextMeasurer"改为
//   **android.text.StaticLayout**。理由（已记入 design/方案设计.md §5.6 修订记录）：
//     1. §5.7 的硬性要求是"行盒必须来自真实排版结果，不许用字号×行高估算"。
//        StaticLayout 就是真实排版结果 —— Compose 的 TextLayoutResult 在 Android 上
//        底层同样是这层文本栈，两者精度等价。
//     2. §8.4 已定"文档渲染与墨迹渲染合并在一个原生 View，一次 onDraw、一次变换"。
//        若分页用 Compose、渲染用原生，就必须在 Compose 与 View 之间搬运行盒坐标，
//        而"同一份布局对象同时提供行盒与绘制"能把漂移变成结构上不可能。
//     3. 依赖更少：本模块不需要 Compose。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.inkleaf.core.document"
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
            // Robolectric 需要读 res/ 与 assets/ 才能把 App 当成真实 App 启动
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:model"))
    // 文档层必须直接用归一化/指纹/锚点构造：块切分的结果要以"归一化全文偏移"为坐标，
    // 由本模块自己再实现一套归一化是必然漂移的开始。
    api(project(":core:anchoring"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // 设备端路径仍然保留依赖：同一份断言换个 Runner 就能搬回 androidTest 跑真实 Skia。
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
