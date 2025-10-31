package com.urik.keyboard.ui.tips

import com.urik.keyboard.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class TipsRepository
    @Inject
    constructor() {
        private val tips =
            listOf(
                TipItem(
                    titleResId = R.string.tip_1_title,
                    descriptionResId = R.string.tip_1_description,
                ),
                TipItem(
                    titleResId = R.string.tip_2_title,
                    descriptionResId = R.string.tip_2_description,
                ),
                TipItem(
                    titleResId = R.string.tip_3_title,
                    descriptionResId = R.string.tip_3_description,
                ),
                TipItem(
                    titleResId = R.string.tip_4_title,
                    descriptionResId = R.string.tip_4_description,
                ),
            )

        private var cachedShuffledTips: List<TipItem>? = null

        fun getShuffledTips(): List<TipItem> {
            if (cachedShuffledTips == null) {
                cachedShuffledTips = tips.toMutableList().apply { shuffle(Random.Default) }
            }
            return cachedShuffledTips!!
        }
    }
