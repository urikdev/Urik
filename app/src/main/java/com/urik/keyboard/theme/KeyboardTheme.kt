package com.urik.keyboard.theme

sealed interface KeyboardTheme {
    val id: String
    val displayName: String
    val colors: ThemeColors

    companion object {
        fun fromId(id: String): KeyboardTheme =
            when (id) {
                Default.id -> Default
                Light.id -> Light
                Abyss.id -> Abyss
                Crimson.id -> Crimson
                Forest.id -> Forest
                Sunset.id -> Sunset
                Ocean.id -> Ocean
                Lavender.id -> Lavender
                Mocha.id -> Mocha
                Slate.id -> Slate
                Peach.id -> Peach
                Mint.id -> Mint
                Neon.id -> Neon
                Ember.id -> Ember
                Steel.id -> Steel
                else -> Default
            }

        fun all(): List<KeyboardTheme> =
            listOf(
                Default,
                Light,
                Abyss,
                Crimson,
                Forest,
                Sunset,
                Ocean,
                Lavender,
                Mocha,
                Slate,
                Peach,
                Mint,
                Neon,
                Ember,
                Steel,
            )
    }
}
