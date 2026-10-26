package dev.inkleaf.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 起 `MainActivity`：**两条路**。
 *
 * ## 为什么不能只有 `ActivityScenario.launch`
 *
 * 它在小米平板（MIUI）上会一直等下去：不超时、不抛错、Activity 也永远不 RESUMED。
 * 整组设备测试看上去就"卡死"了 —— M19 实测一条用例挂了 10 分 18 秒才出结果。
 *
 * 根因在系统的启动记录里，不在应用里：
 *
 * ```
 * ActivityTaskManager: START u0 {cmp=dev.inkleaf/.app.MainActivity}
 *     ... callingPackage dev.inkleaf (BAL_ALLOW_PERMISSION) result code=102
 * ```
 *
 * `102` 是 `START_CANCELED` —— 应用自己发起的这次启动被系统取消了。
 * 而同一台设备上由 shell（uid 2000）发起的启动不受影响：`am start -W`
 * 冷启动实测 **413 ms**。`ActivityScenario.launch` 没有超时参数，于是它就在等
 * 一个永远不会来的回调。
 *
 * ## 两条路怎么选
 *
 * 1. 先试标准路径（25 秒上限）：它能正确接管生命周期，`moveToState` 那类 API 也靠它。
 * 2. 超时就退到 shell 启动，再从 `ActivityLifecycleMonitorRegistry` 里把已经
 *    RESUMED 的实例接管过来 —— 监视器按"哪个 Activity 活了"来认，不依赖 intent
 *    匹配，所以接管是可靠的，代价是没有 `ActivityScenario`。
 *
 * 结论按**进程**记住（[preferShell]）：一个测试进程要跑几十条用例，设备的行为不会
 * 中途变，每条都白等 25 秒的话，37 条就是十五分钟。
 */
object ActivityLauncher {

    /** 起好的 Activity。`scenario` 只有走标准路径时才有值。 */
    class Launched(
        val scenario: ActivityScenario<MainActivity>?,
        val activity: MainActivity,
    ) {
        /** 收尾。两条路都要 finish：否则窗口会留给下一条用例。 */
        fun close() {
            scenario?.close()
            activity.finish()
        }
    }

    /** 标准路径的超时上限。够慢设备冷启动，又不至于把"卡住"拖成"几分钟"。 */
    private const val STANDARD_TIMEOUT_SECONDS = 25L

    /** shell 启动之后等 RESUMED 的上限。正常是几百毫秒。 */
    private const val SHELL_TIMEOUT_MS = 60_000L

    @Volatile
    private var preferShell = false

    fun launch(): Launched {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        if (!preferShell) {
            val worker = Executors.newSingleThreadExecutor()
            try {
                val scenario = worker.submit<ActivityScenario<MainActivity>> {
                    ActivityScenario.launch(MainActivity::class.java)
                }.get(STANDARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                var activity: MainActivity? = null
                scenario.onActivity { activity = it }
                return Launched(scenario, activity ?: error("ActivityScenario 起来了但取不到 Activity"))
            } catch (e: TimeoutException) {
                preferShell = true
                println("INKLEAF_LAUNCH_FALLBACK 标准启动 ${STANDARD_TIMEOUT_SECONDS}s 没有返回，改用 shell 启动（本进程后续都用 shell）")
            } finally {
                worker.shutdown()
            }
        }

        val component = "${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
        shell("am start -W -n $component")
        val deadline = SystemClock.uptimeMillis() + SHELL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            resumedActivity()?.let { return Launched(null, it) }
            SystemClock.sleep(200)
        }
        throw AssertionError(
            "改用 shell 启动之后 ${SHELL_TIMEOUT_MS / 1000}s 也没看到 MainActivity 进 RESUMED。" +
                "设备状态：${screenState()}",
        )
    }

    /** 跑一条 shell 命令并等它结束，返回输出。`executeShellCommand` 是异步的，读完流才算完。 */
    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** 当前 RESUMED 的 MainActivity（没有就是 null）。生命周期监视器只能在主线程读。 */
    fun resumedActivity(): MainActivity? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .lastOrNull()
                ?.let { found = it }
        }
        return found
    }

    /**
     * 屏幕与锁屏的当前状态。只在失败信息里用得上。
     *
     * 只留两行：`dumpsys power` 是一整段，原样塞进断言消息会长到看不见重点。
     */
    fun screenState(): String = runCatching {
        shell("dumpsys power").lines()
            .filter { it.contains("mWakefulness=") || it.contains("mStayOn=") }
            .joinToString(" ") { it.trim() }
            .ifEmpty { "（读不到 mWakefulness / mStayOn）" }
    }.getOrElse { "取不到（${it.javaClass.simpleName}）" }
}
