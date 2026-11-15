package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.NormalizationProfile

/**
 * 源指纹（§7.2 的 `source_fingerprint`）。
 *
 * 它回答的问题是：**"这份文本还是当初那份文本吗？"**
 * 这是锚点解析的规则 1：指纹不符 → 直接判孤儿，绝不尝试重新匹配。
 *
 * 为什么指纹里要混入口径与块数：
 *   - 只哈希正文，则"同一份正文用不同归一化口径解析"会得到同一个指纹，
 *     而两种口径下的字符偏移是不一样的 → 规则 1 会误判为"没变"，
 *     于是把锚点按错误的偏移映射出去。这是最危险的一类错误（静默错位）。
 *   - 混入块数可以低成本地捕捉"段落结构变了但正文碰巧一样"的情况
 *     （例如把 3 段合并成 1 段又加了换行，正文长度不变）。
 *
 * 算法：FNV-1a 64 位。选它不是为了密码学强度（这里不需要），
 * 而是因为它**实现简单到可以自己写、且结果完全可复现** ——
 * 指纹一旦变了全库笔记都会进孤儿态，所以它必须是一个永不改变的定义，
 * 不能依赖某个库的默认实现（例如 `String.hashCode` 或平台相关的 MD5 提供者）。
 */
object SourceFingerprint {

    private const val OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
    private const val PRIME: ULong = 0x100000001b3uL

    /**
     * 计算指纹。
     *
     * @param normalizedText 归一化后的全文（不是原文：原文的换行符/BOM 差异不该算作内容变化）。
     * @param profile 归一化口径。口径的 id 与 version 都参与哈希。
     * @param blockCount 块切分结果中的块数。
     */
    fun compute(
        normalizedText: String,
        profile: NormalizationProfile,
        blockCount: Int,
    ): String {
        var hash = OFFSET_BASIS
        hash = mixString(hash, profile.profileId)
        hash = mixByte(hash, 0x1F)
        hash = mixString(hash, profile.version.toString())
        hash = mixByte(hash, 0x1F)
        hash = mixString(hash, blockCount.toString())
        hash = mixByte(hash, 0x00)
        hash = mixString(hash, normalizedText)
        return hash.toString(16).padStart(16, '0')
    }

    private fun mixByte(hash: ULong, b: Int): ULong {
        var h = hash xor (b.toUByte().toULong())
        h *= PRIME
        return h
    }

    private fun mixString(hash: ULong, s: String): ULong {
        var h = hash
        // 用 UTF-8 字节而不是 Char：Char 会丢掉代理对的结构，
        // 也会让"补码字符不同但 UTF-16 序列碰巧相同"这一情况少一层保护。
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toUByte().toULong())
            h *= PRIME
        }
        return h
    }
}
