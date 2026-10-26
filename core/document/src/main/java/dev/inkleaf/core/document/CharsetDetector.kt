package dev.inkleaf.core.document

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码探测。
 *
 * 这件事对中文 TXT 是**必须的**，不是锦上添花：Windows 上国区用户手里的
 * TXT 大量是 GBK/GB18030，而 Android 的 `InputStreamReader` 默认按 UTF-8 解，
 * 解出来是整篇乱码。没有这一步，"支持 TXT"这个承诺在中国用户手里直接不成立。
 *
 * 能力边界（刻意写清楚，不假装更多）：
 *   1. **有 BOM** → 直接按 BOM 定，置信度 1.0，不会有歧义。
 *   2. **严格合法的 UTF-8** → UTF-8。这是很强的判据：GBK 中文字节序列
 *      极少能通过 UTF-8 的合法性校验。
 *   3. **否则尝试 GB18030**（GBK / GB2312 的超集），置信度中等。
 *   4. **Big5 / 其他** 不做自动判定，交给用户在导入界面手动选。
 *      理由：GBK 与 Big5 的字节范围大量重叠，两者的误判都会解出"看着像汉字
 *      但全是错字"的结果，而**统计上无法可靠区分**（都需要字频表）。
 *      与其猜错后让用户看到一篇通顺的错字文，不如给一个明确的编码下拉框。
 *      这也是真实阅读器的通行做法（多看/静读天下都有编码切换）。
 *
 * 因此本类的输出要当作"建议 + 证据"，而不是事实。[DetectedEncoding.confidence]
 * 低于 [LOW_CONFIDENCE] 时，导入界面必须提供手动切换编码的入口。
 */
object CharsetDetector {

    /** 低于此置信度就必须给用户手动选择编码的入口。 */
    const val LOW_CONFIDENCE = 0.8f

    /** 自动判定支持的编码。顺序即优先级（同级时取靠前者）。 */
    private val AUTO_CANDIDATES = listOf("UTF-8", "GB18030")

    data class DetectedEncoding(
        /** 探测到的编码规范名，可直接交给 Charset.forName。 */
        val charsetName: String,
        val confidence: Float,
        /** BOM 字节数。解码前必须跳过这些字节，否则会在正文开头插入 U+FEFF。 */
        val bomLength: Int,
        /** 判定依据，用于日志与"为什么这次猜对了/错了"的排查。 */
        val evidence: String,
    ) {
        val isLowConfidence: Boolean get() = confidence < LOW_CONFIDENCE
    }

    fun detect(bytes: ByteArray): DetectedEncoding {
        if (bytes.isEmpty()) {
            return DetectedEncoding("UTF-8", 1f, 0, "空文件")
        }

        detectBom(bytes)?.let { return it }

        // 严格解码：只要有一个非法字节就判为不是这种编码。
        // 用宽松解码（替换成 U+FFFD）会掩盖问题，让"猜错了"变成"看起来还行"。
        var best: DetectedEncoding? = null
        for (name in AUTO_CANDIDATES) {
            val decoded = strictDecode(bytes, 0, bytes.size, name) ?: continue
            val confidence = if (name == "UTF-8") 0.95f else 0.6f
            val candidate = DetectedEncoding(
                charsetName = name,
                confidence = confidence,
                bomLength = 0,
                evidence = "严格解码通过；样本={${decoded.take(24).replace("\n", "\\n")}}",
            )
            if (best == null || candidate.confidence > best.confidence) best = candidate
        }

        return best ?: DetectedEncoding(
            charsetName = "UTF-8",
            confidence = 0.2f,
            bomLength = 0,
            evidence = "所有自动候选都解码失败；默认按 UTF-8 交给用户确认",
        )
    }

    /**
     * 按探测结果解码。
     *
     * 解码失败时**不抛异常**，而是回退成"按 UTF-8 宽松解码"：
     * 导入流程里"能打开但可能有乱码"远好于"直接打不开"，
     * 且用户还有手动切编码的机会。
     */
    fun decode(bytes: ByteArray, detected: DetectedEncoding): String {
        val offset = detected.bomLength.coerceIn(0, bytes.size)
        val length = bytes.size - offset
        if (length <= 0) return ""
        return strictDecode(bytes, offset, length, detected.charsetName)
            ?: runCatching { String(bytes, offset, length, Charset.forName("UTF-8")) }.getOrDefault("")
    }

    private fun detectBom(bytes: ByteArray): DetectedEncoding? {
        // 顺序至关重要：UTF-32LE 的 BOM 前两字节是 FF FE，
        // 与 UTF-16LE 完全相同。先判 UTF-16 会把 UTF-32LE 文件解成一堆乱码。
        if (bytes.size >= 4) {
            if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
                bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
            ) {
                return DetectedEncoding("UTF-32BE", 1f, 4, "BOM 00 00 FE FF")
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
            ) {
                return DetectedEncoding("UTF-32LE", 1f, 4, "BOM FF FE 00 00")
            }
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return DetectedEncoding("UTF-8", 1f, 3, "BOM EF BB BF")
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return DetectedEncoding("UTF-16BE", 1f, 2, "BOM FE FF")
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return DetectedEncoding("UTF-16LE", 1f, 2, "BOM FF FE")
            }
        }
        return null
    }

    /**
     * 严格解码。任何非法字节 → null。
     *
     * `CodingErrorAction.REPORT` 是关键：默认的 REPLACE 会把非法字节变成 U+FFFD
     * 而不是失败，于是"GBK 字节被当成 UTF-8"这种情况会被当成成功解码。
     */
    fun strictDecode(bytes: ByteArray, offset: Int, length: Int, charsetName: String): String? {
        if (length <= 0) return ""
        val charset = runCatching { Charset.forName(charsetName) }.getOrNull() ?: return null
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (e: CharacterCodingException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** 供导入界面展示的可选编码列表。UTF-8 与 GB18030 覆盖国区绝大多数 TXT。 */
    val MANUAL_CHOICES: List<String> = listOf(
        "UTF-8", "GB18030", "GBK", "Big5", "UTF-16LE", "UTF-16BE", "ISO-8859-1",
    ).filter { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
}
