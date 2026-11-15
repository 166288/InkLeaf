package dev.inkleaf.core.data

import dev.inkleaf.core.model.Affine2D
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 笔迹点位数据的二进制编解码。
 *
 * 为什么不用 JSON 存点位：一条手写笔迹动辄几百到几千个点，
 * JSON 的 `[0.1234,56.789,...]` 形式会让体积膨胀 3~5 倍，而这是写库最热的数据。
 * 二进制是这里唯一合理的选择。
 *
 * 为什么自己写而不是用 `ink-storage`：`ink-storage` 的 protobuf 编码
 * **只序列化 `StrokeInputBatch`**，而我们的笔迹以"行局部 em 坐标"存储（§6.3.2），
 * 与 Ink 的输入批次坐标系不同；且它的 wire format 由 Ink 版本决定，
 * 一旦 Ink 破坏性升级，历史笔记可能读不出来（这正是版本被锁死在 1.0.0 的原因之一）。
 * 自己定义格式让"历史数据可读性"掌握在自己手里。
 *
 * 格式（小端序）：
 * ```
 *   magic   4B   'I' 'N' 'K' '1'
 *   version 4B   = 2
 *   count   4B   点数 N
 *   points  8N B x0,y0,x1,y1,...（float32）
 *   press   4N B 压力 0..1（float32）
 *   frozen  4B   冻结变换的浮点个数：0 = 没有，6 = 后面跟 a,b,c,d,e,f
 *   affine  24B  （仅当 frozen = 6）落笔那一刻的 em → 文档坐标变换
 * ```
 * 头里带 magic 与 version 是为了让"格式变了"变成**可检测的失败**，
 * 而不是把旧数据按新格式解释成一片垃圾坐标。
 *
 * v2 只加了末尾的冻结变换（`StrokeAnchor.frozenMatrix`，见那里关于 PINNED / FREE 的说明）。
 * 解码仍然**必须**能读 v1：库里已有的笔记就是 v1，读不出来等于把用户的墨迹弄丢。
 * 反过来，v1 的读取器遇到 v2 会明确报错（版本号对不上），不会把末尾那段当成多余字节。
 */
object InkCodec {

    private val MAGIC = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 2

    /** 不带冻结变换的旧版本。库里的历史数据都是它，因此它是"必须一直能读"的清单。 */
    private const val VERSION_WITHOUT_FROZEN = 1

    const val HEADER_BYTES = 12

    /** 一个仿射变换 = 6 个浮点（a b c / d e f，见 [Affine2D]）。 */
    private const val AFFINE_FLOATS = 6

    class CorruptInkException(message: String) : IllegalArgumentException(message)

    /**
     * [frozenMatrix] 为 null 表示这一笔没有冻结几何（FOLLOWER，或旧数据）——
     * 与"冻结变换恰好是单位矩阵"完全不是一回事，所以它不能用零值表示。
     */
    data class DecodedInk(
        val points: FloatArray,
        val pressures: FloatArray,
        val frozenMatrix: Affine2D? = null,
    ) {
        val pointCount: Int get() = points.size / 2
    }

    fun encode(points: FloatArray, pressures: FloatArray, frozenMatrix: Affine2D? = null): ByteArray {
        require(points.size % 2 == 0) { "点位必须是 x,y 成对，实际长度 ${points.size}" }
        val count = points.size / 2
        require(pressures.size == count) {
            "压力数量必须等于点数：${pressures.size} != $count"
        }
        val frozenFloats = if (frozenMatrix == null) 0 else AFFINE_FLOATS
        val size = HEADER_BYTES + points.size * 4 + pressures.size * 4 + 4 + frozenFloats * 4
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putInt(VERSION)
        buffer.putInt(count)
        for (v in points) buffer.putFloat(v)
        for (v in pressures) buffer.putFloat(v)
        buffer.putInt(frozenFloats)
        if (frozenMatrix != null) {
            buffer.putFloat(frozenMatrix.a)
            buffer.putFloat(frozenMatrix.b)
            buffer.putFloat(frozenMatrix.c)
            buffer.putFloat(frozenMatrix.d)
            buffer.putFloat(frozenMatrix.e)
            buffer.putFloat(frozenMatrix.f)
        }
        return buffer.array()
    }

    /**
     * 解码。数据损坏时抛 [CorruptInkException]，**绝不**返回"尽力而为"的结果。
     *
     * 这一点刻意的：一段错位的笔迹画出来是"页面上多了一团乱线"，
     * 用户会以为是 App 的显示 bug 而不是数据损坏，于是不会去重新写这条笔记。
     * 明确失败才能让上层把这条标注标成"无法读取"。
     */
    fun decode(bytes: ByteArray): DecodedInk {
        if (bytes.size < HEADER_BYTES) {
            throw CorruptInkException("长度不足头部：${bytes.size} < $HEADER_BYTES")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw CorruptInkException("magic 不匹配：${magic.joinToString(",") { it.toString() }}")
        }
        val version = buffer.getInt()
        if (version != VERSION && version != VERSION_WITHOUT_FROZEN) {
            throw CorruptInkException("不支持的格式版本：$version（支持 $VERSION_WITHOUT_FROZEN 与 $VERSION）")
        }
        val count = buffer.getInt()
        if (count < 0) {
            throw CorruptInkException("点数为负：$count")
        }
        // 点位与压力段两个版本完全一样，只有末尾的冻结段不同。
        val base = HEADER_BYTES + count * 8 + count * 4
        // 冻结段的长度由**它自己**声明（0 = 没有），所以两个版本的长度都不固定：
        // v1 干脆没有这一段；v2 的 len 是 0 或 6。先读出声明再核对总长度，
        // 顺序反了就会把"跟随文字的笔迹"误判成截断数据。
        val frozenFloats = when {
            version == VERSION_WITHOUT_FROZEN -> 0
            bytes.size < base + 4 -> throw CorruptInkException(
                "长度不足冻结段标记：${bytes.size} < ${base + 4}",
            )
            // 绝对位置读取，不动游标 —— 点位与压力还没读。
            else -> buffer.getInt(base)
        }
        if (frozenFloats != 0 && frozenFloats != AFFINE_FLOATS) {
            // 只可能是"格式又变了"或数据被改过。两种都不该猜：
            // 宁可让这一条笔记被标成读不出来，也不要画出半条错误的笔迹。
            throw CorruptInkException("冻结变换的浮点个数不认识：$frozenFloats（期望 0 或 $AFFINE_FLOATS）")
        }
        val expected = base + (if (version == VERSION_WITHOUT_FROZEN) 0 else 4 + frozenFloats * 4)
        if (bytes.size != expected) {
            // 长度严格相等而不是"至少"：多出来的字节说明数据流已经错位，
            // 继续读下去只会得到一串看似合理的错误坐标。
            throw CorruptInkException(
                "长度与点数不符：实际 ${bytes.size}，按 $count 点的 v$version 应为 $expected",
            )
        }
        val points = FloatArray(count * 2)
        for (i in points.indices) points[i] = buffer.getFloat()
        val pressures = FloatArray(count)
        for (i in pressures.indices) pressures[i] = buffer.getFloat()
        if (version == VERSION_WITHOUT_FROZEN) {
            // 老数据没有冻结几何，如实返回 null：调用方据此让它跟随当前行盒
            // （这时"它原来固定在哪"这个信息是不存在的，不能凭空补一个）。
            return DecodedInk(points, pressures, frozenMatrix = null)
        }
        buffer.getInt() // 跳过已经核对过的浮点个数
        if (frozenFloats == 0) {
            return DecodedInk(points, pressures, frozenMatrix = null)
        }
        val frozen = Affine2D(
            a = buffer.getFloat(),
            b = buffer.getFloat(),
            c = buffer.getFloat(),
            d = buffer.getFloat(),
            e = buffer.getFloat(),
            f = buffer.getFloat(),
        )
        return DecodedInk(points, pressures, frozenMatrix = frozen)
    }

    /** 损坏时返回 null 的版本，供批量加载用（一条坏数据不该让整个文档打不开）。 */
    fun decodeOrNull(bytes: ByteArray?): DecodedInk? {
        if (bytes == null) return null
        return try {
            decode(bytes)
        } catch (e: CorruptInkException) {
            null
        }
    }
}
