package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.LineEnding
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.SpacePolicy

/**
 * 归一化口径注册表。
 *
 * 为什么需要"每种格式一个口径"而不是全局一个：
 *   Markdown 的语法**依赖空白**（4 空格缩进的代码块、行尾两空格的强制换行）。
 *   如果对 Markdown 用"折叠连续空格 + 去行尾空白"的默认口径，
 *   代码块与强制换行会在归一化阶段就被破坏，块切分结果与实际内容不符。
 *
 * 口径 id 必须唯一且随锚点一起持久化 —— 换了口径就等于换了坐标系，
 * 旧锚点必须重算而不是继续使用（§6.3.1）。
 */
object NormalizationProfiles {

    /** 纯文本 / 从 HTML 提取出的正文：空白可安全折叠。 */
    val PLAIN_TEXT: NormalizationProfile = NormalizationProfile()

    /** Markdown：保留空白与行尾空白（语法敏感）。 */
    val MARKDOWN: NormalizationProfile = NormalizationProfile(
        profileId = "nf:md-1",
        spacePolicy = SpacePolicy.PRESERVE,
        trimTrailing = false,
    )

    /** 固定版式（PDF）：换行由版面决定，不做空白折叠。 */
    val FIXED_LAYOUT: NormalizationProfile = NormalizationProfile(
        profileId = "nf:pdf-1",
        spacePolicy = SpacePolicy.PRESERVE,
        trimTrailing = true,
        lineEnding = LineEnding.NORMALIZE_LF,
    )

    /**
     * 按口径 id 找回口径。
     *
     * 找不到时**不能**默认回退到 PLAIN_TEXT 而不出声：那会让旧锚点在一个
     * 不同的坐标系下被解析，属于静默错位。调用方必须区分"找到"与"没找到"。
     */
    fun byId(id: String): NormalizationProfile? = listOf(PLAIN_TEXT, MARKDOWN, FIXED_LAYOUT)
        .firstOrNull { it.profileId == id }
}
