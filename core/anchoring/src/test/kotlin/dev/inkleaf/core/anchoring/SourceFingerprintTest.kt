package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.NormalizationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹的职责只有一条：**内容或口径一变就必须变**。
 * 它变了的后果是全库笔记进入孤儿态（可重新挂接）；
 * 它该变却没变的后果是把错位的锚点当有效锚点用（不可发现的数据损坏）。
 * 所以这里的每个用例都在往"宁可误判为变了"的方向钉。
 */
class SourceFingerprintTest {

    private val profile = NormalizationProfile()
    private val text = "第一章\n\n这是正文。"

    @Test
    fun `指纹是十六进制且长度固定`() {
        val fp = SourceFingerprint.compute(text, profile, blockCount = 2)
        assertEquals(16, fp.length)
        assertTrue("必须是十六进制：$fp", fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun `相同输入得到相同指纹`() {
        val a = SourceFingerprint.compute(text, profile, 2)
        val b = SourceFingerprint.compute(text, profile, 2)
        assertEquals(a, b)
    }

    @Test
    fun `正文变化即指纹变化`() {
        val a = SourceFingerprint.compute(text, profile, 2)
        val b = SourceFingerprint.compute(text + "多一个字", profile, 2)
        assertNotEquals(a, b)
    }

    @Test
    fun `口径变化即指纹变化`() {
        val a = SourceFingerprint.compute(text, profile, 2)
        val b = SourceFingerprint.compute(text, profile.copy(profileId = "nf:loose-2"), 2)
        assertNotEquals("换口径必须换指纹，否则规则 1 会误判为没变", a, b)
    }

    @Test
    fun `规则版本变化即指纹变化`() {
        val a = SourceFingerprint.compute(text, profile, 2)
        val b = SourceFingerprint.compute(text, profile.copy(version = profile.version + 1), 2)
        assertNotEquals("归一化规则改了必须让全库锚点重算", a, b)
    }

    @Test
    fun `块数变化即指纹变化`() {
        // "3 段合并成 1 段" 可能正文不变而结构大变 → 必须能被发现
        val a = SourceFingerprint.compute(text, profile, 2)
        val b = SourceFingerprint.compute(text, profile, 3)
        assertNotEquals(a, b)
    }

    @Test
    fun `分隔符防止字段边界歧义`() {
        // profileId="a" version="1" 与 profileId="a1" version="" 不应撞车
        val a = SourceFingerprint.compute("x", profile.copy(profileId = "a", version = 12), 1)
        val b = SourceFingerprint.compute("x", profile.copy(profileId = "a1", version = 2), 1)
        assertNotEquals(a, b)
    }

    @Test
    fun `空文本也有稳定指纹`() {
        val a = SourceFingerprint.compute("", profile, 0)
        val b = SourceFingerprint.compute("", profile, 0)
        assertEquals(a, b)
        assertEquals(16, a.length)
    }

    @Test
    fun `非 BMP 字符参与指纹`() {
        val a = SourceFingerprint.compute("𠮷", profile, 1)
        val b = SourceFingerprint.compute("吉", profile, 1)
        assertNotEquals(a, b)
    }
}
