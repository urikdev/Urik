package com.urik.keyboard.utils

object KanaTransformUtils {
    private val DAKUTEN_MAP: Map<Char, Char> = mapOf(
        'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
        'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
        'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
        'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ'
    )

    private val HANDAKUTEN_MAP: Map<Char, Char> = mapOf(
        'は' to 'ぱ',
        'ひ' to 'ぴ',
        'ふ' to 'ぷ',
        'へ' to 'ぺ',
        'ほ' to 'ぽ'
    )

    private val BASE_FROM_DAKUTEN: Map<Char, Char> = DAKUTEN_MAP.entries.associate { (k, v) -> v to k }
    private val BASE_FROM_HANDAKUTEN: Map<Char, Char> = HANDAKUTEN_MAP.entries.associate { (k, v) -> v to k }

    private val SMALL_KANA_MAP: Map<Char, Char> = mapOf(
        'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
        'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ',
        'つ' to 'っ', 'わ' to 'ゎ'
    )

    private val LARGE_KANA_MAP: Map<Char, Char> = SMALL_KANA_MAP.entries.associate { (k, v) -> v to k }

    fun toDakuten(kana: Char): Char? = DAKUTEN_MAP[kana]

    fun toHandakuten(kana: Char): Char? = HANDAKUTEN_MAP[kana]

    fun toBase(kana: Char): Char? = BASE_FROM_DAKUTEN[kana] ?: BASE_FROM_HANDAKUTEN[kana]

    fun cycleDakutenOnLast(text: String): String {
        if (text.isEmpty()) return text
        val last = text.last()
        val next = when {
            BASE_FROM_HANDAKUTEN.containsKey(last) -> BASE_FROM_HANDAKUTEN[last]!!
            BASE_FROM_DAKUTEN.containsKey(last) -> {
                HANDAKUTEN_MAP[BASE_FROM_DAKUTEN[last]!!] ?: BASE_FROM_DAKUTEN[last]!!
            }
            DAKUTEN_MAP.containsKey(last) -> DAKUTEN_MAP[last]!!
            else -> return text
        }
        return text.dropLast(1) + next
    }

    fun toSmallKana(kana: Char): Char? = SMALL_KANA_MAP[kana]

    fun toLargeKana(kana: Char): Char? = LARGE_KANA_MAP[kana]

    fun toggleSmallKanaOnLast(text: String): String {
        if (text.isEmpty()) return text
        val last = text.last()
        val toggled = SMALL_KANA_MAP[last] ?: LARGE_KANA_MAP[last] ?: return text
        return text.dropLast(1) + toggled
    }
}
