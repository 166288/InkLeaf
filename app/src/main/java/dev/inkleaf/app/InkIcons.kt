package dev.inkleaf.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 本项目额外用到的 Material 图标。
 *
 * ## 为什么要自己写这 12 个
 *
 * 它们原本来自 `androidx.compose.material:material-icons-extended` —— 那个包把
 * Material 图标库**全部 5000 多个**图标都编进 APK，而本项目只用其中 12 个。
 * 实测（M37）：release 包 45.2 MB 里有 44.5 MB 是 dex，其中 **35.6% 的类型引用
 * （11428 条）来自 `androidx.compose.material.icons`** —— 一份安装包里三分之一
 * 是从不显示的图标。换掉这个依赖后 dex 少约 15 MB。
 *
 * 常规的那批（`Add` / `Search` / `Settings` / `ArrowBack` …）在
 * `material-icons-core` 里本来就有，直接换 import 即可；只有下面这 12 个是
 * extended 独有，所以把**官方 SVG 的路径数据**抄进来，用 [ImageVector] 现场构造。
 *
 * ## 为什么用 ImageVector 而不是 vector drawable 资源
 *
 * 调用点是 `Icon(imageVector = …)`，而 `vectorResource()` 要在 Composable 里读，
 * 有几处（如 `Icon` 的默认参数、非 Composable 的分支）拿不到。抄成 ImageVector
 * 之后调用点只改一个名字，`tint`、尺寸、无障碍全部照旧。
 *
 * ## 路径从哪来
 *
 * `https://fonts.gstatic.com/s/i/materialicons/<name>/v1/24px.svg` 里的 `d`
 * 属性，原样照抄（含逗号与相对坐标），由 [PathParser] 解析。`fill="none"` 的
 * 那条 `M0 0h24v24H0z` 是 24×24 的裁切蒙版，不产生像素，已略去。
 *
 * 改这里的唯一正确方式是**重新抄一遍官方路径**，不要手改坐标 —— 这些图标
 * 是 24 em 网格上的曲线，肉眼调不出来。
 */
internal object InkIcons {

    /** 荧光笔（高亮）：马克笔头 + 三道笔触。 */
    val Highlight: ImageVector by lazy {
        icon(
            "Highlight",
            d = "M6 14l3 3v5h6v-5l3-3V9H6zm5-12h2v3h-2z" +
                "M3.5 5.875L4.914 4.46l2.12 2.122L5.62 7.997z" +
                "m13.46.71l2.123-2.12 1.414 1.414L18.375 8z",
        )
    }

    /** 笔画设置：三根带滑块的横线。 */
    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            d = "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2z" +
                "M7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
        )
    }

    /** 书写：毛笔 + 一滴墨。 */
    val Brush: ImageVector by lazy {
        icon(
            "Brush",
            d = "M7 14c-1.66 0-3 1.34-3 3 0 1.31-1.16 2-2 2 .92 1.22 2.49 2 4 2 2.21 0 " +
                "4-1.79 4-4 0-1.66-1.34-3-3-3zm13.71-9.37l-1.34-1.34c-.39-.39-1.02-.39-1.41 " +
                "0L9 12.25 11.75 15l8.96-8.96c.39-.39.39-1.02 0-1.41z",
        )
    }

    /** 撤销。方向随书写方向翻转，与原来的 `Icons.AutoMirrored.Filled.Undo` 一致。 */
    val Undo: ImageVector by lazy {
        icon(
            "Undo",
            autoMirror = true,
            d = "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 " +
                "5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
        )
    }

    /** 重做。 */
    val Redo: ImageVector by lazy {
        icon(
            "Redo",
            autoMirror = true,
            d = "M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c" +
                "1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z",
        )
    }

    /** 全选：虚线框 + 中间勾出的方框。 */
    val SelectAll: ImageVector by lazy {
        icon(
            "SelectAll",
            d = "M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2z" +
                "m10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2z" +
                "m-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2z" +
                "m0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2z" +
                "m-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z",
        )
    }

    /** 取消全选：一个被斜线划掉的选择框。 */
    val Deselect: ImageVector by lazy {
        icon(
            "Deselect",
            d = "M3,13h2v-2H3V13z M7,21h2v-2H7V21z M13,3h-2v2h2V3z M19,3v2h2C21,3.9,20.1,3,19,3z " +
                "M5,21v-2H3C3,20.1,3.9,21,5,21z M3,17h2 v-2H3V17z M11,21h2v-2h-2V21z " +
                "M19,13h2v-2h-2V13z M19,9h2V7h-2V9z M15,5h2V3h-2V5z M7.83,5L7,4.17V3h2v2H7.83z " +
                "M19.83,17L19,16.17 V15h2v2H19.83z M21.19,21.19L2.81,2.81L1.39,4.22L4.17,7H3v2h2V7.83" +
                "l2,2V17h7.17l2,2H15v2h2v-1.17l2.78,2.78L21.19,21.19z M9,15 v-3.17L12.17,15H9z " +
                "M15,12.17V9h-3.17l-2-2H17v7.17L15,12.17z",
        )
    }

    /** 网格视图：四个方框。用 evenodd —— 四个框是**挖空**的，非零规则会画成实心块。 */
    val GridView: ImageVector by lazy {
        icon(
            "GridView",
            fillType = PathFillType.EvenOdd,
            d = "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8z" +
                "m6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z",
        )
    }

    /** 柱状图：阅读统计的入口。 */
    val BarChart: ImageVector by lazy {
        icon("BarChart", d = "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z")
    }

    /** 列表视图：四行的列表。 */
    val ViewList: ImageVector by lazy {
        icon(
            "ViewList",
            autoMirror = true,
            d = "M4 14h4v-4H4v4zm0 5h4v-4H4v4zM4 9h4V5H4v4zm5 5h12v-4H9v4zm0 5h12v-4H9v4zM9 5v4h12V5H9z",
        )
    }

    /** 在别处打开：方框 + 指向右上角的箭头。 */
    val OpenInNew: ImageVector by lazy {
        icon(
            "OpenInNew",
            autoMirror = true,
            d = "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7z" +
                "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
        )
    }

    /** 新建笔记：纸上一个加号，右上角折角。 */
    val NoteAdd: ImageVector by lazy {
        icon(
            "NoteAdd",
            autoMirror = true,
            d = "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6z" +
                "m2 14h-3v3h-2v-3H8v-2h3v-3h2v3h3v2zm-3-7V3.5L18.5 9H13z",
        )
    }
}

/**
 * 造一个 24 em 的图标。
 *
 * 单位、视口、填充色都与 Material 官方图标一致：填充色写死黑色，真正的颜色由
 * `Icon` 的 `tint` 通过 ColorFilter 覆盖 —— 这正是 material-icons 自己的做法，
 * 换过来之后着色行为不会变。
 *
 * 一条 [d] 就是一条 SVG 路径。上面这些图标都只需要一条（官方 SVG 里那条
 * `fill="none"` 的 `M0 0h24v24H0z` 是裁切蒙版，不画像素，已经略去）；
 * 真遇到多条路径的图标，把它们的 `d` 前后拼起来即可 —— SVG 允许在一条路径里
 * 用多个子路径（`m`/`M`），填充规则照样生效。
 */
private fun icon(
    name: String,
    d: String,
    autoMirror: Boolean = false,
    fillType: PathFillType = PathFillType.NonZero,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).addPath(
    pathData = PathParser().parsePathString(d).toNodes(),
    pathFillType = fillType,
    fill = SolidColor(Color.Black),
).build()
