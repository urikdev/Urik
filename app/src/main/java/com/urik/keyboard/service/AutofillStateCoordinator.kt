package com.urik.keyboard.service

import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AutofillStateCoordinator(
    private val tracker: AutofillStateTracker,
    private val candidateBarController: CandidateBarController,
    private val serviceScope: CoroutineScope,
    private val displaySuggestions: (List<InlineSuggestion>) -> Unit
) {
    fun onFieldChanged(inputType: Int, imeOptions: Int, fieldId: Int, packageHash: Int) {
        tracker.cancelPendingClear()
        tracker.onFieldChanged(inputType, imeOptions, fieldId, packageHash)
    }

    fun onInputViewStarted(isViewReady: Boolean) {
        if (!isViewReady) return
        tracker.drainPendingResponse()?.let { buffered ->
            if (!tracker.isDismissed() && buffered.inlineSuggestions.isNotEmpty()) {
                displaySuggestions(buffered.inlineSuggestions)
            }
        }
    }

    fun onInputViewFinished() {
        tracker.scheduleClear(serviceScope) {
            candidateBarController.forceClearAllSuggestions()
        }
    }

    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse, isViewReady: Boolean): Boolean {
        val suggestions = response.inlineSuggestions

        if (suggestions.isEmpty()) {
            serviceScope.launch(Dispatchers.Main) {
                candidateBarController.forceClearAllSuggestions()
            }
            return false
        }

        if (tracker.isDismissed()) return true

        if (!isViewReady) {
            tracker.bufferResponse(response)
            return true
        }

        displaySuggestions(suggestions)
        return true
    }

    fun onKeyInput() {
        if (candidateBarController.clearAutofillIfShowing()) {
            tracker.dismiss()
        }
    }

    fun cleanup() {
        tracker.cleanup()
    }
}
