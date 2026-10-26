package dev.inkleaf.core.data

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.HighlightRenderMode
import dev.inkleaf.core.model.HighlightStyle
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.InkRepresentation
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.Side
import dev.inkleaf.core.model.TextAnchorChain
import dev.inkleaf.core.model.UnderlineStyle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久化格式的回归测试。
 *
 * 这些测试之所以能跑在 JVM 上（不需要设备），是因为"格式"这件事被刻意
 * 从 Android 里拆了出来：锚点链的 wire format 只依赖 kotlinx.serialization。
 * 而它恰恰是最不能靠"编译通过"来保证的部分 —— 一次字段改名就会让
 * 用户历史上所有的笔记读不出来。
 */
class SerializationTest {

    private fun structural(path: String = "blk[3]", start: Int = 2, end: Int = 11) =
        Selector.Structural(path, start, end, 1.0f)

    private fun hint() = Selector.ProgressHint(0.25f, null, "附近的文字")

    private fun chain(
        selectors: List<Selector> = listOf(structural(), Selector.TextQuote("他说", "前", "后"), hint()),
        profile: NormalizationProfile = NormalizationProfile(),
        fingerprint: String = "0123456789abcdef",
    ) = TextAnchorChain(selectors, profile, fingerprint)

    // ── 锚点链 ───────────────────────────────────────────────────────────────

    @Test
    fun `锚点链往返不丢信息`() {
        val original = chain()
        val restored = AnchorCodec.decode(AnchorCodec.encode(original))
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `口径与指纹一起往返`() {
        val profile = NormalizationProfile(
            version = 7,
            profileId = "nf:test-7",
            caseFold = true,
            stripPunctuation = true,
        )
        val restored = AnchorCodec.decode(AnchorCodec.encode(chain(profile = profile, fingerprint = "abcdef0123456789")))
        assertNotNull(restored)
        assertEquals("nf:test-7", restored!!.normalization.profileId)
        assertEquals(7, restored.normalization.version)
        assertEquals("abcdef0123456789", restored.sourceFingerprint)
        assertTrue(restored.normalization.caseFold)
        assertTrue(restored.normalization.stripPunctuation)
    }

    @Test
    fun `缺少 ProgressHint 的链判为不可读而不是补一个默认的`() {
        // 手工构造一个"少了兜底选择器"的 JSON：链尾不是 ProgressHint。
        // 正确行为是整条链作废并进入孤儿流程，而不是悄悄补一个 docRatio=0 的兜底 ——
        // 那会把笔记定位到文档开头，是最坏的一种"看起来正常"。
        val malformed = """
            {"format":1,
             "selectors":[{"type":"structural","blockPath":"blk[0]","startOffset":0,"endOffset":5,"baselineOffsetEm":1.0}],
             "normalization":{"version":1,"profileId":"nf:std-1","unicodeForm":"NFC",
               "spacePolicy":"COLLAPSE_HALF_WIDTH","lineEnding":"NORMALIZE_LF","stripBom":true,
               "trimTrailing":true,"caseFold":false,"stripPunctuation":false},
             "sourceFingerprint":"0123456789abcdef"}
        """.trimIndent()
        assertNull(AnchorCodec.decode(malformed))
    }

    @Test
    fun `不认识的口径枚举判为不可读`() {
        val bad = AnchorCodec.encode(chain()).replace("COLLAPSE_HALF_WIDTH", "SOME_FUTURE_POLICY")
        assertNull(AnchorCodec.decode(bad))
    }

    @Test
    fun `未来新增字段不影响读取`() {
        // 向前兼容：旧版本读新版本写的 JSON 时，多出来的字段只应被忽略。
        // 忽略不会导致定位错误（少一个字段只是少一个提示），所以这是安全的宽容。
        val json = AnchorCodec.encode(chain())
        val withExtra = json.dropLast(1) + ""","futureField":123}"""
        assertNotNull(AnchorCodec.decode(withExtra))
    }

    @Test
    fun `空串与 null 都返回 null 而不是抛异常`() {
        assertNull(AnchorCodec.decode(null))
        assertNull(AnchorCodec.decode(""))
        assertNull(AnchorCodec.decode("   "))
        assertNull(AnchorCodec.decode("不是 json"))
    }

    // ── 标注五种形态 ──────────────────────────────────────────────────────────

    private val docId = "doc-1"

    @Test
    fun `高亮往返`() {
        val original = Annotation.Highlight(
            id = "a1", docId = docId, createdAt = 100, colorArgb = 0x66FFD54F,
            tags = listOf("重点", "待查"), noteText = "这里说了什么",
            anchor = chain(),
            style = HighlightStyle(HighlightRenderMode.UNDER_TEXT, 0.9f, 0.2f),
        )
        val restored = AnnotationCodecs.toDomain(AnnotationCodecs.toRow(original))
        assertEquals(original, restored)
    }

    @Test
    fun `下划线多条锚点往返`() {
        val original = Annotation.Underline(
            id = "a2", docId = docId, createdAt = 200, colorArgb = 0xFF2196F3.toInt(),
            anchors = listOf(
                chain(listOf(structural("blk[1]", 0, 4), hint())),
                chain(listOf(structural("blk[2]", 0, 9), hint())),
            ),
            style = UnderlineStyle(0.05f, 0.1f, wavy = true),
        )
        val restored = AnnotationCodecs.toDomain(AnnotationCodecs.toRow(original))
        assertEquals(original, restored)
    }

    @Test
    fun `边注往返`() {
        val original = Annotation.MarginNote(
            id = "a3", docId = docId, createdAt = 300, colorArgb = 0xFF8D6E63.toInt(),
            noteText = "作者这里在埋线", anchor = chain(), side = Side.LEFT,
        )
        assertEquals(original, AnnotationCodecs.toDomain(AnnotationCodecs.toRow(original)))
    }

    @Test
    fun `书签不需要锚点链`() {
        val original = Annotation.Bookmark(
            id = "a4", docId = docId, createdAt = 400, colorArgb = 0xFF9E9E9E.toInt(),
            progressHint = Selector.ProgressHint(0.5f, "page-12", "这里"),
            anchor = null,
        )
        val restored = AnnotationCodecs.toDomain(AnnotationCodecs.toRow(original))
        assertEquals(original, restored)
    }

    @Test
    fun `整页自由笔迹可以没有锚点链`() {
        val original = Annotation.Ink(
            id = "a5", docId = docId, createdAt = 500, colorArgb = 0xFF1A1A1A.toInt(),
            anchor = null,
            semantics = InkReflowSemantics.PINNED,
            representation = InkRepresentation.VECTOR,
            strokeIds = listOf("s1", "s2"),
            pages = listOf("page-3"),
        )
        val restored = AnnotationCodecs.toDomain(AnnotationCodecs.toRow(original)) as? Annotation.Ink
        assertNotNull(restored)
        assertEquals(InkReflowSemantics.PINNED, restored!!.semantics)
        assertEquals(listOf("s1", "s2"), restored.strokeIds)
        assertEquals(listOf("page-3"), restored.pages)
        assertNull(restored.anchor)
    }

    @Test
    fun `笔刷调色板随笔迹一起存`() {
        // ink-storage 不序列化 Brush，所以笔刷必须由我们自己带在标注的 extra 里。
        // 少了这一步，历史笔记会以默认黑色细笔重绘。
        val brushes = listOf(BrushSpec.DEFAULT_PEN, BrushSpec.DEFAULT_HIGHLIGHTER)
        val extra = AnnotationExtraDto(brushes = brushes.map { BrushSpecDto.of(it) })
        val json = AnnotationCodecs.encodeExtra(extra)
        val restored = AnnotationCodecs.decodeExtra(json)!!.brushes.map { it.toDomain() }
        assertEquals(brushes, restored)
    }

    // ── 损坏数据的处置 ────────────────────────────────────────────────────────

    @Test
    fun `锚点损坏时标注整体判为不可读`() {
        val row = AnnotationRow(
            id = "broken", docId = docId, kind = AnnotationType.HIGHLIGHT.name,
            createdAt = 1, colorArgb = 0, tagsJson = "[]", noteText = null,
            anchorJson = "{ 这不是有效的锚点链", extraJson = null, deleted = false,
        )
        assertNull(AnnotationCodecs.toDomain(row))
    }

    @Test
    fun `未知标注类型判为不可读`() {
        val row = AnnotationRow(
            id = "future", docId = docId, kind = "SOME_FUTURE_KIND",
            createdAt = 1, colorArgb = 0, tagsJson = "[]", noteText = null,
            anchorJson = AnchorCodec.encode(chain()), extraJson = null, deleted = false,
        )
        assertNull(AnnotationCodecs.toDomain(row))
    }

    @Test
    fun `标签往返且容忍损坏`() {
        val tags = listOf("A", "中文", "with \"quote\"")
        assertEquals(tags, AnnotationCodecs.decodeTags(AnnotationCodecs.encodeTags(tags)))
        assertEquals(emptyList<String>(), AnnotationCodecs.decodeTags("不是 json"))
        assertEquals(emptyList<String>(), AnnotationCodecs.decodeTags(null))
    }

    @Test
    fun `高亮样式的未知渲染模式不再回退到默认值`() {
        // 渲染模式未知时若回退到默认的 MULTIPLY，画出来的颜色与当初不同；
        // 宁可让整条高亮读不出来。
        val dto = HighlightStyleDto(renderMode = "FUTURE_MODE", snapCoverageThreshold = 0.8f, bleed = 0.1f)
        assertNull(dto.toDomain())
    }

    @Test
    fun `下划线有一条链读不出来就整体作废`() {
        // 下划线是"逐行"的：若某一行读不出来却把它当作不存在，
        // 用户看到的是"一条断成两截的下划线"，而他没法知道少了一截。
        val original = Annotation.Underline(
            id = "u1", docId = docId, createdAt = 1, colorArgb = 0,
            anchors = listOf(chain(listOf(structural("blk[1]", 0, 4), hint()))),
        )
        val row = AnnotationCodecs.toRow(original)
        val valid = AnnotationCodecs.decodeExtra(row.extraJson)!!
        val damaged = valid.copy(
            multiAnchors = valid.multiAnchors +
                listOf(AnchorChainDto.of(chain(listOf(structural("blk[9]", 0, 1), hint()))).copy(format = 99)),
        )
        assertNull(AnnotationCodecs.toDomain(row.copy(extraJson = AnnotationCodecs.encodeExtra(damaged))))
    }

    @Test
    fun `旧档案里没有 overprint 字段的笔刷照样读得出来，且不是荧光笔`() {
        // M25 S2 给笔刷加了"叠印"这一档。磁盘上已经躺着**没有这个字段**的旧档案，
        // 它们必须读得出来 —— 读不出来就是"升级之后老笔记全没了"。
        // 反过来也不能把旧笔刷当成荧光笔：那时根本没有荧光笔，
        // 把它读成叠印笔会让历史笔迹在深色纸上换一种混色，等于改了用户的墨。
        val old = """{"name":"pen","colorArgb":-15158707,"widthEm":0.04,"pressureEnabled":true,"pressureScaleMax":2.0,"lowLatency":true}"""

        val brush = AnchorCodec.json.decodeFromString<BrushSpecDto>(old).toDomain()

        assertEquals("pen", brush.name)
        assertEquals(0.04f, brush.widthEm, 1e-6f)
        assertFalse("旧笔刷不可能是叠印笔", brush.overprint)
    }

    @Test
    fun `荧光笔的叠印标志会往返，不会在存一次之后丢掉`() {
        // 丢了的症状很隐蔽：笔迹还在、颜色还在，只是它**不再半透明** ——
        // 于是"标亮这一段"变成"涂掉这一段"，而用户只会以为自己手重了。
        val brush = BrushSpec.DEFAULT_HIGHLIGHTER
        assertTrue("前提：默认荧光笔就是叠印笔", brush.overprint)

        val back = AnchorCodec.json.decodeFromString<BrushSpecDto>(
            AnchorCodec.json.encodeToString(BrushSpecDto.of(brush)),
        ).toDomain()

        assertEquals(brush, back)
        assertTrue(back.overprint)
        assertEquals(BrushSpec.DEFAULT_HIGHLIGHTER.colorArgb, back.colorArgb)
        assertEquals(BrushSpec.DEFAULT_HIGHLIGHTER.widthEm, back.widthEm, 1e-6f)
    }

    @Test
    fun `标注行里没有 deleted 标记的导出不会出现`() {
        val row = AnnotationCodecs.toRow(
            Annotation.Bookmark(
                id = "b1", docId = docId, createdAt = 1, colorArgb = 0,
                progressHint = Selector.ProgressHint(0.1f),
            ),
        )
        assertFalse(row.deleted)
    }
}
