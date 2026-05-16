package com.urik.keyboard.service

class JapaneseCandidateHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val onCommit: (String) -> Unit
) {
    private var currentCandidateIndex = 0

    fun onNextCandidate() {
        val candidates = inputState.pendingSuggestions
        if (candidates.isEmpty()) return
        currentCandidateIndex = (currentCandidateIndex + 1) % candidates.size
        outputBridge.setComposingText(candidates[currentCandidateIndex], 1)
    }

    fun onCommitCandidate() {
        val candidates = inputState.pendingSuggestions
        if (candidates.isEmpty()) return
        val toCommit = candidates[currentCandidateIndex]
        reset()
        onCommit(toCommit)
    }

    fun reset() {
        currentCandidateIndex = 0
    }
}
