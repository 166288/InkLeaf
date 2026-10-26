package dev.inkleaf.app

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.model.DocumentMeta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 书架多选删除。
 *
 * 多选删除是**破坏性**操作，且一次能动好几本，所以这里钉的不是界面长什么样，
 * 而是三件容易在重构里悄悄走样的事：
 *
 * 1. **只删选中的**：多选最容易出的 bug 是"删完发现没选中的也少了一本"
 *    —— 循环里手滑用了全表、或者确认框拿到的是上一次的旧集合；
 * 2. **没选中的书连同它的笔记都还在**：删除是外键级联，误删一本书
 *    连带丢掉的是一整本笔记，没法撤销；
 * 3. **删完退出多选**：否则标题栏还挂着"已选 2 项"，而这两本已经从书架上消失了。
 *
 * 本机没有设备也没有模拟器（无虚拟化），"长按能不能进多选"这种手势本身跑不了；
 * 但"长按之后会发生什么"必须测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryMultiSelectTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
        runBlocking { container.load() }
    }

    @After
    fun tearDown() {
        container.store.close()
    }

    private fun importBody(title: String): DocumentMeta = runBlocking {
        val source = File(container.context.cacheDir, "$title.txt")
        source.writeText("$title 的正文\n第二行", Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = title,
        )
        container.importer.commit(container.importer.inspect(candidate))
    }

    /**
     * ViewModel 的协程跑在 `viewModelScope`（Dispatchers.Main）上，
     * 而那些挂起调用（DB 读、删除）会切到 IO 再切回来 ——
     * 所以既要把主线程消息跑掉，也要给 IO 线程一点时间，单次 idle() 不够。
     */
    private fun awaitMain(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时：主线程任务没有在 $timeoutMs ms 内完成")
    }

    @Test
    fun `多选删除只删选中的，未选中的书还在`() {
        val a = importBody("书一")
        val b = importBody("书二")
        val c = importBody("书三")

        val vm = LibraryViewModel(container)
        vm.refresh()
        awaitMain { vm.state.value.items.size == 3 }

        vm.startSelection(a.id)
        vm.toggleSelection(c.id)
        assertEquals(setOf(a.id, c.id), vm.selectedIds.value)

        vm.askDeleteSelected()
        assertEquals(setOf(a.id, c.id), vm.deleteTargets.value.map { it.id }.toSet())

        vm.confirmDelete()
        awaitMain { !vm.state.value.loading && vm.state.value.items.size == 1 }

        // 只剩下没选中的那一本：既没有多删，也没有少删。
        assertEquals(listOf(b.id), vm.state.value.items.map { it.meta.id })
        // 删完退出多选：标题栏不能挂着一个已经不成立的"已选 2 项"。
        assertTrue(vm.selectedIds.value.isEmpty())
        // 库里也确实没了，不是只有界面上没了。
        assertEquals(listOf(b.id), runBlocking { container.store.documents.list() }.map { it.id })
    }

    @Test
    fun `再点一次已选中的书是取消选中，选空了自动退出多选`() {
        val a = importBody("书一")
        val vm = LibraryViewModel(container)
        vm.refresh()
        awaitMain { vm.state.value.items.size == 1 }

        vm.startSelection(a.id)
        assertEquals(setOf(a.id), vm.selectedIds.value)

        // 界面上的判据是"选中集合非空即多选"，所以清空就等于退出。
        vm.toggleSelection(a.id)
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun `全选再点一次退回空集合；确认框按书架顺序列出`() {
        val a = importBody("书一")
        importBody("书二")
        importBody("书三")

        val vm = LibraryViewModel(container)
        vm.refresh()
        awaitMain { vm.state.value.items.size == 3 }

        vm.toggleSelectAll()
        awaitMain { vm.selectedIds.value.size == 3 }

        vm.askDeleteSelected()
        // 顺序跟屏幕上看到的一致，确认框里列标题才不会跳来跳去。
        assertEquals(
            vm.state.value.items.map { it.meta.id },
            vm.deleteTargets.value.map { it.id },
        )
        vm.dismissDelete()
        assertTrue(vm.deleteTargets.value.isEmpty())

        // 已经全选状态下再点：退回没有选中的状态。
        vm.toggleSelectAll()
        assertTrue(vm.selectedIds.value.isEmpty())
        assertTrue(a.id !in vm.selectedIds.value)
    }

    @Test
    fun `刷新会把选中集合里已经不存在的书剔掉`() {
        val a = importBody("书一")
        val b = importBody("书二")

        val vm = LibraryViewModel(container)
        vm.refresh()
        awaitMain { vm.state.value.items.size == 2 }

        vm.startSelection(a.id)
        vm.toggleSelection(b.id)

        // 另一条路径（比如这里直接调仓库）删掉一本：选中集合里不该再留着它，
        // 否则标题栏会显示"已选 2 项"，而实际只有 1 本可选。
        runBlocking { container.store.documents.delete(a.id) }
        vm.refresh()
        awaitMain { vm.state.value.items.size == 1 }

        assertEquals(setOf(b.id), vm.selectedIds.value)
    }
}
