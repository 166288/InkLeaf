package dev.inkleaf.core.document

import dev.inkleaf.core.anchoring.NormalizationProfiles
import dev.inkleaf.core.anchoring.SourceFingerprint
import dev.inkleaf.core.anchoring.TextNormalizer
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.ScannedPdfPolicy

/**
 * 字节 → 文档会话。
 *
 * 放在 `:core:document` 而不是 `:core:data`：它是"文档"这件事本身的一部分
 * （编码 → 归一化 → 块切分 → 指纹），与"文件放哪、DB 怎么写"无关。
 * 这样它可以在 JVM 单测里被完整执行 —— 而它恰恰是锚点坐标系的产生地，
 * 最不该只靠"编译通过"来保证正确。
 */
object DocumentCodec {

    /**
     * PDF 的 `DecodedText.charsetName` 取值。
     *
     * PDF 的正文不是"某种编码解出来的字节"：它来自平台的文本层。
     * 与前缀 `PDF` 无关的地方（例如界面上"编码"下拉框）因此必须按它分支，
     * 而不是把它当成一个真的字符集名字去 `Charset.forName`。
     */
    const val PDF_TEXT_LAYER_LABEL = "PDF 文本层"

    /**
     * 解码结果。
     *
     * 刻意把 `confidence` / `evidence` / `lowConfidence` 一路带到界面：
     * 编码探测是**概率判断**，把概率藏起来假装是事实，用户就失去了
     * "这篇是乱码还是本来就这样"的判断依据（见 [CharsetDetector] 的能力边界）。
     */
    data class DecodedText(
        val text: String,
        val charsetName: String,
        val confidence: Float,
        val bomLength: Int,
        val evidence: String,
        val lowConfidence: Boolean,
    )

    /**
     * 一次"字节 → 会话"的完整结果。
     *
     * 除了会话本身，还带出**只有解析阶段才知道的事实**：作者（EPUB 元数据）、
     * 解析过程中的取舍（跳过了几个正文文档/几张图）。这些信息若不在这里带出来，
     * 上层就只能猜，或者把整条解析链路再走一遍。
     */
    data class SessionBuild(
        /**
         * 会话。类型是**接口**而不是重排实现：PDF 是固定版式，行盒来自它自己的文本层
         * （[PdfDocumentSession]），与重排的那一套没有共同实现，只有共同的契约。
         */
        val session: DocumentSession,
        val decoded: DecodedText,
        /** 文档**内部**声明的标题（EPUB 的 `dc:title`）；TXT/Markdown 为 null。 */
        val title: String? = null,
        val author: String? = null,
        val warnings: List<String> = emptyList(),
        /**
         * PDF 的文本层判定；非 PDF 为 null。
         *
         * 导入闸门要的就是它（扫描件 → 提示后允许只读打开），所以必须从解析阶段
         * 带出来，而不是让导入层再解析一遍 —— 那正是"导入与打开各算一套"的老问题。
         */
        val pdfAssessment: dev.inkleaf.core.model.ScannedPdfAssessment? = null,
        /**
         * 本机能不能读 PDF 文本层（见 [PdfPageSource.canReadTextLayer]）。
         *
         * 与 [pdfAssessment] 一起用：能力为 false 时，`pdfAssessment` 说的"没有文本层"
         * 只代表**我们看不见**，不能据此把文件判成扫描件。
         */
        val pdfTextLayerAvailable: Boolean = true,
    )

    /**
     * 解码。
     *
     * @param forcedCharset 用户在导入界面上手动指定的编码。给定时**优先于**自动探测，
     *   但仍保留 BOM 判定用于跳过 BOM 字节（否则正文开头会多一个 U+FEFF，
     *   使首块偏移整体 +1，进而让"从第一行开始的锚点"全部错位）。
     *   指定编码解码失败时**不抛异常**，而是走宽松解码并标记低置信度：
     *   "能打开但可能是乱码"比"打不开"对用户更有利。
     */
    fun decode(bytes: ByteArray, forcedCharset: String? = null): DecodedText {
        val detected = CharsetDetector.detect(bytes)

        if (forcedCharset != null) {
            val bomLength = if (sameFamily(detected.charsetName, forcedCharset)) detected.bomLength else 0
            val strict = CharsetDetector.strictDecode(
                bytes, bomLength, bytes.size - bomLength, forcedCharset,
            )
            if (strict != null) {
                return DecodedText(
                    text = strict,
                    charsetName = forcedCharset,
                    confidence = 1f,
                    bomLength = bomLength,
                    evidence = "用户手动指定 $forcedCharset",
                    lowConfidence = false,
                )
            }
            val lenient = runCatching {
                String(bytes, bomLength, bytes.size - bomLength, java.nio.charset.Charset.forName(forcedCharset))
            }.getOrDefault("")
            return DecodedText(
                text = lenient,
                charsetName = forcedCharset,
                confidence = 0.1f,
                bomLength = bomLength,
                evidence = "用户指定 $forcedCharset，但严格解码失败，已按宽松方式解码（可能有乱码）",
                lowConfidence = true,
            )
        }

        return DecodedText(
            text = CharsetDetector.decode(bytes, detected),
            charsetName = detected.charsetName,
            confidence = detected.confidence,
            bomLength = detected.bomLength,
            evidence = detected.evidence,
            lowConfidence = detected.isLowConfidence,
        )
    }

    /**
     * 每种格式的归一化口径。
     *
     * Markdown 走自己的口径（保留空白），见 [NormalizationProfiles] 的说明。
     * 这里**不允许**出现"默认用 PLAIN_TEXT"的分支：口径就是锚点坐标系。
     */
    fun profileFor(kind: DocumentKind): NormalizationProfile = when (kind) {
        DocumentKind.TXT, DocumentKind.EPUB -> NormalizationProfiles.PLAIN_TEXT
        DocumentKind.MARKDOWN -> NormalizationProfiles.MARKDOWN
        DocumentKind.PDF -> NormalizationProfiles.FIXED_LAYOUT
    }

    /** 从文件名推断格式。不认识的扩展名按 TXT 处理 —— 纯文本是最宽的解释。 */
    fun kindFromFileName(fileName: String): DocumentKind {
        val lower = fileName.substringAfterLast('.', "").lowercase()
        return when (lower) {
            "md", "markdown", "mdown", "mkd" -> DocumentKind.MARKDOWN
            "epub" -> DocumentKind.EPUB
            "pdf" -> DocumentKind.PDF
            else -> DocumentKind.TXT
        }
    }

    /**
     * 构造会话。指纹在**归一化文本 + 口径 + 块数**之上计算，三者的组合
     * 才是"这份文本还是当初那份文本吗"的完整回答（见 [SourceFingerprint]）。
     *
     * @param chapters 已知的章节标记（EPUB spine）。为空时用 [BlockSplitter] 的
     *   启发式目录（TXT/Markdown）。
     */
    fun buildSession(
        docId: String,
        kind: DocumentKind,
        rawText: String,
        profile: NormalizationProfile = profileFor(kind),
        chapters: List<ChapterMark> = emptyList(),
        /** 正文里的图片（未归一化的偏移）。归一化落点在这里换算。 */
        images: List<EpubExtractor.EpubImage> = emptyList(),
        /** 取图器。null 表示这份文档没有图（TXT / Markdown）。 */
        imageProvider: DocumentImageProvider? = null,
    ): ReflowableDocumentSession {
        val normalized = TextNormalizer.normalize(rawText, profile)
        // 图片的偏移要换到**归一化**坐标系：排版与锚点都只认它。
        // 占位字符不会被任何归一化规则删掉（它不是空白、不是标点），
        // 所以"原文偏移 → 归一化偏移"直接用 normalizedIndexOf 落点是安全的；
        // 万一落空（口径将来变了），这里再确认一次那个位置真的是占位字符。
        val imageByOffset: Map<Int, String> = if (images.isEmpty()) {
            emptyMap()
        } else {
            val map = HashMap<Int, String>(images.size * 2)
            for (image in images) {
                val offset = normalized.normalizedIndexOf(image.charOffset)
                if (normalized.text.getOrNull(offset) == IMAGE_PLACEHOLDER) {
                    map[offset] = image.href
                }
            }
            map
        }
        val base = BlockSplitter.split(kind, normalized)
        // 把 href 挂到对应的图片块上。块切分只看文本（它不该知道 EPUB 的存在），
        // 所以"哪个块是哪张图"在这里补上，一个偏移对应一个块。
        val blocks = if (imageByOffset.isEmpty()) {
            base.blocks
        } else {
            base.blocks.map { block ->
                val href = imageByOffset[block.absoluteStart]
                if (href != null && block.kind == BlockKind.IMAGE) block.copy(imageHref = href) else block
            }
        }
        val split = if (chapters.isEmpty()) {
            SplitResult(blocks, base.toc)
        } else {
            SplitResult(blocks, BlockSplitter.tocFromChapters(SplitResult(blocks, base.toc), normalized, chapters))
        }
        val fingerprint = SourceFingerprint.compute(normalized.text, profile, split.blockCount)
        return ReflowableDocumentSession(
            docId = docId,
            kind = kind,
            profile = profile,
            normalized = normalized,
            split = split,
            fingerprint = fingerprint,
            images = imageByOffset,
            imageProvider = imageProvider,
        )
    }

    /**
     * 字节 → 会话。**导入与打开共用的唯一入口。**
     *
     * 为什么必须唯一：锚点的 `sourceFingerprint` 是在"解码 + 归一化 + 切块"之后算出来的。
     * 如果导入流程与打开流程各写一遍这三步，任何一次口径不一致（哪怕只是
     * EPUB 解包顺序不同）都会让"刚导入的书，笔记全部失配"。把两条路合成
     * 一个函数，就不存在"忘记同步修改另一处"的可能。
     *
     * EPUB 失败时抛 [EpubFormatException]、PDF 失败时抛 [PdfFormatException]
     * （都由导入层翻译成用户可读的拒绝理由）。
     *
     * @param pdf PDF 的页源。**只有 PDF 需要它**，也**必须**由调用方提供：
     *   解析 PDF 只能靠平台阅读器，而本工程的原则是"不可验证的东西挤到最薄的一层"，
     *   于是平台那层以 [PdfPageSource] 的形式注入进来（见 [PdfPages] 的说明）。
     *   导入与打开走的是同一个函数、同一个入参，因此不可能出现
     *   "导入时能解析、打开时解析不了"。
     */
    fun sessionFromBytes(
        docId: String,
        kind: DocumentKind,
        bytes: ByteArray,
        forcedCharset: String? = null,
        pdf: PdfPageSource.Factory? = null,
    ): SessionBuild {
        if (kind == DocumentKind.PDF) {
            return buildPdfSession(docId, bytes, pdf)
        }

        if (kind == DocumentKind.EPUB) {
            val epub = EpubExtractor.extract(bytes)
            val decoded = DecodedText(
                text = epub.text,
                charsetName = EpubExtractor.CHARSET_LABEL,
                confidence = 1f,
                bomLength = 0,
                evidence = epub.evidence(),
                // "置信度"在 EPUB 上没有意义：编码来自文件内声明，是事实不是猜测。
                // 因此这里不设低置信度，而是把解包过程中的取舍写进 evidence。
                lowConfidence = false,
            )
            val chapters = epub.chapters.map {
                ChapterMark(title = it.title, charOffset = it.charOffset, level = it.level)
            }
            return SessionBuild(
                session = buildSession(
                    docId = docId,
                    kind = kind,
                    rawText = epub.text,
                    chapters = chapters,
                    images = epub.images,
                    imageProvider = EpubImageProvider(epub.imageBytes),
                ),
                decoded = decoded,
                title = epub.title,
                author = epub.author,
                warnings = buildList {
                    if (epub.missingSpineItems > 0) {
                        add("有 ${epub.missingSpineItems} 个正文文档在压缩包里读不到，这部分正文缺失（文件可能损坏或被截断）")
                    }
                    if (epub.skippedSpineItems > 0) {
                        add("有 ${epub.skippedSpineItems} 个正文文档没有文字，已跳过（通常是封面或图片页）")
                    }
                    if (epub.imageCount > 0) {
                        val shown = epub.images.size
                        if (shown == 0) {
                            add("书里的 ${epub.imageCount} 张图片都没能读到（文件缺失或过大），正文按纯文字显示")
                        } else if (shown < epub.imageCount) {
                            add("书里的 ${epub.imageCount} 张图片中有 $shown 张随正文显示，其余读不到（文件缺失、格式不受支持或体积过大）")
                        }
                    }
                    if (epub.chapters.isEmpty()) add("没能识别出章节，目录会只有一项")
                },
            )
        }

        val decoded = decode(bytes, forcedCharset)
        return SessionBuild(
            session = buildSession(docId, kind, decoded.text),
            decoded = decoded,
        )
    }

    /** PDF 的会话构造：页源 → 页事实 → 文本层 → 会话。见 [sessionFromBytes] 的 `pdf` 参数。 */
    private fun buildPdfSession(
        docId: String,
        bytes: ByteArray,
        pdf: PdfPageSource.Factory?,
    ): SessionBuild {
        val factory = pdf ?: throw PdfFormatException(
            PdfFormatException.Failure.NO_PAGE_SOURCE,
            "PDF 没有页源：导入与打开都必须提供 PdfPageSource.Factory",
        )

        val source = factory.open(bytes)
        val pages: List<PdfPageFacts>
        val evidence: String
        val canReadTextLayer: Boolean
        try {
            if (source.pageCount <= 0) {
                throw PdfFormatException(
                    PdfFormatException.Failure.BROKEN,
                    "这份 PDF 里读不到任何页面（文件可能损坏、被加密，或者根本不是 PDF）",
                )
            }
            pages = (0 until source.pageCount).map { source.page(it) }
            // 页源是外来资源，关闭之后不该再问它任何事：证据在这里就取出来。
            evidence = source.textLayerEvidence
            canReadTextLayer = source.canReadTextLayer
        } finally {
            runCatching { source.close() }
        }

        val session = PdfDocumentSession.build(docId, pages)
        val assessment = ScannedPdfPolicy.assess(pages.map { it.text })

        return SessionBuild(
            session = session,
            decoded = DecodedText(
                text = session.rawText,
                charsetName = PDF_TEXT_LAYER_LABEL,
                // 与 EPUB 同理：文本层的来路是事实，不是概率判断。
                confidence = 1f,
                bomLength = 0,
                evidence = assessment.reason + " 文本层来自：" + evidence,
                lowConfidence = false,
            ),
            warnings = if (!canReadTextLayer) {
                listOf(
                    "本机 Android 版本读不到 PDF 文本层（需要 Android 15 及以上）：" +
                        "可以按页阅读，但不能选中文字或按行锚定笔记。" +
                        "这不代表文件是扫描件。",
                )
            } else {
                emptyList()
            },
            pdfAssessment = assessment,
            pdfTextLayerAvailable = canReadTextLayer,
        )
    }

    /** 供导入预览显示的摘录。跳过空行，取前若干非空行。 */
    fun excerpt(session: DocumentSession, maxChars: Int = 320): String {
        val sb = StringBuilder()
        for (block in session.blocks) {
            val line = block.text.trim()
            if (line.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
            if (sb.length >= maxChars) break
        }
        return sb.toString().take(maxChars)
    }

    private fun sameFamily(a: String, b: String): Boolean {
        val ua = a.uppercase()
        val ub = b.uppercase()
        return ua == ub ||
            (ua.startsWith("UTF-8") && ub.startsWith("UTF-8")) ||
            (ua.startsWith("UTF-16") && ub.startsWith("UTF-16")) ||
            (ua.startsWith("UTF-32") && ub.startsWith("UTF-32"))
    }
}
