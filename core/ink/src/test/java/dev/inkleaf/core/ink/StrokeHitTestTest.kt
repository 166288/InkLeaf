package dev.inkleaf.core.ink

import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.UnderlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整笔擦除的命中判据。
 *
 * 这些测试盯的是"聪不聪明"，而不是"能不能跑"：容差大一点，用户在两条相邻的笔迹
 * 之间点一下就会擦错；小一点，用户会觉得"我明明点在线上"。而这两种感受都不会
 * 产生任何异常，只会变成一句"橡皮不好用"。
 */
class StrokeHitTestTest {

    private fun stroke(
        id: String,
        points: FloatArray,
        color: Int = 0xFF1A1A1A.toInt(),
        widthEm: Float = 0.04f,
        fontSizePx: Float = 40f,
    ) = DrawableStroke(
        strokeId = id,
        brush = BrushSpec.DEFAULT_PEN.copy(colorArgb = color, widthEm = widthEm),
        docPoints = points,
        fontSizePx = fontSizePx,
    )

    /** 一条水平线 y = 100，从 x=100 到 x=200。 */
    private val horizontal = stroke("s1", floatArrayOf(100f, 100f, 200f, 100f))

    @Test
    fun `点在线段中部能命中`() {
        val hit = StrokeHitTest.nearest(listOf(horizontal), 150f, 104f, tolerancePx = 24f)
        assertNotNull("线段中部是最常点的位置，必须命中", hit)
        assertEquals("s1", hit!!.strokeId)
    }

    @Test
    fun `点在延长线上不命中`() {
        // 投影参数不夹进 [0,1] 的话，这条会被判成"擦到了"——可以从很远的地方命中。
        assertNull(StrokeHitTest.nearest(listOf(horizontal), 260f, 100f, tolerancePx = 24f))
    }

    @Test
    fun `离得比容差远就不命中`() {
        assertNull(StrokeHitTest.nearest(listOf(horizontal), 150f, 130f, tolerancePx = 24f))
    }

    @Test
    fun `两条笔迹时取更近的那一条`() {
        val other = stroke("s2", floatArrayOf(100f, 140f, 200f, 140f))
        val hit = StrokeHitTest.nearest(listOf(horizontal, other), 150f, 135f, tolerancePx = 24f)
        assertEquals("更近的是下面那条", "s2", hit?.strokeId)
    }

    @Test
    fun `看不见的笔迹不参与命中`() {
        // 擦掉一笔"本来就没画出来"的记录，用户只会看到"某条笔记消失了"。
        val invisible = stroke("ghost", floatArrayOf(100f, 300f, 200f, 300f), color = 0)
        assertNull(StrokeHitTest.nearest(listOf(invisible), 150f, 300f, tolerancePx = 24f))
    }

    @Test
    fun `粗笔的容差按它自己的半径放宽`() {
        // 马克笔 0.14em × 40px = 5.6px 宽，半径 2.8px；容差给 2px 时，
        // 允许范围应当放宽到 半径 + 2 = 4.8px（而不是死守 2px）。
        val marker = stroke(
            "m1",
            floatArrayOf(100f, 200f, 200f, 200f),
            widthEm = 0.14f,
        )
        assertNotNull("落在半径边缘内应当命中", StrokeHitTest.nearest(listOf(marker), 150f, 203.5f, tolerancePx = 2f))
        assertNull("超出 半径 + 容差 就不该命中", StrokeHitTest.nearest(listOf(marker), 150f, 205.5f, tolerancePx = 2f))
    }

    @Test
    fun `单点笔迹按点计算距离`() {
        val dot = stroke("dot", floatArrayOf(50f, 50f))
        assertEquals(0f, StrokeHitTest.distanceToPolyline(dot.docPoints, 50f, 50f), 0.001f)
        assertEquals(10f, StrokeHitTest.distanceToPolyline(dot.docPoints, 60f, 50f), 0.001f)
    }

    @Test
    fun `重复采样点不会算出 NaN`() {
        // 真实手写笔的压力采样比坐标密，重合点很常见；除以 0 得到 NaN 会让
        // 这一笔永远擦不掉（NaN 参与的比较永远是 false），且没有任何异常可看。
        val duplicated = stroke("dup", floatArrayOf(10f, 10f, 10f, 10f, 30f, 10f))
        val distance = StrokeHitTest.distanceToPolyline(duplicated.docPoints, 20f, 10f)
        assertTrue("距离必须是有限数，实际=$distance", distance.isFinite())
        assertEquals(0f, distance, 0.001f)
    }

    @Test
    fun `空笔迹不命中`() {
        val empty = stroke("empty", FloatArray(0))
        assertNull(StrokeHitTest.nearest(listOf(empty), 0f, 0f, tolerancePx = 24f))
    }

    @Test
    fun `默认容差是半个触摸目标`() {
        // 48dp 的触摸目标是 Material 的下限，手指覆盖的半径就是它的一半。
        assertEquals(24f, StrokeHitTest.DEFAULT_TOLERANCE_DP, 0.001f)
    }

    @Test
    fun `下划线样式默认不是波浪`() {
        // 波浪线是显式选择的：默认值写错，会让"我选了下划线却出来一条波浪"。
        assertTrue(!UnderlineStyle().wavy)
    }
}
