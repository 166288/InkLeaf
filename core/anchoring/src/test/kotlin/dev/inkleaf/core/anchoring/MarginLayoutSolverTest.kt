package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginLayoutSolverTest {

    private val rightColumn = MarginLayoutSolver.Column(Side.RIGHT, Box(1000f, 0f, 1200f, 600f))
    private val leftColumn = MarginLayoutSolver.Column(Side.LEFT, Box(0f, 0f, 150f, 600f))

    private fun req(id: String, top: Float, height: Float = 40f, side: Side = Side.RIGHT) =
        MarginLayoutSolver.Request(id, top, height, side)

    @Test
    fun `没有请求时结果为空`() {
        assertTrue(MarginLayoutSolver.solve(emptyList(), listOf(rightColumn)).isEmpty())
    }

    @Test
    fun `单条边注落在其锚点行上`() {
        val result = MarginLayoutSolver.solve(listOf(req("a", 200f)), listOf(rightColumn))
        assertEquals(1, result.size)
        // 默认 padding 4 → 200
        assertEquals(200f, result[0].box.top, 1e-4f)
        assertFalse(result[0].overflow)
        assertEquals(Side.RIGHT, result[0].side)
    }

    @Test
    fun `同一行的两条边注不重叠`() {
        val result = MarginLayoutSolver.solve(
            listOf(req("a", 200f, 40f), req("b", 200f, 40f)),
            listOf(rightColumn),
            gapPx = 6f,
        )
        val a = result.first { it.id == "a" }
        val b = result.first { it.id == "b" }
        assertTrue("b 必须被推到 a 下方", b.box.top >= a.box.bottom + 6f - 1e-3f)
        assertFalse(a.overflow)
        assertFalse(b.overflow)
    }

    @Test
    fun `相距很远的边注各自保持原位`() {
        val result = MarginLayoutSolver.solve(
            listOf(req("a", 100f, 40f), req("b", 400f, 40f)),
            listOf(rightColumn),
        )
        assertEquals(100f, result.first { it.id == "a" }.box.top, 1e-4f)
        assertEquals(400f, result.first { it.id == "b" }.box.top, 1e-4f)
    }

    @Test
    fun `边注只向下调整绝不上移`() {
        // 锚点在上方的边注先占位，锚点在下方且高度较大的边注被下推
        val result = MarginLayoutSolver.solve(
            listOf(req("a", 10f, 300f), req("b", 20f, 300f)),
            listOf(rightColumn),
        )
        val a = result.first { it.id == "a" }
        val b = result.first { it.id == "b" }
        assertTrue("b 的锚点在上方时不得被放到 a 上方", b.box.top >= a.box.bottom)
    }

    @Test
    fun `栏满时明确标记溢出而不是静默重叠`() {
        // 每条约 100 高，栏高 600 - 8 padding = 592 → 放 5 条后第 6 条放不下
        val requests = (1..8).map { req("n$it", 0f, 100f) }
        val result = MarginLayoutSolver.solve(requests, listOf(rightColumn), gapPx = 6f)
        assertTrue("必须至少有一条被标记溢出", result.any { it.overflow })
        assertEquals(8, result.size)
    }

    @Test
    fun `单条比整栏还高时标记溢出并钉在栏顶`() {
        val result = MarginLayoutSolver.solve(listOf(req("tall", 300f, 5000f)), listOf(rightColumn))
        assertEquals(1, result.size)
        assertTrue(result[0].overflow)
        assertEquals(4f, result[0].box.top, 1e-4f)
    }

    @Test
    fun `左右两栏互不影响`() {
        val result = MarginLayoutSolver.solve(
            listOf(
                req("left1", 100f, 40f, Side.LEFT),
                req("right1", 100f, 40f, Side.RIGHT),
            ),
            listOf(leftColumn, rightColumn),
        )
        val l = result.first { it.id == "left1" }
        val r = result.first { it.id == "right1" }
        assertEquals(100f, l.box.top, 1e-4f)
        assertEquals(100f, r.box.top, 1e-4f)
        assertTrue("左栏的横向范围必须在左侧", l.box.right <= 150f)
        assertTrue("右栏的横向范围必须在右侧", r.box.left >= 1000f)
    }

    @Test
    fun `没有对应栏的请求不会被静默丢弃`() {
        val result = MarginLayoutSolver.solve(listOf(req("lonely", 100f, 40f, Side.LEFT)), listOf(rightColumn))
        assertEquals("绝不能让'有一条边注但看不见'发生", 1, result.size)
        assertTrue(result[0].overflow)
        assertEquals(0f, result[0].box.width, 1e-4f)
    }

    @Test
    fun `结果顺序与输入顺序一致`() {
        val requests = listOf(req("c", 300f), req("a", 100f), req("b", 200f))
        val result = MarginLayoutSolver.solve(requests, listOf(rightColumn))
        assertEquals(listOf("c", "a", "b"), result.map { it.id })
    }

    @Test
    fun `同一锚点位置的排布顺序是确定的`() {
        // 输入顺序不同，但锚点位置相同时按 id 排 → 结果必须一致
        val a = MarginLayoutSolver.solve(listOf(req("z", 100f), req("a", 100f)), listOf(rightColumn))
        val b = MarginLayoutSolver.solve(listOf(req("a", 100f), req("z", 100f)), listOf(rightColumn))
        assertEquals(a.first { it.id == "a" }.box.top, b.first { it.id == "a" }.box.top, 1e-4f)
        assertTrue(a.first { it.id == "a" }.box.top < a.first { it.id == "z" }.box.top)
    }

    @Test
    fun `同级边界触碰不算重叠`() {
        // 恰好相接（gap=0）时不应被判为溢出
        val result = MarginLayoutSolver.solve(
            listOf(req("a", 100f, 50f), req("b", 150f, 50f)),
            listOf(rightColumn),
            gapPx = 0f,
        )
        assertFalse(result.any { it.overflow })
        assertEquals(150f, result.first { it.id == "b" }.box.top, 1e-4f)
    }
}
