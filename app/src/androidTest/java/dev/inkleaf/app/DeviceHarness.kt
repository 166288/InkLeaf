package dev.inkleaf.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.inkleaf.core.ink.DocumentInkView
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.DocumentMeta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 设备测试的公共脚手架：起 App、打开一篇样例正文、往原生 View 里注入真实触摸。
 *
 * ## 为什么抽成基类
 *
 * M7 起这些代码都在 `AppOnDeviceTest` 里。M13 要给橡皮 / 换笔 / 导出 PNG 另写
 * 一组测试，而这组测试需要的**全部**基础设施是一样的：先落一篇文档再启动 Activity、
 * 等排版完成、把 `MotionEvent` 派发到 `DocumentInkView`、轮询数据库、截屏数像素。
 * 复制一份意味着两个文件里的 `dispatchSwipeTo` 迟早不一致，而它们的存在理由
 * 恰恰是"测试投递事件的姿势"这件事只能有一种。
 *
 * ## 继承带来的两个约束
 *
 * - `@Rule` / `@Before` / `@After` 必须是 **public**：JUnit4 用 `getMethods()`
 *   找它们，Kotlin 的 `protected` 在字节码里也是 protected，JUnit 看不见。
 *   所以这几个成员不写修饰符（默认 public），其余辅助方法都是 `protected`。
 * - 基类必须是 `abstract`：否则 JUnit 会把它当测试类实例化一次。
 *
 * ## 为什么先建文档再启动 Activity
 *
 * `createAndroidComposeRule` 会在规则生效时就把 Activity 拉起来，于是书架第一帧
 * 读到的是空库；再用 `scenario.recreate()` 补一次，就变成"同一个测试里有新旧两个
 * Activity"—— 点击到底落在哪一个组合上不再显然。这里用
 * `createEmptyComposeRule` + 自己在 `@Before` 里启动：文档先落库，界面第一次组合
 * 就能看见它。
 */
abstract class DeviceHarness {

    companion object {
        /**
         * 本进程里是否已经确认"标准启动会挂"（见 [startActivity]）。
         *
         * 放在伴生对象里而不是实例字段：一个测试进程要跑几十条用例，而设备的行为
         * 在同一个进程里不会变。每条都先等 25 秒再退是很实在的浪费 —— 37 条就是
         * 十五分钟，比"测试卡死"好不了多少。
         */
        @Volatile
        private var preferShellLaunch = false
    }

    /** 不绑定 Activity：Activity 由本基类在文档就位之后自己启动。 */
    @get:Rule
    val compose = createEmptyComposeRule()

    protected val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<InkLeafApplication>().container

    /**
     * 走 `ActivityScenario` 成功时才有值。
     *
     * 为什么不是必然有值：见 [startActivity] —— 在 MIUI 上应用自己发起的启动会
     * 被系统取消，那时只能改用 shell 启动，也就没有 `ActivityScenario` 可言了。
     */
    protected var scenario: ActivityScenario<MainActivity>? = null
        private set

    /** 启动后的那个 Activity。测试线程上随时可取，不必再问 [scenario]。 */
    protected var activity: MainActivity? = null

    protected lateinit var meta: DocumentMeta

    /**
     * 样例正文：**连续段落**，不是一行一句。
     *
     * 这里原先是一行一句、共 40 行。对"翻页 / 落笔"这些用例没影响，对"量正文有多宽"
     * 却是致命的：硬换行的每一行只写到第 40 个字，帧缓冲里量到的墨迹跨度是
     * **那一行的字宽**（17 sp 下约 29 em ≈ 1368 px），而不是排版算出来的栏宽
     * （同一屏上是 1738 px）。M19 因此红过一条用例，产品一丝没坏。
     *
     * 现在每段里的句子是连着的，由排版自己折行：折行顶到栏宽，墨迹右缘就等于
     * 正文右缘（最多差一个字的宽度），跨度才真的等于"正文有多宽"。
     * 12 段 ≈ 2 页，翻页类用例仍然有得翻。
     */
    protected val body: String = (1..12).joinToString("\n") { p ->
        (1..6).joinToString("") { n ->
            "第 $p 段第 $n 句：这一行要被真的量出来、真的画出来、真的能落笔。"
        }
    }

    @Before
    fun openSeededDocument() {
        // 设置是异步读的，读完之前界面不该排任何版（否则会用默认字号排一次再跳）。
        awaitTrue("设置加载完成", 30_000) { container.ready.value }

        // 屏幕必须先亮着。熄屏时 `ActivityScenario.launch` 会一直阻塞在"等 Activity
        // RESUMED"上 —— 不报错、不超时，看上去就是测试卡死。M19 实测在平板上一条
        // 用例挂了 **10 分 18 秒**：logcat 里 22:22:35 开始、22:32:50 才出现
        // `Displayed dev.inkleaf/.app.MainActivity`，中间屏幕还暗过一次。
        // `tools/verify.sh --device` 也会把屏幕钉住，但单独跑一条用例时不会 ——
        // 所以这道保险放在这里，而不是只放在脚本里。
        wakeScreen()

        // 排版参数在用例之间**会串**：上一条把字号拖到 12 sp，这一条量到的正文
        // 宽度就是 12 sp 的（平板上被 40 字行宽封顶成 71.8%，于是"铺满屏宽"
        // 那条断言红得莫名其妙，而产品其实一丝没坏）。每条用例开头把三个
        // 排版参数复原，"这一条改了什么"才是唯一的变量。
        val defaults = ReaderSettings()
        runBlocking {
            container.updateSettings {
                it.copy(
                    fontSizeSp = defaults.fontSizeSp,
                    lineSpacing = defaults.lineSpacing,
                    pageMarginMm = defaults.pageMarginMm,
                )
            }
        }

        // 每次跑用一个新的文档：模拟器上的库是持久化的，
        // 固定标题会让第二次跑的 `onNodeWithText` 命中多个节点而失败。
        val stamp = System.currentTimeMillis()
        val name = "模拟器样例 $stamp.txt"
        val source = File(container.context.cacheDir, name)
        source.writeText(body, Charsets.UTF_8)
        meta = runBlocking {
            val candidate = container.importer.readFromUri(
                resolver = container.context.contentResolver,
                uri = Uri.fromFile(source),
                fallbackTitle = name,
            )
            container.importer.commit(container.importer.inspect(candidate))
        }

        val started = SystemClock.uptimeMillis()
        startActivity()
        // 这条数字是"设备有没有睡着 / 启动有没有被系统取消"的唯一直接读数：
        // 正常几百毫秒，熄屏时是分钟级。
        println("INKLEAF_LAUNCH_MS=${SystemClock.uptimeMillis() - started} via=${if (scenario != null) "scenario" else "shell"}")

        awaitTrue("书架里出现《${meta.title}》", 30_000) {
            compose.onAllNodesWithText(meta.title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(meta.title).performClick()

        // 等到排版完成：翻页条从「排版中…」变成「第 1 / N 页」，才有正文可看。
        awaitTrue("正文排版完成", 30_000) {
            compose.onAllNodesWithContentDescription("下一页").fetchSemanticsNodes().isNotEmpty()
        }
        // 再给一帧时间把这一页画出来。
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(600)

        // 视图树写进 logcat：这是"原生 View 到底挂没挂上"的唯一直接证据。
        println("INKLEAF_VIEW_TREE\n${viewTree()}")
    }

    @After
    fun closeActivity() {
        scenario?.close()
        scenario = null
        // shell 启动那条路上没有 scenario 管收尾：自己 finish 掉，
        // 否则窗口会留给下一条用例，`onNodeWithText` 就可能在两个界面里各命中一次。
        activity?.finish()
        activity = null
    }

    /** 起 Activity：两条路的选择与理由都在 [ActivityLauncher] 里。 */
    private fun startActivity() {
        val launched = ActivityLauncher.launch()
        scenario = launched.scenario
        activity = launched.activity
    }

    /**
     * 把 App 真的送到后台：按一次 HOME，等系统把 Activity 走到 `CREATED`。
     *
     * 这一版之前用的是 `Instrumentation.callActivityOnStop(activity)`（替代
     * `ActivityScenario.moveToState(CREATED)`）。它能调通 `Activity.onStop()`，
     * 但**没有真的把 App 变到后台**：进程还在前台、任务栈没动、窗口还可见。
     * 于是"阅读时长落库"那条用例红了 —— 时长是靠 `LocalLifecycleOwner` 上的
     * `ON_STOP` 提交的，而一次孤立的 `callActivityOnStop` 并没有让 Compose 的
     * 生命周期观察者收到事件（回调序列缺了 `ON_PAUSE`，Activity 自己的状态也没变）。
     *
     * 现在走真实路径：`input keyevent KEYCODE_HOME` 由系统派发正常的
     * PAUSE → STOP，再用 [activity] 自己的 `lifecycle.currentState` 作证据 ——
     * 状态没落到 `CREATED` 就是没真退到后台，等下去也没意义。
     */
    protected fun moveToBackground() {
        val target = activity ?: throw AssertionError("Activity 还没起来")
        shell("input keyevent KEYCODE_HOME")
        awaitTrue("App 退到后台（Activity 应当走到 CREATED）", 10_000) {
            var state = Lifecycle.State.RESUMED
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                state = target.lifecycle.currentState
            }
            !state.isAtLeast(Lifecycle.State.STARTED)
        }
    }

    // ── 下面都是"在设备上等一件事发生"的小工具 ────────────────────────────────

    /**
     * 点亮屏幕并解锁。
     *
     * 为什么这不是"环境问题，不归测试管"：熄屏时 [ActivityScenario.launch] **不会
     * 失败**，它会一直等下去，而调用它的是一条测试用例 —— 于是整组测试既不前进也
     * 不报错，看起来就是卡死。设备是否恰好在这十几分钟里睡着，取决于用户的熄屏设置，
     * 那不是测试能假设的事。所以先把屏幕弄亮，再起 Activity。
     *
     * `svc power stayon` 这里不用：它会改设备的电源行为（而且只在充电时生效），
     * 该由 `tools/verify.sh --device` 在跑之前设、跑完还原。测试只做"这一下"。
     */
    protected fun wakeScreen() {
        // 拿不到 shell（个别精简 ROM）时不该让整条用例挂掉：真起不来，
        // 后面的 ActivityScenario 自然会失败，失败信息里会有更直接的原因。
        runCatching { shell("input keyevent KEYCODE_WAKEUP") }
        runCatching { shell("wm dismiss-keyguard") }
    }

    /** 跑一条 shell 命令并等它结束，返回它的输出。`executeShellCommand` 是异步的，读完流才算完。 */
    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** 屏幕与锁屏的当前状态。只在"起 Activity 超时"的失败信息里用得上。 */
    private fun screenState(): String = runCatching {
        shell("dumpsys power | grep -E 'mWakefulness=|mStayOn='").trim().replace('\n', ' ')
    }.getOrElse { "取不到（${it.javaClass.simpleName}）" }

    /**
     * 主线程此刻的调用栈。
     *
     * 只在超时的失败信息里用：如果主线程停在 Looper 里，说明 Activity 根本没被创建
     * （请求还压在系统侧）；如果停在我们自己的代码上，就是应用自己的问题。
     * 这两种情况的处置完全相反，而"卡了 90 秒"这句话本身区分不了它们。
     */
    private fun mainThreadStack(): String = runCatching {
        Looper.getMainLooper().thread.stackTrace
            .take(12)
            .joinToString("\n") { "  at $it" }
            .ifEmpty { "（主线程栈是空的）" }
    }.getOrElse { "取不到（${it.javaClass.simpleName}）" }

    /**
     * 轮询等待，**不用** `compose.waitUntil`。
     *
     * 打开文档的那一小段里界面有个转圈的进度指示器 —— 无限动画会让
     * Compose 测试的 `waitForIdle` 永远不空闲。这里的等待不碰 Compose 的时钟，
     * 只让出测试线程，UI 线程照常跑。
     */
    protected fun awaitTrue(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("等了 ${timeoutMs}ms 仍未满足：$what\n${uiSnapshot()}")
    }

    /** 失败时把当前界面的语义树带进断言消息里 —— 否则"没找到"和"点丢了"分不清。 */
    protected fun uiSnapshot(): String = try {
        "当前界面语义树：\n" + compose.onRoot().printToString(maxDepth = 16)
    } catch (e: Throwable) {
        "（连语义树都取不到：${e.javaClass.simpleName} ${e.message}）"
    }

    protected fun notes(): List<Annotation> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations
    }

    /**
     * 翻页条上写着「第 1 / N 页」，从语义树里把 N 读出来。
     *
     * 为什么不问排版要页数：这条断言要证明的是"用户看得见的那一行字变了"，
     * 而不是"排版内部算出来一个更大的数字"。
     *
     * @return -1 表示翻页条还不在（排版中）。
     */
    protected fun pageCount(): Int = pageNumberAndCount()?.second ?: -1

    /**
     * 当前页码（「第 N / M 页」里的 N）。
     *
     * @return -1 表示翻页条还不在。
     */
    protected fun currentPage(): Int = pageNumberAndCount()?.first ?: -1

    private fun pageNumberAndCount(): Pair<Int, Int>? {
        // 不写死"第 1 /"：翻页之后那串文字就成了"第 2 /"，旧实现因此在翻页后
        // 永远返回 -1。现在一次把 N 和 M 都取出来，页码与总页数用同一段正则，
        // 不会再出现"两个辅助函数各解析各的、对不上"。
        for (node in compose.onAllNodesWithText("/", substring = true).fetchSemanticsNodes()) {
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text } ?: continue
            val match = Regex("""第\s*(\d+)\s*/\s*(\d+)\s*页""").find(text) ?: continue
            val page = match.groupValues[1].toIntOrNull() ?: continue
            val total = match.groupValues[2].toIntOrNull() ?: continue
            return page to total
        }
        return null
    }

    /** 等到库里正好有 [count] 条手写笔迹（并返回它们）。 */
    protected fun awaitInksExactly(count: Int, timeoutMs: Long = 20_000): List<Annotation.Ink> {
        var seen: List<Annotation.Ink> = emptyList()
        awaitTrue("库里有 $count 条笔迹", timeoutMs) {
            seen = notes().filterIsInstance<Annotation.Ink>()
            seen.size == count
        }
        return seen
    }

    /** 轮询 [timeoutMs] 毫秒，返回届时的笔迹列表（不满足也不抛：由调用方决定怎么用）。 */
    protected fun pollInks(timeoutMs: Long): List<Annotation.Ink> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val inks = notes().filterIsInstance<Annotation.Ink>()
            if (inks.isNotEmpty()) return inks
            Thread.sleep(100)
        }
        return notes().filterIsInstance<Annotation.Ink>()
    }

    /**
     * 一次滑动的派发工具。
     *
     * ## 为什么不用系统级注入
     *
     * 这两条路在这台模拟器上都试过，事件都没落到原生文档 View 上：
     *
     * - `UiAutomation.injectInputEvent`：事件被派发了，但笔模式已开、状态行一句提示都没有，
     *   说明 `DocumentInkView.onTouchEvent` 压根没被调用；
     * - `UiAutomation.executeShellCommand("input swipe …")`：同一条命令在
     *   `adb shell input swipe` 下手工跑是**能画出笔迹的**（见 build/m-ink.png），
     *   放进 instrumentation 里却什么也没发生。
     *
     * ## 派发到窗口能到哪一层
     *
     * `DecorView.dispatchTouchEvent` 是能进 Compose 手势层的 —— 右侧三分之一点一下
     * 真的会翻页（见 `翻页热区…` 那条测试）。但它**到不了** `AndroidView` 里的原生子 View：
     * 笔模式已开（`attachInk true` 写进 logcat）、状态行没有提示、`onTouchEvent`
     * 一次都没被调用。这是 Compose 互操作层的触摸路由，不是本工程能改的东西。
     *
     * 所以落笔那条测试先按"真实手指"派发到窗口；若库里没笔迹，再退一步直接派发给
     * `DocumentInkView` —— 那一步仍然覆盖 Robolectric 覆盖不到的东西
     * （真实 `MotionEvent` → `onTouchEvent` → 采集 → 锚定 → 落库 → 撤销），
     * 只是不再声称"Compose 的互操作路由也被这段代码验证过了"。哪条路成功会打进 logcat。
     *
     * ## 事件本身
     *
     * 用带 `PointerProperties` 的 `MotionEvent.obtain`：`toolType` 显式写成手指
     * （或调用方指定的笔）—— 这是"只用手写笔"那条判据的唯一输入。
     * 单指针坐标直接用屏幕坐标（`DecorView` 左上角就是屏幕左上角）。
     */
    protected fun makeTouchEvent(
        downTime: Long,
        at: Long,
        action: Int,
        x: Float,
        y: Float,
        /**
         * 指针的工具类型。默认手指；测"只用手写笔"时要能派发 `TOOL_TYPE_STYLUS` ——
         * 那是整条判据的唯一输入，用别的方式模拟不了（模拟器没有真笔）。
         */
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime,
            at,
            action,
            1,
            arrayOf(props),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    protected fun dispatchSwipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int = 24,
        stepMs: Long = 8,
    ) {
        val target = activity ?: throw AssertionError("Activity 还没起来")
        compose.runOnUiThread {
            dispatchSwipeTo(target.window.decorView, fromX, fromY, toX, toY, steps, stepMs)
        }
    }

    /** 直接派发给文档 View（坐标从屏幕坐标换算成 View 坐标）。 */
    protected fun dispatchSwipeToDocumentView(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int = 24,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
    ) {
        val view = awaitDocumentView()
        compose.runOnUiThread {
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            dispatchSwipeTo(
                view,
                fromX - origin[0],
                fromY - origin[1],
                toX - origin[0],
                toY - origin[1],
                steps,
                toolType = toolType,
            )
        }
    }

    protected fun dispatchSwipeTo(
        view: View,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int = 24,
        stepMs: Long = 8,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
    ) {
        val downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(
            makeTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY, toolType),
        )
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            view.dispatchTouchEvent(
                makeTouchEvent(
                    downTime,
                    downTime + i * stepMs,
                    MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * f,
                    fromY + (toY - fromY) * f,
                    toolType,
                ),
            )
        }
        view.dispatchTouchEvent(
            makeTouchEvent(
                downTime,
                downTime + (steps + 1) * stepMs,
                MotionEvent.ACTION_UP,
                toX,
                toY,
                toolType,
            ),
        )
    }

    /**
     * 半截笔迹：按下 + 若干移动，**不发抬起**。
     *
     * 存在的理由是要验"写到一半屏幕上就该有墨"（用户报的"每次写完才显示"）。
     * [dispatchSwipeToDocumentView] 是一次完整手势，中间没有"停在这儿"的机会。
     *
     * 返回 `downTime`，必须原样交给 [finishPenStroke]，否则两个事件的 downTime
     * 对不上 —— 系统会把它当成两次独立触摸，而这里要的是同一次。
     */
    protected fun startPenStroke(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int = 12,
        stepMs: Long = 8,
        paceMs: Long = 0L,
    ): Long {
        val view = awaitDocumentView()
        val downTime = SystemClock.uptimeMillis()
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        if (paceMs > 0L) {
            // 逐点派发、每点之间等一帧：这才是笔在屏幕上划过的节奏（60Hz ≈ 16ms）。
            //
            // 一次性把几十个点塞进同一个 `Runnable` 里，事件都到了、**但一帧都没画**——
            // 那测不到"写得快不快"，也凑不出延迟埋点要的样本（踩过：日志一行没有）。
            for (i in 0..steps) {
                val f = i / steps.toFloat()
                val action = if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
                val now = SystemClock.uptimeMillis()
                compose.runOnUiThread {
                    view.dispatchTouchEvent(
                        makeTouchEvent(
                            downTime,
                            now,
                            action,
                            fromX - origin[0] + (toX - fromX) * f,
                            fromY - origin[1] + (toY - fromY) * f,
                            MotionEvent.TOOL_TYPE_STYLUS,
                        ),
                    )
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                SystemClock.sleep(paceMs)
            }
            return downTime
        }
        compose.runOnUiThread {
            for (i in 0..steps) {
                val f = i / steps.toFloat()
                val action = if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
                view.dispatchTouchEvent(
                    makeTouchEvent(
                        downTime,
                        downTime + i * stepMs,
                        action,
                        fromX - origin[0] + (toX - fromX) * f,
                        fromY - origin[1] + (toY - fromY) * f,
                        MotionEvent.TOOL_TYPE_STYLUS,
                    ),
                )
            }
        }
        return downTime
    }

    /** 把上一条半截笔迹收尾（发抬起）。 */
    protected fun finishPenStroke(x: Float, y: Float, downTime: Long) {
        val view = awaitDocumentView()
        compose.runOnUiThread {
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            view.dispatchTouchEvent(
                makeTouchEvent(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    x - origin[0],
                    y - origin[1],
                    MotionEvent.TOOL_TYPE_STYLUS,
                ),
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** 一次点击：按下与抬起在同一个点，走窗口（能到 Compose 的手势层）。 */
    protected fun dispatchTap(x: Float, y: Float) {
        val target = activity ?: throw AssertionError("Activity 还没起来")
        compose.runOnUiThread {
            val decor = target.window.decorView
            val now = SystemClock.uptimeMillis()
            decor.dispatchTouchEvent(makeTouchEvent(now, now, MotionEvent.ACTION_DOWN, x, y))
            decor.dispatchTouchEvent(makeTouchEvent(now, now + 60, MotionEvent.ACTION_UP, x, y))
        }
    }

    /**
     * 把顶栏和托盘唤回来 —— 与真实用户同一个动作：点一下正文中央。
     *
     * 为什么需要它：停手 2.8 秒后界面进入沉浸（顶栏淡出、托盘缩成一条线）。
     * 测试里"等排版""等库落盘"经常超过这个时长，接下来的按钮就不在树上了。
     * 与其把超时调大（那会让真实行为在测试里消失），不如按真实路径唤醒。
     */
    protected fun revealChrome() {
        // M32 起"点正文中间"是**真切换**：已经显示时再点一下会把它收起来。
        // 这个辅助函数的语义是"保证控件在屏幕上"，所以先看一眼 ——
        // 已经在就不点，否则它会做着与名字相反的事（把界面收起来）。
        //
        // 推论：**书写模式下它是空操作**。`ReadingScaffold` 的 `hold` 在书写时压住
        // 自动隐藏、把顶栏留住，控件一直在，这里就永远不点。所以别指望它替你在纸上
        // 留一个点 —— 要"手指点一下正文"这个动作，就自己 `dispatchTap` 并断言结果。
        val visible = compose.onAllNodesWithContentDescription("返回书架")
            .fetchSemanticsNodes().isNotEmpty()
        if (visible) return
        val decor = decorView() ?: throw AssertionError("窗口还没起来")
        // 屏幕正中：既不在左/右三分之一的翻页热区，也不在底部托盘上。
        dispatchTap(decor.width / 2f, decor.height / 2f)
        compose.waitForIdle()
    }

    /** 等到视图树里出现一个有大小的文档 View（原生 View 不在语义树里，只能走视图树）。 */
    protected fun awaitDocumentView(timeoutMs: Long = 30_000): DocumentInkView {        var found: DocumentInkView? = null
        awaitTrue("视图树里出现有大小的 DocumentInkView", timeoutMs) {
            found = findDocumentView(decorView())
            found != null && found!!.width > 0 && found!!.height > 0
        }
        @Suppress("UNCHECKED_CAST")
        return found as DocumentInkView
    }

    protected fun decorView(): View? {
        val target = activity ?: return null
        var view: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = target.window.decorView
        }
        return view
    }

    private fun findDocumentView(root: View?): DocumentInkView? = when {
        root == null -> null
        root is DocumentInkView -> root
        root is ViewGroup ->
            (0 until root.childCount).firstNotNullOfOrNull { findDocumentView(root.getChildAt(it)) }

        else -> null
    }

    protected fun <T> awaitOne(timeoutMs: Long = 20_000, pick: () -> List<T>): T {
        var one: T? = null
        awaitTrue("拿到那一条记录", timeoutMs) {
            one = pick().singleOrNull()
            one != null
        }
        @Suppress("UNCHECKED_CAST")
        return one as T
    }

    /**
     * 截屏（帧缓冲，不是 `View.draw`）并落盘留证。
     *
     * 写进 App 自己的外部私有目录：这是唯一**一定**可写的位置 ——
     * 试过走 shell（`executeShellCommand`）写 `/sdcard/Download/`，
     * 命令确实跑了，但写出来的东西不是文件；也试过直接往 `executeShellCommand`
     * 的 PFD 里写 PNG 字节（那个 PFD 是只读的，`Bitmap.compress` 默默返回 false）。
     *
     * 代价：`connectedAndroidTest` 默认跑完就卸载 App，目录会跟着消失。
     * 所以验证时一律加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`，
     * 跑完再从 `adb shell run-as`，或直接 `adb pull` 取出来。
     */
    protected fun screenshot(name: String): Bitmap {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(container.context.getExternalFilesDir(null), "device-test").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** 页面区（去掉工具栏、翻页条、系统栏）里"明显偏暗"的像素数：正文与墨迹都是深色。 */
    protected fun countDarkPixels(
        bitmap: Bitmap,
        top: Int,
        bottom: Int,
        count: (Int) -> Boolean = { it < 100 },
    ): Int {
        var dark = 0
        var y = top
        while (y < minOf(bottom, bitmap.height)) {
            var x = 0
            while (x < bitmap.width) {
                if (count(luminanceOf(bitmap.getPixel(x, y)))) dark++
                x += 3
            }
            y += 3
        }
        return dark
    }

    /**
     * 正文横向到底占了多宽（像素）。
     *
     * 逐列统计"这一列在 [top]..[bottom] 之间有没有足够多的深色像素"，返回最左与最右
     * 命中列之间的距离。判据与 [countDarkPixels] 一致（亮度 < 100），
     * 但这里数的是**列**：正文铺满屏宽时这个跨度接近屏宽，挤在中间一条时只有一半。
     *
     * 为什么用帧缓冲而不是问 View 要 `layout.metrics.contentWidthPx`：
     * 后者只会重复排版自己的说法 —— 如果绘制时没照它画，断言照样会绿。
     */
    protected fun textColumnSpan(bitmap: Bitmap, top: Int, bottom: Int): Int {
        var left = -1
        var right = -1
        var x = 0
        while (x < bitmap.width) {
            var dark = 0
            var y = top
            while (y < bottom) {
                if (luminanceOf(bitmap.getPixel(x, y)) < 100) dark++
                y += 3
            }
            // 一行里至少几个深色点才算"这一列有字"，避免抗锯齿的边缘像素把跨度撑大。
            if (dark >= 4) {
                if (left < 0) left = x
                right = x
            }
            x += 1
        }
        return if (left < 0) 0 else right - left + 1
    }

    /** 亮度（0..255）。墨迹判据与文字判据共用同一套算式，否则两条断言没法互相参照。 */
    protected fun luminanceOf(color: Int): Int =
        ((color shr 16 and 0xFF) * 299 + (color shr 8 and 0xFF) * 587 + (color and 0xFF) * 114) / 1000

    /** 原生视图树（类名 + 尺寸）：用来分清"没建出来"和"建出来了但没量到"。 */
    protected fun viewTree(): String {
        val target = activity ?: return "（取不到 Activity）"
        var text = "（取不到 Activity）"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sb = StringBuilder("视图树：\n")
            appendTree(target.window.decorView, 0, sb)
            text = sb.toString()
        }
        return text
    }

    private fun appendTree(v: View, depth: Int, sb: StringBuilder) {
        val mark = if (v is DocumentInkView) "  ⇐ DocumentInkView" else ""
        sb.append("  ".repeat(depth)).append(v.javaClass.simpleName)
            .append(" [").append(v.left).append(',').append(v.top).append(' ')
            .append(v.width).append('x').append(v.height).append(']').append(mark).append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) appendTree(v.getChildAt(i), depth + 1, sb)
        }
    }
}
