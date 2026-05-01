package com.urik.keyboard.service

import com.urik.keyboard.data.ClipboardRepository
import com.urik.keyboard.data.database.ClipboardItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface ClipboardPanelHost {
    suspend fun onClipboardDataLoaded(pinnedItems: List<ClipboardItem>, recentItems: List<ClipboardItem>)
}

class ClipboardActionCoordinator(
    private val clipboardRepository: ClipboardRepository,
    private val outputBridge: OutputBridge,
    private val serviceScope: CoroutineScope,
    private val panelHost: ClipboardPanelHost
) {
    fun loadAndDisplayContent() {
        serviceScope.launch {
            val pinnedItems = clipboardRepository.getPinnedItems().getOrElse { emptyList() }
            val recentItems = clipboardRepository.getRecentItems().getOrElse { emptyList() }
            panelHost.onClipboardDataLoaded(pinnedItems, recentItems)
        }
    }

    fun pasteContent(content: String) {
        serviceScope.launch {
            outputBridge.commitText(content, 1)
        }
    }

    fun togglePin(item: ClipboardItem) {
        serviceScope.launch {
            clipboardRepository.togglePin(item.id, !item.isPinned)
            refreshContent()
        }
    }

    fun deleteItem(item: ClipboardItem) {
        serviceScope.launch {
            clipboardRepository.deleteItem(item.id)
            refreshContent()
        }
    }

    fun deleteAllUnpinned() {
        serviceScope.launch {
            clipboardRepository.deleteAllUnpinned()
            refreshContent()
        }
    }

    private suspend fun refreshContent() {
        val pinnedItems = clipboardRepository.getPinnedItems().getOrElse { emptyList() }
        val recentItems = clipboardRepository.getRecentItems().getOrElse { emptyList() }
        panelHost.onClipboardDataLoaded(pinnedItems, recentItems)
    }
}
