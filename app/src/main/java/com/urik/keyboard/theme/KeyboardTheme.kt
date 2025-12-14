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
                MaterialYou.Default.id -> Default
                else -> Default
            }

        fun all(): List<KeyboardTheme> =
            buildList {
                add(Default)
                add(Light)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    add(MaterialYou.Default)
                }
                add(Abyss)
                add(Crimson)
                add(Forest)
                add(Sunset)
                add(Ocean)
                add(Lavender)
                add(Mocha)
                add(Slate)
                add(Peach)
                add(Mint)
                add(Neon)
                add(Ember)
                add(Steel)
            }
    }
}
