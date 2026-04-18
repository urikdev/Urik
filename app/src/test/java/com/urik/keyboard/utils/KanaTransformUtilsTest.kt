package com.urik.keyboard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KanaTransformUtilsTest {
    // ── toDakuten ─────────────────────────────────────────────────────────────

    @Test
    fun `toDakuten か returns が`() = assertEquals('が', KanaTransformUtils.toDakuten('か'))

    @Test
    fun `toDakuten き returns ぎ`() = assertEquals('ぎ', KanaTransformUtils.toDakuten('き'))

    @Test
    fun `toDakuten さ returns ざ`() = assertEquals('ざ', KanaTransformUtils.toDakuten('さ'))

    @Test
    fun `toDakuten た returns だ`() = assertEquals('だ', KanaTransformUtils.toDakuten('た'))

    @Test
    fun `toDakuten は returns ば`() = assertEquals('ば', KanaTransformUtils.toDakuten('は'))

    @Test
    fun `toDakuten ん returns null`() = assertNull(KanaTransformUtils.toDakuten('ん'))

    @Test
    fun `toDakuten A returns null`() = assertNull(KanaTransformUtils.toDakuten('A'))

    // ── toHandakuten ──────────────────────────────────────────────────────────

    @Test
    fun `toHandakuten は returns ぱ`() = assertEquals('ぱ', KanaTransformUtils.toHandakuten('は'))

    @Test
    fun `toHandakuten ひ returns ぴ`() = assertEquals('ぴ', KanaTransformUtils.toHandakuten('ひ'))

    @Test
    fun `toHandakuten か returns null`() = assertNull(KanaTransformUtils.toHandakuten('か'))

    @Test
    fun `toHandakuten ん returns null`() = assertNull(KanaTransformUtils.toHandakuten('ん'))

    // ── toBase ────────────────────────────────────────────────────────────────

    @Test
    fun `toBase が returns か`() = assertEquals('か', KanaTransformUtils.toBase('が'))

    @Test
    fun `toBase ぱ returns は`() = assertEquals('は', KanaTransformUtils.toBase('ぱ'))

    @Test
    fun `toBase ば returns は`() = assertEquals('は', KanaTransformUtils.toBase('ば'))

    @Test
    fun `toBase か returns null`() = assertNull(KanaTransformUtils.toBase('か'))

    // ── cycleDakutenOnLast ────────────────────────────────────────────────────

    @Test
    fun `cycleDakutenOnLast unvoiced か becomes が`() = assertEquals("あが", KanaTransformUtils.cycleDakutenOnLast("あか"))

    @Test
    fun `cycleDakutenOnLast voiced が cycles to base か`() =
        assertEquals("あか", KanaTransformUtils.cycleDakutenOnLast("あが"))

    @Test
    fun `cycleDakutenOnLast は cycles to ば`() = assertEquals("ば", KanaTransformUtils.cycleDakutenOnLast("は"))

    @Test
    fun `cycleDakutenOnLast ば cycles to ぱ`() = assertEquals("ぱ", KanaTransformUtils.cycleDakutenOnLast("ば"))

    @Test
    fun `cycleDakutenOnLast ぱ cycles back to は`() = assertEquals("は", KanaTransformUtils.cycleDakutenOnLast("ぱ"))

    @Test
    fun `cycleDakutenOnLast ん unchanged`() = assertEquals("ん", KanaTransformUtils.cycleDakutenOnLast("ん"))

    @Test
    fun `cycleDakutenOnLast empty string unchanged`() = assertEquals("", KanaTransformUtils.cycleDakutenOnLast(""))

    // ── toSmallKana ───────────────────────────────────────────────────────────

    @Test
    fun `toSmallKana つ returns っ`() = assertEquals('っ', KanaTransformUtils.toSmallKana('つ'))

    @Test
    fun `toSmallKana や returns ゃ`() = assertEquals('ゃ', KanaTransformUtils.toSmallKana('や'))

    @Test
    fun `toSmallKana あ returns ぁ`() = assertEquals('ぁ', KanaTransformUtils.toSmallKana('あ'))

    @Test
    fun `toSmallKana ん returns null`() = assertNull(KanaTransformUtils.toSmallKana('ん'))

    // ── toLargeKana ───────────────────────────────────────────────────────────

    @Test
    fun `toLargeKana っ returns つ`() = assertEquals('つ', KanaTransformUtils.toLargeKana('っ'))

    @Test
    fun `toLargeKana ゃ returns や`() = assertEquals('や', KanaTransformUtils.toLargeKana('ゃ'))

    @Test
    fun `toLargeKana つ returns null`() = assertNull(KanaTransformUtils.toLargeKana('つ'))

    // ── toggleSmallKanaOnLast ─────────────────────────────────────────────────

    @Test
    fun `toggleSmallKanaOnLast つ becomes っ`() = assertEquals("あっ", KanaTransformUtils.toggleSmallKanaOnLast("あつ"))

    @Test
    fun `toggleSmallKanaOnLast っ becomes つ`() = assertEquals("あつ", KanaTransformUtils.toggleSmallKanaOnLast("あっ"))

    @Test
    fun `toggleSmallKanaOnLast ん unchanged`() = assertEquals("ん", KanaTransformUtils.toggleSmallKanaOnLast("ん"))

    @Test
    fun `toggleSmallKanaOnLast empty string unchanged`() =
        assertEquals("", KanaTransformUtils.toggleSmallKanaOnLast(""))
}
