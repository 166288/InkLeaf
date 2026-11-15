package dev.inkleaf.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标模型的单测。
 *
 * 这个测试文件本身就是 §4.1 那条约束的**证据**：它证明 `:core:model`
 * 真的没有 Android 依赖 —— 因为它在纯 JVM 上跑，没有模拟器、没有 Robolectric。
 * 一旦有人往这个模块里 import 了 android.graphics.*，这个文件连编译都过不去。
 */
class GeometryTest {

    private val delta = 1e-3f

    // ── Box ──────────────────────────────────────────────────────────────────

    @Test
    fun `coverageRatio_完全包含为一_无交集为零`() {
        val outer = Box(0f, 0f, 100f, 100f)
        val inner = Box(10f, 10f, 50f, 50f)
        val half = Box(0f, 0f, 100f, 50f)

        assertEquals(1f, inner.coverageRatio(outer), delta)
        assertEquals(1f, half.coverageRatio(outer), delta)

        // 参数顺序是有语义的：coverageRatio 问的是"**self** 有多少比例落在 other 内"。
        // §6.3.3 用的是 inkBox.coverageRatio(lineBox)（笔迹有多少落在行里）。
        // 顺序写反会静默地把 FOLLOWER 判成 PINNED —— 所以这里把两个方向都钉住。
        assertEquals(0.5f, outer.coverageRatio(half), delta)

        assertEquals(0f, outer.coverageRatio(Box(200f, 200f, 300f, 300f)), delta)
        // 无交集方向对称
        assertEquals(0f, Box(200f, 200f, 300f, 300f).coverageRatio(outer), delta)
    }

    @Test
    fun `coverageRatio_面积为零时返回零而不是除零`() {
        assertEquals(0f, Box.ZERO.coverageRatio(Box(0f, 0f, 10f, 10f)), delta)
    }

    @Test
    fun `行盒重合度阈值可直接用于语义判定`() {
        // §6.3.3：墨迹盒与行盒重合度 >= 60% → FOLLOWER
        val lineBox = Box(0f, 100f, 400f, 140f)
        val mostlyOnLine = Box(10f, 110f, 390f, 135f)
        val mostlyOffLine = Box(10f, 130f, 390f, 260f)

        assertTrue(mostlyOnLine.coverageRatio(lineBox) >= 0.6f)
        assertTrue(mostlyOffLine.coverageRatio(lineBox) < 0.6f)
    }

    // ── Affine2D ─────────────────────────────────────────────────────────────

    @Test
    fun `em转文档坐标按字号线性缩放`() {
        val m = Affine2D.emToDoc(fontSizePx = 48f, originLeft = 100f, originTop = 20f)

        assertEquals(100f, m.mapX(0f, 0f), delta)
        assertEquals(20f, m.mapY(0f, 0f), delta)
        // 2em 宽 → 96px
        assertEquals(196f, m.mapX(2f, 0f), delta)
        // 1em 高 → 48px
        assertEquals(68f, m.mapY(0f, 1f), delta)
    }

    @Test
    fun `逆变换可往返`() {
        val m = Affine2D.emToDoc(48f, 100f, 20f)
        val inv = m.invert()
        assertNotNull(inv)

        assertEquals(2f, inv!!.mapX(196f, 164f), delta)
        assertEquals(3f, inv.mapY(196f, 164f), delta)
    }

    @Test
    fun `退化矩阵求逆返回空而不是抛异常`() {
        val singular = Affine2D(1f, 2f, 3f, 2f, 4f, 6f) // 第二行 = 第一行 × 2

        assertTrue(singular.determinant.let { kotlin.math.abs(it) } <= Affine2D.EPSILON)
        assertNull(singular.invert())
    }

    @Test
    fun `复合变换先应用右侧再应用左侧`() {
        val translate = Affine2D.translation(10f, 20f)
        val scale = Affine2D.scaling(2f)

        // scale ∘ translate：(0,0) → 平移 (10,20) → 缩放 (20,40)
        val composed = scale.times(translate)
        assertEquals(20f, composed.mapX(0f, 0f), delta)
        assertEquals(40f, composed.mapY(0f, 0f), delta)
    }

    @Test
    fun `矩形变换在九十度旋转下给出精确包围盒`() {
        // 90° 旋转：(x,y) → (-y, x)
        val rotate90 = Affine2D(a = 0f, b = -1f, c = 0f, d = 1f, e = 0f, f = 0f)

        val result = rotate90.mapBox(Box(0f, 0f, 10f, 20f))

        assertEquals(20f, result.width, delta)
        assertEquals(10f, result.height, delta)
    }

    // ── LineBox ──────────────────────────────────────────────────────────────

    @Test
    fun `行盒可在em空间与文档坐标间往返`() {
        val lineBox = LineBox(
            anchor = Selector.Structural("blk[0]", 0, 10, baselineOffsetEm = 0.8f),
            rectDoc = Box(40f, 100f, 440f, 148f),
            baselineY = 138f,
            fontSizePx = 48f,
        )

        val toDoc = lineBox.matrixInDoc()
        val toEm = lineBox.docToEmSpace()
        assertNotNull(toEm)

        // 行盒左上角 → em 空间原点
        assertEquals(0f, toEm!!.mapX(40f, 100f), delta)
        assertEquals(0f, toEm.mapY(40f, 100f), delta)

        // 往返一致
        val (x, y) = 1.5f to 0.25f
        val docX = toDoc.mapX(x, y)
        val docY = toDoc.mapY(x, y)
        assertEquals(x, toEm.mapX(docX, docY), delta)
        assertEquals(y, toEm.mapY(docX, docY), delta)
    }

    // ── 锚点链不变量 ─────────────────────────────────────────────────────────

    @Test
    fun `锚点链必须非空且以进度兜底结尾`() {
        val profile = NormalizationProfile()

        assertThrows(IllegalArgumentException::class.java) {
            TextAnchorChain(emptyList(), profile, "fp")
        }

        assertThrows(IllegalArgumentException::class.java) {
            // 少了 ProgressHint 兜底：孤儿状态将无处可去
            TextAnchorChain(
                listOf(Selector.Structural("blk[0]", 0, 5, 0.8f)),
                profile,
                "fp",
            )
        }
    }

    @Test
    fun `锚点链能正确取出结构型与文本型选择器`() {
        val chain = TextAnchorChain(
            selectors = listOf(
                Selector.Structural("blk[2]", 10, 20, 0.8f),
                Selector.TextQuote("原文", "前", "后"),
                Selector.ProgressHint(docRatio = 0.5f),
            ),
            normalization = NormalizationProfile(),
            sourceFingerprint = "fp",
        )

        assertEquals("blk[2]", chain.structural?.blockPath)
        assertEquals("原文", chain.textQuote?.exact)
        assertEquals(0.5f, chain.progressHint.docRatio, delta)
    }
}
