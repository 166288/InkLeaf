package dev.inkleaf.core.data

import dev.inkleaf.core.model.Affine2D
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 笔迹二进制编解码的回归测试。
 *
 * 为什么这类格式必须有回归测试而不是"跑一下试试"：
 * 它是唯一一份**用户数据的实际载体**。一旦编码改了而解码没跟上，
 * 症状是"笔记画在了错误的位置"，而不是报错 —— 用户不会发现，
 * 也不会来报告，只会觉得这个 App 的笔记会自己乱跑。
 */
class InkCodecTest {

    private val points = floatArrayOf(0f, 0f, 1.5f, -2.25f, 300.125f, 42.5f)
    private val pressures = floatArrayOf(0.1f, 1f, 0.5f)

    @Test
    fun `往返完全一致`() {
        val decoded = InkCodec.decode(InkCodec.encode(points, pressures))
        assertArrayEquals(points, decoded.points, 0f)
        assertArrayEquals(pressures, decoded.pressures, 0f)
        assertEquals(3, decoded.pointCount)
    }

    @Test
    fun `空笔迹可往返`() {
        val decoded = InkCodec.decode(InkCodec.encode(FloatArray(0), FloatArray(0)))
        assertEquals(0, decoded.pointCount)
    }

    @Test
    fun `单点可往返`() {
        val decoded = InkCodec.decode(InkCodec.encode(floatArrayOf(7f, 8f), floatArrayOf(0.7f)))
        assertEquals(1, decoded.pointCount)
        assertEquals(7f, decoded.points[0], 0f)
        assertEquals(8f, decoded.points[1], 0f)
    }

    @Test
    fun `浮点精度不丢失`() {
        // float32 往返必须是逐位相同的：笔宽是 em 单位，坐标值很小，
        // 任何舍入都会在放大后变成可见的抖动。
        val tricky = floatArrayOf(
            Float.MIN_VALUE, Float.MAX_VALUE, -0.0f, 1e-30f, 123456.789f, -1.1754944E-38f,
        )
        val decoded = InkCodec.decode(InkCodec.encode(tricky, FloatArray(3)))
        for (i in tricky.indices) {
            assertEquals("第 $i 位", tricky[i].toRawBits(), decoded.points[i].toRawBits())
        }
    }

    @Test
    fun `magic 不匹配时报错而不是返回垃圾坐标`() {
        val bytes = InkCodec.encode(points, pressures)
        bytes[0] = 'X'.code.toByte()
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(bytes) }
        assertNull(InkCodec.decodeOrNull(bytes))
    }

    @Test
    fun `版本不认识时报错`() {
        val bytes = InkCodec.encode(points, pressures)
        bytes[4] = 99 // 版本号小端序首字节
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(bytes) }
    }

    @Test
    fun `长度与点数不符时报错`() {
        val bytes = InkCodec.encode(points, pressures)
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(bytes.copyOf(bytes.size - 4)) }
        // 多出来的尾部字节同样判错：数据流已经错位，继续读只会得到看似合理的错误坐标。
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(bytes + byteArrayOf(0)) }
    }

    @Test
    fun `头部不完整时报错`() {
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(ByteArray(4)) }
        assertNull(InkCodec.decodeOrNull(null))
    }

    @Test
    fun `点数与压力数不一致时拒绝编码`() {
        assertThrows(IllegalArgumentException::class.java) {
            InkCodec.encode(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InkCodec.encode(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f))
        }
    }

    @Test
    fun `编码结果大小可预测`() {
        // 头部 12 字节 + 每点 8 字节坐标 + 每点 4 字节压力 + 冻结段标记 4 字节。
        // 测试这个是为了让"BLOB 外置阈值"这件事有一个可核对的事实：
        // 64KB 约等于 5461 个点，正常的一笔远小于它。
        val encoded = InkCodec.encode(points, pressures)
        assertEquals(InkCodec.HEADER_BYTES + 3 * 8 + 3 * 4 + 4, encoded.size)
        assertNotNull(InkCodec.decodeOrNull(encoded))
        // 冻结几何（冻结语义的笔迹）额外带 6 个浮点，仍然远在阈值之内。
        val frozen = InkCodec.encode(points, pressures, frozenOf(24f, 60f, 400f))
        assertEquals(InkCodec.HEADER_BYTES + 3 * 8 + 3 * 4 + 4 + 6 * 4, frozen.size)
    }

    // ── 冻结几何（v2） ────────────────────────────────────────────────────────

    @Test
    fun `冻结变换逐位往返`() {
        // 冻结几何决定"边注写在页面的哪个位置"，差一点点就是可见的错位。
        val frozen = frozenOf(24f, 60f, 400f)
        val decoded = InkCodec.decode(InkCodec.encode(points, pressures, frozen))

        assertEquals(frozen, decoded.frozenMatrix)
        assertArrayEquals(points, decoded.points, 0f)
        assertArrayEquals(pressures, decoded.pressures, 0f)
    }

    @Test
    fun `没有冻结几何与单位矩阵是两回事`() {
        // 跟随文字的笔迹没有冻结几何。若把它编码成"单位矩阵"，解码方就会以为
        // 这一笔固定在文档原点上 —— 症状是"所有下划线都跑到页面左上角"。
        val none = InkCodec.decode(InkCodec.encode(points, pressures))
        assertNull(none.frozenMatrix)

        val identity = InkCodec.decode(InkCodec.encode(points, pressures, Affine2D.IDENTITY))
        assertEquals(Affine2D.IDENTITY, identity.frozenMatrix)
    }

    @Test
    fun `旧版本数据仍然能读且没有冻结几何`() {
        // 库里的历史笔迹都是 v1（没有末尾那段）。读不出来等于把用户的墨迹弄丢，
        // 所以"能读旧版本"是格式变更的硬要求，不是顺手兼容。
        val v1 = v1Bytes(points, pressures)
        val decoded = InkCodec.decode(v1)

        assertArrayEquals(points, decoded.points, 0f)
        assertArrayEquals(pressures, decoded.pressures, 0f)
        assertNull("v1 根本没存过冻结坐标，不能凭空补一个", decoded.frozenMatrix)
        assertEquals(3, decoded.pointCount)
    }

    @Test
    fun `长度不对时一律报错_两个版本都算`() {
        val v1 = v1Bytes(points, pressures)
        assertThrows(InkCodec.CorruptInkException::class.java) {
            InkCodec.decode(v1.copyOf(v1.size - 4))
        }
        val v2 = InkCodec.encode(points, pressures, frozenOf(24f, 60f, 400f))
        // 冻结段少一个浮点：不能"读到哪算哪"，否则平面坐标会少读一半。
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(v2.copyOf(v2.size - 4)) }
        // 多出来的尾部字节同样判错：数据流已经错位。
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(v2 + byteArrayOf(0)) }
    }

    @Test
    fun `冻结段的浮点个数不认识时报错`() {
        val bytes = InkCodec.encode(points, pressures, frozenOf(24f, 60f, 400f))
        // 冻结段标记在"头部 + 点位 + 压力"之后。把 6 改成 5：
        // 长度检查会通过（它按版本算，不看这个值），因此必须在读完标记后再判一次。
        bytes[InkCodec.HEADER_BYTES + 3 * 8 + 3 * 4] = 5
        assertThrows(InkCodec.CorruptInkException::class.java) { InkCodec.decode(bytes) }
    }

    private fun frozenOf(fontSizePx: Float, left: Float, top: Float): Affine2D =
        Affine2D.emToDoc(fontSizePx = fontSizePx, originLeft = left, originTop = top)

    /** 手写一份 v1 载荷：格式一旦定型，历史数据的样子就再也不由编码器决定。 */
    private fun v1Bytes(p: FloatArray, pressures: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(InkCodec.HEADER_BYTES + p.size * 4 + pressures.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte()))
        buffer.putInt(1)
        buffer.putInt(p.size / 2)
        for (v in p) buffer.putFloat(v)
        for (v in pressures) buffer.putFloat(v)
        return buffer.array()
    }
}
