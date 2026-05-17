@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.settings.layoutmapper

import com.urik.keyboard.data.CustomKeyMappingRepository
import com.urik.keyboard.data.database.CustomKeyMapping
import com.urik.keyboard.service.CustomKeyMappingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests [LayoutMapperViewModel] state management and repository interactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LayoutMapperViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: CustomKeyMappingRepository
    private lateinit var mappingsFlow: MutableStateFlow<List<CustomKeyMapping>>
    private lateinit var viewModel: LayoutMapperViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        repository = mock()
        mappingsFlow = MutableStateFlow(emptyList())

        whenever(repository.mappings).thenReturn(mappingsFlow)

        viewModel = LayoutMapperViewModel(repository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `mappings is empty initially`() {
        assertEquals(emptyMap<String, List<String>>(), viewModel.mappings.value)
    }

    @Test
    fun `mappings updates when repository emits single symbol`() = runTest {
        mappingsFlow.value =
            listOf(
                CustomKeyMapping.create("a", "@"),
                CustomKeyMapping.create("b", "#")
            )

        val result = viewModel.mappings.first()

        assertEquals(2, result.size)
        assertEquals(listOf("@"), result["a"])
        assertEquals(listOf("#"), result["b"])
    }

    @Test
    fun `mappings updates when repository emits multi-symbol delimiter value`() = runTest {
        val raw = "ǔ${CustomKeyMappingService.LONG_PRESS_DELIMITER}ū"
        mappingsFlow.value = listOf(CustomKeyMapping.create("u", raw))

        val result = viewModel.mappings.first()

        assertEquals(listOf("ǔ", "ū"), result["u"])
    }

    @Test
    fun `selectedKey is null initially`() {
        assertNull(viewModel.selectedKey.value)
    }

    @Test
    fun `selectKey sets selectedKey in lowercase`() {
        viewModel.selectKey("A")

        assertEquals("a", viewModel.selectedKey.value)
    }

    @Test
    fun `clearSelection sets selectedKey to null`() {
        viewModel.selectKey("a")
        viewModel.clearSelection()

        assertNull(viewModel.selectedKey.value)
    }

    @Test
    fun `getMappingForKey returns list for mapped key`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        viewModel.mappings.first()

        assertEquals(listOf("@"), viewModel.getMappingForKey("a"))
    }

    @Test
    fun `getMappingForKey is case insensitive`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        viewModel.mappings.first()

        assertEquals(listOf("@"), viewModel.getMappingForKey("A"))
    }

    @Test
    fun `getMappingForKey returns null for unmapped key`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        viewModel.mappings.first()

        assertNull(viewModel.getMappingForKey("b"))
    }

    @Test
    fun `saveMapping encodes single symbol to repository`() = runTest {
        whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))

        viewModel.saveMapping("u", "ǔ")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).setMapping("u", "ǔ")
    }

    @Test
    fun `saveMapping encodes space-separated symbols as delimiter-joined string`() = runTest {
        whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))
        val captor = argumentCaptor<String>()

        viewModel.saveMapping("u", "ǔ ū")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).setMapping(any(), captor.capture())
        val stored = captor.firstValue
        val parts = stored.split(CustomKeyMappingService.LONG_PRESS_DELIMITER)
        assertEquals(listOf("ǔ", "ū"), parts)
    }

    @Test
    fun `saveMapping NFC-normalizes symbols before storage`() = runTest {
        whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))
        val captor = argumentCaptor<String>()
        // decomposed é (e + combining acute)
        val decomposed = "é"

        viewModel.saveMapping("e", decomposed)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).setMapping(any(), captor.capture())
        // stored value should be NFC precomposed é (U+00E9)
        assertEquals("é", captor.firstValue)
    }

    @Test
    fun `saveMapping trims whitespace around each symbol`() = runTest {
        whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))
        val captor = argumentCaptor<String>()

        viewModel.saveMapping("u", "  ǔ  ū  ")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).setMapping(any(), captor.capture())
        val parts = captor.firstValue.split(CustomKeyMappingService.LONG_PRESS_DELIMITER)
        assertEquals(listOf("ǔ", "ū"), parts)
    }

    @Test
    fun `saveMapping clears selection on success`() = runTest {
        whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))
        viewModel.selectKey("a")

        viewModel.saveMapping("a", "@")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.selectedKey.value)
    }

    @Test
    fun `saveMapping does not clear selection on failure`() = runTest {
        whenever(repository.setMapping(any(), any()))
            .thenReturn(Result.failure(Exception("Test error")))
        viewModel.selectKey("a")

        viewModel.saveMapping("a", "@")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("a", viewModel.selectedKey.value)
    }

    @Test
    fun `saveMapping with blank symbol calls removeMapping instead`() = runTest {
        whenever(repository.removeMapping(any())).thenReturn(Result.success(true))

        viewModel.saveMapping("a", "   ")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).removeMapping("a")
        verify(repository, never()).setMapping(any(), any())
    }

    @Test
    fun `saveMapping with empty symbol calls removeMapping instead`() = runTest {
        whenever(repository.removeMapping(any())).thenReturn(Result.success(true))

        viewModel.saveMapping("a", "")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).removeMapping("a")
        verify(repository, never()).setMapping(any(), any())
    }

    @Test
    fun `saveMapping deduplicates NFC-equivalent inputs before applying cap`() = runTest {
        whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))
        val captor = argumentCaptor<String>()
        // 4 distinct inputs + 1 NFC-duplicate of the first → should store 4 not 5
        val precomposed = "é"
        val decomposed = "é"
        viewModel.saveMapping("e", "$precomposed ā ǎ à $decomposed")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).setMapping(any(), captor.capture())
        val parts = captor.firstValue.split(CustomKeyMappingService.LONG_PRESS_DELIMITER)
        assertEquals(4, parts.size)
        assertEquals(precomposed, parts[0])
    }

    @Test
    fun `removeMapping calls repository removeMapping`() = runTest {
        whenever(repository.removeMapping(any())).thenReturn(Result.success(true))

        viewModel.removeMapping("a")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).removeMapping("a")
    }

    @Test
    fun `removeMapping clears selection on success`() = runTest {
        whenever(repository.removeMapping(any())).thenReturn(Result.success(true))
        viewModel.selectKey("a")

        viewModel.removeMapping("a")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.selectedKey.value)
    }

    @Test
    fun `removeMapping does not clear selection on failure`() = runTest {
        whenever(repository.removeMapping(any()))
            .thenReturn(Result.failure(Exception("Test error")))
        viewModel.selectKey("a")

        viewModel.removeMapping("a")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("a", viewModel.selectedKey.value)
    }

    @Test
    fun `clearAllMappings calls repository clearAllMappings`() = runTest {
        whenever(repository.clearAllMappings()).thenReturn(Result.success(5))

        viewModel.clearAllMappings()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(repository).clearAllMappings()
    }
}
