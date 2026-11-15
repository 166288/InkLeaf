pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止在子模块里自己声明仓库：版本与仓库来源必须只有一处事实来源（版本目录 + 这里）
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "InkLeaf"

// ---- 模块骨架（对应 design/方案设计.md §4 与 §12）----
//
// 与方案 §12 的一处**有意偏差**（已记入 design/方案设计.md §12.1）：
//   feature:library / reader / notes / settings 不单独建模块，直接放在 :app 里做包内分层。
//   理由：这些界面共享同一份 ViewModel 状态与导航栈，拆成模块只会增加跨模块
//   internal 可见性问题与构建开销，换不到边界价值（方案 §4.1 自己给的拆模块理由是
//   "编译期隔离 + 强制边界"，而界面层恰恰是最不需要强行隔离的一层）。
//   等某个界面复杂到需要独立演进时再拆。
include(":app")

// 纯 Kotlin（零 Android 依赖）—— 方案 §4.1 强制约束
include(":core:model")
include(":core:anchoring")

// Android 库
include(":core:common")
include(":core:document")
include(":core:data")
include(":core:ink")
include(":core:ui")
