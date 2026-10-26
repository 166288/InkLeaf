package dev.inkleaf.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 阅读界面的外框：**一层纸 + 两层浮起来的控件**。
 *
 * ## 为什么要重做这一层
 *
 * M14 之前的阅读界面是"Scaffold + 顶部工具栏 + 状态行 + 翻页条 + 正文"：
 * 四条东西在竖直方向依次排开，共占 116 dp（不含状态栏），而且全在**书眉**，
 * 单手拿手机时拇指根本够不到。书写面板再从底部浮上来盖住 136 dp ——
 * 用户真正能看字的地方只剩 591 dp。
 *
 * 这一版把它翻过来：正文占据整块纸，控件全部**浮在纸上面**，
 * 而且能缩到只剩一条 2 dp 的进度线。
 *
 * ## 一条不能违反的约束
 *
 * 正文的可用区**必须与控件的可见性无关**。
 *
 * 这不是审美选择。正文区一变就要重新分页（`PageGeometry.solve` → `StaticLayout`），
 * 而重排会移动每一个字符 —— 用户正在读的那一行会跳走，正在写的那一笔会重新锚定。
 * 本工程为此吃过两次亏（M8 记过一次，M13 §8 第 9 条又记过一次）。
 *
 * 所以这里用两条规矩守住它：
 *   1. 控件是 `Box` 的兄弟，不是 `Column` 的孩子 —— 它们的高度变化不影响正文；
 *   2. 正文两头的让位是**常量**，不跟着控件显隐、也不跟着托盘档位变。
 *
 * ## 从 M34 起：控件允许压在正文上
 *
 * M32/M33 把"控件不许压到字"也当成了硬约束，办法是让正文给顶栏和托盘各让出一块
 * **永久**留白。真机上量了一下：1880×3008 的屏，一页有 492 px（16% 屏高）是空的 ——
 * 控件只在开头那 2.8 秒出现，纸却一年到头空着上下两条。这不是"页脚留白"，
 * 这是把屏幕的四分之一让给了大部分时间不存在的东西。
 *
 * 现在反过来：正文贴到安全区下沿排，控件浮在它上面。
 * 沉浸态（绝大多数时间）一页多出约三行；唤出控件的那 2.8 秒里，
 * 顶栏会盖住首行、托盘会盖住末行。这是"用短暂遮挡换长期饱满"的取舍，
 * 有意的，不是疏漏 —— 所以顶栏的不透明度也必须够高（见 [ReaderFloatingTopBar]），
 * 否则底下的字会透上来变成重影。
 *
 * ## 顶部安全区为什么是一个常量
 *
 * 沉浸之后系统状态栏会真的隐藏，`WindowInsets.statusBars` 随之变成 0。
 * 如果正文拿它当 padding，隐藏那一刻整篇会重排一次。
 * 所以 [statusBarHeightPx] 只在**首次组合**读一次，之后永远当常量用：
 * 状态栏在或不在，正文都从同一个 y 开始。隐藏状态栏省下的 24 dp 就变成纸的上边距。
 */
@Stable
internal class ReaderChromeState {

    /**
     * 自动隐藏用 `Handler.postDelayed`，不用 `LaunchedEffect { delay(...) }`。
     *
     * 这不是风格选择，是本轮实测出来的：`LaunchedEffect` 的协程跑在 Compose 的
     * 重组调度上，而 instrumentation 下那个调度器被 `FrameDeferringContinuationInterceptor`
     * 包着 —— `delay` 到期后的恢复要等一帧，可那一帧没有任何人请求（没有待重组、没有动画）。
     * 表现是：`onRead` 每次都被调用，`hideChrome` 一次都不执行，四条沉浸用例全红，
     * logcat 里只有四行 `onRead seq=1`。
     *
     * `Handler.postDelayed` 走的是主线程 Looper 的真实时间，与 Compose 的帧无关，
     * 在生产环境里也是同一个行为（停手 2.8 秒就是 2.8 秒）。
     */
    private val handler = Handler(Looper.getMainLooper())
    private var pendingHide: Runnable? = null

    /** 托盘的当前形态。 */
    var level by mutableStateOf(TrayLevel.MINI)

    /** 悬浮顶栏是否在屏幕上。它与 [level] 一起构成"沉浸"。 */
    var topBarVisible by mutableStateOf(true)

    /**
     * 阅读动作的序号。一次翻页自增一次。
     *
     * 计时器把它当版本号用：到期时序号变了，说明这 2.8 秒里又翻了一页，不该隐藏。
     */
    var touchSeq by mutableLongStateOf(0L)

    /**
     * 最近一次按下是否"把界面从收着变成了显示"。
     *
     * 只在 [onPress] 里写、在 [onTap] 里读一次就清掉 —— 它是"这一次手指的按下"
     * 与"这一次手指的抬起"之间的一个中间事实，不承载任何长期状态。
     */
    private var pressRevealed = false

    /** 把控件唤回来：顶栏出现，托盘至少回到迷你档。 */
    fun revealTopBar() {
        topBarVisible = true
        if (level == TrayLevel.HIDDEN) level = TrayLevel.MINI
    }

    /** 控件现在是不是"在屏幕上"。顶栏与托盘两只手都改过状态，所以两处一起看。 */
    val isVisible: Boolean get() = topBarVisible || level != TrayLevel.HIDDEN

    /**
     * 手指按下：先把控件叫回来（按住不放的人也应该看见它们），
     * 同时记下"按下这一瞬间界面其实是不是收着的"。
     *
     * 这个记录是 [onTap] 唯一的输入，也是 M32 修掉"长按才能唤出"的关键：
     * 手指一落下就先唤出，等抬手时再看一眼当前状态，得到的永远是"已经是显示态"，
     * 于是"点一下切换"变成了"点一下保证显示" —— 用户必须按住不放才看得见控件。
     */
    fun onPress() {
        pressRevealed = !isVisible
        revealTopBar()
    }

    /**
     * 用户碰了界面（点按钮、点正文），把控件叫回来 —— **但不算"在读"**。
     *
     * 为什么不计数：点工具栏、翻菜单是"我在操作界面"，不是"我在读书"。
     * 如果这些也算，用户点开菜单犹豫三秒，界面就在他眼前淡出，这比不隐藏更烦。
     */
    fun onTouch() {
        revealTopBar()
    }

    /**
     * 用户做了一次**阅读动作**（翻页）。从这里开始计时，停手就进入沉浸。
     *
     * 这是"适时隐藏"里"适时"的定义：不是"打开就藏"，也不是"任何触摸都藏"，
     * 而是**在读者真的往前读的时候藏**。
     */
    fun onRead() {
        touchSeq += 1
        onTouch()
        scheduleHide()
    }

    /** 进入沉浸：顶栏退场，托盘缩到一条线。 */
    fun hideChrome() {
        cancelAutoHide()
        topBarVisible = false
        level = TrayLevel.HIDDEN
    }

    /**
     * 点正文：控件在就收起来，已经收着就唤回来（M31；M32 修成真·切换）。
     *
     * 为什么要看 [pressRevealed] 而不只看当前状态：按下那一刻 [onPress] 已经
     * 把控件唤出来了，抬手时若照着"现在是显示态"去切换，就会立刻又收回去 ——
     * 净效果是"点一下什么都不发生，长按才显示"。所以这里的判据是
     * **按下之前**是不是收着的：
     *
     * - 按下前收着 → 这一下是唤出；
     * - 按下前显示着 → 这一下是收起。
     *
     * ## 点出来的控件也会自己退场（M33）
     *
     * M32 曾让它一直留着（"自己点开的自己点回去"）。用起来是错的：
     * 点一下只是想看一眼页码、瞥一眼书名，控件就那样挂在屏幕上，
     * 直到你再点一次 —— 阅读界面从此多了一块永远占着的横条。
     * 现在唤出之后照样开始计 2.8 秒，与翻页后一样退场；
     * 想让它常驻，去按托盘上的按钮（那些动作走 [onRead]/[onTouch]，同样会重新计时）。
     */
    fun onTap() {
        val revealedByThisPress = pressRevealed
        pressRevealed = false
        when {
            !isVisible -> {
                revealTopBar()
                scheduleHide()
            }

            revealedByThisPress -> {
                // 这一下按下才把界面叫出来的：抬手时保持显示，并开始退场计时。
                scheduleHide()
            }

            else -> hideChrome()
        }
    }

    /**
     * 进入阅读界面：先让控件出场（读者要看见书名与页码），随后按同一条停手规则退场。
     *
     * ## 为什么需要这一下
     *
     * 退场计时只在 [onRead]（翻页）和 [onTap]（点正文）里启动，
     * 而"刚从书架进来"既不是翻页也不是点击 —— `topBarVisible` 的初始值 `true`
     * 于是**一直**挂着。读者第一次进书看到的是一块永远不走的横条，
     * 非得自己点一下才收，这跟"2.8 秒后自动退场"是两回事（M33 真机复现）。
     */
    fun enterReader() {
        revealTopBar()
        scheduleHide()
    }

    /** 撤掉还没到点的自动隐藏。离开界面、进入书写、打开弹层时都要调。 */
    fun cancelAutoHide() {
        pendingHide?.let { handler.removeCallbacks(it) }
        pendingHide = null
    }

    private fun scheduleHide() {
        cancelAutoHide()
        val seq = touchSeq
        val runnable = Runnable { if (touchSeq == seq) hideChrome() }
        pendingHide = runnable
        handler.postDelayed(runnable, AUTO_HIDE_MS)
    }
}

/** 托盘的三档。数值不承载语义，"多高"由 [ReaderTray] 决定。 */
internal enum class TrayLevel { HIDDEN, MINI, EXPANDED }

/**
 * 迷你托盘的高度。
 *
 * M34 起它**不再**是正文底部的常驻让位（正文一直排到手势条上沿，托盘浮在它上面）；
 * 滚动模式仍需要它 —— 那里的内容可以一直滚，末尾补这么一块，
 * 滚到底时最后一行才不会被托盘压住。
 */
internal val TRAY_MINI = 52.dp

/** 收起态：只剩一条进度线的高度。 */
private val TRAY_HIDDEN = 6.dp

/** 停手多久之后进入沉浸。2.8 s 是"读完一段、抬起头"的量级。 */
private const val AUTO_HIDE_MS = 2_800L

@Composable
internal fun rememberReaderChromeState(): ReaderChromeState = remember { ReaderChromeState() }

/**
 * 阅读界面的骨架。
 *
 * @param hold 为 true 时**不允许自动隐藏**（书写中、菜单开着、对话框开着）。
 *   它只压住计时器，不改托盘形态 —— 两者混在一起会出问题：打开「排版」面板时
 *   托盘会趁机展开出一块笔刷面板，躲在系统弹层后面，谁也不需要它。
 * @param expand 为 true 时把托盘展开成完整面板（目前只有书写时要）。
 * @param topBar 悬浮顶栏的内容。它自己负责背景 —— 这里只负责把它摆到状态栏下面。
 * @param notice 浮在托盘**之上**的一条提示（可空）。放在这一层而不是正文层，
 *   是因为展开的面板会向上生长盖住正文底部；提示条必须和面板同处一个纵向栈里，
 *   才不会出现"擦掉一笔之后提示条被笔刷面板挡住、点不到撤销"。
 * @param tray 底部托盘。用 [ReaderTray] 构造。
 * @param body 正文。它拿到的是"整块纸"的尺寸，不含任何控件。
 */
@Composable
internal fun ReadingScaffold(
    chrome: ReaderChromeState,
    paper: Color,
    hold: Boolean,
    expand: Boolean = false,
    topBar: @Composable () -> Unit,
    notice: @Composable () -> Unit = {},
    tray: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 只在首次组合取一次，之后当常量。理由见文件头"顶部安全区为什么是一个常量"。
    val topInset = remember { statusBarHeightPx(context) }
    // 导航栏不跟着沉浸走（只隐藏 statusBars），所以它可以跟着重组更新。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 暂停自动隐藏：书写 / 弹层打开时。托盘形态不在这里决定，见 `expand`。
    LaunchedEffect(hold) {
        if (hold) {
            chrome.cancelAutoHide()
            chrome.topBarVisible = true
        }
    }

    // 托盘形态由 [expand] 单独决定；[hold] 结束（弹层关掉）时回到迷你档，
    // 也就是"控件还在，只是不展开" —— 用户刚关掉一个面板，不该看到整条工具栏消失。
    LaunchedEffect(expand, hold) {
        chrome.level = if (expand) TrayLevel.EXPANDED else TrayLevel.MINI
    }

    // 离开界面时撤掉还没到点的计时器。
    DisposableEffect(chrome) {
        onDispose { chrome.cancelAutoHide() }
    }

    SyncStatusBar(visible = chrome.topBarVisible)

    Box(
        Modifier
            .fillMaxSize()
            .background(paper)
            // 只用 Initial pass 观察、**不消费**：下层的翻页手势与落笔照常收到事件。
            // 用 pointerInput 而不是给每个控件加回调，是因为"任何触摸都算交互"
            // 是这个设计的一句话规则；分散到各处一定会漏掉某一处。
            //
            // 这里调 `onPress` 而不是 `onTouch`：按下要顺手记下"按之前是不是收着的"，
            // 否则同一个手势的"按下唤出"与"抬手切换"会互相抵消（M32）。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) chrome.onPress()
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(top = topInset)) {
            Box(Modifier.fillMaxWidth().weight(1f)) { body() }
            // 只留系统手势条的高度：正文一直排到手势条上沿，托盘浮在它上面（M34）。
            // 上一版这里是 `TRAY_MINI + navBottom` —— 那条"给托盘预留的位置"
            // 在托盘收起的 99% 时间里就是一块白纸。
            Spacer(Modifier.height(navBottom))
        }

        AnimatedVisibility(
            visible = chrome.topBarVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 2 },
            exit = fadeOut(tween(240)) + slideOutVertically(tween(240)) { -it / 2 },
        ) {
            Box(Modifier.padding(top = topInset)) { topBar() }
        }

        // 提示条与托盘同一个纵向栈：面板向上生长时，提示条被顶到它上面去。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = navBottom),
        ) {
            Column(Modifier.fillMaxWidth()) {
                notice()
                tray()
            }
        }
    }
}

/**
 * 底部托盘：一条常驻的迷你栏，可以向上展开成一整块工具面板，也可以缩成一条线。
 *
 * ## 为什么进度线放在托盘上沿而不是另起一条
 *
 * 阅读器到处都有一条进度条，而每一条都要吃掉几个 dp。把"读到哪了"画在托盘
 * 上沿的 2 dp 里，等于免费 —— 而且它成了收起状态下**唯一还看得见**的东西，
 * 让"缩到只剩一条线"不至于变成"什么都不剩"。
 */
@Composable
internal fun ReaderTray(
    level: TrayLevel,
    progress: Float?,
    modifier: Modifier = Modifier,
    /** 批注刻度（画在进度线上）。 */
    ticks: List<ReaderTick> = emptyList(),
    /** 点刻度 → 跳到那条笔记。 */
    onJumpToTick: (ReaderTick) -> Unit = {},
    /**
     * 拖动/点按进度线翻页（M28）。null = 不可拖（滚动模式的进度线不接）。
     *
     * `finished = false` 是拖动中的一帧预览（调用方用它盖住进度显示），
     * `finished = true` 是松手（或直接点按）—— 调用方此时才真正跳页。
     * 位置语义由调用方定义：分页界面是页号，PDF 同。
     */
    onSeek: ((ratio: Float, finished: Boolean) -> Unit)? = null,
    panel: @Composable ColumnScope.() -> Unit = {},
    bar: @Composable RowScope.() -> Unit,
) {
    val barHeight by animateDpAsState(
        targetValue = if (level == TrayLevel.HIDDEN) TRAY_HIDDEN else TRAY_MINI,
        animationSpec = tween(if (level == TrayLevel.HIDDEN) 260 else 200),
        label = "trayHeight",
    )
    val surface = MaterialTheme.colorScheme.surface
    // 四角统一圆角（M33）。M32 之前是"上圆下直"：那时它被当成贴在屏幕底边的一块面板，
    // 下方接着系统手势条，做直角是对的。现在托盘浮在纸上方、下面还留着导航栏的高度，
    // 上方圆、下方直就成了一把"没收口的抽屉"—— 尤其迷你档只有 52 dp 高时，
    // 两个直角与纸面的接缝看得很清楚。
    // 展开的面板与迷你栏现在共用**同一个** Surface：两块各画各的圆角时，
    // 接缝处会叠出两条半径不同的弧线。
    val shape = RoundedCornerShape(22.dp)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = surface,
        tonalElevation = 3.dp,
        shape = shape,
    ) {
        Column(Modifier.fillMaxWidth().clip(shape)) {
            // 展开的面板在托盘**上方**长出来：Column 从下往上堆，所以它不会把 bar 推下去。
            AnimatedVisibility(
                visible = level == TrayLevel.EXPANDED,
                enter = expandVertically(tween(220), expandFrom = Alignment.Bottom),
                exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Bottom),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = panel,
                )
            }

            Column(Modifier.fillMaxWidth().height(barHeight)) {
                TrayProgressBar(
                    progress = progress,
                    ticks = ticks,
                    onJumpToTick = onJumpToTick,
                    onSeek = onSeek,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = bar,
                )
            }
        }
    }
}

/**
 * 一枚批注刻度。
 *
 * @param ratio 它在全文里的位置（0..1），用来决定刻度画在进度线的哪。
 * @param annotationId 它对应哪条笔记。跳转由界面层用自己的表解析 ——
 *        重排格式查"字符偏移"，PDF 查"页号"，两者不是一回事，
 *        所以这里只带 id，不带一个含义随格式变化的数字。
 * @param ink 是手写笔迹（true）还是打字批注（false）。两种画得不一样，
 *        因为用户要能一眼分出"这里是我写的字"还是"这里是我划的线"。
 */
data class ReaderTick(
    val ratio: Float,
    val annotationId: String,
    val ink: Boolean,
)

/**
 * 托盘上沿的进度线。没有进度信息时留一条极淡的轨道，让高度不跳。
 *
 * ## 为什么批注刻度画在这条线上
 *
 * 它是阅读器里**唯一**一条从头到尾都在的进度指示。刻度画在别处就要另起一条
 * （多占空间），画在这里则是"免费的"—— 而且位置符合直觉：
 * 进度线上的位置就是书里的位置。
 *
 * 刻度只显示，不承担定位职责：百分比在重排后误差可以到整章
 * （方案 §6.3.1 规则 3），所以**不能**拿它当滑块用。
 * 但"点刻度跳到那条笔记"是准的 —— 跳转用的是那一笔自己的字符偏移，
 * 不是百分比。
 */
@Composable
private fun TrayProgressBar(
    progress: Float?,
    ticks: List<ReaderTick> = emptyList(),
    onJumpToTick: (ReaderTick) -> Unit = {},
    onSeek: ((ratio: Float, finished: Boolean) -> Unit)? = null,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val inkTick = MaterialTheme.colorScheme.primary
    val typedTick = MaterialTheme.colorScheme.tertiary

    // onSeek 经 rememberUpdatedState 进检测器：拖动中每一帧都会让调用方
    // 重组（预览进度在变），如果直接拿 lambda 当 key，检测器会被中途重启、
    // 手势就此断掉。用状态包装则检测器只装一次，永远读最新回调。
    val latestSeek by rememberUpdatedState(onSeek)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(ticks) {
                // 点按：先看是不是点在批注刻度上；没命中刻度（或根本没有刻度）
                // 就当作一次直接跳页。两件事共用一次点按，优先级刻度在前。
                detectTapGestures { offset ->
                    val width = size.width.toFloat()
                    if (width <= 0f) return@detectTapGestures
                    // 找最近的一枚刻度：命中半径给到 12dp，
                    // 太紧的话 1dp 级的位置误差就会点不中。
                    val target = ticks.minByOrNull { tick ->
                        kotlin.math.abs(tick.ratio * width - offset.x)
                    }
                    if (target != null &&
                        kotlin.math.abs(target.ratio * width - offset.x) <= 12.dp.toPx()
                    ) {
                        onJumpToTick(target)
                    } else {
                        latestSeek?.invoke((offset.x / width).coerceIn(0f, 1f), true)
                    }
                }
            }
            .pointerInput(Unit) {
                // 拖动翻页（M28）。与上面的点按是两个独立检测器：
                // 手指滑过触摸阈值后由这里接管，点按检测器自然让路。
                if (latestSeek == null) return@pointerInput
                var current = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        current = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        latestSeek?.invoke(current, false)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        current = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        latestSeek?.invoke(current, false)
                    },
                    // 取消（手势被系统抢走）也按"松手"处理，落到 current：
                    // 否则预览进度会永远停在半路，进度条与实际页面对不上。
                    onDragEnd = { latestSeek?.invoke(current, true) },
                    onDragCancel = { latestSeek?.invoke(current, true) },
                )
            },
    ) {
        val width = size.width
        val centerY = size.height / 2f
        // 轨道
        drawRect(
            color = track,
            topLeft = androidx.compose.ui.geometry.Offset(0f, centerY - 1.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(width, 2.dp.toPx()),
        )
        // 已读部分
        if (progress != null) {
            val p = progress.coerceIn(0f, 1f)
            drawRect(
                color = fill,
                topLeft = androidx.compose.ui.geometry.Offset(0f, centerY - 1.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(width * p, 2.dp.toPx()),
            )
        }
        // 批注刻度：细而短（1.5 × 6 dp），贴着进度线像一个"针脚"。
        // 以前是 2 × 10 dp，笔记一多整条线就变成一排栅栏，喧宾夺主 ——
        // 刻度是路的记号，不是路本身。点按命中半径仍是 12 dp，大小不影响可点性。
        ticks.forEach { tick ->
            val x = tick.ratio.coerceIn(0f, 1f) * width
            drawRect(
                color = if (tick.ink) inkTick else typedTick,
                topLeft = androidx.compose.ui.geometry.Offset(x - 0.75.dp.toPx(), centerY - 3.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), 6.dp.toPx()),
            )
        }
    }
}

/**
 * 把状态栏真的藏起来。
 *
 * 只动 `statusBars`，不动 `navigationBars`：手势条是"怎么退出这个应用"的
 * 唯一可见提示，藏掉它会让界面看起来卡死了。
 *
 * 离开阅读界面时一定要恢复 —— 用 `DisposableEffect` 兜住，而不是只写 `LaunchedEffect`：
 * 后者在 Composable 被移除时不会执行"恢复"那一步。
 */
@Composable
private fun SyncStatusBar(visible: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() } ?: return
    DisposableEffect(activity) {
        onDispose { setStatusBarVisible(activity, true) }
    }
    LaunchedEffect(activity, visible) { setStatusBarVisible(activity, visible) }
}

private fun setStatusBarVisible(activity: Activity, visible: Boolean) {
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    // 允许从屏幕边缘上滑临时调出 —— 没有这一条，隐藏状态栏就变成了"时钟再也看不见"。
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (visible) controller.show(WindowInsetsCompat.Type.statusBars())
    else controller.hide(WindowInsetsCompat.Type.statusBars())
}

/**
 * 系统状态栏的高度（dp）。
 *
 * 读 `android:dimen/status_bar_height`，读不到就用 24 dp。
 * 为什么不读 `WindowInsets`：那个值会随状态栏隐藏变成 0，而正文的可用高度
 * 一旦跟着它变，隐藏状态栏就会让整篇重排一次。这里要的正是一个**不会变**的数。
 */
private fun statusBarHeightPx(context: Context): Dp {
    val res = context.resources
    val id = res.getIdentifier("status_bar_height", "dimen", "android")
    val px = if (id > 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    return if (px > 0) (px / res.displayMetrics.density).dp else 24.dp
}

internal fun Context.findActivityOrNull(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 悬浮顶栏的总高度。
 *
 * M33 时正文的顶部让位等于它；M34 之后不再参与让位 —— 顶栏是浮层，
 * 直接盖在正文上。这个常量留着有两个用处：说明"它会盖住多高"，
 * 以及给测试一个"顶栏比呼吸高得多"的锚点（[READER_TOP_BREATH]）。
 *
 * 44 dp 不是随手取的：真机上（450 dpi、48 px 字、1.4 倍行距）正文首行墨迹
 * 在 124–168 px、第二行从 215 px 起。顶栏底 = 安全区 92 + 44×2.8125 ≈ 216 px，
 * 正好压在第二行上沿 1 px 处 —— 只盖住首行一整行，第二行一个字都不切。
 * 52 dp 时它切掉第二行顶部 23 px（约四分之一行高），那半截字看着像排版错误。
 */
internal val READER_TOP_BAR_HEIGHT = 44.dp

/**
 * 正文距安全区下沿的呼吸（M34）。
 *
 * 这是顶部**唯一**的留白。上一版是"顶栏高度 + 空隙"共 58 dp，
 * 也就是顶栏不在的时候，那 58 dp 照样空着 —— 真机上一页 492 px 的空白里
 * 有 163 px 来自这里。既然控件可以压字了，这块让位就没有理由存在：
 * 安全区（状态栏 / 挖孔）本身已经是留白，8 dp 只够让首行不贴死。
 */
internal val READER_TOP_BREATH = 8.dp

/**
 * 正文最后一行与屏幕下沿（手势条之上）的呼吸（M34）。
 *
 * 同样不再跟着"页边距（mm）"走：那个数在手机上被换成纵向留白时大到离谱 ——
 * 用户把边距调到 9 mm，上下各让出 58 dp，一页少三行，页面看上去"不饱满"。
 * 毫米是纸书的**左右**边距概念；纵向只需要一口气。
 */
internal val READER_BOTTOM_GAP = 8.dp

/**
 * 悬浮顶栏：一条浮在纸上的胶囊。
 *
 * 为什么不做成 `Scaffold` 的 `topBar`：那会占掉 56 dp 的真实高度，把正文压低，
 * 而且每一次显隐都要重排一次。做成覆盖层之后，"沉浸"就只是把它淡出去，
 * 纸一个字都不会动。
 *
 * ## 半透明磨砂（M33）
 *
 * 真·磨砂要把"我背后画的东西"模糊掉 —— Compose 拿不到那个中间产物
 * （`Modifier.blur` 模糊的是自己的内容，不是背景）。所以这里做的是**近似**：
 * 不透明的纸色 + 0.5 dp 描边 + 投影。纸面从底下淡淡透出来，
 * 白光下看起来像一块磨砂玻璃；深色主题里则像一层薄墨。
 *
 * ## 为什么是 96% 而不是 88%（M34）
 *
 * M33 时顶栏**不盖字**（正文给它让位），透一点只会透出纸，好看。
 * M34 之后正文贴到安全区下沿，顶栏底下就是首行的大字 —— 12% 的透光
 * 会让墨迹浮上来变成一层灰影，正好落在书名后面。96% 时透光量只够
 * 让纸的色调渗进来（约 2.7% 亮度差），看不出字，磨砂的观感还在。
 *
 * 它只放两样东西：回哪去、在读什么。所有动作都下沉到拇指可达的托盘里。
 */
@Composable
internal fun ReaderFloatingTopBar(title: String, onBack: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // 上下不留 padding：44 dp 是"刚好压住首行、不碰第二行"算出来的，
            // 多出的 2 dp 就会变成切在第二行上的 6 px。
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = 4.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回书架",
                    modifier = Modifier.width(22.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 托盘里的一个"翻页"按钮：`‹` 或 `›`。
 *
 * 为什么页码两侧还要留箭头，尽管点屏幕左右三分之一已经能翻页：
 * 翻页手势不可发现、不可被读屏软件使用，而且**书写模式下被关掉了**。
 * 一个只能靠手势完成的核心动作，在无障碍上是缺失的。
 */
@Composable
internal fun TrayTurnButton(
    forward: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = if (forward) "下一页" else "上一页"
    Box(
        Modifier
            .width(36.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = if (forward) "›" else "‹",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
            },
        )
    }
}
