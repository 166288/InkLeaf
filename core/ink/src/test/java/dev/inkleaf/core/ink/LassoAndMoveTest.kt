package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1 套索与移动的测试。
 *
 * 这一组跑在**纯 JVM**上（不需要 Robolectric）：命中判定与重新锚定都是纯函数，
 * 只有"画出来"才需要真的画布。把它挡在 Robolectric 之外是有意的 ——
 * 每多一条跑在 NATIVE 图形下的用例，整套测试的启动就要多付一次 Skia 初始化。
 */
class LassoAndMoveTest {

    private val doc = TestDoc(3)

    private fun stroke(
        id: String,
        points: FloatArray,
        brush: BrushSpec = BrushSpec.DEFAULT_PEN,
    ) = DrawableStroke(strokeId = id, brush = brush, docPoints = points, fontSizePx = 40f)

    private fun square(left: Float, top: Float, right: Float, bottom: Float) = floatArrayOf(
        left, top, right, top, right, bottom, left, bottom,
    )

    // ── 套索命中 ────────────────────────────────────────────────────────────

    @Test
    fun `圈住的一笔会被选中，没圈住的不会`() {
        val inside = stroke("a", floatArrayOf(50f, 50f, 60f, 60f))
        val outside = stroke("b", floatArrayOf(500f, 50f, 510f, 60f))

        val selected = StrokeHitTest.selectByLasso(
            listOf(inside, outside),
            square(0f, 0f, 200f, 200f),
        )

        assertEquals(listOf("a"), selected.map { it.strokeId })
    }

    @Test
    fun `只有一点点探进圈里也算圈住，反过来差一点也不算`() {
        // 判据是"任一采样点在内"，不是"全部在内"：
        // 用户把圈画在它周围了，差一个像素就落选会让人以为套索坏了。
        val justIn = stroke("in", floatArrayOf(199f, 100f, 300f, 100f))
        val justOut = stroke("out", floatArrayOf(201f, 100f, 300f, 100f))
        val polygon = square(0f, 0f, 200f, 200f)

        assertTrue(StrokeHitTest.caughtByLasso(justIn, polygon))
        assertFalse(StrokeHitTest.caughtByLasso(justOut, polygon))
    }

    @Test
    fun `太短的圈不构成多边形，什么都不选`() {
        // 两点（一条线）没有"里面"可言。判成"选中全部"会一次性把整页笔迹都选走。
        val line = floatArrayOf(0f, 0f, 200f, 200f)
        assertEquals(
            emptyList<String>(),
            StrokeHitTest.selectByLasso(listOf(stroke("a", floatArrayOf(5f, 5f, 6f, 6f))), line)
                .map { it.strokeId },
        )
    }

    @Test
    fun `看不见的笔迹不会被圈住`() {
        // 与整笔擦除同一条规则：选中一笔"根本看不见的东西"，
        // 用户唯一的感受就是"我画了个圈，某条笔记消失了"。
        val invisible = stroke("x", floatArrayOf(50f, 50f, 60f, 60f), brush = BrushSpec.DEFAULT_ERASER)
        assertFalse(StrokeHitTest.caughtByLasso(invisible, square(0f, 0f, 200f, 200f)))
    }

    @Test
    fun `凹多边形的凹口不算里面`() {
        // 一个 C 形：中间那块被"咬掉"的地方不算圈住。
        val cShape = floatArrayOf(
            0f, 0f, 300f, 0f, 300f, 300f, 200f, 300f, 200f, 100f, 100f, 100f, 100f, 300f, 0f, 300f,
        )
        assertFalse("凹口里的点不算圈住", StrokeHitTest.containsPoint(cShape, 150f, 250f))
        assertTrue("C 的两条臂里面算圈住", StrokeHitTest.containsPoint(cShape, 250f, 250f))
    }

    // ── 移动：重新锚定 ──────────────────────────────────────────────────────

    /**
     * 一条落在第 [lineIndex] 行上的横线（文档坐标）。
     *
     * TestDoc 的行：第 i 行 y ∈ [i·50, i·50+50]，x ∈ [24, 624]。
     */
    private fun lineStroke(id: String, lineIndex: Int): DrawableStroke {
        val y = doc.lines()[lineIndex].rectDoc.let { (it.top + it.bottom) / 2f }
        return stroke(id, floatArrayOf(100f, y, 300f, y))
    }

    @Test
    fun `移动会重新锚到新的那一行，而不是存一个偏移量`() {
        val outcome = InkMove.plan(
            strokes = listOf(lineStroke("a", 0)),
            dx = 0f,
            // 往下挪一整行：第 0 行 → 第 1 行。
            dy = 50f,
            facts = doc.facts(),
            idFactory = { "moved-1" },
        )

        val moved = outcome as InkMove.Outcome.Moved
        val relocation = moved.relocations.single()
        assertEquals("原来那条要留着，撤销要用到它", "a", relocation.fromStrokeId)
        assertEquals(
            "新段必须锚到挪过去之后的那一行",
            "blk[1]",
            relocation.segments.single().stroke.anchor.blockPath,
        )
        assertEquals(
            "新段的 id 不能复用旧的",
            "moved-1",
            relocation.segments.single().stroke.strokeId,
        )
    }

    @Test
    fun `移动后的语义重新判定，不沿用旧的`() {
        // 从行内挪到左页边空白：形状判定会把它判成"钉在页面上"。
        // 这不是 bug —— 挪到页边的东西本来就不该再跟着文字跑。
        //
        // 笔画要**短**：判据是"落在页边空白里的面积占比 ≥ 70%"，
        // 一条横贯整页的长笔画挪进去也只有一小截在页边里，不该翻面。
        val y = doc.lines()[0].rectDoc.let { (it.top + it.bottom) / 2f }
        val outcome = InkMove.plan(
            strokes = listOf(stroke("a", floatArrayOf(100f, y, 140f, y))),
            dx = -110f, // x 从 100..140 → -10..30，绝大部分落进正文栏（x≥24）之外
            dy = 0f,
            facts = doc.facts(),
            idFactory = { "moved-1" },
        )

        val moved = outcome as InkMove.Outcome.Moved
        assertEquals(
            "挪到页边之后语义该跟着变（沿用现有三态，不新增第四态）",
            InkReflowSemantics.PINNED,
            moved.relocations.single().semantics,
        )
    }

    @Test
    fun `挪到拿不到引文的行上会整体拒绝，不会只挪一半`() {
        // 只挪一部分比不挪更糟：用户要对着屏幕数哪一笔没动，
        // 而"移动"这个动作本身看起来是成功的。
        //
        // 构造一条"没有文字可取"的行：TestDoc 的 `absoluteRangeOf` 对块号越界的行
        // 返回 null，那正是落笔时会被跳过并计数的那一种（落在空行上的笔迹）。
        // 手工造这一行：`TestDoc.line(...)` 会去取 `texts[index]`，而 9 号块不存在。
        val blankLine = LineBox(
            anchor = Selector.Structural("blk[9]", 0, 4, 1f),
            rectDoc = Box(24f, 200f, 624f, 250f),
            baselineY = 240f,
            fontSizePx = 40f,
        )
        val withBlankLine = doc.facts(lines = doc.lines() + blankLine)
        val y = doc.lines()[0].rectDoc.let { (it.top + it.bottom) / 2f }
        val outcome = InkMove.plan(
            strokes = listOf(stroke("a", floatArrayOf(100f, y, 300f, y))),
            dx = 0f,
            dy = 200f, // 落到第 9 块那一行（y 200..250）
            facts = withBlankLine,
            idFactory = { "moved-1" },
        )

        assertTrue(outcome is InkMove.Outcome.Rejected)
        assertTrue(
            "必须说清为什么没挪，实际是「${(outcome as InkMove.Outcome.Rejected).reason}」",
            outcome.reason.contains("没有移动"),
        )
    }

    @Test
    fun `没有选中任何笔迹时明确拒绝`() {
        val outcome = InkMove.plan(emptyList(), 10f, 10f, doc.facts()) { "x" }
        assertTrue(outcome is InkMove.Outcome.Rejected)
    }

    @Test
    fun `多次移动走同一条路径：移动过的一笔还能再移动`() {
        // 这是"不给 dx/dy 而是重新锚定"换来的性质：
        // 重新锚定之后它就是一条普通的记录，第二次移动与第一次走完全相同的代码。
        val first = InkMove.plan(
            strokes = listOf(lineStroke("a", 0)),
            dx = 0f,
            dy = 50f,
            facts = doc.facts(),
            idFactory = { "moved-1" },
        ) as InkMove.Outcome.Moved

        val y = doc.lines()[1].rectDoc.let { (it.top + it.bottom) / 2f }
        val second = InkMove.plan(
            strokes = listOf(stroke("moved-1", floatArrayOf(100f, y, 300f, y))),
            dx = 0f,
            dy = 50f,
            facts = doc.facts(),
            idFactory = { "moved-2" },
        ) as InkMove.Outcome.Moved

        assertEquals(
            "blk[2]",
            second.relocations.single().segments.single().stroke.anchor.blockPath,
        )
    }
}
