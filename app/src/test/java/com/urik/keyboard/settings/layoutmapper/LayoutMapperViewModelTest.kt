@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.settings.layoutmapper

import com.urik.keyboard.data.CustomKeyMappingRepository
import com.urik.keyboard.data.database.CustomKeyMapping
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
        assertEquals(emptyMap<String, String>(), viewModel.mappings.value)
    }

    @Test
    fun `mappings updates when repository emits`() =
        runTest {
            mappingsFlow.value =
                listOf(
                    CustomKeyMapping.create("a", "@"),
                    CustomKeyMapping.create("b", "#"),
                )

            val result = viewModel.mappings.first()

            assertEquals(2, result.size)
            assertEquals("@", result["a"])
            assertEquals("#", result["b"])
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
    fun `getMappingForKey returns mapping from state`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            viewModel.mappings.first()

            val result = viewModel.getMappingForKey("a")

            assertEquals("@", result)
        }

    @Test
    fun `getMappingForKey is case insensitive`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            viewModel.mappings.first()

            val result = viewModel.getMappingForKey("A")

            assertEquals("@", result)
        }

    @Test
    fun `getMappingForKey returns null for unmapped key`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            viewModel.mappings.first()

            val result = viewModel.getMappingForKey("b")

            assertNull(result)
        }

    @Test
    fun `saveMapping calls repository setMapping`() =
        runTest {
            whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))

            viewModel.saveMapping("a", "@")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(repository).setMapping("a", "@")
        }

    @Test
    fun `saveMapping trims whitespace`() =
        runTest {
            whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))

            viewModel.saveMapping("a", "  @  ")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(repository).setMapping("a", "@")
        }

    @Test
    fun `saveMapping clears selection on success`() =
        runTest {
            whenever(repository.setMapping(any(), any())).thenReturn(Result.success(Unit))
            viewModel.selectKey("a")

            viewModel.saveMapping("a", "@")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.selectedKey.value)
        }

    @Test
    fun `saveMapping does not clear selection on failure`() =
        runTest {
            whenever(repository.setMapping(any(), any()))
                .thenReturn(Result.failure(Exception("Test error")))
            viewModel.selectKey("a")

            viewModel.saveMapping("a", "@")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("a", viewModel.selectedKey.value)
        }

    @Test
    fun `saveMapping with blank symbol calls removeMapping instead`() =
        runTest {
            whenever(repository.removeMapping(any())).thenReturn(Result.success(true))

            viewModel.saveMapping("a", "   ")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(repository).removeMapping("a")
            verify(repository, never()).setMapping(any(), any())
        }

    @Test
    fun `saveMapping with empty symbol calls removeMapping instead`() =
        runTest {
            whenever(repository.removeMapping(any())).thenReturn(Result.success(true))

            viewModel.saveMapping("a", "")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(repository).removeMapping("a")
            verify(repository, never()).setMapping(any(), any())
        }

    @Test
    fun `removeMapping calls repository removeMapping`() =
        runTest {
            whenever(repository.removeMapping(any())).thenReturn(Result.success(true))

            viewModel.removeMapping("a")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(repository).removeMapping("a")
        }

    @Test
    fun `removeMapping clears selection on success`() =
        runTest {
            whenever(repository.removeMapping(any())).thenReturn(Result.success(true))
            viewModel.selectKey("a")

            viewModel.removeMapping("a")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.selectedKey.value)
        }

    @Test
    fun `removeMapping does not clear selection on failure`() =
        runTest {
            whenever(repository.removeMapping(any()))
                .thenReturn(Result.failure(Exception("Test error")))
            viewModel.selectKey("a")

            viewModel.removeMapping("a")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("a", viewModel.selectedKey.value)
        }

    @Test
    fun `clearAllMappings calls repository clearAllMappings`() =
        runTest {
            whenever(repository.clearAllMappings()).thenReturn(Result.success(5))

            viewModel.clearAllMappings()
            testDispatcher.scheduler.advanceUntilIdle()

            verify(repository).clearAllMappings()
        }
}
