package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制器的测试：**落笔 → 锚定 → 落盘**这条链路的每一段，以及失败时的表现。
 *
 * 全部用 `Dispatchers.Unconfined`：控制器的 `launch` 会同步跑完，
 * 于是断言可以直接写在 `endStroke()` 之后，不需要 `runTest` 或轮询等待。
 * 这不是"测试技巧"，而是控制器把调度器做成参数换来的 —— 它也应该这样被测。
 */
class InkLayerControllerTest {

    private class FakeSink : InkSink {
        val committedSegments = ArrayList<InkAnchorFactory.Segment>()
        val committedIds = ArrayList<String>()
        val revoked = ArrayList<List<String>>()
        val brushes = ArrayList<BrushSpec>()

        override suspend fun commit(
            annotationId: String,
            segment: InkAnchorFactory.Segment,
            brush: BrushSpec,
        ): Boolean {
            committedIds += annotationId
            committedSegments += segment
            brushes += brush
            return true
        }

        override suspend fun revoke(annotationIds: List<String>): Int {
            revoked += annotationIds
            return annotationIds.size
        }
    }

    private val doc = TestDoc(2)
    private val sink = FakeSink()
    private var counter = 0

    private fun controller(facts: (() -> LayoutFacts?)? = null): InkLayerController =
        InkLayerController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            sink = sink,
            facts = facts ?: { doc.facts() },
            onChanged = {},
            worker = Dispatchers.Unconfined,
            idFactory = { "id-${counter++}" },
        )

    /** 在 [doc] 的第 [lineIndex] 行上画一条横线（两个点，落在行盒内）。 */
    private fun InkLayerController.strokeOn(lineIndex: Int) {
        val line = doc.lines()[lineIndex]
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        beginStroke(100f, y, 1f)
        extendStroke(200f, y, 1f)
    }

    @Test
    fun `落笔抬笔会锚定并落盘成一条记录`() {
        val controller = controller()

        controller.strokeOn(0)
        controller.endStroke()

        assertEquals(1, sink.committedSegments.size)
        assertEquals("blk[0]", sink.committedSegments.single().stroke.anchor.blockPath)
        assertTrue("落笔之后应当可以撤销", controller.canUndo)
        assertFalse("刚落笔时没有可重做的", controller.canRedo)
        assertNull("正常落笔不该留下提示", controller.lastNotice)
    }

    @Test
    fun `跨两行的一笔会落盘成两条各自独立的记录`() {
        val controller = controller()
        val lines = doc.lines()

        // 第 0 行两个点、第 1 行两个点：一笔跨两行。
        controller.beginStroke(100f, 10f, 1f)
        controller.extendStroke(200f, 20f, 1f)
        controller.extendStroke(100f, lines[1].rectDoc.top + 10f, 1f)
        controller.extendStroke(200f, lines[1].rectDoc.top + 20f, 1f)
        controller.endStroke()

        assertEquals(
            "跨行必须切成两段，各锚到自己的行",
            listOf("blk[0]", "blk[1]"),
            sink.committedSegments.map { it.stroke.anchor.blockPath },
        )
        assertEquals(
            "每条记录必须有自己的 id（它是主键）",
            sink.committedIds.size,
            sink.committedIds.toSet().size,
        )
    }

    @Test
    fun `排版没就绪时明确丢弃这一笔并给出提示`() {
        val controller = controller(facts = { null })

        controller.strokeOn(0)
        controller.endStroke()

        assertTrue("没有排版就没有坐标系，不该硬存", sink.committedSegments.isEmpty())
        assertFalse(controller.canUndo)
        assertNotNull("必须告诉用户，而不是静默丢弃", controller.lastNotice)
    }

    @Test
    fun `点数不足两点时什么也不做也不报警`() {
        val controller = controller()

        // 笔尖点一下：真实设备上一定会出现 DOWN/UP，它不是"写失败"。
        controller.beginStroke(100f, 100f, 1f)
        controller.endStroke()

        assertTrue(sink.committedSegments.isEmpty())
        assertNull("点一下不算失败，不该弹提示", controller.lastNotice)
        assertFalse(controller.canUndo)
    }

    @Test
    fun `撤销会把一次落笔产生的全部记录一起删掉`() {
        val controller = controller()
        val lines = doc.lines()
        controller.beginStroke(100f, 10f, 1f)
        controller.extendStroke(200f, 20f, 1f)
        controller.extendStroke(100f, lines[1].rectDoc.top + 10f, 1f)
        controller.extendStroke(200f, lines[1].rectDoc.top + 20f, 1f)
        controller.endStroke()
        val written = sink.committedIds.toList()

        controller.undo()

        assertEquals(
            "撤销的单位是**一次落笔**，不是一段",
            listOf(written),
            sink.revoked,
        )
        assertFalse(controller.canUndo)
        assertTrue(controller.canRedo)
    }

    @Test
    fun `重做会把同一笔重新落盘并重新进入撤销栈`() {
        val controller = controller()
        controller.strokeOn(0)
        controller.endStroke()
        controller.undo()

        controller.redo()

        assertEquals("重做 = 再写一遍", 2, sink.committedSegments.size)
        assertTrue(controller.canUndo)
        assertFalse(controller.canRedo)
        assertTrue(controller.lastNotice == null)
    }

    @Test
    fun `取消书写不留任何痕迹`() {
        val controller = controller()
        controller.strokeOn(0)
        assertNotNull("书写过程中渲染层应当拿得到跟手的点", controller.inProgressPoints)

        controller.cancelStroke()

        assertNull(controller.inProgressPoints)
        assertTrue(sink.committedSegments.isEmpty())
        assertFalse(controller.canUndo)
    }

    @Test
    fun `抬笔之后跟手的点立刻清空`() {
        val controller = controller()
        controller.strokeOn(0)
        assertNotNull(controller.inProgressPoints)

        controller.endStroke()

        assertNull("抬笔之后这些点已经变成库里的记录，不该再留在跟手缓冲里", controller.inProgressPoints)
        assertNull(controller.inProgressBrush)
        assertFalse(controller.isWriting)
    }

    // ── 荧光笔（叠印笔）──────────────────────────────────────────────────────
    //
    // M25 §3 S2 的第三条口径："荧光笔笔迹**不参与覆盖文字**（它是覆盖在字上的）"。
    // 落到代码上就是它的重排语义被**钉死**为 FOLLOWER —— 不让锚定工厂按笔画形状
    // 判成 PINNED。判成 PINNED 的后果是 `PinnedPlacement` 会把它挪到行旁边的
    // 空白处避让文字，于是"标亮这一段"变成"旁边多了一条黄杠"。

    @Test
    fun `荧光笔永远锚成跟随文字，不会因为画得长就被判成钉住`() {
        val controller = controller()
        val lines = doc.lines()

        // 一条横穿整行、压在两行上的长笔画：按普通笔的形状判定，
        // 这种"与某一行的重合度不够、又明显不是行内小标记"的笔画最容易被判成 PINNED。
        controller.brush = BrushSpec.DEFAULT_HIGHLIGHTER
        val y0 = (lines[0].rectDoc.top + lines[0].rectDoc.bottom) / 2f
        val y1 = (lines[1].rectDoc.top + lines[1].rectDoc.bottom) / 2f
        controller.beginStroke(40f, y0, 1f)
        controller.extendStroke(120f, y0, 1f)
        controller.extendStroke(200f, y0, 1f)
        controller.extendStroke(200f, y1, 1f)
        controller.extendStroke(120f, y1, 1f)
        controller.endStroke()

        assertTrue("前提：这一笔确实落盘了", sink.committedSegments.isNotEmpty())
        for (segment in sink.committedSegments) {
            assertEquals(
                "荧光笔必须锚成跟随文字 —— 钉住的会被挪到行外，就不再标在这一段上了",
                InkReflowSemantics.FOLLOWER,
                segment.stroke.semantics,
            )
            assertNull(
                "FOLLOWER 不允许有冻结几何（它的位置由**当前**行盒决定）",
                segment.stroke.frozenMatrix,
            )
        }
    }

    /**
     * 写一条**贴在左页边空白里**的竖线（x=5，正文栏从 x=24 起）。
     *
     * 按形状判定它必然是 PINNED（[InkSemanticsClassifier.PINNED_MIN_MARGIN_RATIO]
     * = 70%，而它 100% 在页边里）。这正是对照组要的那一条：同一条笔画，
     * 换一支笔，落盘的语义应当**不同**。
     */
    private fun InkLayerController.strokeInMargin() {
        beginStroke(5f, 10f, 1f)
        extendStroke(5f, 40f, 1f)
        extendStroke(5f, 60f, 1f)
        extendStroke(5f, 90f, 1f)
    }

    @Test
    fun `同一条页边笔画：普通笔判成钉住，荧光笔仍然跟随`() {
        // 反向确认（不是可有可无的）：override **只在叠印笔上**生效。
        // 写成"所有笔都固定 FOLLOWER"也能让上面那条测试通过，
        // 但那样整页手写的"钉在页面上"语义就被悄悄改掉了 —— 这是本条要挡的。
        val pen = controller()
        pen.strokeInMargin()
        pen.endStroke()
        val asPen = sink.committedSegments.map { it.stroke.semantics }.toSet()
        sink.committedSegments.clear()

        val marker = controller()
        marker.brush = BrushSpec.DEFAULT_HIGHLIGHTER
        marker.strokeInMargin()
        marker.endStroke()
        val asHighlighter = sink.committedSegments.map { it.stroke.semantics }.toSet()

        assertEquals(
            "前提：这条笔画按形状确实该判成 PINNED（否则本条测不到东西）",
            setOf(InkReflowSemantics.PINNED),
            asPen,
        )
        assertEquals(
            "同一条笔画换成荧光笔必须改成跟随文字",
            setOf(InkReflowSemantics.FOLLOWER),
            asHighlighter,
        )
    }

    @Test
    fun `切换笔刷只影响之后写的笔迹`() {
        val controller = controller()
        controller.strokeOn(0)
        controller.endStroke()

        controller.brush = BrushSpec.DEFAULT_HIGHLIGHTER
        controller.strokeOn(0)
        controller.endStroke()

        assertEquals(
            listOf(BrushSpec.DEFAULT_PEN.name, BrushSpec.DEFAULT_HIGHLIGHTER.name),
            sink.brushes.map { it.name },
        )
    }

    // ── 暂存层：抬笔到干墨到场之间的那一段必须有墨可画 ─────────────────────────
    //
    // 这一组守的是用户报的"每次写完才显示笔迹"。修法不是"画得更快"，而是
    // "抬笔时别把点丢掉"：点移交到 pending，屏幕上那条线一帧都不空。

    @Test
    fun `抬笔之后这一笔仍然留在暂存里可以画`() {
        val controller = controller()

        controller.strokeOn(0)
        controller.endStroke()

        val pending = controller.pendingStrokes.single()
        assertEquals("暂存里应当是刚写的那一笔的两个点", 2, pending.pointCount)
        assertEquals(BrushSpec.DEFAULT_PEN.name, pending.brush.name)
        assertEquals("跟手缓冲该清空（它要让给下一笔）", 0, controller.inProgressPointCount)
    }

    @Test
    fun `干墨提交一版之后暂存才撤`() {
        val controller = controller()
        controller.strokeOn(0)
        controller.endStroke()
        val id = controller.pendingStrokes.single().strokeId

        controller.acknowledgeCommitted(InkCoverage(drawnRootIds = setOf(id)))

        assertTrue("干墨已经到场，暂存该让位", controller.pendingStrokes.isEmpty())
    }

    @Test
    fun `与它无关的一版提交不会撤掉刚抬笔的那一笔`() {
        // 真机时序（M21 用户报的"圈有时候消失、有时候又出现"）：
        //   抬笔 → 落盘完成 → 刷新还没跑完 → 用户点一下书写开关
        // → Compose 的 update 块拿**手上那一版**（还不含这一笔）又提交了一次。
        //
        // 旧规则是"结论之后再提交过一版就撤"，它默认了"提交的那一版一定包含这一笔"。
        // 这个假设在真机上不成立：于是湿墨被这次陈旧提交撤掉、干墨那一版也没有它，
        // 屏幕上就空一段，等下一次解析回来才重新出现。笔画越长、切出的段越多
        // （圈是最典型的一类），落盘窗口越宽，越容易被撞上。
        val controller = controller()
        controller.strokeOn(0)
        controller.endStroke()
        val id = controller.pendingStrokes.single().strokeId
        assertTrue("前提：这一笔已经落盘成功", controller.canUndo)

        controller.acknowledgeCommitted(
            InkCoverage(drawnRootIds = setOf("ink-别人"), storedRootIds = setOf("ink-别人")),
        )

        assertEquals("这一版里没有它，就不许撤", 1, controller.pendingStrokes.size)
        assertEquals(id, controller.pendingStrokes.single().strokeId)

        // 真到干墨那一版把它带回来，才撤。
        controller.acknowledgeCommitted(InkCoverage(drawnRootIds = setOf(id)))
        assertTrue(controller.pendingStrokes.isEmpty())
    }

    @Test
    fun `库里有它但这一版画不出来时也撤掉湿墨`() {
        // 否则界面自相矛盾：状态行写着"有 1 条笔迹暂时画不出来"，
        // 屏幕上却明摆着一份湿墨。撤掉它，让状态行的话成立。
        val controller = controller()
        controller.strokeOn(0)
        controller.endStroke()
        val id = controller.pendingStrokes.single().strokeId

        controller.acknowledgeCommitted(InkCoverage(storedRootIds = setOf(id)))

        assertTrue("解析已经说过它画不出来，湿墨不该继续冒充干墨", controller.pendingStrokes.isEmpty())
    }

    @Test
    fun `落盘失败时暂存立刻撤掉并给出提示`() {
        val failing = object : InkSink {
            override suspend fun commit(
                annotationId: String,
                segment: InkAnchorFactory.Segment,
                brush: BrushSpec,
            ): Boolean = false

            override suspend fun revoke(annotationIds: List<String>): Int = 0
        }
        val controller = InkLayerController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            sink = failing,
            facts = { doc.facts() },
            onChanged = {},
            worker = Dispatchers.Unconfined,
            idFactory = { "id-${counter++}" },
        )

        controller.strokeOn(0)
        controller.endStroke()
        controller.maintainPending()

        assertTrue("写成失败还留着墨，就是一条永远不会消失的幽灵", controller.pendingStrokes.isEmpty())
        assertNotNull("失败必须说出来", controller.lastNotice)
    }

    @Test
    fun `撤销会顺手清掉暂存`() {
        val controller = controller()
        controller.strokeOn(0)
        controller.endStroke()

        controller.undo()

        assertTrue("撤销之后屏幕上不该还有那一笔", controller.pendingStrokes.isEmpty())
    }

    @Test
    fun `丢弃暂存不影响已经落盘的数据`() {
        // PDF 翻页时用：暂存里是上一页的坐标，画在新页上就是错位的墨。
        val controller = controller()
        controller.strokeOn(0)
        controller.endStroke()

        controller.discardPending()

        assertTrue(controller.pendingStrokes.isEmpty())
        assertTrue("库里那一条仍然在，撤销栈也不该被清", controller.canUndo)
    }
}
