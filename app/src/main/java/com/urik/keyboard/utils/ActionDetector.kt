package com.urik.keyboard.utils

import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardKey

/**
 * Detects contextual action type from EditorInfo for dynamic enter key behavior.
 *
 */
object ActionDetector {
    /**
     * Detects contextual action type for dynamic enter key behavior.
     *
     * Maps EditorInfo IME action flags to KeyboardKey.ActionType enum:
     * - IME_ACTION_SEARCH → SEARCH
     * - IME_ACTION_SEND → SEND
     * - IME_ACTION_DONE → DONE
     * - IME_ACTION_GO → GO
     * - IME_ACTION_NEXT → NEXT
     * - IME_ACTION_PREVIOUS → PREVIOUS
     * - All others → ENTER (default)
     *
     * @param info EditorInfo from input field, or null if unavailable
     * @return Detected action type, defaults to ENTER if no specific action set
     */
    fun detectAction(info: EditorInfo?): KeyboardKey.ActionType {
        if (info == null) return KeyboardKey.ActionType.ENTER

        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> KeyboardKey.ActionType.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyboardKey.ActionType.SEND
            EditorInfo.IME_ACTION_DONE -> KeyboardKey.ActionType.DONE
            EditorInfo.IME_ACTION_GO -> KeyboardKey.ActionType.GO
            EditorInfo.IME_ACTION_NEXT -> KeyboardKey.ActionType.NEXT
            EditorInfo.IME_ACTION_PREVIOUS -> KeyboardKey.ActionType.PREVIOUS
            else -> KeyboardKey.ActionType.ENTER
        }
    }
}
