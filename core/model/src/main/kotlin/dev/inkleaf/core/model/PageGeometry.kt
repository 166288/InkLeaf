package dev.inkleaf.core.model

/**
 * 页面几何：把"用户想要的页边距"换算成"这一屏实际能给的正文框"。
 *
 * ## 为什么需要这一层（这是 M8 的起点）
 *
 * 在这之前，正文框就是 `屏宽 - 2 × 用户边距`，而用户边距的单位是**毫米**（方案 §5.6
 * 用物理单位定义排版参数，为的是不同 DPI 下手写体验一致）。两个事实一相乘就出了问题：
 *
 * - 默认边距 **16 mm**（≈ 0.63 英寸，按纸书边距定的）；
 * - 手机屏宽只有 **411 dp ≈ 65 mm**。
 *
 * 于是每边吃掉 100 dp，正文只剩 209 dp ≈ **12 个汉字一行**，屏幕上真正有字的宽度
 * 只有 51%。第一次在模拟器上看截图时，正文确实"只占中间一小条"（M7 记录 §7 的
 * `t-reader-text.png`）。
 *
 * 但也不能简单把边距调小：平板（800 dp 以上）上边距 4 mm 会让一行排到 50 多个汉字，
 * 眼睛回行时会串行。**窄屏要的是"铺满"，宽屏要的是"限制行宽"** —— 两个方向相反的需求，
 * 用"边距"一个参数表达不了。
 *
 * ## 规则（三条，都可单独测试）
 *
 * 1. **边距让位给正文**：用户边距是"我想要的留白"，不是"正文必须让出的宽度"。
 *    当它把正文挤到 [MIN_CONTENT_EM] 个字以下时，边距自动收缩到刚好够 [MIN_CONTENT_EM] 个字。
 * 2. **行宽封顶**：[MAX_LINE_EM] 个字。超出部分变成左右边距（居中），
 *    所以大屏上得到一条读起来舒服的窄栏，而不是一行 60 个字。
 * 3. **上下边距最多各占四分之一**：正文至少留一半高度。用户把边距拉到 40 mm
 *    也不会把一页挤成三行。
 *
 * ## 为什么放在 `:core:model`
 *
 * 它是纯函数、零 Android 依赖（`PageLayoutMetrics` 也在这里），所以这套规则能在
 * JVM 上用真实数字反复试：411 dp 的手机、800 dp 的平板、横屏、32 sp 的大字号，
 * 全部是普通单元测试，不需要模拟器。
 *
 * 单位：除 [MIN_EDGE_DP] 与 [density] 外全部是**像素**。`density` 传
 * `DisplayMetrics.density`（不是 densityDpi）—— 这个函数要的是 dp→px 的倍率。
 */
object PageGeometry {

    /** 最小留白（dp）。用户把边距调到 0 也至少留这么多：贴边的长行很难读。 */
    const val MIN_EDGE_DP = 12f

    /** 正文至少要有几个字宽（em）。低于这个数，边距就得让位。 */
    const val MIN_CONTENT_EM = 12f

    /** 一行最多几个字宽（em）。CJK 一个字约 1 em，所以是"最多 40 个汉字"。 */
    const val MAX_LINE_EM = 40f

    /** 上下边距各占高度的上限比例。正文至少留一半高度。 */
    const val MAX_VERTICAL_EDGE_RATIO = 0.25f

    /**
     * 让位口径下，固定让出的**总量**最多能占屏高的比例（M33）。
     *
     * 顶栏 + 空隙是调用方给的常量，但常量也可能被喂进一个荒谬的值
     * （横屏时屏高只有 360 dp，顶栏 52 dp 就吃掉七分之一）。这里兜一个底：
     * 上下加起来让出的高度不超过 40%，正文永远留 60% —— 一页三行字的版面不叫版面。
     *
     * 注意是**总量**而不是各自：两头各让 40% 会让正文一行不剩。
     */
    const val MAX_RESERVED_RATIO = 0.4f

    /**
     * 求解一屏的正文框。
     *
     * @param widthPx 可用宽度（已经扣掉工具栏 / 翻页条之后的真实区域）
     * @param heightPx 可用高度
     * @param userMarginPx 用户设置的边距（mm 换算成 px 之后传进来）
     * @param textSizePx 正文字号（像素）。行宽上限按它算，所以字号变了行宽也跟着变。
     * @param density `DisplayMetrics.density`（dp → px）
     * @param topReservePx 顶部必须让出的固定高度（M33）。大于 0 时**纵向**改用
     *   "让位"口径：`marginTopPx == topReservePx`，而不再跟着 [userMarginPx] 走。
     *   0 = 沿用原来的对称纵向边距规则。
     *   注意它描述的是**读者想要的呼吸**，不是控件高度：顶栏/托盘是浮层，
     *   会盖在正文上，所以从 M34 起这里传的是几 dp 的留白（见 `READER_TOP_BREATH`）。
     * @param bottomReservePx 底部固定留白（M33）。只有 [topReservePx] 大于 0 时才生效。
     */
    fun solve(
        widthPx: Int,
        heightPx: Int,
        userMarginPx: Float,
        textSizePx: Float,
        density: Float,
        topReservePx: Float = 0f,
        bottomReservePx: Float = 0f,
    ): PageLayoutMetrics {
        // 任何一项为 0 都会让后面的除法与 coerce 失去意义（宽度 0 时 margin 会变成负的）。
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val dp = density.coerceAtLeast(0f)
        val size = textSizePx.coerceAtLeast(1f)
        val wanted = userMarginPx.coerceAtLeast(0f)

        val minEdge = MIN_EDGE_DP * dp
        val minContent = MIN_CONTENT_EM * size
        val maxContent = MAX_LINE_EM * size

        // ── 水平 ────────────────────────────────────────────────────────────
        // 边距的上限由"正文至少 minContent 宽"反推。窄屏上这个上限比 minEdge 还小，
        // 那时以它为准（宁可不留白，也要有 12 个字）。
        val edgeCapH = ((width - minContent) / 2f).coerceAtLeast(0f)
        val edgeH = maxOf(minEdge, wanted).coerceAtMost(edgeCapH)

        val contentW = (width - 2f * edgeH)
            .coerceAtLeast(1f)
            .coerceAtMost(maxContent.coerceAtLeast(1f))

        // 居中：行宽被封顶时，多出来的宽度平均分到左右。
        val marginH = (width - contentW) / 2f

        // ── 垂直 ────────────────────────────────────────────────────────────
        //
        // 两种口径，由调用方选一个：
        //
        // 1. **让位口径**（M33，`topReservePx > 0`）：纵向的两头由那两个常量决定。
        //    阅读界面的控件是浮层，会盖住正文；而"毫米"是纸书的**左右**边距概念，
        //    它在手机上换算成纵向留白时大得离谱 —— 默认 4 mm 就有 45 px，
        //    用户把边距调到 9 mm 时上下各空出 58 dp，一页少三行，页面看着"不饱满"。
        //    所以纵向不再跟着毫米走：两头只留一口气（M34 起是 8 dp 量级）。
        //    两者都是**常量**，不随控件显隐变化 —— 否则显示/隐藏顶栏就会重排。
        //
        // 2. **对称口径**（`topReservePx <= 0`）：纵向边距 = 用户边距，上限四分之一屏高。
        //    排版预览、以及不浮顶栏的地方用它，也是这条规则最初的形态。
        val reserveTop = topReservePx.coerceAtLeast(0f)
        if (reserveTop > 0f) {
            val reserveBottom = bottomReservePx.coerceAtLeast(0f)
            // 顶栏再高也不能把正文挤没：两头让出的**总量**封顶，按原比例压缩
            // （等比压缩而不是"先保顶栏、底部归零" —— 后者会让顶栏独吞全部预算）。
            val maxReserved = height * MAX_RESERVED_RATIO
            val requested = reserveTop + reserveBottom
            val scale = if (requested > maxReserved && requested > 0f) {
                maxReserved / requested
            } else {
                1f
            }
            val top = reserveTop * scale
            val bottom = reserveBottom * scale
            return PageLayoutMetrics(
                contentWidthPx = contentW.toInt().coerceAtLeast(1),
                contentHeightPx = (height - top - bottom).toInt().coerceAtLeast(1),
                marginLeftPx = marginH,
                marginRightPx = marginH,
                marginTopPx = top,
                marginBottomPx = bottom,
            )
        }

        val edgeCapV = height * MAX_VERTICAL_EDGE_RATIO
        val edgeV = maxOf(minEdge, wanted).coerceAtMost(edgeCapV)
        val contentH = (height - 2f * edgeV).coerceAtLeast(1f)

        return PageLayoutMetrics(
            contentWidthPx = contentW.toInt().coerceAtLeast(1),
            contentHeightPx = contentH.toInt().coerceAtLeast(1),
            marginLeftPx = marginH,
            marginRightPx = marginH,
            marginTopPx = edgeV,
            marginBottomPx = edgeV,
        )
    }
}
