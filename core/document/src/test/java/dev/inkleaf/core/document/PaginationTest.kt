package dev.inkleaf.core.document

import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 排版与分页的回归测试 —— 跑**真实** `StaticLayout`。
 *
 * ## 为什么这些断言必须走真实排版
 *
 * 方案 §5.7 的硬性约束是：行盒只能来自真实排版结果，不允许用"字号 × 行高"自己算。
 * 因此"行盒是否正确"在纯 JVM 上无法验证：它依赖 Android 的文本栈。
 *
 * 选择 Robolectric 的 `NATIVE` 图形模式而不是 `connectedAndroidTest`，原因是
 * **可执行性**：本机没有虚拟化（`HypervisorPresent=False`，AEHD 未安装，
 * 开 WHPX 需要管理员权限加重启），设备测试在这台机器上永远跑不起来。
 * `NATIVE` 模式走的是真实 Skia，`StaticLayout` 是真的在排版 ——
 * 不是"看起来像排版"的桩实现。
 *
 * 因此这里断言得比别处更细：不只断言"有行盒"，还断言
 * **两套访问器（`lineBoxes()` 与 `section.lineTop/lineBaseline`）给出同一组坐标**。
 * 前者是笔迹锚定用的，后者是绘制与分页用的；两者一旦按不同方式推算，
 * 笔迹就会与文字错开，而这种错开在小字号下几乎看不见、放大后非常刺眼。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaginationTest {

    private val theme = DocumentTheme.light(textSizePx = 48f, lineSpacingMultiplier = 1.4f)

    private fun metrics(width: Int = 900, height: Int = 1400) = PageLayoutMetrics(
        contentWidthPx = width,
        contentHeightPx = height,
        marginLeftPx = 24f,
        marginRightPx = 24f,
        marginTopPx = 24f,
        marginBottomPx = 24f,
    )

    private fun paragraphs(count: Int): String = (1..count).joinToString("\n") { i ->
        "第 $i 段：这一段刻意写得长一些，用来把行撑满并产生折行，" +
            "这样才能验证折行之后行盒是否仍然落在同一个块里，以及分页是否按真实行高进行。"
    }

    private fun session(text: String = paragraphs(40), kind: DocumentKind = DocumentKind.TXT) =
        DocumentCodec.buildSession("test-doc", kind, text)

    @Test
    fun `有正文时排版产生非空的行盒与页`() {
        val layout = session().layout(metrics(), theme)
        assertTrue("应当至少有一个排版段", layout.sections.isNotEmpty())
        assertTrue("应当有行", layout.lineCount > 0)
        assertTrue("应当有页", layout.pages.isNotEmpty())
        assertTrue("文档总高必须为正", layout.totalHeightPx > 0)
    }

    @Test
    fun `行盒数量与真实排版的行数一致`() {
        val layout = session().layout(metrics(), theme)
        // 一个视觉行丢一个行盒：任何"少算一行"的错误都会让 L2 锚定在某一行上失效。
        assertEquals(layout.lineCount, layout.lineBoxes().size)
    }

    @Test
    fun `每个行盒都有真实矩形与落在行内的基线`() {
        val boxes = session().layout(metrics(), theme).lineBoxes()
        assertTrue(boxes.isNotEmpty())
        for (box in boxes) {
            assertTrue("行盒必须有正高度：$box", box.rectDoc.height > 0f)
            assertTrue("字号必须为正：$box", box.fontSizePx > 0f)
            // 基线可能略微超出墨迹盒（下伸部），但绝不可能整行跑到盒子外面去。
            assertTrue("基线应落在行盒内：$box", box.baselineY >= box.rectDoc.top - box.fontSizePx)
            assertTrue("基线应落在行盒内：$box", box.baselineY <= box.rectDoc.bottom + box.fontSizePx)
        }
    }

    /**
     * 本文件里最重要的一条：**行盒与绘制同源**。
     *
     * 左：`lineBoxes()`（笔迹锚定用）。右：`section.lineTop/lineBaseline`
     * （绘制与分页用）。两者必须逐行逐像素一致。
     */
    @Test
    fun `行盒坐标与绘制坐标系完全一致`() {
        val layout = session().layout(metrics(), theme)
        val boxes = layout.lineBoxes()
        val m = layout.metrics
        var cursor = 0
        for (section in layout.sections) {
            for (line in 0 until section.lineCount) {
                val box = boxes[cursor]
                assertEquals(
                    "第 ${section.index} 段第 $line 行的顶边应与绘制坐标系一致",
                    section.lineTop(line).toFloat(), box.rectDoc.top, 0.01f,
                )
                assertEquals(
                    "第 ${section.index} 段第 $line 行的基线应与绘制坐标系一致",
                    section.lineBaseline(line).toFloat(), box.baselineY, 0.01f,
                )
                assertEquals(
                    "行盒左边 = 页左边距 + 行左边（含缩进）",
                    m.marginLeftPx + section.layout.getLineLeft(line), box.rectDoc.left, 0.01f,
                )
                cursor++
            }
        }
        assertEquals("所有行都要被检查到", boxes.size, cursor)
    }

    @Test
    fun `行盒的块内偏移不越界且能取回文本`() {
        val s = session()
        val layout = s.layout(metrics(), theme)
        val boxes = layout.lineBoxes()
        for (box in boxes) {
            val index = DocumentSession.blockIndexFromPath(box.anchor.blockPath)
            assertTrue("行盒必须指向真实存在的块：${box.anchor.blockPath}", index != null && index >= 0)
            val block = s.blocks[index!!]
            assertTrue("块内起点越界：${box.anchor}", box.anchor.startOffset >= 0)
            assertTrue("块内终点越界：${box.anchor}", box.anchor.endOffset <= block.span.length)
            assertTrue("块内区间反了：${box.anchor}", box.anchor.startOffset <= box.anchor.endOffset)
        }
        // 抽一行确认"从行盒能取回文本"这条路是通的（笔记列表显示引文靠它）。
        val middle = boxes[boxes.size / 2]
        assertTrue("从行盒必须能取回文本", s.textOfLine(middle).isNotBlank())
    }

    @Test
    fun `分页覆盖全部行且不重叠`() {
        val layout = session().layout(metrics(), theme)
        var covered = 0
        // 第 0 页的上边界之前的"已占用底部"取一个比任何真实坐标都小的值：
        // 页面坐标是文档坐标系里的像素整数，-1 保证第 0 页不会被误判为越界。
        var previousBottom = -1
        for (page in layout.pages) {
            assertFalse("页不能为空", page.isEmpty)
            covered += page.lineCount
            // 注意是 >= 而不是 >：相邻两页是**首尾相接**的
            // （下一页的顶边正好等于上一页的底边），这正是"不重叠"的含义。
            // 这里断言的是不重叠 + 不倒序，而不是"页与页之间有空隙"。
            assertTrue(
                "第 ${page.index} 页（top=${page.topPx}）与上一页的底部（$previousBottom）重叠了",
                page.topPx >= previousBottom,
            )
            previousBottom = page.topPx + page.heightPx
        }
        assertEquals("所有行都必须恰好被一页覆盖", layout.lineCount, covered)
    }

    @Test
    fun `每页高度不超过可用内容高度`() {
        val m = metrics(height = 1400)
        val layout = session().layout(m, theme)
        for (page in layout.pages) {
            assertTrue(
                "第 ${page.index} 页高度 ${page.heightPx} 超过了可用高度 ${m.contentHeightPx}",
                page.heightPx <= m.contentHeightPx,
            )
        }
    }

    @Test
    fun `页与行的对应关系可以互相反查`() {
        val layout = session().layout(metrics(), theme)
        for (page in layout.pages) {
            for (span in page.spans) {
                for (line in span.lineStart until span.lineEndExclusive) {
                    assertEquals(
                        "反查必须得到同一页",
                        page.index,
                        layout.pageContainingLine(span.sectionIndex, line),
                    )
                }
            }
        }
    }

    @Test
    fun `字符偏移到页号的映射单调不减`() {
        val s = session()
        val layout = s.layout(metrics(), theme)
        var previous = -1
        var offset = 0
        while (offset <= s.normalized.length) {
            val page = layout.pageIndexOfCharOffset(offset)
            assertTrue("页号映射必须单调：offset=$offset 得到 $page，上一处是 $previous", page >= previous)
            previous = page
            offset += 97
        }
        assertEquals(0, layout.pageIndexOfCharOffset(0))
    }

    @Test
    fun `字号变化会改变分页但行盒始终完整覆盖`() {
        val s = session()
        val small = s.layout(metrics(), DocumentTheme.light(28f, 1.3f))
        val large = s.layout(metrics(), DocumentTheme.light(64f, 1.3f))
        assertEquals("小字号下所有行都必须有行盒", small.lineCount, small.lineBoxes().size)
        assertEquals("大字号下所有行都必须有行盒", large.lineCount, large.lineBoxes().size)
        assertTrue("字号变大应当页数变多", large.pages.size > small.pages.size)
        assertNotEquals(small.lineCount, large.lineCount)
    }

    @Test
    fun `页宽变化会重新折行且行盒仍覆盖全文`() {
        // 这是重排阅读器的核心承诺：换宽度 = 换折行，锚点必须仍然能把每一行找回来。
        val s = session()
        val wide = s.layout(metrics(width = 1400, height = 2000), theme)
        val narrow = s.layout(metrics(width = 420, height = 2000), theme)
        assertTrue("窄页必须折出更多行", narrow.lineCount > wide.lineCount)
        assertEquals(wide.lineCount, wide.lineBoxes().size)
        assertEquals(narrow.lineCount, narrow.lineBoxes().size)
        assertEquals("两种宽度下都要覆盖全部行", wide.lineCount, wide.pages.sumOf { it.lineCount })
        assertEquals(narrow.lineCount, narrow.pages.sumOf { it.lineCount })
    }

    @Test
    fun `视口比一行还矮时仍然产出页而不是死循环`() {
        val layout = session().layout(metrics(height = 4), theme)
        assertTrue("再矮也必须产出页", layout.pages.isNotEmpty())
        for (page in layout.pages) {
            assertTrue("至少要强放一行", page.lineCount > 0)
        }
    }

    // ── 目录跳转的定位（滚动模式）───────────────────────────────────────────

    @Test
    fun `跳到某一块的像素位置就是该块第一行行盒的顶边`() {
        // 目录跳转与笔迹锚定必须落在同一个位置上：跳过去的那一行就是笔记所在的行。
        // 若两者按不同方式推算，用户点目录跳过去之后会发现笔记"不在那一行了"。
        val s = session()
        val layout = s.layout(metrics(), theme)
        val boxes = layout.lineBoxes()
        val samples = listOf(s.blocks.first(), s.blocks[s.blocks.size / 2], s.blocks.last())
        for (block in samples) {
            val target = boxes.first { it.anchor.blockPath == block.blockPath }
            assertEquals(
                "跳到 ${block.blockPath} 的纵坐标必须等于它第一行行盒的顶边",
                target.rectDoc.top,
                layout.scrollYOfCharOffset(block.absoluteStart).toFloat(),
                0f,
            )
        }
    }

    @Test
    fun `滚动纵坐标随偏移单调不减且越界被夹住`() {
        val s = session()
        val layout = s.layout(metrics(), theme)
        var last = -1
        for (block in s.blocks) {
            val y = layout.scrollYOfCharOffset(block.absoluteStart)
            assertTrue("纵坐标必须随偏移单调不减：$y 出现在 $last 之后", y >= last)
            last = y
        }
        // 越界（例如偏移恰好等于全文长度）不许抛异常，也不许给出荒谬的值
        val end = layout.scrollYOfCharOffset(s.normalized.length)
        assertEquals(end, layout.scrollYOfCharOffset(s.normalized.length + 9999))
        assertTrue("末尾的纵坐标必须在文档高度之内", end <= layout.totalHeightPx)
    }

    @Test
    fun `像素位置与字符偏移互为逆运算`() {
        // 这条守的是"换版式不漂"：滚动模式用像素记录位置，恢复时再换回字符偏移。
        // 若两者不是互逆的，反复旋转屏幕、换字号、在分页与滚动之间切换，
        // 阅读位置就会每次漂一点。
        val s = session()
        val layout = s.layout(metrics(), theme)
        for (block in s.blocks) {
            val y = layout.scrollYOfCharOffset(block.absoluteStart)
            assertEquals(
                "偏移 → 像素 → 偏移 必须回到原处（块 ${block.blockPath}）",
                block.absoluteStart,
                layout.charOffsetOfScrollY(y),
            )
        }
    }

    @Test
    fun `每一行的顶边都能换回这一行的首字符`() {
        // 上一条只量了**段首**。段首恰好都在行首上，所以哪怕
        // `charOffsetOfScrollY` 永远返回"这一段的第一个字"，那条断言照样全绿 ——
        // 而滚动模式记录位置时量的是任意一行的顶边，不是段首。
        //
        // 这条逐行量：每一个视觉行的顶边，换回来必须还是这一行的第一个字符。
        // 恢复阅读位置、目录跳转、音量键滚一屏全都建在这上面。
        val s = session()
        val layout = s.layout(metrics(), theme)
        var lines = 0
        for (section in layout.sections) {
            for (line in 0 until section.layout.lineCount) {
                val firstChar = section.charStart + section.layout.getLineStart(line)
                assertEquals(
                    "第 $line 行的顶边换回来必须是这一行的首字符（段 ${section.index}）",
                    firstChar,
                    layout.charOffsetOfScrollY(section.lineTop(line)),
                )
                lines++
            }
        }
        assertTrue("样例文档要有足够多的行，否则这条测试量不到折行", lines > 20)
    }

    @Test
    fun `滚到底那一屏的顶端落在书的末尾而不是开头`() {
        // 恢复位置时若上界算错，症状是"最后几行永远滚不到"——
        // 界面照常能滚，只有真读到全书最后的人才会发现少了一截。
        val s = session()
        val layout = s.layout(metrics(), theme)
        val viewport = metrics().contentHeightPx
        val lastScreenTop = (layout.totalHeightPx - viewport).coerceAtLeast(0)
        val offset = layout.charOffsetOfScrollY(lastScreenTop)
        assertTrue(
            "最后一屏的顶端应当在全书后半段（实得 $offset / ${s.normalized.length}）",
            offset > s.normalized.length / 2,
        )
        assertEquals(
            "再往下也停在同一个字符上（越界被夹住，不许跳回开头）",
            layout.charOffsetOfScrollY(layout.totalHeightPx),
            layout.charOffsetOfScrollY(layout.totalHeightPx + 5_000),
        )
        // 最后一行的首字符当然在最后一行上：它前面必须有字，否则"滚到底"滚到的是开头。
        assertTrue(
            "滚到底映射到的位置必须在正文内部",
            layout.charOffsetOfScrollY(layout.totalHeightPx) > layout.charOffsetOfScrollY(0),
        )
    }

    @Test
    fun `像素位置转回字符偏移随像素单调不减且越界被夹住`() {
        val s = session()
        val layout = s.layout(metrics(), theme)
        var last = -1
        var y = 0
        while (y < layout.totalHeightPx) {
            val offset = layout.charOffsetOfScrollY(y)
            assertTrue("字符偏移必须随像素单调不减：$offset 出现在 $last 之后", offset >= last)
            last = offset
            // 反算回去的像素不得晚于原位置（也就是"回到那一行的顶边"）
            val back = layout.scrollYOfCharOffset(offset)
            assertTrue("反算的像素不得晚于原位置：$back > $y", back <= y)
            y += 7
        }
        assertEquals(
            layout.charOffsetOfScrollY(layout.totalHeightPx),
            layout.charOffsetOfScrollY(layout.totalHeightPx + 9999),
        )
    }

    @Test
    fun `空文档得到空排版而不是崩溃`() {
        val layout = DocumentCodec.buildSession("empty", DocumentKind.TXT, "").layout(metrics(), theme)
        assertTrue(layout.sections.isEmpty())
        assertTrue(layout.lineBoxes().isEmpty())
        assertTrue(layout.pages.isEmpty())
        assertEquals(0, layout.totalHeightPx)
    }

    @Test
    fun `Markdown 的标题行按更大的字号排版`() {
        val s = DocumentCodec.buildSession("md", DocumentKind.MARKDOWN, "# 一级标题\n正文一行")
        val boxes = s.layout(metrics(), theme).lineBoxes()
        val heading = boxes.first { it.anchor.blockPath == "blk[0]" }
        val body = boxes.first { it.anchor.blockPath == "blk[1]" }
        // em → px 的换算必须用"这一行自己的字号"，否则标题行的笔迹会明显偏小。
        assertTrue("标题行字号应大于正文", heading.fontSizePx > body.fontSizePx)
        assertTrue("标题行的行盒也应更高", heading.rectDoc.height > body.rectDoc.height)
    }

    @Test
    fun `同一份排版重复取用得到同一组行盒`() {
        // 行盒是锚定的坐标事实。若每次调用都重新推算（而不是来自同一个布局对象），
        // 就会出现"同一次阅读里同一行有两个位置"的诡异现象。
        val s = session()
        val layout = s.layout(metrics(), theme)
        val a = layout.lineBoxes()
        val b = layout.lineBoxes()
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].rectDoc, b[i].rectDoc)
            assertEquals(a[i].baselineY, b[i].baselineY, 0f)
        }
    }

    // ── 搜索命中的高亮几何 ──────────────────────────────────────────────────

    /** 某一块的全部行盒（按排版顺序）。 */
    private fun linesOf(layout: ReflowableTextLayout, blockPath: String) =
        layout.lineBoxes().filter { it.anchor.blockPath == blockPath }

    @Test
    fun `命中高亮的矩形贴合所在行且落在行内`() {
        // 高亮是给用户看"找到的是哪几个字"的：它必须落在那一行里，
        // 既不能跑到别的行上，也不能超出行的左右边界。
        val s = session()
        val layout = s.layout(metrics(), theme)
        val block = s.blocks[0]
        val line = linesOf(layout, block.blockPath).first()
        val start = block.absoluteStart + line.anchor.startOffset
        val end = block.absoluteStart + line.anchor.endOffset

        val rects = layout.rectsOfCharRange(start, end)

        assertEquals("一个视觉行只该有一个矩形", 1, rects.size)
        val rect = rects.single()
        assertEquals("高亮的上下界必须与所在行盒一致", line.rectDoc.top, rect.top, 0.01f)
        assertEquals("高亮的上下界必须与所在行盒一致", line.rectDoc.bottom, rect.bottom, 0.01f)
        assertTrue("不得超出行的左边：$rect vs ${line.rectDoc}", rect.left >= line.rectDoc.left - 0.01f)
        assertTrue("不得超出行的右边：$rect vs ${line.rectDoc}", rect.right <= line.rectDoc.right + 0.01f)
        assertTrue("必须有正宽度", rect.width > 0f)
    }

    @Test
    fun `只涂命中的那几个字而不是整行`() {
        // "命中一个字却涂了一整行"会让用户以为整行都命中了。
        val s = session()
        val layout = s.layout(metrics(), theme)
        val block = s.blocks[0]
        val line = linesOf(layout, block.blockPath).first()
        val lineStart = block.absoluteStart + line.anchor.startOffset
        val lineEnd = block.absoluteStart + line.anchor.endOffset

        val oneChar = layout.rectsOfCharRange(lineStart, lineStart + 1).single()
        val wholeLine = layout.rectsOfCharRange(lineStart, lineEnd).single()

        assertTrue(
            "一个字的高亮必须比整行窄：${oneChar.width} vs ${wholeLine.width}",
            oneChar.width < wholeLine.width,
        )
    }

    @Test
    fun `跨行的命中按行给出多个矩形且每个都在各自行内`() {
        // 若只给一个包围盒，两行之间那一整片本来不该高亮的区域也会被涂上。
        val s = session()
        val layout = s.layout(metrics(), theme)
        val block = s.blocks.first { linesOf(layout, it.blockPath).size >= 3 }
        val lines = linesOf(layout, block.blockPath)

        val rects = layout.rectsOfCharRange(block.absoluteStart, block.absoluteStart + block.span.length)

        assertEquals("跨了 ${lines.size} 行就该有 ${lines.size} 个矩形", lines.size, rects.size)
        for (i in rects.indices) {
            val rect = rects[i]
            val line = lines[i]
            assertEquals("第 $i 个矩形必须落在第 $i 行上", line.rectDoc.top, rect.top, 0.01f)
            assertTrue("第 $i 个矩形越过了行边界", rect.left >= line.rectDoc.left - 0.01f)
            assertTrue("第 $i 个矩形越过了行边界", rect.right <= line.rectDoc.right + 0.01f)
        }
    }

    @Test
    fun `空区间与越界区间不会崩也不会给出荒唐矩形`() {
        val s = session()
        val layout = s.layout(metrics(), theme)

        assertTrue("空区间没有高亮", layout.rectsOfCharRange(100, 100).isEmpty())
        assertTrue("反向区间没有高亮", layout.rectsOfCharRange(100, 50).isEmpty())

        // 越界（例如一次误算多给出几百个字符）必须被夹在文档之内，而不是抛异常。
        val tail = layout.rectsOfCharRange(s.normalized.length - 2, s.normalized.length + 5000)
        assertTrue("越界区间仍应给出高亮", tail.isNotEmpty())
        for (rect in tail) {
            assertTrue("高亮不得超出文档高度：$rect", rect.bottom <= layout.totalHeightPx + 0.01f)
        }
    }

    // ── 分页绘制窗口（M32）────────────────────────────────────────────────────

    private fun boxIndexOf(layout: ReflowableTextLayout, sectionIndex: Int, line: Int): Int =
        layout.sections.take(sectionIndex).sumOf { it.lineCount } + line

    /**
     * 绘制窗口 = **本页首行的顶边 → 本页最后一行的底边**，且不超出正文框。
     *
     * 这是 M32 那个"换页时字被切一半"的根因所在：视图比页高（底部还要留页边距
     * 那段纸），而绘制只是把文档平移到视图里。窗口若跟着视图高度走，
     * 上一页最后一行就会画进上页边距、下一页第一行就会画进下页边距。
     *
     * 注意窗口下界取的是**这一页的实际高度**而不是正文框高度：一页很少刚好填满，
     * 差出来的那点高度正好能塞下下一页第一行的上半截 —— 用正文框高度剪等于没剪。
     */
    @Test
    fun `绘制窗口就是本页首行到末行，且不超出正文框`() {
        val m = metrics()
        val layout = session().layout(m, theme)
        for (page in layout.pages) {
            val win = layout.pageWindowAt(page.topPx)
            assertTrue("第 ${page.index} 页必须查得到窗口", win != null)
            assertEquals("窗口上界就是这一页的首行顶边", page.topPx, win!!.first)
            assertEquals("窗口下界就是这一页的末行底边", page.topPx + page.heightPx, win.last + 1)
            assertTrue(
                "窗口高度不得超过正文框（${m.contentHeightPx}）：第 ${page.index} 页为 ${page.heightPx}",
                page.heightPx <= m.contentHeightPx,
            )
        }
    }

    /**
     * 窗口里只能有**本页**的行。
     *
     * 与 `分页覆盖全部行且不重叠` 互补：那一条保证"页与行对得上"，
     * 这一条保证"照着窗口剪出来的画面里不会混进邻居的行"，也就是用户在
     * 换页时看不见半截字。用正文框高度当窗口时这条会红 —— 差值里塞得下半行。
     */
    @Test
    fun `相邻页的行都落在本页绘制窗口之外`() {
        val m = metrics()
        val layout = session().layout(m, theme)
        val boxes = layout.lineBoxes()
        assertTrue("样例正文应当不止一页", layout.pages.size > 1)

        for (page in layout.pages) {
            val win = layout.pageWindowAt(page.topPx)!!
            // 与窗口相交 = 顶边不高于窗口下界，且底边低于窗口上界（不是）。
            val touched = boxes.filter { it.rectDoc.bottom > win.first && it.rectDoc.top <= win.last }
            val own = page.lineCount
            assertEquals(
                "第 ${page.index} 页（top=${page.topPx}, 高=${page.heightPx}）的窗口里" +
                    "混进了别的页的行",
                own,
                touched.size,
            )
        }
    }

    /**
     * 为什么窗口非剪不可：默认版式里页边距**装不下一整行**。
     *
     * 行高约 1.4 倍字号、上下边距只有 24 px，所以一张"整屏高"的视图里，
     * 页边距那一条永远露着相邻页的半行字。这条断言把这件事钉成事实 ——
     * 以后谁把页边距调大（真能装下一整行），这里会先红，提醒他重新决定窗口怎么剪。
     */
    @Test
    fun `页边距装不下一整行，所以绘制必须剪窗口`() {
        val m = metrics()
        val layout = session().layout(m, theme)
        val boxes = layout.lineBoxes()
        val second = layout.pages[1]
        val firstSpan = second.spans.first()
        val firstBox = boxIndexOf(layout, firstSpan.sectionIndex, firstSpan.lineStart)
        val prevLast = boxes[firstBox - 1]
        assertTrue("上一页最后一行应当比页边距高：行高=${prevLast.rectDoc.height}", prevLast.rectDoc.height > m.marginTopPx)

        // 把页面映射到视图坐标：页首画在 marginTop 处。
        val viewTop = prevLast.rectDoc.top - second.topPx + m.marginTopPx
        assertTrue("上一页最后一行确实会落进上页边距（视图 y=$viewTop）", viewTop < m.marginTopPx)
    }
}
