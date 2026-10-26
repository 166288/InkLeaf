package dev.inkleaf.core.document

import android.os.Build
import android.os.ext.SdkExtensions
import dev.inkleaf.core.model.PdfBackend
import dev.inkleaf.core.model.PdfBackendPolicy

/**
 * 读本机的 PDF 能力档（方案 §5.4 的三档探测，M5 第 1 天就要做的那件事）。
 *
 * 为什么必须"第 1 天就做"：PDF 的能力**不由 Android 版本决定**，而由
 * S Extensions 决定，而 S Extensions 是经 Google Play 系统更新投递的 ——
 * 一台 Android 13 的平板可能拿到 S Ext 18，也可能只有 S Ext 8。
 * 在写完整个 PDF 路径之后才发现目标设备落在最低档，返工面会大很多。
 *
 * 这里刻意只做**探测**：判定规则本身（`>= 18` / `>= 13`）在
 * [PdfBackendPolicy] 里，是纯函数、有测试；本对象只负责把平台上的两个数字读出来。
 * 这样"读到的值对不对"只能在真机上验证，而"读到的值怎么解释"在本机就能钉死。
 */
object PdfBackendProbe {

    /** 本机能力档。 */
    fun current(): PdfBackend = PdfBackendPolicy.of(
        sdkInt = Build.VERSION.SDK_INT,
        sExtensionVersion = sExtensionVersion(),
    )

    /**
     * 本机的 S Extensions 版本；读不到时返回 0（等价于"没有这个能力"）。
     *
     * `getExtensionVersion` 对非法参数抛 `IllegalArgumentException` 而不是返回 0，
     * 而且厂商 ROM 上的行为未必与 AOSP 一致。**探测没有资格让应用崩溃**：
     * 探测失败最坏的结果是"用户少了一个能力"，而不是"应用打不开"。
     */
    fun sExtensionVersion(): Int = runCatching {
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)
    }.getOrDefault(0)
}
