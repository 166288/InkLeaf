package dev.inkleaf.core.document

import dev.inkleaf.core.model.PdfBackend
import dev.inkleaf.core.model.PdfBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 探测器的测试。
 *
 * 诚实地说：**这里测不了真正要看的东西**。S Extensions 的版本是设备的属性
 * （由 Google Play 系统更新投递），Robolectric 既不模拟它，也没有"低档设备"
 * 可切换 —— 三档里到底落在哪一档，只有真机能回答（这也是方案 §11 里那条
 * 取证待办：`adb shell getprop | grep build.version.extensions`）。
 *
 * 所以这里只钉两件在本机能钉死的事：
 *  1. 探测**永不抛异常**（厂商 ROM 上读扩展版本可能失败，失败的后果必须是
 *     "降档"，不是"崩溃"）；
 *  2. 探测结果与政策函数一致 —— 也就是"读到的数字怎么解释"没有第二套说法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfBackendProbeTest {

    @Test
    fun `探测不会抛异常且总是给出一个合法的档位`() {
        val backend = PdfBackendProbe.current()

        assertTrue("必须是三档之一", backend in PdfBackend.entries)
        assertTrue("扩展版本不许是负数", PdfBackendProbe.sExtensionVersion() >= 0)
    }

    @Test
    fun `探测结果与政策函数逐位一致`() {
        // 如果哪天有人在探测器里另写一套版本判断，这条会先失败，
        // 而不是让"某个入口说能选中、另一个说不能"这种症状流到用户面前。
        val expected = PdfBackendPolicy.of(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            sExtensionVersion = PdfBackendProbe.sExtensionVersion(),
        )

        assertEquals(expected, PdfBackendProbe.current())
    }
}
