package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PINNED 落位求解的测试（方案 §6.3.3）。
 *
 * 这里断言的是**规则、不变量与边界**，不是"某个坐标等于某个数"：
 *
 *   - "已经贴着目标行、又没压着字的笔记一个像素都不动"（得到它的那一行才算完）；
 *   - "离目标行很远（或压在正文上）的笔记被整块挪到那一行旁边的空白"
 *     —— 只平移：横竖都不缩放、横向一个像素都不动；
 *   - 挪完之后**真的不压字**（用同一把尺子回验，而不是信实现自己说的话）；
 *   - 两条笔记抢同一处空白时**级联下推**（§6.3.3 对 Q12 的回应），
 *     而且**不动的笔记也算占着地方**（不许把一条摆到另一条身上）；
 *   - 放不下就**留在原处并如实上报**（不许发明位置）；
 *   - **幂等**：摆好之后再解一次，一条都不动（否则每打开一次文档就往下挪一点）。
 *
 * 坐标都是手算过的：行盒按 `RectF` 的语义（左闭右开）相交，所以"贴着下边缘"
 * 不算压字，而"盖住 12.5px 以上"（一个 50px 高行盒的 25%）才算。
 */
class PinnedPlacementTest {

    private fun line(index: Int, top: Float, height: Float = 50f) = LineBox(
        anchor = Selector.Structural("blk[$index]", 0, 10, 1f),
        rectDoc = Box(24f, top, 624f, top + height),
        baselineY = top + height - 10f,
        fontSizePx = 40f,
    )

    /**
     * 两段正文，中间一条 80px 的段间空白：
     *
     * ```
     *   行 0   0..50
     *   行 1  50..100
     *   行 2 100..150
     *        ← 空白 150..230（段间距，PINNED 的主要来源）
     *   行 3 230..280
     *   行 4 280..330
     *   行 5 330..380
     * ```
     */
    private val paragraph = listOf(
        line(0, 0f), line(1, 50f), line(2, 100f),
        line(3, 230f), line(4, 280f), line(5, 330f),
    )

    /** 只有一段正文，下面是"到文档末尾为止"的大片空白。 */
    private val openEnd = listOf(line(0, 0f), line(1, 50f), line(2, 100f))

    /** 正文栏内、压在行 1 上的一个盒子（高 50，盖住行 1 的 80%）。 */
    private val coveringBox = Box(left = 100f, top = 40f, right = 200f, bottom = 90f)

    /** 左边距里的一个小格子（x < 24，永远碰不到正文）。 */
    private val marginBox = Box(left = 0f, top = 10f, right = 20f, bottom = 40f)

    /** 用**与实现同一把尺子**回验"落位之后不压字"。尺子写死在测试里，实现改了它就红。 */
    private fun assertNotCovering(box: Box, lines: List<LineBox>) {
        for (line in lines) {
            val ratio = box.coverageRatio(line.rectDoc)
            assertTrue(
                "落位之后仍然压在 ${line.anchor.blockPath} 上（${ratio}）",
                ratio < PinnedPlacement.COVER_RATIO,
            )
        }
    }

    private fun PinnedPlacement.Placement.placed(box: Box) = box.translate(0f, dy)

    @Test
    fun `没有笔记时返回空`() {
        assertEquals(emptyList<PinnedPlacement.Placement>(), PinnedPlacement.solve(emptyList(), paragraph, 4000f))
    }

    // ── 不动的那些（M3.2 的承诺：改挂/重排都不该把笔记搬走） ──────────────────

    @Test
    fun `贴着目标行又没压着字的笔记一个像素都不动`() {
        val item = PinnedPlacement.Item("a", marginBox, paragraph[0])

        val placement = PinnedPlacement.solve(listOf(item), paragraph, 4000f).single()

        assertEquals(0f, placement.dy, 0f)
        assertFalse(placement.moved)
        assertFalse(placement.coveringText)
        assertFalse(placement.leftTargetLine)
        assertFalse(placement.blocked)
    }

    @Test
    fun `离目标行很远的笔记被摆到那一行旁边`() {
        // 这一条是 M3.4 的核心行为：PINNED 的墨迹形状冻住（一个字都不许拉伸），
        // 但**位置要跟着它记的那一行走** —— 像一张便利贴，而不是钉在屏幕第几像素上。
        // 不这样的话，改一次字号之后边注就永远对着别的段落了。
        val item = PinnedPlacement.Item("a", marginBox, paragraph[5])

        val placement = PinnedPlacement.solve(listOf(item), paragraph, 4000f).single()

        assertTrue("要挪", placement.moved)
        assertTrue("而且要说得出它原来离得远", placement.leftTargetLine)
        assertFalse("它没压着字，本来就不挡道", placement.coveringText)
        assertFalse(placement.blocked)
        // 横向一个像素都不动，落点是目标行盒的顶边。
        val moved = placement.placed(marginBox)
        assertEquals("横向不许动", marginBox.left, moved.left, 0f)
        assertEquals(marginBox.right, moved.right, 0f)
        assertEquals(paragraph[5].rectDoc.top, moved.top, 1e-3f)
    }

    @Test
    fun `段间空白里、仍算贴着目标行的笔记不挪`() {
        // 一个行高的余量：写在段间空白里的笔记本来就没压着任何一行，
        // 不该因为"没落在行盒里"被判成离得很远。
        val note = Box(left = 100f, top = 160f, right = 200f, bottom = 200f)
        val item = PinnedPlacement.Item("a", note, paragraph[2])

        val placement = PinnedPlacement.solve(listOf(item), paragraph, 4000f).single()

        assertFalse(placement.moved)
        assertFalse("它就在目标行旁边（隔着一个行高之内）", placement.leftTargetLine)
    }

    @Test
    fun `锚点解不开的笔记不参与求解但事实照报`() {
        val item = PinnedPlacement.Item("a", coveringBox, targetLine = null)

        val placement = PinnedPlacement.solve(listOf(item), paragraph, 4000f).single()

        assertFalse("没有目标行就没有'附近'，不许猜一个位置出来", placement.moved)
        assertFalse(placement.blocked)
        assertTrue("它此刻确实压着正文，这件事不因为它解不开就变成假的", placement.coveringText)
    }

    // ── 要动的那些（§6.3.3 / M3 §20.6） ──────────────────────────────────────

    @Test
    fun `压在正文上的笔记被挪到最近的空白`() {
        val item = PinnedPlacement.Item("a", coveringBox, paragraph[1])

        val placement = PinnedPlacement.solve(listOf(item), paragraph, 4000f).single()

        assertTrue(placement.moved)
        assertTrue(placement.coveringText)
        assertFalse("它本来就在目标行上（只是压住了字）", placement.leftTargetLine)
        assertTrue("只有一个方向：往下挪", placement.dy > 0f)

        val moved = placement.placed(coveringBox)
        assertNotCovering(moved, paragraph)
        assertTrue("必须挪到目标行的顶边之下", moved.top >= paragraph[1].rectDoc.top)
        assertTrue(
            "只需挪到下一段正文之前：${moved.top}",
            moved.bottom <= paragraph[3].rectDoc.top,
        )
    }

    @Test
    fun `笔记压在正文上又离目标行很远时_挪到目标行下方那一片空白`() {
        // 原本写在段间空白里的笔记，重排之后那片空白被文字填上了，而且它的目标行
        // 已经被排到了别处：两件事一起报（压着字 + 离得远），落点是**目标行下方的**
        // 空白 —— 正文栏里没有"旁边"可言，目标行本身是文字，占不得。
        val note = Box(left = 100f, top = 10f, right = 200f, bottom = 60f)
        val item = PinnedPlacement.Item("a", note, paragraph[4])

        val placement = PinnedPlacement.solve(listOf(item), paragraph, 4000f).single()

        assertTrue(placement.moved)
        assertTrue(placement.coveringText)
        assertTrue(placement.leftTargetLine)
        assertTrue("往下挪", placement.dy > 0f)
        val moved = placement.placed(note)
        assertNotCovering(moved, paragraph)
        assertTrue("不许越过目标行下面那一段正文：${moved.top}", moved.top <= paragraph[5].rectDoc.bottom)
    }

    @Test
    fun `两条笔记抢同一处空白时级联下推`() {
        // §6.3.3 末尾回应 Q12 的那一条：同一行旁边的两条笔记不能叠在一起。
        // 两条笔记的盒子完全相同（用户在同一处写了两笔），落位必须把它们分开。
        val a = PinnedPlacement.Item("a", coveringBox, openEnd[1])
        val b = PinnedPlacement.Item("b", coveringBox, openEnd[1])

        val first = PinnedPlacement.solve(listOf(a, b), openEnd, 900f)
        val topA = first[0].placed(coveringBox)
        val topB = first[1].placed(coveringBox)

        assertTrue(first[0].moved)
        assertTrue("第二条也必须挪（它不能压在第一条上）", first[1].moved)
        assertNotCovering(topA, openEnd)
        assertNotCovering(topB, openEnd)
        assertTrue("两条笔记的间距必须留够：${topA.bottom} → ${topB.top}", topB.top >= topA.bottom + PinnedPlacement.MIN_GAP_PX - 1e-3f)

        // 顺序必须确定：把入参反过来，每一条笔记自己的落位不能变
        // （否则"打开两次，边注跳到两个地方"）。
        val reversed = PinnedPlacement.solve(listOf(b, a), openEnd, 900f).associateBy { it.id }
        assertEquals(first[0], reversed.getValue("a"))
        assertEquals(first[1], reversed.getValue("b"))
    }

    @Test
    fun `返回顺序与入参一致`() {
        // 调用方是**按下标**把结果对回每条笔记的；顺序跟着内部排序走，
        // 症状是"位移加到了另一条笔记身上"。
        val a = PinnedPlacement.Item("a", coveringBox, paragraph[0])
        val b = PinnedPlacement.Item("b", coveringBox, paragraph[5])

        val ids = PinnedPlacement.solve(listOf(b, a), paragraph, 4000f).map { it.id }

        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun `目标行附近找不到空白时留在原处并如实报告`() {
        // 只剩 180px 高、而且全是文字：没有一处能放下这 50px 的笔记。
        val item = PinnedPlacement.Item("a", coveringBox, openEnd[1])

        val placement = PinnedPlacement.solve(listOf(item), openEnd, bottomPx = 180f).single()

        assertEquals("不许发明一个'看起来还行'的位置", 0f, placement.dy, 0f)
        assertFalse(placement.moved)
        assertTrue("放不下这件事必须说出来", placement.blocked)
        assertTrue(placement.coveringText)
    }

    @Test
    fun `正文有几千行时仍然只找目标行附近的空白`() {
        // 行 104 与行 105 之间留了 200px 的段间空白（其余地方全是连续的文字）。
        // 笔记压在行 100 上：它必须精确地滑到那一段空白里，
        // 而不是滑到别处或者滑不动（二分 + 局部扫描的正确性）。
        val big = (0 until 5000).map { i ->
            line(i, if (i <= 104) i * 50f else i * 50f + 200f)
        }
        val note = Box(left = 100f, top = 5000f, right = 200f, bottom = 5050f)
        val item = PinnedPlacement.Item("a", note, big[100])

        val placement = PinnedPlacement.solve(listOf(item), big, bottomPx = 200_000f).single()

        assertTrue(placement.moved)
        val moved = placement.placed(note)
        assertNotCovering(moved, big)
        assertTrue("要滑到那一段空白里（≈5238），实际 ${moved.top}", moved.top in 5230f..5260f)
        assertTrue("不许越过下一段正文", moved.bottom <= 5450f)
    }

    // ── 不变量：幂等 / 不动的笔记占位 ────────────────────────────────────────

    @Test
    fun `不动的笔记也占着地方`() {
        // 规则 5。一条已经在目标行旁边的边注，不该被"要挪到那一行旁边"的另一条压住 ——
        // 否则同一次解析里就会造出叠在一起的两条笔记，而它们各自都"没错"。
        val sitting = Box(left = 0f, top = 50f, right = 20f, bottom = 90f)
        val s = PinnedPlacement.Item("s", sitting, openEnd[1])
        val coming = Box(left = 0f, top = 300f, right = 20f, bottom = 340f)
        val m = PinnedPlacement.Item("m", coming, openEnd[1])

        val placements = PinnedPlacement.solve(listOf(m, s), openEnd, 900f).associateBy { it.id }

        assertFalse("已经在那一行旁边的笔记不该被顺手挪走", placements.getValue("s").moved)
        val placed = placements.getValue("m").placed(coming)
        assertTrue("要挪的那条必须让开", placements.getValue("m").moved)
        assertTrue("不许摆到另一条笔记身上：${placed.top}", placed.top >= sitting.bottom)

        // 对照：没有那条坐着的笔记时，它就该落在理想的位置上（否则上面那条断言什么都没测）。
        val alone = PinnedPlacement.solve(listOf(m), openEnd, 900f).single().placed(coming)
        assertEquals(openEnd[1].rectDoc.top, alone.top, 1e-3f)
    }

    @Test
    fun `摆好之后再解一次一动不动`() {
        // 幂等。少了它，症状是**每打开一次文档，边注往下挪一点** ——
        // 用户会以为自己的书在慢慢坏掉。这一条要求两件事同时成立：
        // "摆好之后判据就成立"（规则 4）与"摆在同一个地方时得到同一个答案"（规则 5 + 确定顺序）。
        val items = listOf(
            PinnedPlacement.Item("beside", marginBox, openEnd[0]),
            PinnedPlacement.Item("far", Box(0f, 300f, 20f, 340f), openEnd[0]),
            PinnedPlacement.Item("coverA", coveringBox, openEnd[1]),
            PinnedPlacement.Item("coverB", coveringBox, openEnd[1]),
        )

        val first = PinnedPlacement.solve(items, openEnd, 900f).associateBy { it.id }
        assertTrue("前提：这一组里确实有笔记被挪了", first.values.count { it.moved } >= 2)

        val settled = items.map { it.copy(box = it.box.translate(0f, first.getValue(it.id).dy)) }
        val second = PinnedPlacement.solve(settled, openEnd, 900f)

        for (placement in second) {
            assertEquals("第二次解它已经在原地：${placement.id}", 0f, placement.dy, 0f)
            assertFalse("摆好之后不许再动：${placement.id}", placement.moved)
            assertFalse("更不该变成放不下：${placement.id}", placement.blocked)
        }
    }
}
