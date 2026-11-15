// :core:ink —— 笔迹的采集、锚定编排、解析与绘制（方案 §4.2 / §6）
//
// 模块边界（与方案 §4.2 的范围一致，但把纯逻辑的下沉写清楚）：
//   * **纯逻辑**（语义判定、按行切段锚定）在 :core:anchoring，零 Android 依赖；
//   * **需要 Canvas / MotionEvent / StaticLayout 的部分**在这里；
//   * **持久化**不在这里：本模块只依赖一个 [InkSink] 接口，由 :app 用 :core:data
//     的仓储实现。这样"笔迹怎么存"与"笔迹怎么画"互不牵连 —— 也让本模块的
//     测试不必拉起数据库。
//
// ★ 与方案 §4.2 的一处有意修订（记入 design/M3-实现记录与设计修订.md）：
//   自方案定稿时起计划用 androidx.ink 的低延迟墨迹层（InProgressStrokesView /
//   CanvasStrokeRenderer）。M3b 改为**自研 Path 渲染**。理由：
//     1. 本机（以及本工程目前能触及的任何环境）无法运行真机测试，
//        而低延迟渲染的全部价值就在真机上的延迟与掉帧表现 ——
//        没有设备就没有任何证据能证明它更快，只有"更复杂"是确定的。
//     2. 笔迹的持久化格式是自己的 INK1（见 :core:data 的 InkCodec），
//        与渲染引擎无关。因此换渲染引擎**不需要数据迁移** ——
//        这个自由度正是当初坚持自定格式换来的，现在用它一次。
//     3. androidx.ink 1.0.0 有一批未修缺陷（maskPath 闪烁、内存泄漏、
//        并发崩溃、API 29–32 detach 崩溃），在没有设备验证的前提下引入它们是
//        纯负债。
//   真机可用之后，若要换回低延迟路径，只需替换 [InkStrokeRenderer] 的实现：
//   em 坐标 → 文档坐标 → 渲染 这条链路与渲染引擎无关。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.inkleaf.core.ink"
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
    // 行盒、笔迹模型来自 model；锚定与语义判定来自 anchoring；
    // 行盒的**来源**（DocumentSession / ReflowableTextLayout）来自 document。
    // 三者都用 api：本模块的类型签名直接暴露它们（DrawableStroke 带 BrushSpec，
    // LayoutFacts 带 LineBox），调用方不该被迫再声明一遍。
    api(project(":core:model"))
    api(project(":core:anchoring"))
    api(project(":core:document"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
