package com.urik.keyboard

import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrikInputMethodServiceFilteredLayoutTest {
    private val letterRow1: List<KeyboardKey> =
        listOf(KeyboardKey.Character("q", KeyboardKey.KeyType.LETTER))
    private val letterRow2: List<KeyboardKey> =
        listOf(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))

    private fun numberRow(): List<KeyboardKey> =
        (0..9).map { KeyboardKey.Character(it.toString(), KeyboardKey.KeyType.NUMBER) }

    private fun flickRow(): List<KeyboardKey> = listOf(
        KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_NUMBERS),
        KeyboardKey.FlickKey("あ", "い", "う", "え", "お", KeyboardKey.KeyType.LETTER),
        KeyboardKey.FlickKey("か", "き", "く", "け", "こ", KeyboardKey.KeyType.LETTER),
        KeyboardKey.FlickKey("さ", "し", "す", "せ", "そ", KeyboardKey.KeyType.LETTER),
        KeyboardKey.FlickKey("た", "ち", "つ", "て", "と", KeyboardKey.KeyType.LETTER)
    )

    private fun lettersLayout(vararg rows: List<KeyboardKey>) =
        KeyboardLayout(mode = KeyboardMode.LETTERS, rows = rows.toList())

    private fun symbolsLayout(vararg rows: List<KeyboardKey>) =
        KeyboardLayout(mode = KeyboardMode.SYMBOLS, rows = rows.toList())

    @Test
    fun `showNumberRow=false and top row is NUMBER row drops row 0`() {
        val layout = lettersLayout(numberRow(), letterRow1, letterRow2)
        val result = computeFilteredLayout(layout, showNumberRow = false)
        assertEquals(2, result.rows.size)
        assertEquals(letterRow1, result.rows[0])
    }

    @Test
    fun `showNumberRow=false and top row is flick row keeps layout unchanged`() {
        val layout = lettersLayout(flickRow(), letterRow1)
        val result = computeFilteredLayout(layout, showNumberRow = false)
        assertEquals(2, result.rows.size)
        assertEquals(flickRow(), result.rows[0])
    }

    @Test
    fun `showNumberRow=false and top row has fewer than 10 number keys keeps layout unchanged`() {
        val partialNumberRow = (0..7).map { KeyboardKey.Character(it.toString(), KeyboardKey.KeyType.NUMBER) }
        val layout = lettersLayout(partialNumberRow, letterRow1)
        val result = computeFilteredLayout(layout, showNumberRow = false)
        assertEquals(2, result.rows.size)
        assertEquals(partialNumberRow, result.rows[0])
    }

    @Test
    fun `showNumberRow=true and top row is NUMBER row keeps layout unchanged`() {
        val layout = lettersLayout(numberRow(), letterRow1)
        val result = computeFilteredLayout(layout, showNumberRow = true)
        assertEquals(2, result.rows.size)
        assertEquals(numberRow(), result.rows[0])
    }

    @Test
    fun `LETTERS mode empty rows returns layout unchanged`() {
        val layout = lettersLayout()
        val result = computeFilteredLayout(layout, showNumberRow = false)
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `SYMBOLS mode injects number row at top regardless of showNumberRow=true`() {
        val symbolRow = listOf(KeyboardKey.Character("!", KeyboardKey.KeyType.SYMBOL))
        val layout = symbolsLayout(symbolRow)
        val result = computeFilteredLayout(layout, showNumberRow = true)
        assertEquals(2, result.rows.size)
        val injectedRow = result.rows[0]
        assertEquals(10, injectedRow.size)
        injectedRow.forEach { key ->
            assertTrue(key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.NUMBER)
        }
        assertEquals(symbolRow, result.rows[1])
    }

    @Test
    fun `SYMBOLS mode injects number row at top regardless of showNumberRow=false`() {
        val symbolRow = listOf(KeyboardKey.Character("!", KeyboardKey.KeyType.SYMBOL))
        val layout = symbolsLayout(symbolRow)
        val result = computeFilteredLayout(layout, showNumberRow = false)
        assertEquals(2, result.rows.size)
        val injectedRow = result.rows[0]
        assertEquals(10, injectedRow.size)
        injectedRow.forEach { key ->
            assertTrue(key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.NUMBER)
        }
    }

    @Test
    fun `SYMBOLS_SECONDARY mode injects number row at top`() {
        val symbolRow = listOf(KeyboardKey.Character("€", KeyboardKey.KeyType.SYMBOL))
        val layout = KeyboardLayout(mode = KeyboardMode.SYMBOLS_SECONDARY, rows = listOf(symbolRow))
        val result = computeFilteredLayout(layout, showNumberRow = true)
        assertEquals(2, result.rows.size)
        val injectedRow = result.rows[0]
        assertEquals(10, injectedRow.size)
        injectedRow.forEach { key ->
            assertTrue(key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.NUMBER)
        }
        assertEquals(symbolRow, result.rows[1])
    }
}
