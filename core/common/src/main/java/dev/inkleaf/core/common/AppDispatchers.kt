package dev.inkleaf.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 调度器注入点。
 *
 * 存在的唯一理由是**可测试性**：分页与墨迹处理必须能在 JVM 单测里用
 * `UnconfinedTestDispatcher` 确定性地跑完，不允许出现 "delay(…) 后碰运气"。
 * 因此代码里一律不直接写 `Dispatchers.IO`，而是注入 [AppDispatchers]。
 */
interface AppDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

/** 生产实现。 */
class DefaultAppDispatchers : AppDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
}
