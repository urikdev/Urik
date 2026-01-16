package com.urik.keyboard.service

data class InputStateSnapshot(
    val composingText: String,
    val composingStart: Int,
    val displayBuffer: String,
    val wordState: WordState,
    val pendingSuggestions: List<String>,
    val isShiftPressed: Boolean,
    val isCapsLockOn: Boolean,
)
