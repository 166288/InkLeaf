package dev.inkleaf.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 本地图标（[InkIcons]）的**像素级**体检。
 *
 * ## 为什么要量像素
 *
 * 这 12 个图标的路径是从官方 SVG 抄过来的字符串（见 [InkIcons] 的注释）。
 * 手抄 `d` 属性最典型的两种错法，编译器一个都抓不到：
 *
 * 1. **少抄一段** —— 图标还画得出来，只是缺个笔画（比如撤销少了箭头尾巴）。
 * 2. **坐标抄错一位** —— 图形跑到 24 em 外面被裁掉，或者糊成一整块。
 *
 * 只断言"路径非空"对这两种都无效。这里把每个图标真的画进位图，量**落墨的
 * 像素占比**：空白（≈0%）和实心块（≈100%）都会红。
 *
 * 第三种错法（面积正常但形状不对）量不出来 —— 那要靠人看拼图，所以顺带把
 * 12 个图标画成一张 `build/reports/ink-icons.png`。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class InkIconsTest {

    private val icons: List<Pair<String, ImageVector>> = listOf(
        "Highlight" to InkIcons.Highlight,
        "Tune" to InkIcons.Tune,
        "Brush" to InkIcons.Brush,
        "Undo" to InkIcons.Undo,
        "Redo" to InkIcons.Redo,
        "SelectAll" to InkIcons.SelectAll,
        "Deselect" to InkIcons.Deselect,
        "GridView" to InkIcons.GridView,
        "BarChart" to InkIcons.BarChart,
        "ViewList" to InkIcons.ViewList,
        "OpenInNew" to InkIcons.OpenInNew,
        "NoteAdd" to InkIcons.NoteAdd,
    )

    @Test
    fun `每个图标都画得出东西，而且不会填成一整块`() {
        val report = StringBuilder()
        icons.forEach { (name, image) ->
            val ratio = inkRatio(render(image, 96))
            report.append(name).append(' ').append("%.1f%%".format(ratio * 100)).append('\n')
            assertTrue(
                "「$name」一个像素都没画出来 —— 路径抄空了？\n$report",
                ratio > 0.02f,
            )
            assertTrue(
                "「$name」落墨面积 %.1f%% 太大，画面糊成一整块（多半是坐标抄错、子路径少了 z）\n%s"
                    .format(ratio * 100, report),
                ratio < 0.70f,
            )
        }
        println("INKLEAF_ICONS\n$report")
    }

    @Test
    fun `路径坐标都在 24 em 的视口里`() {
        icons.forEach { (name, image) ->
            val nodes = image.root.flatMap { node ->
                if (node is VectorPath) node.pathData else emptyList()
            }
            assertTrue("「$name」没有路径数据", nodes.isNotEmpty())
            nodes.forEach { node ->
                // 绝对坐标必须落在 24 em 视口里；相对指令给的是**偏移量**，
                // 可以是负数（`v-5` 就是往上走 5），判据是"别比整条路径还长"。
                coordinates(node).forEach { (value, isOffset) ->
                    val (low, high) = if (isOffset) -25f to 25f else -1f to 25f
                    assertTrue(
                        "「$name」的坐标 $value 超出范围（节点：$node）",
                        value >= low && value <= high,
                    )
                }
            }
        }
    }

    /** 画一张拼图，供人眼核对形状。路径：`build/reports/ink-icons.png`。 */
    @Test
    fun `生成拼图`() {
        val cell = 96
        val gap = 12
        val cols = 4
        val rows = (icons.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(
            cols * cell + (cols + 1) * gap,
            rows * cell + (rows + 1) * gap,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(sheet).apply { drawColor(Color.WHITE) }
        icons.forEachIndexed { i, (_, image) ->
            canvas.drawBitmap(
                render(image, cell),
                (gap + (i % cols) * (cell + gap)).toFloat(),
                (gap + (i / cols) * (cell + gap)).toFloat(),
                null,
            )
        }
        val out = File("build/reports/ink-icons.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("INKLEAF_ICONS_SHEET=${out.absolutePath}")
    }

    // ── 画图 ────────────────────────────────────────────────────────────────

    private fun render(image: ImageVector, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.scale(size / 24f, size / 24f)
        val pen = Pen()
        image.root.forEach { node ->
            if (node is VectorPath) {
                pen.path.fillType = node.pathFillType.toAndroid()
                node.pathData.forEach { pen.apply(it) }
            }
        }
        canvas.drawPath(pen.path, paint)
        return bitmap
    }

    /** 落了墨的像素占比（背景透明）。 */
    private fun inkRatio(bitmap: Bitmap): Float {
        var ink = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) ushr 24 > 8) ink++
            }
        }
        return ink.toFloat() / (bitmap.width * bitmap.height)
    }

    private fun PathFillType.toAndroid(): Path.FillType =
        if (this == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING

    /**
     * 把 Compose 的路径节点喂给原生 `Path`，同时**记住当前点**。
     *
     * `h` / `v`（[PathNode.HorizontalTo] / [PathNode.VerticalTo]）只带一个坐标，
     * 另一个必须取当前点 —— 不维护它就只能猜。`z` 之后当前点回到子路径起点，
     * 这也是 SVG 的规定。
     */
    private class Pen {
        val path = Path()
        private var cx = 0f
        private var cy = 0f
        private var startX = 0f
        private var startY = 0f

        fun apply(node: PathNode) {
            when (node) {
                is PathNode.MoveTo -> move(node.x, node.y)
                is PathNode.RelativeMoveTo -> move(cx + node.dx, cy + node.dy)
                is PathNode.LineTo -> line(node.x, node.y)
                is PathNode.RelativeLineTo -> line(cx + node.dx, cy + node.dy)
                is PathNode.HorizontalTo -> line(node.x, cy)
                is PathNode.RelativeHorizontalTo -> line(cx + node.dx, cy)
                is PathNode.VerticalTo -> line(cx, node.y)
                is PathNode.RelativeVerticalTo -> line(cx, cy + node.dy)
                is PathNode.CurveTo -> curve(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                is PathNode.RelativeCurveTo -> curve(
                    cx + node.dx1, cy + node.dy1,
                    cx + node.dx2, cy + node.dy2,
                    cx + node.dx3, cy + node.dy3,
                )
                is PathNode.QuadTo -> quad(node.x1, node.y1, node.x2, node.y2)
                is PathNode.RelativeQuadTo -> quad(
                    cx + node.dx1, cy + node.dy1, cx + node.dx2, cy + node.dy2,
                )
                is PathNode.Close -> {
                    path.close(); cx = startX; cy = startY
                }
                // 本项目的 12 个图标里没有圆弧 `A` 与反射曲线 `S`/`T`。真出现了**宁可红**，
                // 也不静默少画一条 —— 那时补映射，而不是让它画不出来还"通过"。
                else -> throw AssertionError("图标里出现了测试还没支持的路径指令：$node")
            }
        }

        private fun move(x: Float, y: Float) {
            path.moveTo(x, y); cx = x; cy = y; startX = x; startY = y
        }

        private fun line(x: Float, y: Float) {
            path.lineTo(x, y); cx = x; cy = y
        }

        private fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            path.cubicTo(x1, y1, x2, y2, x3, y3); cx = x3; cy = y3
        }

        private fun quad(x1: Float, y1: Float, x2: Float, y2: Float) {
            path.quadTo(x1, y1, x2, y2); cx = x2; cy = y2
        }
    }

    /**
     * 一个节点带的所有坐标，第二项说明它是**偏移量**还是绝对坐标。
     *
     * 相对指令（`m` / `l` / `c` …的小写形式）给的是相对当前点的偏移，可以是负数，
     * 判据与绝对坐标不同 —— 混在一起判会把 `v-5`（往上 5）当成"坐标 -5"。
     */
    private fun coordinates(node: PathNode): List<Pair<Float, Boolean>> = when (node) {
        is PathNode.MoveTo -> listOf(node.x.at(), node.y.at())
        is PathNode.RelativeMoveTo -> listOf(node.dx.offset(), node.dy.offset())
        is PathNode.LineTo -> listOf(node.x.at(), node.y.at())
        is PathNode.RelativeLineTo -> listOf(node.dx.offset(), node.dy.offset())
        is PathNode.HorizontalTo -> listOf(node.x.at())
        is PathNode.RelativeHorizontalTo -> listOf(node.dx.offset())
        is PathNode.VerticalTo -> listOf(node.y.at())
        is PathNode.RelativeVerticalTo -> listOf(node.dy.offset())
        is PathNode.CurveTo -> listOf(
            node.x1.at(), node.y1.at(), node.x2.at(), node.y2.at(), node.x3.at(), node.y3.at(),
        )
        is PathNode.RelativeCurveTo -> listOf(
            node.dx1.offset(), node.dy1.offset(), node.dx2.offset(),
            node.dy2.offset(), node.dx3.offset(), node.dy3.offset(),
        )
        is PathNode.QuadTo -> listOf(node.x1.at(), node.y1.at(), node.x2.at(), node.y2.at())
        is PathNode.RelativeQuadTo ->
            listOf(node.dx1.offset(), node.dy1.offset(), node.dx2.offset(), node.dy2.offset())
        else -> emptyList()
    }

    private fun Float.at() = this to false

    private fun Float.offset() = this to true
}
