@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.ui.keyboard

import com.ibm.icu.util.ULocale
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.service.LanguageInfo
import com.urik.keyboard.service.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

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
    private lateinit var viewModel: KeyboardViewModel
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var languageFlow: MutableStateFlow<LanguageInfo?>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        languageManager = mock()

        languageFlow =
            MutableStateFlow(
                LanguageInfo(
                    languageTag = "en",
                    displayName = "English",
                    nativeName = "English",
                    isActive = true,
                    isPrimary = true,
                ),
            )
        whenever(languageManager.currentLanguage).thenReturn(languageFlow)

        runTest {
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))
        }

        viewModel = KeyboardViewModel(repository, languageManager)
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
    fun `shouldAutoCapitalize returns true after period`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello."))
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
    fun `shouldAutoCapitalize returns true after exclamation`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello!"))
    }

    @Test
    fun `shouldAutoCapitalize returns true after exclamation with space`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello! "))
    }

    @Test
    fun `shouldAutoCapitalize returns true after question mark`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello?"))
    }

    @Test
    fun `shouldAutoCapitalize returns true after question mark with space`() {
        assertTrue(viewModel.shouldAutoCapitalize("Hello? "))
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
    fun `shouldAutoCapitalize handles period at start`() {
        assertTrue(viewModel.shouldAutoCapitalize("."))
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
    fun `checkAndApplyAutoCapitalization enables shift after period`() {
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
    fun `checkAndApplyAutoCapitalization does not override caps lock`() =
        runTest {
            viewModel.onEvent(KeyboardEvent.CapsLockToggled)

            viewModel.checkAndApplyAutoCapitalization("Hello. ")

            assertTrue(viewModel.state.value.isCapsLockOn)
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
            languageFlow.value =
                LanguageInfo(
                    languageTag = "sv",
                    displayName = "Swedish",
                    nativeName = "Svenska",
                    isActive = true,
                    isPrimary = true,
                )
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
    fun `layout loading defaults to en when language null`() =
        runTest {
            languageFlow.value = null
            whenever(repository.getLayoutForMode(any(), any(), any()))
                .thenReturn(Result.success(createMockLayout(KeyboardMode.LETTERS)))

            verify(repository, atLeastOnce()).getLayoutForMode(
                any(),
                eq(ULocale.forLanguageTag("en")),
                any(),
            )
        }

    @Test
    fun `getCurrentLocale extracts language from en-US`() =
        runTest {
            languageFlow.value =
                LanguageInfo(
                    languageTag = "en-US",
                    displayName = "English (US)",
                    nativeName = "English",
                    isActive = true,
                    isPrimary = true,
                )
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
            languageFlow.value =
                LanguageInfo(
                    languageTag = "sv-SE",
                    displayName = "Swedish",
                    nativeName = "Svenska",
                    isActive = true,
                    isPrimary = true,
                )
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
            languageFlow.value =
                LanguageInfo(
                    languageTag = "en",
                    displayName = "English",
                    nativeName = "English",
                    isActive = true,
                    isPrimary = true,
                )
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

    private fun createMockLayout(mode: KeyboardMode): KeyboardLayout =
        KeyboardLayout(
            mode = mode,
            rows = emptyList(),
        )
}
