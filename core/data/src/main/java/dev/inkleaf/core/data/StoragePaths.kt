package dev.inkleaf.core.data

import android.content.Context
import java.io.File

/**
 * 私有目录布局。
 *
 * 方案 §7.1 的 L1 层。这里只负责"文件放哪里、怎么安全地拼路径"，
 * 不涉及数据库。
 *
 * 一条安全规则：**所有对外暴露的路径都是相对路径，拼接时必须拒绝目录穿越**。
 * 相对路径持久化进数据库后，将来即使把存储根目录改到 SAF 或外部位置，
 * 历史数据依然可用；而绝对路径一旦换用户/换设备就全部失效。
 */
class StoragePaths(context: Context) {

    private val root: File = context.applicationContext.filesDir

    /**
     * 缓存根目录。必须在这里取一次并存下来，而不是每处 `get()` 里再调
     * `context.applicationContext` —— 构造函数参数（非 `val`）只在初始化阶段可见，
     * 在属性 getter 里根本引用不到它。
     */
    private val cacheRoot: File = context.applicationContext.cacheDir

    /** 文档正文副本。 */
    val documentsDir: File get() = ensureDir(File(root, "documents"))

    /** 外置的大 BLOB（> 64KB 的笔迹点位）。 */
    val blobsDir: File get() = ensureDir(File(root, "blobs"))

    /** 封面等派生资源。 */
    val derivedDir: File get() = ensureDir(File(root, "derived"))

    /**
     * 导出的临时目录。
     *
     * 刻意放在 `cacheDir` 而不是 `filesDir`：导出产物是"用户主动拿走"的东西，
     * 拿走之后留在私有目录里只是白占空间；系统在空间紧张时清理它也正合期望。
     */
    val exportsDir: File get() = ensureDir(File(cacheRoot, "exports"))

    fun documentsRoot(): File = documentsDir

    /** 解析文档相对路径。非法路径抛异常，绝不"尽力而为"。 */
    fun resolveDocument(relativePath: String): File = resolveSafely(documentsDir, relativePath)

    fun resolveBlob(relativePath: String): File = resolveSafely(blobsDir, relativePath)

    fun resolveDerived(relativePath: String): File = resolveSafely(derivedDir, relativePath)

    /**
     * 安全拼接。
     *
     * 数据库里的相对路径是"我们写进去的"，但导入的 zip 存档里的路径**是外部输入**。
     * 不校验的话，一个 `../../databases/inkleaf.db` 就能让导入操作覆盖数据库本身。
     */
    fun resolveSafely(base: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "相对路径不能为空" }
        require(!relativePath.contains("..")) { "相对路径不得包含 ..：$relativePath" }
        require(!relativePath.startsWith("/") && !relativePath.startsWith("\\")) {
            "相对路径不得以分隔符开头：$relativePath"
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(relativePath)) {
            "相对路径不得包含盘符：$relativePath"
        }
        val basePath = base.canonicalFile
        val target = File(base, relativePath).canonicalFile
        require(target.path.startsWith(basePath.path)) {
            "解析后的路径越出了存储根目录：$relativePath"
        }
        return target
    }

    /** 为一个新文档分配一个不会被猜到的相对路径（避免不同文档互相覆盖）。 */
    fun newDocumentRelativePath(docId: String, extension: String): String {
        val safeExt = extension.trimStart('.').lowercase().takeIf { it.isNotBlank() } ?: "txt"
        return "doc_$docId.$safeExt"
    }

    fun newBlobRelativePath(blobId: String): String = "blob_$blobId.ink"

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
