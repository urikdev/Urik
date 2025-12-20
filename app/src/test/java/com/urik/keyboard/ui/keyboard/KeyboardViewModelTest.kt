@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard

import com.ibm.icu.util.ULocale
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.service.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests KeyboardViewModel state management and event handling.
 *
 * Verifies auto-capitalization, shift/caps lock behavior, character input,
 * mode switching, layout loading, and locale handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardViewModelTest {
    private lateinit var repository: KeyboardRepository
    private lateinit var languageManager: LanguageManager
    private lateinit var themeManager: com.urik.keyboard.theme.ThemeManager
    private lateinit var viewModel: KeyboardViewModel
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var languageFlow: MutableStateFlow<String>
    private lateinit var layoutLanguageFlow: MutableStateFlow<String>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        languageManager = mock()
        themeManager = mock()

        languageFlow = MutableStateFlow("en")
        whenever(languageManager.currentLanguage).thenReturn(languageFlow)

        layoutLanguageFlow = MutableStateFlow("en")
        whenever(languageManager.currentLayoutLanguage).thenReturn(layoutLanguageFlow)

        val activeLanguagesFlow = MutableStateFlow(listOf("en"))
        whenever(languageManager.activeLanguages).thenReturn(activeLanguagesFlow)

        val themeFlow = MutableStateFlow<com.urik.keyboard.theme.KeyboardTheme>(com.urik.keyboard.theme.Default)
        whenever(themeManager.currentTheme).thenReturn(themeFlow)

        runTest {
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))
        }

        viewModel = KeyboardViewModel(repository, languageManager, themeManager)
    }

    @Test
    fun `shouldAutoCapitalize returns true for null text`() {
        assertTrue(viewModel.shouldAutoCapitalize(null))
    }

    @Test
    fun `shouldAutoCapitalize returns true for empty text`() {
        assertTrue(viewModel.shouldAutoCapitalize(""))
    }

    @Test
    fun `shouldAutoCapitalize returns true for blank text`() {
        assertTrue(viewModel.shouldAutoCapitalize("   "))
    }

    @Test
    fun `shouldAutoCapitalize returns false after period without space`() {
        assertFalse(viewModel.shouldAutoCapitalize("Hello."))
    }

    @Test
    fun `shouldAutoCapitalize returns true after period with space`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello. "))
    }

    @Test
    fun `shouldAutoCapitalize returns true after period with multiple spaces`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello.   "))
    }

    @Test
    fun `shouldAutoCapitalize returns false after exclamation without space`() {
        assertFalse(viewModel.shouldAutoCapitalize("Hello!"))
    }

    @Test
    fun `shouldAutoCapitalize returns true after exclamation with space`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello! "))
    }

    @Test
    fun `shouldAutoCapitalize returns false after question mark without space`() {
        assertFalse(viewModel.shouldAutoCapitalize("Hello?"))
    }

    @Test
    fun `shouldAutoCapitalize returns true after question mark with space`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello? "))
    }

    @Test
    fun `shouldAutoCapitalize returns false for email addresses`() {
        assertFalse(viewModel.shouldAutoCapitalize("user@example."))
    }

    @Test
    fun `shouldAutoCapitalize returns false for file extensions`() {
        assertFalse(viewModel.shouldAutoCapitalize("file."))
    }

    @Test
    fun `shouldAutoCapitalize returns false for URLs`() {
        assertFalse(viewModel.shouldAutoCapitalize("example."))
    }

    @Test
    fun `shouldAutoCapitalize returns false for decimals`() {
        assertFalse(viewModel.shouldAutoCapitalize("3."))
    }

    @Test
    fun `shouldAutoCapitalize returns false mid-sentence`() {
        assertFalse(viewModel.shouldAutoCapitalize("Hello world"))
    }

    @Test
    fun `shouldAutoCapitalize returns false after comma`() {
        assertFalse(viewModel.shouldAutoCapitalize("Hello, "))
    }

    @Test
    fun `shouldAutoCapitalize returns false with text after punctuation`() {
        assertFalse(viewModel.shouldAutoCapitalize("Hello. World"))
    }

    @Test
    fun `shouldAutoCapitalize handles leading whitespace`() {
        assertTrue(viewModel.shouldAutoCapitalize("  "))
    }

    @Test
    fun `shouldAutoCapitalize returns false for period at start without space`() {
        assertFalse(viewModel.shouldAutoCapitalize("."))
    }

    @Test
    fun `shouldAutoCapitalize returns true for period with trailing space`() {
        assertTrue(viewModel.shouldAutoCapitalize(". "))
    }

    @Test
    fun `getCharacterForInput returns lowercase when shift off`() {
        val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)

        val result = viewModel.getCharacterForInput(key)

        assertEquals("a", result)
    }

    @Test
    fun `getCharacterForInput returns uppercase when shift on`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)

            val result = viewModel.getCharacterForInput(key)

            assertEquals("A", result)
        }

    @Test
    fun `getCharacterForInput returns uppercase when caps lock on`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)
            val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)

            val result = viewModel.getCharacterForInput(key)

            assertEquals("A", result)
        }

    @Test
    fun `getCharacterForInput returns uppercase when both shift and caps lock on`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)

            val result = viewModel.getCharacterForInput(key)

            assertEquals("A", result)
        }

    @Test
    fun `getCharacterForInput preserves non-letter characters`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            val key = KeyboardKey.Character("1", KeyboardKey.KeyType.NUMBER)

            val result = viewModel.getCharacterForInput(key)

            assertEquals("1", result)
        }

    @Test
    fun `getCharacterForInput preserves punctuation with shift`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            val key = KeyboardKey.Character(".", KeyboardKey.KeyType.PUNCTUATION)

            val result = viewModel.getCharacterForInput(key)

            assertEquals(".", result)
        }

    @Test
    fun `clearShiftAfterCharacter clears shift for letter when shift on`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)

            viewModel.clearShiftAfterCharacter(key)

            assertFalse(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `clearShiftAfterCharacter does not clear shift when caps lock on`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)

            viewModel.clearShiftAfterCharacter(key)

            assertTrue(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `clearShiftAfterCharacter does not affect non-letter keys`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            val key = KeyboardKey.Character("1", KeyboardKey.KeyType.NUMBER)

            viewModel.clearShiftAfterCharacter(key)

            assertTrue(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `clearShiftAfterCharacter when shift already off`() =
        runTest {
            val key = KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER)

            viewModel.clearShiftAfterCharacter(key)

            assertFalse(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `initial state has shift off`() {
        assertFalse(viewModel.state.value.isShiftPressed)
    }

    @Test
    fun `ShiftStateChanged toggles shift on`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))

            assertTrue(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `ShiftStateChanged toggles shift off`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))

            assertFalse(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `enableAutoCapitalization sets shift on`() {
        viewModel.enableAutoCapitalization()

        assertTrue(viewModel.state.value.isShiftPressed)
    }

    @Test
    fun `initial state has caps lock off`() {
        assertFalse(viewModel.state.value.isCapsLockOn)
    }

    @Test
    fun `CapsLockToggled turns caps lock on`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)

            assertTrue(viewModel.state.value.isCapsLockOn)
        }

    @Test
    fun `CapsLockToggled turns caps lock off`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)

            assertFalse(viewModel.state.value.isCapsLockOn)
        }

    @Test
    fun `CapsLockToggled clears shift state`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)

            assertFalse(viewModel.state.value.isShiftPressed)
            assertTrue(viewModel.state.value.isCapsLockOn)
        }

    @Test
    fun `caps lock persists across shift toggles`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
            viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))

            assertTrue(viewModel.state.value.isCapsLockOn)
        }

    @Test
    fun `checkAndApplyAutoCapitalization enables shift after period with space`() {
        viewModel.checkAndApplyAutoCapitalization("Hello. ")

        assertTrue(viewModel.state.value.isShiftPressed)
    }

    @Test
    fun `checkAndApplyAutoCapitalization enables shift for empty text`() {
        viewModel.checkAndApplyAutoCapitalization("")

        assertTrue(viewModel.state.value.isShiftPressed)
    }

    @Test
    fun `checkAndApplyAutoCapitalization does not enable shift mid-sentence`() {
        viewModel.checkAndApplyAutoCapitalization("Hello world")

        assertFalse(viewModel.state.value.isShiftPressed)
    }

    @Test
    fun `checkAndApplyAutoCapitalization does not enable shift after period without space`() {
        viewModel.checkAndApplyAutoCapitalization("example.")

        assertFalse(viewModel.state.value.isShiftPressed)
    }

    @Test
    fun `checkAndApplyAutoCapitalization preserves caps lock after sentence end`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)

            viewModel.checkAndApplyAutoCapitalization("Hello. ")

            assertTrue(viewModel.state.value.isCapsLockOn)
            assertFalse(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `checkAndApplyAutoCapitalization preserves caps lock mid-sentence`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)

            viewModel.checkAndApplyAutoCapitalization("HELLO ")

            assertTrue(viewModel.state.value.isCapsLockOn)
            assertFalse(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `disableCapsLockAfterPunctuation turns off caps lock and enables shift`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)

            viewModel.disableCapsLockAfterPunctuation()

            assertFalse(viewModel.state.value.isCapsLockOn)
            assertTrue(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `disableCapsLockAfterPunctuation does nothing when caps lock is off`() =
        runTest {
            viewModel.disableCapsLockAfterPunctuation()

            assertFalse(viewModel.state.value.isCapsLockOn)
            assertFalse(viewModel.state.value.isShiftPressed)
        }

    @Test
    fun `initial mode is LETTERS`() {
        assertEquals(KeyboardMode.LETTERS, viewModel.state.value.currentMode)
    }

    @Test
    fun `ModeChanged switches to NUMBERS`() =
        runTest {
            whenever(repository.getLayoutForMode(eq(KeyboardMode.NUMBERS), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.NUMBERS)))

            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.NUMBERS))

            assertEquals(KeyboardMode.NUMBERS, viewModel.state.value.currentMode)
            assertEquals(KeyboardMode.NUMBERS, viewModel.layout.value?.mode)
        }

    @Test
    fun `ModeChanged switches to SYMBOLS`() =
        runTest {
            whenever(repository.getLayoutForMode(eq(KeyboardMode.SYMBOLS), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.SYMBOLS)))

            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.SYMBOLS))

            assertEquals(KeyboardMode.SYMBOLS, viewModel.state.value.currentMode)
            assertEquals(KeyboardMode.SYMBOLS, viewModel.layout.value?.mode)
        }

    @Test
    fun `ModeChanged back to LETTERS`() =
        runTest {
            whenever(repository.getLayoutForMode(eq(KeyboardMode.NUMBERS), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.NUMBERS)))
            whenever(repository.getLayoutForMode(eq(KeyboardMode.LETTERS), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))

            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.NUMBERS))
            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))

            assertEquals(KeyboardMode.LETTERS, viewModel.state.value.currentMode)
        }

    @Test
    fun `ModeChanged with same mode does not reload`() =
        runTest {
            clearInvocations(repository)

            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))

            verify(repository, never()).getLayoutForMode(any(), any(), any())
        }

    @Test
    fun `layout loads successfully on init`() {
        assertNotNull(viewModel.layout.value)
        assertEquals(KeyboardMode.LETTERS, viewModel.layout.value?.mode)
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `layout loading handles failure`() =
        runTest {
            whenever(repository.getLayoutForMode(eq(KeyboardMode.NUMBERS), any(), any()))
                .thenReturn(Result.failure(Exception("Test error")))

            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.NUMBERS))

            assertFalse(viewModel.state.value.isLoading)
            assertNotNull(viewModel.state.value.error)
            assertTrue(
                viewModel.state.value.error!!
                    .contains("Test error"),
            )
        }

    @Test
    fun `layout loading uses current locale`() =
        runTest {
            layoutLanguageFlow.value = "sv"
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))

            viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))

            verify(repository).getLayoutForMode(
                eq(KeyboardMode.LETTERS),
                eq(ULocale.forLanguageTag("sv")),
                any(),
            )
        }

    @Test
    fun `getCurrentLocale extracts language from en-US`() =
        runTest {
            languageFlow.value = "en-US"
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))

            verify(repository, atLeastOnce()).getLayoutForMode(
                any(),
                eq(ULocale.forLanguageTag("en")),
                any(),
            )
        }

    @Test
    fun `getCurrentLocale extracts language from sv-SE`() =
        runTest {
            layoutLanguageFlow.value = "sv-SE"
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))

            verify(repository, atLeastOnce()).getLayoutForMode(
                any(),
                eq(ULocale.forLanguageTag("sv")),
                any(),
            )
        }

    @Test
    fun `getCurrentLocale handles simple language tag`() =
        runTest {
            languageFlow.value = "en"
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))

            verify(repository, atLeastOnce()).getLayoutForMode(
                any(),
                eq(ULocale.forLanguageTag("en")),
                any(),
            )
        }

    @Test
    fun `updateActionType reloads layout when action changes`() =
        runTest {
            clearInvocations(repository)
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))

            viewModel.updateActionType(KeyboardKey.ActionType.SEARCH)

            verify(repository).getLayoutForMode(
                eq(KeyboardMode.LETTERS),
                any(),
                eq(KeyboardKey.ActionType.SEARCH),
            )
        }

    @Test
    fun `updateActionType does not reload when action same`() =
        runTest {
            viewModel.updateActionType(KeyboardKey.ActionType.ENTER)
            clearInvocations(repository)

            viewModel.updateActionType(KeyboardKey.ActionType.ENTER)

            verify(repository, never()).getLayoutForMode(any(), any(), any())
        }

    @Test
    fun `layout contains RTL and script information for Arabic script`() {
        val rtlLayout =
            KeyboardLayout(
                mode = KeyboardMode.LETTERS,
                rows = emptyList(),
                isRTL = true,
                script = "Arab",
            )

        runTest {
            whenever(repository.getLayoutForMode(any(), any(), any())).thenReturn(Result.success(rtlLayout))
        }

        layoutLanguageFlow.value = "fa"

        val layout = viewModel.layout.value
        assertEquals(true, layout?.isRTL)
        assertEquals("Arab", layout?.script)
    }

    private fun createMockLayout(mode: KeyboardMode): KeyboardLayout =
        KeyboardLayout(
            mode = mode,
            rows = emptyList(),
        )
}
