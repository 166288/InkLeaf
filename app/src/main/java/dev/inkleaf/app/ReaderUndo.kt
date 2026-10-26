package dev.inkleaf.app

/**
 * "刚刚那一下还能撤回吗"的两种答案。
 *
 * 为什么不是 `String?`（标注 id）：本次补上了整笔擦除，而它对撤销的要求与删除一条
 * 打字批注**根本不同** ——
 *
 *  - 删除打字批注是软删除，撤销 = 把那一行 `undelete` 回来，只需要 id；
 *  - 擦除笔迹会硬删笔迹行（文件不能悬空），撤销必须把删除前取出的标注 + 点集 + 笔刷
 *    **原样写回去**，只留一个 id 是救不回来的。
 *
 * 用 `Any?` 或"再开一个 pendingErase 状态"也能跑，但会出现"两个撤销窗口同时存在"的
 * 状态：界面上只有一行字，用户看到的撤销到底撤回哪一个？把它收成一个 sealed 类型，
 * "当前只有一个可撤销的动作"就成了类型上的事实，而不是两条 `if` 的巧合。
 */
internal sealed interface ReaderUndo {

    /** 删掉了一条打字批注（软删除）。 */
    data class Note(val annotationId: String) : ReaderUndo

    /** 擦掉了一笔墨迹。现场在这里，撤销靠它重建。 */
    data class Ink(val erased: ErasedInk) : ReaderUndo

    /**
     * 挪走了一批笔迹（S1 套索）。
     *
     * 现场比 [Ink] 多一头：移动是"写新的 + 撤旧的"，撤销要**两头都动** ——
     * 删掉新建的那些，并把旧的写回去。只留新建的 id 会让撤销留下两份墨，
     * 只留旧的会让撤销变成"什么都没发生"的反面（旧的不在了、新的还在）。
     */
    data class InkMoved(val moved: MovedInk) : ReaderUndo

    /**
     * 一次清掉多条画不出来的笔迹。撤销要把**每一条**的现场都写回去。
     *
     * 与 [Ink] 分开而不是合成一个 `List`：单笔擦除是用户的日常动作，
     * 批量清除是一次性的"清理"，两者的提示文案与失败语义都不一样
     * （一个回来就够，一个要数清楚回来了几条）。
     */
    data class InkBatch(val erased: List<ErasedInk>) : ReaderUndo
}
