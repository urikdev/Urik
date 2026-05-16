package com.urik.keyboard.service

import com.urik.keyboard.data.database.ClipboardItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardActionCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val clipboardRepository: com.urik.keyboard.data.ClipboardRepository = mock()
    private val outputBridge: OutputBridge = mock()
    private val panelHost: ClipboardPanelHost = mock()

    private lateinit var coordinator: ClipboardActionCoordinator

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coordinator = ClipboardActionCoordinator(
            clipboardRepository = clipboardRepository,
            outputBridge = outputBridge,
            serviceScope = testScope,
            panelHost = panelHost
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pasteContent commits text via outputBridge`() = testScope.runTest {
        coordinator.pasteContent("hello")
        advanceUntilIdle()

        verify(outputBridge).commitText("hello", 1)
    }

    @Test
    fun `togglePin calls repository togglePin with toggled flag`() = testScope.runTest {
        val item = ClipboardItem(id = 1, content = "test", timestamp = 0L, isPinned = false)
        whenever(clipboardRepository.togglePin(any(), any())).thenReturn(Result.success(Unit))
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.success(emptyList()))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.success(emptyList()))

        coordinator.togglePin(item)
        advanceUntilIdle()

        verify(clipboardRepository).togglePin(1L, true)
    }

    @Test
    fun `togglePin refreshes panel after pin change`() = testScope.runTest {
        val item = ClipboardItem(id = 1, content = "test", timestamp = 0L, isPinned = false)
        whenever(clipboardRepository.togglePin(any(), any())).thenReturn(Result.success(Unit))
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.success(emptyList()))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.success(emptyList()))

        coordinator.togglePin(item)
        advanceUntilIdle()

        verify(panelHost).onClipboardDataLoaded(emptyList(), emptyList())
    }

    @Test
    fun `deleteItem calls repository deleteItem`() = testScope.runTest {
        val item = ClipboardItem(id = 5, content = "test", timestamp = 0L)
        whenever(clipboardRepository.deleteItem(any())).thenReturn(Result.success(Unit))
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.success(emptyList()))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.success(emptyList()))

        coordinator.deleteItem(item)
        advanceUntilIdle()

        verify(clipboardRepository).deleteItem(5L)
    }

    @Test
    fun `deleteItem refreshes panel after deletion`() = testScope.runTest {
        val item = ClipboardItem(id = 5, content = "test", timestamp = 0L)
        whenever(clipboardRepository.deleteItem(any())).thenReturn(Result.success(Unit))
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.success(emptyList()))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.success(emptyList()))

        coordinator.deleteItem(item)
        advanceUntilIdle()

        verify(panelHost).onClipboardDataLoaded(emptyList(), emptyList())
    }

    @Test
    fun `deleteAllUnpinned calls repository deleteAllUnpinned`() = testScope.runTest {
        whenever(clipboardRepository.deleteAllUnpinned()).thenReturn(Result.success(Unit))
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.success(emptyList()))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.success(emptyList()))

        coordinator.deleteAllUnpinned()
        advanceUntilIdle()

        verify(clipboardRepository).deleteAllUnpinned()
    }

    @Test
    fun `deleteAllUnpinned refreshes panel after deletion`() = testScope.runTest {
        whenever(clipboardRepository.deleteAllUnpinned()).thenReturn(Result.success(Unit))
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.success(emptyList()))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.success(emptyList()))

        coordinator.deleteAllUnpinned()
        advanceUntilIdle()

        verify(panelHost).onClipboardDataLoaded(emptyList(), emptyList())
    }

    @Test
    fun `loadAndDisplayContent calls onClipboardDataLoaded with fetched items`() = testScope.runTest {
        val pinnedItem = ClipboardItem(id = 1, content = "pinned", timestamp = 0L, isPinned = true)
        val recentItem = ClipboardItem(id = 2, content = "recent", timestamp = 0L)
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.success(listOf(pinnedItem)))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.success(listOf(recentItem)))

        coordinator.loadAndDisplayContent()
        advanceUntilIdle()

        verify(panelHost).onClipboardDataLoaded(listOf(pinnedItem), listOf(recentItem))
    }

    @Test
    fun `loadAndDisplayContent passes empty lists when repository returns failure`() = testScope.runTest {
        whenever(clipboardRepository.getPinnedItems()).thenReturn(Result.failure(RuntimeException()))
        whenever(clipboardRepository.getRecentItems()).thenReturn(Result.failure(RuntimeException()))

        coordinator.loadAndDisplayContent()
        advanceUntilIdle()

        verify(panelHost).onClipboardDataLoaded(emptyList(), emptyList())
    }
}
