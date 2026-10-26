package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.AnchorResolver
import dev.inkleaf.core.anchoring.PinnedPlacement
import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.TextAnchorChain

/**
 * 一条已存笔迹相对**当前排版**的状态。
 *
 * 只有三种，且**没有第四种**（方案 §6.3.4）：解析成功 / 需用户确认 / 孤儿。
 * "猜一个位置然后当作成功"是被明确排除的第四种，所以这里不存在 `GUESSED`。
 *
 * 注意它说的是**锚点**的状态，不是墨迹的状态 —— 后者是 [InkResolution.Record.drawn]。
 * 两者会分开（PINNED / FREE 的锚点可能解不开，而墨迹照样画在原处），
 * 硬把它们塞进一个枚举就会多出"解不开但画出来了"这种第四、第五种状态。
 */
enum class InkState { RESOLVED, AMBIGUOUS, ORPHANED }

/**
 * 解析结果。
 *
 * [drawable] 是**能画的**，[records] 是**全部**（含画不出来的）——
 * 两个列表刻意分开，因为它们的用途不同：前者给渲染层，后者给笔记界面。
 * 让渲染层自己去过滤"哪些解得开"，就会有人图省事把画不出来的也算进去，
 * 于是"笔记跑到别处"这种最伤信任的 bug 就出现了。
 */
/**
 * 一版解析结果"覆盖了哪些笔迹"，按**根 id**（去掉跨行段号 `#n`）计。
 *
 * 为什么要按根 id 而不是标注 id：抬笔时投进暂存的是**一笔**（`ink-xxx`），
 * 而落库时它被按行切成若干条标注（各自的标注 id 是另生成的、暂存根本不知道）。
 * 唯一能穿过这道切割把它们对上的是笔迹 id 的前缀。
 *
 * 两个集合回答两个不同的问题：
 * - [drawnRootIds]：屏幕上**真的画出来了**吗？—— 是，暂存就该让位。
 * - [storedRootIds]：库里**到底有没有**它？—— 有而画不出来，暂存也该撤，
 *   否则状态行写着"这条笔迹暂时画不出来"，屏幕上却明摆着一份湿墨。
 *
 * 两者都没有 = 这一版解析还没轮到它（可能库读在落盘之前），暂存必须留着。
 */
data class InkCoverage(
    val drawnRootIds: Set<String> = emptySet(),
    val storedRootIds: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = InkCoverage()

        /** `ink-abc#3` → `ink-abc`；没有段号时原样返回。 */
        fun rootOf(strokeId: String): String = strokeId.substringBefore('#')
    }
}

data class InkResolution(
    val drawable: List<DrawableStroke>,
    val records: List<Record>,
    /**
     * 这一版解析对"哪些笔迹有话可说"。
     *
     * 存在的唯一理由是**抬笔暂存的交接**（[InkLayerController.acknowledgeCommitted]）：
     * 暂存那一笔能不能撤，取决于这一版里到底有没有它 —— 而不是"有没有提交过一版"。
     * 后者会让一次与它无关的重组（比如点一下书写开关）把它从屏幕上撤掉，
     * 表现为"刚画的圈闪一下没了、过一会儿又自己回来"。
     */
    val coverage: InkCoverage = InkCoverage.EMPTY,
) {
    /**
     * 一条笔记的解析结果。
     *
     * [reason] 是给用户看的：方案 §6.3.4 要求"说清哪一路证据弱了"，
     * 而不是甩一个"需要确认"。一句话说不出原因的解析结果，等于没有解释。
     */
    data class Record(
        val annotationId: String,
        val state: InkState,
        val semantics: InkReflowSemantics,
        val line: LineBox?,
        val confidence: Float,
        val reason: String,
        /**
         * 这条笔记**当时标在哪句话上**（存在锚点链里的文字），不是它现在解析到哪一行。
         *
         * 两种情况下它是唯一的线索：`Ambiguous`（有多处都可能，要靠这句话去搜出候选）
         * 与 `Orphaned`（当前排版里根本没有它那一行）。所以它必须**原样来自库里**，
         * 不能拿"解析结果"反推 —— 那会把"我当时写在这句上"悄悄换成"系统觉得在这句上"。
         *
         * 取不到（老数据没有引文、且进度兜底里也没有文字）时为 null。
         */
        val originalText: String? = null,
        /**
         * 这条笔记**有没有墨迹被画到屏幕上**（哪怕只画出了一部分）。
         *
         * 判据刻意取"一个像素都没有"：界面上那句"笔迹暂时画不出来"只有在
         * 墨迹真的完全不在屏幕上时才是实话。它与 [state] 是两条轴 ——
         * 冻结语义（PINNED / FREE）的墨迹不依赖解析结果，所以会出现
         * "锚点是孤儿、墨迹照画"这种组合，界面必须能分辨。
         */
        val drawn: Boolean = false,
        /**
         * 这条笔记在库里有**笔迹行可挂**吗（能不能重挂/改挂的前提）。
         *
         * 与 [drawn] 是两件事：一条笔记可能一个像素都没画出来却仍然存着笔迹点
         * （锚点解不开），也可能挂在好位置上而笔迹点已经丢了（存储不完整）。
         * "能不能换一行"问的是后者，所以判据刻意取**库里实际存在的**笔迹行，
         * 而不是 `Annotation.Ink.strokeIds` 那份声明 —— 重挂恰好是把"声明"与"实际"
         * 对上的时机（见 `InkReanchor`）。
         *
         * 界面靠它决定给不给"确认位置 / 改挂"入口：给一个点下去必然失败的按钮，
         * 比不给按钮更糟。
         */
        val hasStrokes: Boolean = false,
    )

    val resolvedCount: Int get() = records.count { it.state == InkState.RESOLVED }
    val ambiguous: List<Record> get() = records.filter { it.state == InkState.AMBIGUOUS }
    val orphaned: List<Record> get() = records.filter { it.state == InkState.ORPHANED }

    /**
     * 屏幕上**一个像素都没有**的笔记 —— 界面上唯一需要解释的那一类。
     *
     * 用 [drawn] 而不是 [state] 来筛：解不开但画在原处的冻结笔迹不该出现在
     * "画不出来的笔迹"面板里（那会让用户去找一条其实就在眼前的笔记）。
     */
    val notDrawn: List<Record> get() = records.filter { !it.drawn }
}

/**
 * 把已存的笔迹**解析回当前排版的文档坐标**，得到可以直接画的东西。
 *
 * 这是"重排后笔迹仍然贴在原来那句话上"的实现所在：库里存的是
 * 「哪一行 + 行内 em 坐标」，这里把它换成「当前排版下的像素坐标」。
 * 换算是单向且无状态的：给定同一份排版，结果永远一样；排版变了就重新算一遍。
 *
 * ## 一条硬规则：解不开就不画
 *
 * 需要行盒才能换算的笔迹（FOLLOWER）在 `AMBIGUOUS` 与 `ORPHANED` 时
 * **不进 [InkResolution.drawable]**。理由不是保守，而是数学：不画只是"暂时看不见"，
 * 画错是"告诉用户一个假事实"。前者可以在笔记界面里补救，后者会直接毁掉用户对墨迹的信任。
 *
 * ## 唯一的例外：冻结笔迹
 *
 * PINNED / FREE 的几何来自落笔那一刻的**冻结变换**（[StrokeAnchor.frozenMatrix]），
 * 它**不依赖任何解析结果** —— 于是"解不开"对它们不构成"可能画错"的风险：
 * 画在它们当时被写下的位置上，就是唯一正确的答案。所以它们即使锚点解不开也照画，
 * 而记录里 [InkResolution.Record.drawn] 会如实说"墨迹在屏幕上"（见 M3 §20）。
 *
 * ## 仍未做的（方案 §6.3.3 的完整形态，见 design/M3-实现记录与设计修订.md）
 *
 * - PINNED 墨迹的落位求解**已做**（[PinnedPlacement]）：形状与笔宽冻在落笔那一刻，
 *   位置则**整块挪到目标行旁边的空白处**（级联下推、留出间距、放不下就如实报告）。
 *   方案 §6.3.3 第 4 步的"折叠为批注堆叠角标"（放不下的那几条怎么给用户看）是界面的活。
 * - 冻结坐标存的是像素几何而不是方案 §6.4 的**页内归一化坐标**：
 *   页宽改变（横竖屏、改页边距）时**横向**不会等比跟随（纵向已由 [PinnedPlacement] 负责）。
 * - 版式锁定模式（`FixedPage` 锚点）没做。
 */
object InkResolver {

    /**
     * "这条笔记当时标在哪句话上"的长度上限。
     *
     * 48 个字足够消歧（重复的短句在这个长度上几乎必然分得开），
     * 又短到能整段显示在笔记列表里、当搜索词填进搜索框。
     */
    const val MAX_ORIGINAL_TEXT_CHARS = 48

    /**
     * @param strokesOf **按标注 id** 取它的笔迹行，对应
     *        `AnnotationSnapshot.strokesOf(annotationId)`。这里刻意不复用
     *        `Annotation.Ink.strokeIds` 当查询键：那个字段是"这条笔记**声明**有哪几段"，
     *        用来核对存储完整性；拿它当查询键会把"声明"与"实际"混成一个概念，
     *        两者一旦不一致（部分写入、导入包缺文件）就再也分不出谁对。
     * @param brushesOf 按标注 id 取它的笔刷定义。
     */
    fun resolve(
        inks: List<Annotation.Ink>,
        strokesOf: (String) -> List<StrokeAnchor>,
        brushesOf: (String) -> List<BrushSpec>,
        facts: LayoutFacts,
    ): InkResolution {
        val drawable = ArrayList<DrawableStroke>()
        val records = ArrayList<InkResolution.Record>(inks.size)

        // 与 [records] 下标对齐的两份旁记，供**最后那一步**（PINNED 落位求解）使用：
        //   - `drawnGroups`：这条笔记画出来的那几段。落位要按"一条笔记"整体平移，
        //     而 [drawable] 是扁平的一串，找不回"哪几段属于同一条"；
        //   - `placeable`：它是不是"真冻结的 PINNED"。只有这一类参与落位。
        // 用与 records 同步的列表而不是 Map：下标本身就是对应关系，
        // 再引入一个键只是给"两处对不上"多开一个机会。
        val drawnGroups = ArrayList<List<DrawableStroke>>(inks.size)
        val placeable = ArrayList<Boolean>(inks.size)

        // 这一版解析"覆盖了哪些笔迹"（见 [InkCoverage]）。`stored` 顺手收集：
        // 它本来就是每条笔记都要读一遍的东西，再单独扫一次只是白花一次 IO。
        val storedRoots = HashSet<String>()

        // 行盒按块分组的索引，建**一次**给整轮解析用。
        // 每次解析都要把几百条笔记逐一送回 [AnchorResolver]，而它一次只扫 200 行；
        // 不先按块收窄的话，第 200 行以后的笔迹全都判成孤儿（见 [resolveWithFallback]）。
        val spans = lineSpans(facts.lines)

        for (ink in inks) {
            val chain = ink.anchor
            // 这条笔记"当时标在哪句话上"。它来自**库里存的链**，与"现在解析到哪"无关，
            // 因此对解不开的笔记它依然是有效信息 —— 那正是重挂（确认位置）需要的线索。
            val originalText = chain?.let { storedTextOf(it) }
            val brushes = brushesOf(ink.id)

            // ★ 这里的 [strokesOf] 按**标注 id** 查，不是按笔迹 id 查 ——
            // 它对应 `AnnotationSnapshot.strokesOf(annotationId)`。
            // 这两个 id 在模型里长得很像（都是 String），一旦弄反，
            // 症状是"锚点解析成功、但屏幕上什么都不画"，
            // 而两侧各自的单元测试都会是绿的（各自内部自洽）。
            // 本工程正是靠 app 级的整链路测试抓到这一处（见 design/M3 记录）。
            val stored = strokesOf(ink.id)
            val declared = ink.strokeIds
            val kept = if (declared.isEmpty()) {
                // 旧数据可能没写 strokeIds。笔迹行本身是外键挂在标注上的，
                // 所以"没有声明"时画全部 —— 这不是猜位置，就是在画这个标注自己的笔迹。
                stored
            } else {
                stored.filter { it.strokeId in declared }
            }
            val missing = if (declared.isEmpty()) {
                0
            } else {
                declared.count { id -> stored.none { it.strokeId == id } }
            }
            stored.forEach { storedRoots.add(InkCoverage.rootOf(it.strokeId)) }

            // "真冻结"：语义不是跟随，且每一段都带着冻结几何。
            // 旧数据里的 PINNED / FREE 没存过冻结坐标，这里如实为 false ——
            // 于是界面上能说清"它本该固定，但这一笔是旧版本记下的，仍会跟着文字走"。
            val frozen = kept.isNotEmpty() &&
                ink.semantics != InkReflowSemantics.FOLLOWER &&
                kept.all { it.frozenMatrix != null }
            val legacyFrozen = kept.isNotEmpty() &&
                ink.semantics != InkReflowSemantics.FOLLOWER &&
                kept.none { it.frozenMatrix != null }

            if (chain == null) {
                // 没有锚点链（旧数据）。冻结笔迹照样画得出来：它的几何不依赖锚点。
                val drawnHere = drawablesOf(kept, brushes, line = null)
                drawable += drawnHere
                drawnGroups += drawnHere
                placeable += false
                records += InkResolution.Record(
                    annotationId = ink.id,
                    state = InkState.ORPHANED,
                    semantics = ink.semantics,
                    line = null,
                    confidence = 0f,
                    reason = if (drawnHere.isEmpty()) {
                        "${AnchorReason.NO_CHAIN_CAUSE}，无法定位。"
                    } else {
                        "${AnchorReason.NO_CHAIN_CAUSE}，但它固定在页面上，" +
                            "所以仍画在落笔时的位置上。"
                    },
                    originalText = originalText,
                    drawn = drawnHere.isNotEmpty(),
                    hasStrokes = stored.isNotEmpty(),
                )
                continue
            }

            when (val resolution = resolveWithFallback(chain, facts, spans)) {
                is AnchorResolution.Resolved -> {
                    val line = resolution.line
                    // 冻结笔迹用自己那份变换（因此解析到哪一行都不影响它画在哪），
                    // 跟随笔迹用**当前**这一行的变换。
                    val drawnHere = drawablesOf(kept, brushes, line)
                    drawable += drawnHere
                    drawnGroups += drawnHere
                    // 只有"真冻结的 PINNED"能参与落位求解：
                    //   - FREE 与行盒无关（它的定义就是"不跟随文字"），没有"目标行附近的空白"；
                    //   - 旧数据（legacyFrozen）的几何是借当前行盒算出来的，本来就在文字旁边，
                    //     再挪一次等于给一笔"其实会跟着文字走"的笔迹编一个固定位置。
                    placeable += ink.semantics == InkReflowSemantics.PINNED && frozen
                    val base = when {
                        // 锚点解开了但笔迹点缺失：渲染不出东西，如实说明，
                        // 不要显示成"已定位"让用户以为墨迹在屏幕上。
                        drawnHere.isEmpty() && missing > 0 ->
                            "锚点已定位，但这条笔记有 $missing 段笔迹点缺失（存储不完整）。"
                        drawnHere.isEmpty() ->
                            "锚点已定位，但这条笔记没有笔迹点。"
                        else ->
                            AnchorReason.resolved(line.anchor.blockPath, resolution.confidence)
                    }
                    val extra = if (drawnHere.isEmpty()) {
                        ""
                    } else {
                        buildString {
                            append(frozenNote(frozen, legacyFrozen))
                            if (missing > 0) append("另有 $missing 段笔迹点缺失。")
                        }
                    }
                    records += InkResolution.Record(
                        annotationId = ink.id,
                        state = InkState.RESOLVED,
                        semantics = ink.semantics,
                        line = line,
                        confidence = resolution.confidence,
                        reason = base + extra,
                        originalText = originalText,
                        drawn = drawnHere.isNotEmpty(),
                        hasStrokes = stored.isNotEmpty(),
                    )
                }

                // 下面两路都只画得出冻结笔迹：跟随笔迹拿不到行盒，而拿不到就不画
                // （硬规则）。`line = null` 正是这个意思，不需要另写判断。
                is AnchorResolution.Ambiguous -> {
                    val drawnHere = drawablesOf(kept, brushes, line = null)
                    drawable += drawnHere
                    drawnGroups += drawnHere
                    // 解不开就没有目标行，也就没有"目标行附近的空白"：不落位（M3.1 的有界例外）。
                    placeable += false
                    records += InkResolution.Record(
                        annotationId = ink.id,
                        state = InkState.AMBIGUOUS,
                        semantics = ink.semantics,
                        line = null,
                        confidence = 0f,
                        reason = buildString {
                            append(AnchorReason.ambiguous(resolution.candidates.size))
                            if (drawnHere.isNotEmpty()) {
                                append("这一笔固定在页面上，仍画在原处；确认位置只是把它与其中一处重新对上。")
                            }
                        },
                        originalText = originalText,
                        drawn = drawnHere.isNotEmpty(),
                        hasStrokes = stored.isNotEmpty(),
                    )
                }

                is AnchorResolution.Orphaned -> {
                    val drawnHere = drawablesOf(kept, brushes, line = null)
                    drawable += drawnHere
                    drawnGroups += drawnHere
                    placeable += false
                    records += InkResolution.Record(
                        annotationId = ink.id,
                        state = InkState.ORPHANED,
                        semantics = ink.semantics,
                        line = null,
                        confidence = 0f,
                        // Orphaned 只携带一个进度兜底选择器：说得出"文件可能变了"，
                        // 说不出"变成了什么"—— 不编造原因。
                        reason = buildString {
                            append(AnchorReason.ORPHANED_CAUSE)
                            append(
                                if (drawnHere.isNotEmpty()) {
                                    "这一笔固定在页面上，仍画在原来的位置；只是它不再挂在任何一行上。"
                                } else {
                                    "笔记还在，但没有被放到任何一行上。"
                                },
                            )
                        },
                        originalText = originalText,
                        drawn = drawnHere.isNotEmpty(),
                        hasStrokes = stored.isNotEmpty(),
                    )
                }
            }
        }

        // ── PINNED 墨迹的落位（方案 §6.3.3）────────────────────────────────────
        //
        // 这是本对象里唯一**跨笔记**的一步：一条笔记能被摆到哪，取决于旁边
        // 有没有别的笔记已经占了位置。所以它只能在全部笔记都解析完之后跑，
        // 而不能在每条笔记的分支里各算一次。
        applyPinnedPlacement(records, drawnGroups, placeable, facts)

        // ── 段与段之间的接缝 ────────────────────────────────────────────────────
        // 跨行的一笔在库里是**几条独立的笔记**（一段 = 一行 = 一条 Annotation.Ink），
        // 每条只带一段笔迹 —— 这一点很关键：按"一条笔记内部多段"去接是接不到的，
        // 因为 kept 永远只有一个元素。
        //
        // 绘制时它们是各自的 Path、各自画自己的圆头笔帽；帽半径盖不住一个采样间距
        // （实测点距约 11px、线宽 6px），屏幕上就留下一道 4~8px 的缝。
        // 一笔跨九行成了九段虚线 —— 用户说的"在有文字的地方笔迹会中断"就是这个。
        //
        // 只能在这里做：必须在全部笔记解析完、PINNED 落位算完之后，
        // 否则补上去的点会被落位那一步挪走。
        //
        // 覆盖集合从**最终**的 drawable 里取：接缝那一步不改 id，但"画出来的"
        // 以屏幕为准 —— 交接判据要的正是"屏幕上有没有它"。
        val drawnRoots = HashSet<String>(drawable.size)
        drawable.forEach { drawnRoots.add(InkCoverage.rootOf(it.strokeId)) }
        return InkResolution(
            drawable = joinSegmentSeams(drawable),
            records = records,
            coverage = InkCoverage(drawnRoots, storedRoots),
        )
    }

    /**
     * 给"真冻结的 PINNED"笔记算一次落位，并把结果**就地**写回几何与记录。
     *
     * 就地改有三件事同时发生，缺一件都会让用户看到自相矛盾的界面：
     *   1. 画出来的点整体平移（[translateInPlace]）—— 屏幕上真的挪了；
     *   2. 记录里补一句为什么（[placementNote]）—— 面板里说得清它为什么不在我写的地方；
     *   3. 落位结果**不写回库**：库里那条锚点与冻结几何一个字节都不动，
     *      下次打开重算一遍。写回去等于"打开文档"变成了一次改数据的操作 ——
     *      而用户只是翻了翻书。
     *
     * 只平移、不缩放：形状、比例、笔宽仍是落笔那一刻的。见 [PinnedPlacement] 规则 2。
     */
    private fun applyPinnedPlacement(
        records: MutableList<InkResolution.Record>,
        drawnGroups: List<List<DrawableStroke>>,
        placeable: List<Boolean>,
        facts: LayoutFacts,
    ) {
        val items = ArrayList<PinnedPlacement.Item>(2)
        val owner = ArrayList<Int>(2)
        for (i in records.indices) {
            if (!placeable[i]) continue
            // 解不开的笔记 `line` 为 null：不参与（M3.1 的有界例外，它照画在原处）。
            val line = records[i].line ?: continue
            val group = drawnGroups[i]
            if (group.isEmpty()) continue
            val box = boxOf(group)
            // 点集为空的笔迹（存储不完整）没有几何可言：[boxOf] 会给回一个"空集"记号
            // （left > right），拿它去求解会得出一段荒唐的位移。
            if (box.left > box.right || box.top > box.bottom) continue
            items += PinnedPlacement.Item(
                id = records[i].annotationId,
                box = box,
                targetLine = line,
            )
            owner += i
        }
        if (items.isEmpty()) return

        val placements = PinnedPlacement.solve(
            items = items,
            lines = facts.lines,
            bottomPx = facts.geometry.contentBox.bottom,
        )

        for (k in placements.indices) {
            val placement = placements[k]
            val note = placementNote(placement)
            if (note.isEmpty()) continue
            val i = owner[k]
            if (placement.moved) {
                for (stroke in drawnGroups[i]) translateInPlace(stroke.docPoints, placement.dy)
            }
            records[i] = records[i].copy(reason = records[i].reason + note)
        }
    }

    /**
     * 落位那半句话。刻意分开写，方便测试逐句钉住（与 [frozenNote] 同一个理由）。
     *
     * 三种情形都必须说得出**为什么**：用户看到自己的边注不在写下的位置上，
     * 唯一能让他相信这不是 bug 的，就是一句说清原因的话。
     */
    private fun placementNote(placement: PinnedPlacement.Placement): String {
        if (placement.blocked) {
            return "它现在压在正文上（或离目标行很远），目标行附近又找不到空白可放，" +
                "所以仍留在落笔时的位置上。"
        }
        if (!placement.moved) return ""
        val why = when {
            placement.coveringText && placement.leftTargetLine -> "压在正文上、而且离目标行很远"
            placement.coveringText -> "压在正文上"
            else -> "离目标行很远"
        }
        return "它现在${why}，已挪到目标行旁边的空白处" +
            "（只挪位置：形状与笔宽仍是落笔那一刻的）。"
    }

    /** 一组笔迹在文档坐标里的外接矩形。落位只关心"这一片有没有字"，不关心形状。 */
    private fun boxOf(strokes: List<DrawableStroke>): Box {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (stroke in strokes) {
            val points = stroke.docPoints
            var i = 0
            while (i < points.size) {
                val x = points[i]
                val y = points[i + 1]
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                i += 2
            }
        }
        return Box(left, top, right, bottom)
    }

    /**
     * 把一组点整体纵向平移 [dy]。
     *
     * 就地改而不是新建数组：调用方（`DocumentInkView`）拿到的就是 [InkResolution.drawable]
     * 里那批对象，新建一批会让"落位之后的几何"与"屏幕上画的东西"变成两份。
     * 平移到 x 上：横向一个像素都不动（见 [PinnedPlacement] 规则 1）。
     */
    private fun translateInPlace(points: FloatArray, dy: Float) {
        if (dy == 0f) return
        var i = 1
        while (i < points.size) {
            points[i] += dy
            i += 2
        }
    }

    /**
     * 单次锚点解析的候选窗口（行数）。
     *
     * 与 [AnchorResolver.DEFAULT_MAX_CANDIDATE_SCAN] 同量级：那边是"最多扫多少"，
     * 这里是"先给多少"。取 200 行，约等于一屏到一章。
     */
    private const val SEARCH_WINDOW_LINES = 200

    /**
     * 行盒按 `blockPath` 分组：块路径 → 它在 [lines] 里的下标区间（闭区间）。
     *
     * 行盒按文档顺序排，同一个块的行必然是连续的一段 —— 这是"按块收窄候选"
     * 能成立的前提。建一次的成本是 O(行数)，换来的是每条笔记不必再扫一遍全书。
     */
    private fun lineSpans(lines: List<LineBox>): Map<String, IntRange> {
        if (lines.isEmpty()) return emptyMap()
        val out = HashMap<String, IntRange>()
        var start = 0
        for (i in 1..lines.size) {
            val path = lines[start].anchor.blockPath
            if (i == lines.size || lines[i].anchor.blockPath != path) {
                out[path] = start..(i - 1)
                start = i
            }
        }
        return out
    }

    /**
     * 一次锚点解析：先在那条笔记**自己所在的块**附近找，找不到再全文找。
     *
     * 为什么必须收窄：[AnchorResolver] 单次只扫前 [AnchorResolver.DEFAULT_MAX_CANDIDATE_SCAN] 行。
     * 调用方曾经把**全书**行盒原样递进去，于是第 200 行之后的笔迹一律判孤儿 ——
     * 一本两千页的书从第十页开始，"正文里写的字"就再也画不出来了（用户看到的是
     * "有笔迹暂时画不出来"，而屏幕上的墨其实一直在库里）。
     *
     * 为什么两段式而不是干脆一次全扫：全书五万行、每条笔记各扫一遍，一次翻页就是
     * 几亿次比较。变孤儿的笔记是少数，让多数笔记只付窗口的代价，才有余量对少数
     * 笔记做全文搜索 —— 重排/换版本之后块号变了，只有全文能把它找回来。
     */
    private fun resolveWithFallback(
        chain: TextAnchorChain,
        facts: LayoutFacts,
        spans: Map<String, IntRange>,
    ): AnchorResolution {
        val window = searchWindow(chain, facts.lines, spans)
        val near = AnchorResolver.resolve(
            chain = chain,
            currentSourceFingerprint = facts.fingerprint,
            candidates = window,
            textOf = facts::textOf,
            currentProfile = facts.profile,
            // 窗口本身已经是工作量上限：这里再按 200 行截一刀，长段落会被从中间切断，
            // 而那正是"读到第 10 页笔迹开始消失"的成因。
            maxCandidateScan = window.size,
            contextOf = facts::textWindow,
        )
        if (near !is AnchorResolution.Orphaned || window.size >= facts.lines.size) return near
        return AnchorResolver.resolve(
            chain = chain,
            currentSourceFingerprint = facts.fingerprint,
            candidates = facts.lines,
            textOf = facts::textOf,
            currentProfile = facts.profile,
            maxCandidateScan = facts.lines.size,
            contextOf = facts::textWindow,
        )
    }

    /**
     * 在 [lines] 里取"这条笔记自己那个块"及其前后一段。
     *
     * 取不到块（没有结构型选择器，或者块路径在当前排版里根本不存在）时返回全文：
     * 那是"没有线索可收窄"的情形，只能让 [AnchorResolver] 自己扫。
     */
    private fun searchWindow(
        chain: TextAnchorChain,
        lines: List<LineBox>,
        spans: Map<String, IntRange>,
    ): List<LineBox> {
        val structural = chain.structural ?: return lines
        val span = spans[structural.blockPath] ?: return lines
        val slack = (SEARCH_WINDOW_LINES - (span.last - span.first + 1)).coerceAtLeast(0)
        val from = (span.first - slack / 2).coerceAtLeast(0)
        val to = (span.last + (slack - slack / 2) + 1).coerceAtMost(lines.size)
        return lines.subList(from, to)
    }

    /**
     * 把一段已存的笔迹换算成能画的东西；**画不出来时返回 null，不返回半个近似值**。
     *
     * 几何只有两个来源，优先级是固定的：
     * 1. [StrokeAnchor.frozenMatrix]（PINNED / FREE）：落笔那一刻的位置，
     *    与当前排版无关，所以 [line] 为 null 也能画 —— 这正是"固定在页面上"的定义。
     * 2. [line] 的 [LineBox.matrixInDoc]（FOLLOWER）：跟着当前行盒走。
     * 两者都没有 → null，调用方据此记成"画不出来"。
     */
    private fun drawableOf(stroke: StrokeAnchor, brushes: List<BrushSpec>, line: LineBox?): DrawableStroke? {
        val matrix = stroke.frozenMatrix ?: line?.matrixInDoc() ?: return null
        val fontSizePx = matrix.emScale
        return DrawableStroke(
            strokeId = stroke.strokeId,
            brush = brushOf(stroke, brushes),
            docPoints = mapToDoc(stroke.localPoints, matrix),
            docPressures = stroke.localPressures.copyOf(),
            // 笔宽以 em 存，画的时候要乘回 px：跟随笔迹用**当前**行的字号，
            // 冻结笔迹用它自己那份变换里的比例 —— 否则改一次正文字号，
            // 边注的粗细也会跟着变，那就不叫"冻结"了。
            // 比例退化（0 / 负 / NaN，只可能来自损坏的变换）时退回默认字号，
            // 宁可粗细不对，也不要整条笔记画不出来或者画出一团 NaN。
            fontSizePx = fontSizePx.takeIf { it.isFinite() && it > 0f }
                ?: DrawableStroke.DEFAULT_FONT_SIZE_PX,
        )
    }

    /**
     * 把一条笔记的若干段转成可绘制笔迹。
     *
     * 排序是给 [joinSegmentSeams] 铺路：同根的段在列表里按段序号相邻，
     * 接缝那一步才能按 id 直接找到前一段。真正的接缝在那边做 ——
     * 因为跨行的一笔在库里根本不是一条笔记的多段，而是**多条各带一段的笔记**。
     */
    private fun drawablesOf(
        strokes: List<StrokeAnchor>,
        brushes: List<BrushSpec>,
        line: LineBox?,
    ): List<DrawableStroke> {
        if (strokes.size == 1) return listOfNotNull(drawableOf(strokes[0], brushes, line))
        return strokes.sortedBy { segmentIndexOf(it.strokeId) }
            .mapNotNull { drawableOf(it, brushes, line) }
    }

    /**
     * 把同一笔的相邻段在接缝处接上：后一段的开头插入前一段的**文档坐标末点**。
     *
     * 为什么不改绘制顺序、不改任何一段自己的坐标 —— 只让后一段的 Path 从接缝处起笔。
     * 两条路径在那里各画一个圆头笔帽，帽心重合，缝就没了。
     *
     * 只接"根 id 相同、首尾间距在一个采样间隔内"的段：跨页的两段、
     * 或者两支互不相干的笔，不能凭空连出一条线。
     */
    private fun joinSegmentSeams(strokes: List<DrawableStroke>): List<DrawableStroke> {
        if (strokes.size < 2) return strokes
        val byId = strokes.associateBy { it.strokeId }
        return strokes.map { stroke ->
            val index = segmentIndexOf(stroke.strokeId)
            if (index == 0) return@map stroke
            val root = stroke.strokeId.substringBefore('#')
            val previousId = if (index == 1) root else "$root#${index - 1}"
            val previous = byId[previousId] ?: return@map stroke
            if (gapBetween(previous, stroke) > JOIN_MAX_GAP_PX) return@map stroke
            DrawableStroke(
                strokeId = stroke.strokeId,
                brush = stroke.brush,
                docPoints = prependPoint(
                    floatArrayOf(
                        previous.docPoints[previous.docPoints.size - 2],
                        previous.docPoints[previous.docPoints.size - 1],
                        0f,
                    ),
                    stroke.docPoints,
                ),
                docPressures = prependPressure(
                    previous.docPressures.lastOrNull() ?: 1f,
                    stroke.docPressures,
                ),
                fontSizePx = stroke.fontSizePx,
            )
        }
    }

    /** `ink-xxx#3` → 3；没有后缀（首段）→ 0。 */
    private fun segmentIndexOf(strokeId: String): Int =
        strokeId.substringAfter('#', "").toIntOrNull() ?: 0

    /** 前一段的末点到后一段的首点有多远。任一段点数不足时返回"远"，即不接。 */
    private fun gapBetween(previous: DrawableStroke, next: DrawableStroke): Float {
        val a = previous.docPoints
        val b = next.docPoints
        if (a.size < 2 || b.size < 2) return Float.MAX_VALUE
        val dx = b[0] - a[a.size - 2]
        val dy = b[1] - a[a.size - 1]
        return kotlin.math.hypot(dx, dy)
    }

    /** 在点数组前面插入一个点。 */
    private fun prependPoint(point: FloatArray, points: FloatArray): FloatArray {
        val out = FloatArray(points.size + 2)
        out[0] = point[0]
        out[1] = point[1]
        points.copyInto(out, destinationOffset = 2)
        return out
    }

    /** 在压力数组前面插入一个压力值。 */
    private fun prependPressure(pressure: Float, pressures: FloatArray): FloatArray {
        val out = FloatArray(pressures.size + 1)
        out[0] = pressure
        pressures.copyInto(out, destinationOffset = 1)
        return out
    }

    /** 冻结与否的那半句话，拼在状态说明后面。刻意分开，方便测试逐句钉住。 */
    private fun frozenNote(frozen: Boolean, legacyFrozen: Boolean): String = when {
        frozen -> "这一笔固定在页面上，不随重排移动。"
        legacyFrozen -> "注意：这类笔迹本该固定在页面上，但这一笔是旧版本记下的、没有冻结坐标，仍会跟着文字走。"
        else -> ""
    }

    /** 缺少笔刷定义时退回默认笔：宁可颜色不对，也不要整条笔记消失。 */
    private fun brushOf(stroke: StrokeAnchor, brushes: List<BrushSpec>): BrushSpec =
        brushes.firstOrNull { it.name == stroke.brushId }
            ?: brushes.firstOrNull()
            ?: BrushSpec.DEFAULT_PEN

    /**
     * 这条笔记当时标在哪句话上 —— 优先引文，退而求其次用进度兜底里的"附近那段文字"。
     *
     * 不加省略号：这个值会被原样填进搜索框当查询词（确认位置的入口），
     * 塞一个 `…` 进去会让查询永远命中不了 —— 显示层的截断（`InkNoteRow.excerpt`）
     * 与存储层的截断是两件事，不能共用同一个串。
     */
    private fun storedTextOf(chain: dev.inkleaf.core.model.TextAnchorChain): String? {
        val raw = chain.textQuote?.exact?.takeIf { it.isNotBlank() }
            ?: chain.progressHint.nearbyText
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.length <= MAX_ORIGINAL_TEXT_CHARS -> trimmed
            else -> trimmed.take(MAX_ORIGINAL_TEXT_CHARS)
        }
    }

    private fun mapToDoc(localPoints: FloatArray, matrix: dev.inkleaf.core.model.Affine2D): FloatArray {
        val out = FloatArray(localPoints.size)
        var i = 0
        while (i < localPoints.size) {
            out[i] = matrix.mapX(localPoints[i], localPoints[i + 1])
            out[i + 1] = matrix.mapY(localPoints[i], localPoints[i + 1])
            i += 2
        }
        return out
    }
}

/**
 * 相邻两段之间"还算连续"的最大间距（文档像素）。
 *
 * 正常的一笔被切成若干段时，接缝处的两点就是一个采样间隔：手写笔 120~240Hz、
 * 行高一百来像素，实测大约 10~15px。取半行高（48~96px 的量级）留足余量，
 * 而真正的"错乱"（跨页、损坏数据）会是几百像素，不会被误连。
 */
private const val JOIN_MAX_GAP_PX = 96f
