package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.StrokeAnchor

/**
 * 把已经落盘的笔迹**挪个位置**（S1 套索的后半段）。
 *
 * ## 为什么不能只改一个偏移量
 *
 * 最省事的写法是"给这条记录存一个 dx/dy，画的时候加上"。那会把笔迹从
 * **锚点体系**里摘出去：它不再指向某一行，重排（改字号、换版式）之后
 * 这个偏移量加在什么上面就成了没有定义的事 —— 而本工程全部的可信度
 * 都建立在"每一笔都指向某一行"上。
 *
 * 所以移动做的是**重新锚定**：把点平移之后重新走一遍 [InkAnchorFactory]，
 * 让它按新的位置重新判行、重新算 em 坐标、重建锚点链。
 * 落笔与移动因此是同一段代码，"移动过的笔迹在重排后行为不一样"就不会发生。
 *
 * ## 移动之后语义可能会变（这是对的）
 *
 * 移动会让笔迹落到**别的行**上，于是它与行带的重合度、跨了几行都会变，
 * [InkSemanticsClassifier] 给出的判定自然也可能变 —— 一条原本"跟随文字"的
 * 下划线被挪到页边空白，就该变成"钉在页面上"。
 *
 * 所以这里**不给** `semanticsOverride`（落笔时荧光笔用它钉死 FOLLOWER，
 * 那是另一回事）：移动的语义重新判定，沿用现有三态，不新增第四态。
 *
 * ## 跨行移动会切成多段
 *
 * 一笔原本只跨一行，挪到两行之间之后会被切成两段 —— 与落笔时按行切段是
 * 同一个机制、同一条代码。调用方必须接受"一条旧记录 → 多条新记录"，
 * 并且把它们当作**同一次移动**一起撤销。
 */
object InkMove {

    /**
     * 一条被移动的笔迹的新位置。
     *
     * [segments] 可能多于一条（跨行移动会切段），也可能为空 ——
     * 空的情况由 [Outcome] 的 `Rejected` 表达，不让调用方去猜。
     */
    class Relocation(
        /** 原来那一条记录的笔迹 id（用来找到要撤掉的旧记录）。 */
        val fromStrokeId: String,
        val segments: List<InkAnchorFactory.Segment>,
    ) {
        val semantics: InkReflowSemantics get() = segments.first().stroke.semantics
    }

    sealed interface Outcome {

        /** 全部挪好了。调用方据此写新 + 撤旧。 */
        data class Moved(val relocations: List<Relocation>) : Outcome

        /** 挪不动。[reason] 是给用户看的一句话，说清卡在哪一步。 */
        data class Rejected(val reason: String) : Outcome
    }

    /**
     * 把 [strokes] 平移 ([dx], [dy]) 之后重新锚定。
     *
     * @param strokes 被选中的段，必须是**已解析的** [DrawableStroke] ——
     *        它们的 `docPoints` 是当前排版下真实画在屏幕上的那些点。
     *        拿库里原始记录来平移会得到"移动前的旧坐标"，
     *        于是"挪一点"看起来像"挪了一大截"。
     * @param facts 当前排版。移动的落点判行要用它，与落笔时用的是同一套。
     * @param idFactory 新段的 id。旧段会被撤掉，id 不能复用 ——
     *        复用会让"撤销之后到底哪一份还在"变成不确定的事。
     */
    fun plan(
        strokes: List<DrawableStroke>,
        dx: Float,
        dy: Float,
        facts: LayoutFacts,
        idFactory: () -> String,
    ): Outcome {
        if (strokes.isEmpty()) return Outcome.Rejected("没有选中任何笔迹。")
        if (!dx.isFinite() || !dy.isFinite()) return Outcome.Rejected("移动的位移不是有效数字。")

        val relocations = ArrayList<Relocation>(strokes.size)
        val rejected = ArrayList<String>()

        for (stroke in strokes) {
            val moved = translate(stroke.docPoints, dx, dy)
            val result = InkAnchorFactory.anchor(
                InkAnchorFactory.Request(
                    strokeId = idFactory(),
                    docPoints = moved,
                    docPressures = stroke.docPressures,
                    brushId = stroke.brush.name,
                    lines = facts.lines,
                    geometry = facts.geometry,
                    source = facts.lineSource(),
                    document = facts.document(),
                ),
            )
            val segments = result?.segments.orEmpty()
            if (segments.isEmpty()) {
                // 挪到了拿不到引文的行（空行、页眉页脚那一类）上。
                // 逐条说出是哪一条，而不是笼统一句"有一部分没挪动" ——
                // 用户要能对着屏幕数出来哪一笔没动。
                rejected += stroke.strokeId
                continue
            }
            relocations += Relocation(fromStrokeId = stroke.strokeId, segments = segments)
        }

        if (relocations.isEmpty()) {
            return Outcome.Rejected(
                "这一笔挪过去之后落不到任何有文字的行上，没有移动。" +
                    "（空行与页边拿不到引文，硬记下来会在下次打开时变成孤儿。）",
            )
        }
        if (rejected.isNotEmpty()) {
            // 部分成功也要说：静默少挪几笔会让用户以为"移动"这个动作坏了。
            return Outcome.Rejected(
                "有 ${rejected.size} 笔挪不到有文字的行上，为避免只挪一半，这一次全部没有移动。",
            )
        }
        return Outcome.Moved(relocations)
    }

    /**
     * 平移点集。
     *
     * 只改 x/y、不动压力序列：压力是"当时用了多大劲"，与位置无关，
     * 顺手改它等于改笔宽。
     */
    private fun translate(points: FloatArray, dx: Float, dy: Float): FloatArray {
        val out = FloatArray(points.size)
        var i = 0
        while (i < points.size) {
            out[i] = points[i] + dx
            out[i + 1] = points[i + 1] + dy
            i += 2
        }
        return out
    }

    /** 这些段在移动后各自落在哪一行 —— 给界面画"命中了哪些行"的提示用。 */
    fun linesOf(relocations: List<Relocation>): List<dev.inkleaf.core.model.LineBox> =
        relocations.flatMap { it.segments.map { segment -> segment.line } }

    /** 移动之后这些段用的还是不是原来那支笔（笔刷不随移动变）。 */
    fun strokeAnchorsOf(relocation: Relocation): List<StrokeAnchor> =
        relocation.segments.map { it.stroke }
}
