package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.LineBox

/**
 * PINNED 墨迹的落位求解 —— 方案 §6.3.3 里"**只重新摆放到目标行附近的空白区**"的实现。
 *
 * ## 它解决的是哪一半问题
 *
 * 冻结语义（`InkReflowSemantics.PINNED`）把墨迹的形状与位置一起冻在落笔那一刻：
 * 形状是对的（用户写的就是这个样子，重排不许把它拉伸），但**位置只是当时对**。
 * 改一次字号、旋转一次屏幕、缩一次正文栏之后，原来那条"贴着第 200 行左边"的
 * 边注可能：
 *
 *   1. **已经离目标行很远** —— 文字整体重排，第 200 行跑到下面几百像素处，
 *      边注还留在上面，看上去像挂在别的段落上；
 *   2. **压在正文上** —— 它原来落在页边/行间的空白里，那一片空白被文字填上了；
 *   3. 与**另一条笔记**挤在一起（多条笔记的目标行被重排挤到了一处）。
 *
 * 三种情形是同一件事的三个面："这个位置不再合适了"。本对象给出的补救是
 * [solve]：**把墨迹整块平移到目标行旁边的空白处**，除此之外什么都不改。
 * 于是 PINNED 在用户眼里的含义变得一句话说得清：
 *
 * > 笔迹是钉在**那一行**上的，不是钉在屏幕第几像素上的 —— 像一张便利贴。
 *
 * 三种重排语义由此各就各位，互相之间没有含混：
 *
 * | 语义 | 形状 | 位置 |
 * | --- | --- | --- |
 * | FOLLOWER | 按当前行盒的 em 空间换算（字号变了就跟着变） | 跟着行盒走 |
 * | PINNED | **不变**（落笔那一刻的字号与比例） | 重新摆到目标行旁边（本对象） |
 * | FREE | 不变 | 不动（与行盒无关，只随页面滚动） |
 *
 * ## 为什么"重新摆放"是必要的，而不是可选的
 *
 * 不摆的代价是不对称的。位置偏一点，用户看得到（笔记还在屏幕上，只是看着不像
 * 挂在那一段上），改挂一次就能救回来；而**压在正文上**是另一种错：
 * 那几行字被盖住了，而墨迹是画在文字上面的 —— 一页上只要有一条边注压在正文上，
 * 那次字号调整就变成了"我的书被弄坏了"。
 *
 * ## 规则（全部是刻意的，没有一条是顺手写的）
 *
 *   1. **只做纵向平移。** 横向不动：笔记写在左边距还是右边距、离栏多远，
 *      都是用户当时的选择；把它横向搬到另一侧是"替用户改主意"。
 *   2. **只平移，不缩放不旋转。** 形状、比例、笔宽都还是落笔那一刻的
 *      （§6.3.3 "不平移不缩放"里的"不平移"指的正是**相对自身**不发生形变）。
 *   3. **落点从目标行盒的顶边开始，只往下找。** 往上找会把边注放到它标注的
 *      那句话**上面**（阅读顺序反了），而下推正是 §6.3.3 第 3 步说的"级联下推"。
 *      注意这说的是**搜索方向**：笔记相对自己的旧位置可能上移也可能下移
 *      （旧位置在目标行下方时就是上移），但绝不会跑到目标行盒的上方。
 *   4. **已经贴着目标行、又没压着字的笔记一动不动。**
 *      判据是 [isBeside] 与 [coversText]。这一条挡住了绝大多数"无谓的挪动"：
 *      排版没变、或者只变了无关的地方时，用户的笔记必须逐像素留在原处。
 *   5. **不动的笔记也算占着地方。** 挪动的那条不许摆到另一条（没动的）笔记身上 ——
 *      否则"同一次解析"里就会造出叠在一起的两条笔记。
 *   6. **锚点解不开的笔记不参与求解。** 没有目标行就没有"附近"可言，
 *      留在落笔时的位置上是唯一诚实的答案（M3.1 的有界例外）。
 *   7. **放不下就留在原处，并如实上报**（[Placement.blocked]）。
 *      不发明一个"看起来还行"的位置 —— 那正是本工程禁止的第四种状态。
 *      方案 §6.3.3 第 4 步的"折叠为批注堆叠角标"是界面的补救了，不在这里。
 *
 * ## 一条可验证的性质：幂等
 *
 * 把 [solve] 的输出（已经摆好的位置）再喂给 [solve]，结果必须一个字都不变。
 * 这不是"顺手成立"：它要求"摆好之后判据就成立"（规则 4）与"摆放在同一处时
 * 得到同一个答案"（确定顺序 + 不动者占位，规则 5）同时满足。
 * 少了任何一条，症状都是**每打开一次文档，边注往下挪一点**。
 * 这条由 `PinnedPlacementTest` 里的幂等测试钉住。
 *
 * ## 不做的事（写清楚，免得下次有人以为漏了）
 *
 *   - **`FREE` 笔迹不参与**：它的定义是"不跟随文字，只随页面滚动"，
 *     与行盒无关，也就没有"目标行附近的空白"这回事。调用方负责筛（[Item] 只收 PINNED）。
 *   - **不解决"两条静止笔记互相重叠"**：只有当一条笔记因为规则 3/4 必须动时，
 *     它才会避开别的笔记。用户自己叠在一起写的两条、且两条都合格地待在原处的，
 *     重排后仍然叠在一起 —— 那是他写的，不是我们摆的。
 *   - **不改数据**：求解结果只在渲染时生效，库里那条锚点与冻结几何一个字节都不动。
 *     否则"打开文档"就变成了一次改数据的操作，而用户只是翻了翻书。
 *   - **页内归一化坐标**（方案 §6.4）没做：横向位移仍按像素冻结，页宽改变
 *     （横竖屏、改页边距）时不会等比跟随。补齐它之后，规则 1 才谈得上放宽。
 *
 * ## 前置条件
 *
 *   - [solve] 的 [LineBox] 列表必须**自上而下有序**（`LayoutFacts.lines` 的契约），
 *     且行与行在纵向上不互相重叠（重排排版与 PDF 文本层都是这样：一行一个行距带）。
 *     有了前者，[coversText] 才能用二分找到"这一片附近的行"，而不是每问一次就扫全篇 ——
 *     十万字的书有几万行，逐行扫会让每次打开文档都卡一下。
 *   - [Item.id] 必须唯一（同一份 [solve] 入参里）。它以"标注 id"的身份被用来
 *     把结果对回每条笔记；重号会让两条笔记互换位移。
 */
object PinnedPlacement {

    /**
     * 判"压在正文上"时，笔记自身有多大的比例落在行盒上才算数。
     *
     * 不取 0.0（碰一下就挪）：用户在页边写字时笔尖偏一点、行盒又按"墨迹盒"给得很紧，
     * 一两像素的擦碰非常常见，把这种笔记挪走是替用户做主。
     * 也不取 0.5（盖住一半才挪）：笔记盖住小半行字，那几行字就已经读不出来了。
     *
     * 0.25 落在两者之间，而且它的分母是**笔记自己**（小的那个）：
     * "这几行字读不出来了"取决于笔记盖了多大一片，而不取决于那行字有多长。
     *
     * 落位的判据与触发的判据是**同一把尺子**：挪到一个"我们本来就不认为它压着字"
     * 的位置。要求一像素都不碰字是做不到的 —— 页面上本来就有墨迹盒被行盒切到的地方
     * （标点、下伸部），而做不到就只剩"放不下"一条路，那比轻轻碰到一下更糟。
     */
    const val COVER_RATIO = 0.25f

    /** 向下找空白时的步长。取 4px：够细（不会漏掉相邻两行之间）也够快。 */
    const val SLIDE_STEP_PX = 4f

    /** 两条**被摆到一起**的笔记之间至少要留的纵向间距，免得挤成一条线。 */
    const val MIN_GAP_PX = 8f

    /**
     * 判定"还算贴着目标行"时允许的上下余量（以目标行自身的高度为单位）。
     *
     * 取一个行高：笔记常常写在两行之间（段间、行间的空白），严格按行盒判会把它
     * 判成"离得很远"而无谓地挪一下 —— 而它本来就没压着任何字。
     * 一个行高之外才叫"跑到别处去了"（那时它多半已经对着别的段落了）。
     */
    const val BESIDE_SLACK_RATIO = 1f

    /**
     * 最多向下找多远（1500px 大约 20 行）。
     *
     * 没有这个上限，一篇十万字的文档里"找不到空白"会让搜索走遍全篇（几十万步）；
     * 而一千五百像素之外的空白也**不叫**"目标行附近" ——
     * 把边注扔到几页之外，位置的信息量还不如它原来那个。
     */
    const val MAX_SLIDE_PX = 1_500f

    /**
     * 一条待落位的笔记。
     *
     * [box] 是它**当前**在文档坐标里的包围盒（即冻结几何画出来的那个位置），
     * 也就是"如果不动，它会占住哪一片"。求解只回答"要不要整体挪多少"，
     * 所以这里只给它一个盒子、不给点集：形状在本对象里根本不参与判断。
     */
    data class Item(
        val id: String,
        val box: Box,
        /** 解析结果给出的目标行。为 null = 锚点解不开，[solve] 会原样放过它。 */
        val targetLine: LineBox?,
    )

    /**
     * 一条笔记的落位结果。
     *
     * [dy] 是**整体纵向位移**（0 表示不动）。后面三个布尔量是"为什么"，
     * 界面与解析记录靠它们说清"这条笔记为什么不在我写的地方"。
     */
    data class Placement(
        val id: String,
        val dy: Float,
        /** `dy != 0`。为 false 时调用方什么都不用做（也不必解释）。 */
        val moved: Boolean,
        /** 它当前压在正文上（笔记自身 ≥ [COVER_RATIO] 的面积落在某条行盒上）。 */
        val coveringText: Boolean,
        /** 它当前已经离目标行太远（连"贴着那一行"都算不上）。 */
        val leftTargetLine: Boolean,
        /** 要挪、但目标行附近找不到空白：留在原处，什么都不改。 */
        val blocked: Boolean,
    )

    /**
     * 求每一条笔记的纵向位移。返回顺序与 [items] 一致，一条不少。
     *
     * @param bottomPx 可用纵向区间的下界（文档/页面的内容底边）：笔记整体掉到它以下
     *        就没有"摆放"可言了。重排格式传排版总高度，固定版式传堆叠页面的高度。
     */
    fun solve(items: List<Item>, lines: List<LineBox>, bottomPx: Float): List<Placement> {
        if (items.isEmpty()) return emptyList()

        // 第一遍只算**事实**，与顺序无关：压不压字、算不算贴着目标行
        // （于是"谁要挪、谁不动"在下决定之前就已经确定，规则 5 才有依据）。
        val covering = HashMap<String, Boolean>(items.size)
        val leftTargetLine = HashMap<String, Boolean>(items.size)
        val moving = HashSet<String>(items.size)
        val occupied = ArrayList<Box>(items.size)
        for (item in items) {
            val covers = coversText(item.box, lines)
            covering[item.id] = covers
            val line = item.targetLine
            if (line == null) {
                // 锚点解不开 ⇒ 没有"目标行附近"可言。M3.1 的有界例外：位置是落笔时
                // 存下的，不是猜的，所以留在原处（挪走才是把用户的墨迹搬到自己编的位置上）。
                leftTargetLine[item.id] = false
                occupied += item.box
                continue
            }
            val beside = isBeside(item.box, line)
            leftTargetLine[item.id] = !beside
            if (covers || !beside) {
                moving += item.id
            } else {
                occupied += item.box
            }
        }

        // 顺序必须**确定**：同一份输入两次求解必须给出同一个结果，否则"打开两次，
        // 边注跳到两个地方"。按"目标行位置 → 当前盒子位置 → id"排，
        // 于是同一行的多条笔记按它们原来的先后依次落位。
        val ordered = items
            .filter { it.id in moving }
            .sortedWith(
                compareBy(
                    { it.targetLine?.rectDoc?.top ?: Float.MAX_VALUE },
                    { it.box.top },
                    { it.id },
                ),
            )

        val decided = HashMap<String, Placement>(items.size)
        for (item in items) {
            if (item.id !in moving) {
                decided[item.id] = Placement(
                    id = item.id,
                    dy = 0f,
                    moved = false,
                    coveringText = covering.getValue(item.id),
                    leftTargetLine = leftTargetLine.getValue(item.id),
                    blocked = false,
                )
            }
        }

        for (item in ordered) {
            // `ordered` 里的每一条都有目标行（没有目标行的在 `moving` 之外）。
            val line = item.targetLine!!
            val top = firstFreeTop(item.box, line, lines, occupied, bottomPx)
            if (top == null) {
                decided[item.id] = Placement(
                    id = item.id,
                    dy = 0f,
                    moved = false,
                    coveringText = covering.getValue(item.id),
                    leftTargetLine = leftTargetLine.getValue(item.id),
                    blocked = true,
                )
                continue
            }
            val dy = top - item.box.top
            occupied += item.box.translate(0f, dy)
            decided[item.id] = Placement(
                id = item.id,
                dy = dy,
                // `moved` 说的是"位置真的变了"：dy 恰好为 0 时（它已经站在该站的地方）
                // 调用方不该去改点集，也不该给用户讲一段"它被挪过"的故事。
                moved = dy != 0f,
                coveringText = covering.getValue(item.id),
                leftTargetLine = leftTargetLine.getValue(item.id),
                blocked = false,
            )
        }

        // 按入参顺序还回去：调用方是**按下标**把结果对回每条笔记的，
        // 顺序一旦跟着排序走，位移就会被加到另一条笔记身上（症状是两条笔记互换位置）。
        return items.map { decided.getValue(it.id) }
    }

    /**
     * 这一条笔记当前是否压在正文上。
     *
     * 判据是"笔记自身有多少比例落在**某一条**行盒上"（≥ [COVER_RATIO]）。
     * 取某一条而不是"总和"：压在两条行盒上各 15% 的笔记，那两条字同样读不出来。
     *
     * 面积为 0 的退化盒子（一条完全垂直或水平的线）恒返回 false：它没有"盖住"的
     * 面积可言，把它算成压字会让一笔竖线在页边反复被搬走。
     */
    private fun coversText(box: Box, lines: List<LineBox>): Boolean {
        if (box.area <= 0f) return false
        return anyLineNear(box, lines) { line ->
            val overlap = box.intersection(line.rectDoc).area
            overlap / box.area >= COVER_RATIO
        }
    }

    /**
     * 这条笔记是否还算"贴着"它的目标行。
     *
     * 允许上下各 [BESIDE_SLACK_RATIO] 个行高的余量：笔记常常写在两行之间
     * （段间、行间的空白），严格按行盒判会让这种笔记被判成"离得很远"而无谓地挪一下 ——
     * 而它本来就没压着任何字。一个行高之外才叫"跑到别处去了"。
     */
    private fun isBeside(box: Box, line: LineBox): Boolean {
        val row = line.rectDoc
        val slack = row.height * BESIDE_SLACK_RATIO
        return box.top < row.bottom + slack && box.bottom > row.top - slack
    }

    /**
     * 从目标行的顶边起向下找第一个"不与任何行盒相交、也不与已占位的笔记挤在一起"的落点。
     *
     * 返回的是新的 `box.top`（不是位移）：位移 = 它减去原 `box.top`，
     * 这样"往哪儿放"与"放哪儿去了"分开，读起来不会混。
     *
     * 找不到返回 null（[Placement.blocked] 那条路）。
     */
    private fun firstFreeTop(
        box: Box,
        line: LineBox,
        lines: List<LineBox>,
        occupied: List<Box>,
        bottomPx: Float,
    ): Float? {
        val limit = minOf(bottomPx, line.rectDoc.top + MAX_SLIDE_PX)
        var top = line.rectDoc.top
        while (top + box.height <= limit) {
            val candidate = box.translate(0f, top - box.top)
            val crowded = occupied.any { inflate(it, MIN_GAP_PX).intersects(candidate) }
            if (!crowded && !coversText(candidate, lines)) return top
            top += SLIDE_STEP_PX
        }
        return null
    }

    /**
     * 遍历**纵向落在 [box] 附近**的行盒，命中一条就停（[test] 返回 true）。
     *
     * 为什么要有这个函数：判"压不压字"每次都要问"这一片有没有行"，
     * 而十万字的书有几万行。行盒按 `top` 有序（见类文档的前置条件），
     * 于是二分找到起点、只前后扫那些纵向真的有重叠的行 —— 复杂度 O(log n + k)。
     *
     * 向后扫的终止条件是"某一行的底边已经在盒子上方"：行与行不重叠的前提下，
     * 再往前不可能有别的重叠。
     */
    private inline fun anyLineNear(box: Box, lines: List<LineBox>, test: (LineBox) -> Boolean): Boolean {
        var i = firstLineWithTopAtOrBelow(lines, box.top)
        var j = i - 1
        while (j >= 0) {
            val line = lines[j]
            if (line.rectDoc.bottom <= box.top) break
            if (test(line)) return true
            j--
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.rectDoc.top >= box.bottom) break
            if (test(line)) return true
            i++
        }
        return false
    }

    /** 第一个 `rectDoc.top >= y` 的下标（行盒按 top 有序，见类文档）。 */
    private fun firstLineWithTopAtOrBelow(lines: List<LineBox>, y: Float): Int {
        var lo = 0
        var hi = lines.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].rectDoc.top < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun inflate(box: Box, amount: Float): Box =
        Box(box.left - amount, box.top - amount, box.right + amount, box.bottom + amount)
}
