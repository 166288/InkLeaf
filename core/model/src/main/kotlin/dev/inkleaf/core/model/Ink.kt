package dev.inkleaf.core.model

/**
 * 笔迹的语义分类 —— 决定它在重排后如何表现（§6.3.3）。
 *
 * 这个分类不是"锦上添花"的标签，而是唯一能让"下划线跟着文字走、边注不跟着走"
 * 这件事有明确定义的东西。它的来源有据可依：微软专利 US8250463B2 正是按
 * "笔迹的墨迹是否依赖文本行重排"来分类的。
 */
enum class InkReflowSemantics {
    /**
     * 跟随文本：下划线、删除线、圈选、行内小标记。
     *
     * 判定条件：笔迹的墨迹盒与某一行的行盒重合度 ≥ 60%（见 [Box.coverageRatio]）。
     * 重排后按 em 坐标重新映射到新行盒。
     */
    FOLLOWER,

    /**
     * 固定在页面上：整页手写、跨多行的长箭头、手绘图形、页边距涂鸦。
     *
     * 不随文本移动。只在"页"这个层级有效（EPUB 无固定页，因此 EPUB 上
     * 这类笔迹会被吸附到最近的页边界并在版式变化时提示用户）。
     */
    PINNED,

    /**
     * 自由笔迹：不属于以上两类，或用户显式指定"锁在原地"。
     * 版式变化时不动，只在必要时提示可能错位。
     */
    FREE,
}

/** 笔刷规格。 */
data class BrushSpec(
    val name: String,
    val colorArgb: Int,
    /**
     * 笔宽，单位 em（不是像素！）。
     *
     * 这是刻意的：见 §5.7，Android 14+ 的非线性字号缩放会让"px 笔宽"
     * 在高倍字号下显不出应有的粗细比例；而 em 笔宽在任何字号下都成立。
     */
    val widthEm: Float,
    /** 压感开关。开启后笔宽随压力线性插值到 [widthEm] × pressureScaleMax。 */
    val pressureEnabled: Boolean = true,
    val pressureScaleMax: Float = 2.0f,
    /** 是否使用系统笔迹预测/美化（Jetpack Ink 的低延迟路径）。 */
    val lowLatency: Boolean = true,
    /**
     * 叠印笔（荧光笔）：这一笔是**叠在文字之上**的半透明标记，不是覆盖。
     *
     * 这一个开关带出三件事，所以它必须在模型层而不是渲染层：
     *
     * 1. **混色方向由纸色决定**（见 `InkStrokeRenderer.paperIsDark`）：浅色纸正片叠底、
     *    深色纸滤色。反过来会分别得到"标记整个消失"与"字被压暗"。
     * 2. **重排语义固定为 [InkReflowSemantics.FOLLOWER]**：它贴着某一行，行搬到哪它
     *    到哪，绝不参与"挪到空白处避让文字"的落位（`PinnedPlacement`）——
     *    那正是"标亮这一段"变成"涂掉这一段"的方向。
     * 3. **仍然参与擦除**：`StrokeHitTest` 只跳过全透明的笔（橡皮自身），
     *    半透明色照样能被擦到。
     *
     * 具体的混色代码在 `:core:ink`；这里只存"这是不是叠印笔"，因为 `:core:model`
     * 不依赖 Android（`BlendMode` 在这里不可用）。
     */
    val overprint: Boolean = false,
) {
    companion object {
        /**
         * 钢笔：细分明的硬笔。
         *
         * 与圆珠笔的差别**只在笔宽与压感幅度**，不在颜色：现实中两者的墨色是同一个黑，
         * 差别是笔尖粗细与"用力压会不会变粗"。给它们编两种颜色会让用户以为这是
         * 两种颜料，而不是同一种字迹的两种笔锋。
         */
        val DEFAULT_PEN = BrushSpec(name = "pen", colorArgb = 0xFF1A1A1A.toInt(), widthEm = 0.04f)

        /**
         * 圆珠笔：比钢笔更细、压感更不明显（笔尖是滚珠，压力对线宽影响小）。
         *
         * [pressureScaleMax] 特意压到 1.35（钢笔是 2.0）：这不是美学选择，
         * 而是"换了一支笔"必须能被看出来 —— 否则两档笔刷只有 0.01em 的差别，
         * 用户会认为按钮坏了。
         */
        val DEFAULT_BALLPOINT = BrushSpec(
            name = "ballpoint",
            colorArgb = 0xFF1A1A1A.toInt(),
            widthEm = 0.026f,
            pressureScaleMax = 1.35f,
        )

        /**
         * 马克笔：宽、不透明、不走压感 —— 用来圈画与强调，不用来写字。
         *
         * 与荧光笔的分工：荧光笔是**半透明**的（0x66 alpha），压在字上还能读；
         * 马克笔是不透明的，压在字上就是盖住。两者都有存在价值，但混为一谈
         * 会让"标亮这一段"变成"涂掉这一段"。
         */
        val DEFAULT_MARKER = BrushSpec(
            name = "marker",
            colorArgb = 0xFFE0573F.toInt(),
            widthEm = 0.14f,
            pressureEnabled = false,
        )

        /**
         * 荧光笔：宽、半透明、叠印 —— 用来标亮，不用来写字。
         *
         * **笔宽是行高口径，不是笔尖口径。** 设计（M25 §3 S2）要求"笔宽取正文行高的
         * 0.8～1.0 倍"，而 `widthEm` 的单位是 em（相对字号），所以必须先经
         * [LINE_HEIGHT_EM] 把行高翻译成 em：1.30em ≈ 0.9 × 1.45 行高，取区间中点。
         * 这也解释了它为什么**不能共用**普通笔那条 0.015～0.18em 的粗细滑杆 ——
         * 单位相同，量级差了将近十倍，共用滑杆等于让"最粗的笔"只有荧光笔的八分之一宽。
         *
         * alpha 取 0x59（89/255 ≈ 0.35）：再低在白纸上几乎看不见，再高压在字上就读不清。
         *
         * [lowLatency] 关掉：荧光笔是"大面积抹过去"，不是"写连笔字"，
         * 系统笔迹预测给不出有用的东西，反而把一条直线篡改成带圆角的曲线。
         */
        val DEFAULT_HIGHLIGHTER = BrushSpec(
            name = "highlighter",
            colorArgb = 0x59FFD54F,
            widthEm = 1.30f,
            pressureEnabled = false,
            lowLatency = false,
            overprint = true,
        )
        val DEFAULT_ERASER = BrushSpec(name = "eraser", colorArgb = 0x00000000, widthEm = 0.20f, pressureEnabled = false)

        /**
         * 工具栏里可选的三支笔，顺序即界面顺序（细 → 中 → 粗）。
         *
         * 这是一个**列表**而不是三个常量各自被界面引用：界面的循环与
         * "哪些笔刷算书写工具"的判断因此只有一处，加第四支笔时不会漏掉某个 case。
         */
        val PEN_BRUSHES: List<BrushSpec> = listOf(DEFAULT_BALLPOINT, DEFAULT_PEN, DEFAULT_MARKER)

        /**
         * 工具栏里可选的全部笔，顺序即界面顺序（细 → 中 → 粗 → 叠印）。
         *
         * 荧光笔排在最后而不是插在中间：它与前三支不是同一个维度的选择
         * （那三支是"用什么写字"，这一支是"要不要标亮"），界面也据此给它单独一档
         * 笔宽口径（[HIGHLIGHTER_WIDTH_EM_MIN] … [HIGHLIGHTER_WIDTH_EM_MAX]）。
         * 加第五支笔时只改这一处。
         */
        val TOOL_BRUSHES: List<BrushSpec> = PEN_BRUSHES + DEFAULT_HIGHLIGHTER

        /**
         * 正文行高相对字号的倍数（em）。
         *
         * 与 `DocumentTheme` 的默认 `lineSpacingMultiplier` 是同一个数。荧光笔的笔宽
         * 口径是"行高的 0.8～1.0 倍"，而本模块只认 em —— 没有这个数就没法把"行高"
         * 翻译成 `widthEm`。
         *
         * 用户在设置里改行距时它**不会**跟着变，这是刻意接受的近似：让每一笔都记下
         * 当时的行距，等于给 `BrushSpec` 加一个每笔都冗余的字段，而"荧光笔比行稍窄一点"
         * 这个观感在 1.2～2.0 倍行距下都不破。
         */
        const val LINE_HEIGHT_EM = 1.45f

        /** 荧光笔笔宽下限：行高的 0.8 倍 = 1.16em。 */
        const val HIGHLIGHTER_WIDTH_EM_MIN = 1.16f

        /** 荧光笔笔宽上限：行高的 1.0 倍 = 1.45em。 */
        const val HIGHLIGHTER_WIDTH_EM_MAX = 1.45f

        /** 叠印笔（荧光笔）的半透明度：0x59 / 255 ≈ 0.35。 */
        const val HIGHLIGHTER_ALPHA = 0x59

        /**
         * 可选墨色。
         *
         * 第一批四色（黑 / 朱红 / 松绿 / 靛蓝）是"看得清我写过什么"的底线：
         * 手写批注的第一需求不是调色，颜色多了反而让"这一笔为什么是这个颜色"
         * 变成一个需要管理的问题。
         *
         * 后来加到八色，是因为真实的用法变成了**在一页里区分不同轮次**：
         * 第一遍读用黑、复习时用红、查资料补的用蓝、自己存疑的用黄褐 ——
         * 这四种意思只有四色时只能互相挤。新增的四种刻意选**低饱和的墨色**
         * （朱橙 / 赭棕 / 深青 / 紫），压在白纸与夜间纸上都还认得出是"笔"，不是荧光笔。
         *
         * 顺序固定，界面按 [BrushSpec.copy] 换色时不会改变笔刷身份（name 不变）。
         * 顺序也是按色相排的：同一族（红—橙—棕）挨着，用户扫一眼就知道往哪找。
         */
        val PEN_COLORS: List<Int> = listOf(
            0xFF1A1A1A.toInt(), // 墨黑
            0xFFB3261E.toInt(), // 朱红
            0xFFE0573F.toInt(), // 朱橙（马克笔的默认色，归入色板后它也有"已选中"可看）
            0xFF7A4B2A.toInt(), // 赭棕
            0xFF2E7D32.toInt(), // 松绿
            0xFF00695C.toInt(), // 深青
            0xFF1F5FA8.toInt(), // 靛蓝
            0xFF6A3E9B.toInt(), // 紫
        )

        /**
         * 荧光色板（给叠印笔用）。
         *
         * 与 [PEN_COLORS] 分开，是因为那八色**全不透明** —— 拿去当荧光笔会立刻破掉
         * "压在字上还能读"这件事，变成"涂掉这一段"。这里六色一律带 [HIGHLIGHTER_ALPHA]，
         * 且都是**高饱和亮色**：荧光笔的物理特性就是"在纸上抹一层发亮的颜料"，
         * 低饱和的墨色（赭棕、深青）抹上去与"没抹匀"看不出区别。
         *
         * 顺序固定，界面按 [BrushSpec.copy] 换色时不改变笔刷身份。
         */
        val HIGHLIGHTER_COLORS: List<Int> = listOf(
            0x59FFD54F, // 荧光黄（默认）
            0x5969F0AE, // 荧光绿
            0x5982B1FF, // 荧光蓝
            0x59FF80AB, // 荧光粉
            0x59FFB74D, // 荧光橙
            0x59B388FF, // 荧光紫
        )
    }
}

/**
 * 输入笔迹（尚未锚定、尚未持久化）。
 *
 * 刻意设计成一个接口而不是 data class：真实实现在渲染层要复用
 * Jetpack Ink 的 [androidx.ink.strokes.StrokeInputBatch] 缓冲区，
 * 不能每笔都做一次 FloatArray 拷贝（§5.2 的延迟预算不允许）。
 */
interface StrokeInput {
    val brushId: String
    val startTimeMs: Long
    /** 以 x0,y0,x1,y1,... 排列的点。 */
    val points: FloatArray
    /** 与 points 等长的压力序列，取值 0..1；不支持压感时全为 1。 */
    val pressures: FloatArray
    val semantics: InkReflowSemantics
}

/** 供单测与导入用的最简单实现。渲染层不要用它。 */
class SimpleStrokeInput(
    override val brushId: String,
    override val startTimeMs: Long,
    override val points: FloatArray,
    override val pressures: FloatArray = FloatArray(points.size / 2) { 1f },
    override val semantics: InkReflowSemantics = InkReflowSemantics.FOLLOWER,
) : StrokeInput {
    init {
        require(points.size % 2 == 0) { "points 必须是 x,y 成对" }
        require(pressures.size == points.size / 2) { "pressures 长度必须等于点数" }
    }
}

/**
 * 一段已经锚定的笔迹。
 *
 * [localPoints] 的坐标系是**行局部空间，单位 em，原点 = 行盒左上角**。
 * 这是"锚定精度 L2"的全部含义 —— 存的是"在这一行的哪个相对位置"，
 * 而不是"在屏幕/文档的哪个绝对像素"。绝对像素在字号变化后立刻失效。
 *
 * 但"不随文本移动"的那两类语义（[InkReflowSemantics.PINNED] / [InkReflowSemantics.FREE]）
 * 恰恰要的就是**绝对位置**：[frozenMatrix] 存下它们落笔那一刻的 em → 文档坐标变换，
 * 于是行被搬到别处时它们不动（方案 §6.3.3 "墨迹不平移不缩放"）。
 * 两个几何量各有各的用途，不是二选一的兜底关系：
 *
 * | 字段 | 谁用 | 排版变了会怎样 |
 * | --- | --- | --- |
 * | [localPoints] | FOLLOWER 的绘制、以及任何重挂 | 跟着行盒走 |
 * | [frozenMatrix] | PINNED / FREE 的绘制 | 一动不动（这是"固定"的定义） |
 */
data class StrokeAnchor(
    val strokeId: String,
    /** 该段笔迹所属的行（结构型锚点）。 */
    val anchor: Selector.Structural,
    val semantics: InkReflowSemantics,
    val brushId: String,
    /** em 空间的点序列，x0,y0,x1,y1,... */
    val localPoints: FloatArray,
    val localPressures: FloatArray,
    /**
     * 落笔那一刻的"行局部 em → 文档坐标"变换（[LineBox.matrixInDoc]），
     * 只有 PINNED / FREE 才有；FOLLOWER 必须是 null（它们的几何由**当前**行盒决定，
     * 存一份冻结变换等于把"跟随文字"这件事写坏）。
     *
     * 存变换而不是存展开后的文档坐标点：变换只用 6 个浮点，且顺手把**字号**
     * （= 笔宽 em → px 的比例）也带上了 —— 冻结的是"这一笔当时在页面上长什么样"。
     *
     * 旧数据（这次改动之前写下的）一律为 null：那时根本没有存过冻结坐标，
     * 信息不可逆地缺了。它们仍按老办法跟随最近一行绘制，界面会如实说出来。
     */
    val frozenMatrix: Affine2D? = null,
)
