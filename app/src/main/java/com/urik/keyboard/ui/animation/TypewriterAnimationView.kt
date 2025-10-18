package com.urik.keyboard.ui.animation

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TypewriterAnimationView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr),
        DefaultLifecycleObserver {
        private var editTextSimulator: EditTextSimulatorView = EditTextSimulatorView(context)
        private var suggestionBar: SuggestionBarView = SuggestionBarView(context)
        private val sequencer = AnimationSequencer()

        private var animationJob: Job? = null

        private val fullSentence: String by lazy {
            context.getString(R.string.animation_sentence)
        }

        private val typos: List<TypoConfig> by lazy {
            buildTypoConfigs()
        }

        init {
            editTextSimulator = EditTextSimulatorView(context)
            suggestionBar = SuggestionBarView(context)

            val suggestionBarHeight = (resources.displayMetrics.density * 45).toInt()
            val suggestionBarMargin = (8f * resources.displayMetrics.density).toInt()

            val suggestionBarParams =
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    suggestionBarHeight,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setMargins(suggestionBarMargin, 0, suggestionBarMargin, 0)
                }

            val editTextMargin = (16f * resources.displayMetrics.density).toInt()
            val editTextTopMargin = suggestionBarHeight + (16f * resources.displayMetrics.density).toInt()

            val editTextHeight = calculateRequiredHeight()

            val editTextParams =
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    editTextHeight,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setMargins(editTextMargin, editTextTopMargin, editTextMargin, 0)
                }

            addView(suggestionBar, suggestionBarParams)
            addView(editTextSimulator, editTextParams)
        }

        private fun calculateRequiredHeight(): Int {
            val padding = (16f * resources.displayMetrics.density).toInt()
            val textSize =
                android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    18f,
                    resources.displayMetrics,
                )
            val editTextMargin = (16f * resources.displayMetrics.density).toInt()

            val paint =
                android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.textSize = textSize
                }

            val viewWidth = resources.displayMetrics.widthPixels - (editTextMargin * 2)
            val availableWidth = viewWidth - (padding * 2)

            val layout =
                android.text.StaticLayout.Builder
                    .obtain(fullSentence, 0, fullSentence.length, paint, availableWidth)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(4f, 1.2f)
                    .setIncludePad(true)
                    .build()

            return layout.height + padding * 2
        }

        private fun buildTypoConfigs(): List<TypoConfig> {
            val sentence = fullSentence

            val typo1Correct = context.getString(R.string.animation_typo_1_correct)
            val typo1Wrong = context.getString(R.string.animation_typo_1_wrong)
            val typo1Suggestions =
                context.resources.getStringArray(R.array.animation_typo_1_suggestions).toList()

            val typo2Correct = context.getString(R.string.animation_typo_2_correct)
            val typo2Wrong = context.getString(R.string.animation_typo_2_wrong)
            val typo2Suggestions =
                context.resources.getStringArray(R.array.animation_typo_2_suggestions).toList()

            val typo3Correct = context.getString(R.string.animation_typo_3_correct)
            val typo3Wrong = context.getString(R.string.animation_typo_3_wrong)
            val typo3Suggestions =
                context.resources.getStringArray(R.array.animation_typo_3_suggestions).toList()

            val configs = mutableListOf<TypoConfig>()

            val typo1Start = sentence.indexOf(typo1Correct, ignoreCase = true)
            if (typo1Start >= 0) {
                configs.add(
                    TypoConfig(
                        correctWord = typo1Correct,
                        typoWord = typo1Wrong,
                        suggestions = typo1Suggestions,
                        startIndex = typo1Start,
                        endIndex = typo1Start + typo1Correct.length,
                    ),
                )
            }

            val typo2Start = sentence.indexOf(typo2Correct, ignoreCase = true)
            if (typo2Start >= 0) {
                configs.add(
                    TypoConfig(
                        correctWord = typo2Correct,
                        typoWord = typo2Wrong,
                        suggestions = typo2Suggestions,
                        startIndex = typo2Start,
                        endIndex = typo2Start + typo2Correct.length,
                    ),
                )
            }

            val typo3Start = sentence.indexOf(typo3Correct, ignoreCase = true)
            if (typo3Start >= 0) {
                configs.add(
                    TypoConfig(
                        correctWord = typo3Correct,
                        typoWord = typo3Wrong,
                        suggestions = typo3Suggestions,
                        startIndex = typo3Start,
                        endIndex = typo3Start + typo3Correct.length,
                    ),
                )
            }

            return configs
        }

        fun startAnimation() {
            animationJob?.cancel()

            val lifecycleOwner = findViewTreeLifecycleOwner()
            if (lifecycleOwner == null) {
                postDelayed({ startAnimation() }, 100)
                return
            }

            lifecycleOwner.lifecycle.addObserver(this)

            animationJob =
                lifecycleOwner.lifecycleScope.launch {
                    while (isActive) {
                        sequencer.animateSequence(
                            fullSentence = fullSentence,
                            typos = typos,
                            onStateChange = { state ->
                                handleStateChange(state)
                            },
                        )
                    }
                }
        }

        fun stopAnimation() {
            animationJob?.cancel()
            animationJob = null
        }

        private fun handleStateChange(state: TypewriterState) {
            editTextSimulator.setState(state)

            when (state) {
                is TypewriterState.ShowingSuggestions -> {
                    suggestionBar.setSuggestions(state.suggestions)
                    suggestionBar.animateAppear()
                }
                is TypewriterState.SelectingSuggestion -> {
                    val selectedIndex = state.suggestions.indexOf(state.selectedSuggestion)
                    suggestionBar.simulateTapOnSuggestion(selectedIndex) {
                    }
                }
                is TypewriterState.Typing -> {
                    if (suggestionBar.isVisible) {
                        suggestionBar.animateDisappear()
                    }
                }
                else -> {
                }
            }
        }

        override fun onResume(owner: LifecycleOwner) {
            super.onResume(owner)
            editTextSimulator.resumeAnimation()
        }

        override fun onPause(owner: LifecycleOwner) {
            super.onPause(owner)
            editTextSimulator.pauseAnimation()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startAnimation()
        }

        override fun onDetachedFromWindow() {
            findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(this)
            stopAnimation()
            super.onDetachedFromWindow()
        }
    }
