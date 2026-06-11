package com.urik.keyboard.dictionary

/**
 * Bare forms present in source frequency corpora only as encoding artifacts of
 * apostrophe forms (e.g. "im" for "i'm", "jai" for "j'ai") plus OCR garbage
 * (e.g. "weii" for "well"). Filtered out of dictionary lookups, candidates and
 * completions at runtime so the apostrophe form wins spell check and autocorrect.
 *
 * All entries must be lowercase.
 */
object BareFormRemovelist {
    private val REMOVELIST: Map<String, Set<String>> = mapOf(
        "en" to setOf(
            "dont", "wont", "cant", "didnt", "doesnt", "wasnt", "isnt", "arent",
            "wouldnt", "couldnt", "shouldnt", "hadnt", "hasnt", "havent",
            "thats", "whats", "whos", "hows", "heres", "theres", "wheres",
            "hes", "shes", "youre", "theyre", "weve", "theyve",
            "youll", "theyll", "itll", "youve", "youd", "hed", "shed", "wed",
            "wouldve", "couldve", "shouldve", "werent", "aint",
            "howd", "whatre", "lm", "ld", "lll", "lts", "lve", "weii",
            "im", "ive", "theyd"
        ),
        "fr" to setOf(
            "jai", "cest", "tai", "lai", "nai", "quil", "nest",
            "aujourdhui", "taime", "cetait"
        ),
        "de" to setOf(
            "gibts",
            "gehts",
            "habs",
            "stimmts",
            "bins",
            "sies"
        ),
        "el" to setOf(
            "μένα", "σένα", "κάντο", "σενα", "παρόλα", "βάλτο", "δώστο",
            "κόφτο", "πάρτο", "γιαυτό", "βούλωστο", "σόλο", "απέξω",
            "πάρτα", "δώστου", "πάρτον", "δώσμου", "πάρτην", "βάλτα",
            "φέρτον", "απτο", "γιαυτο", "ρίξτου", "απτην", "ναναι",
            "σευχαριστώ", "μαρέσει", "σαυτό", "απτη", "θαναι", "σαγαπώ",
            "απτον", "σουπα", "γιαυτόν", "απαυτά", "απτα", "απότι",
            "νασαι", "σαρέσει", "απαυτό", "γιαυτήν", "σαυτή", "μαυτό",
            "σαγαπάω", "ναχει", "θαπρεπε", "σαυτόν", "απόλα", "ναμαι",
            "γιαυτά", "απτους", "γιαυτή", "θαμαι", "γιαυτούς", "θαθελα",
            "απτις", "απόσο", "οσι", "σαυτο", "μαρέσουν", "σόλους",
            "θαρθει", "ναμαστε", "σταλήθεια", "μόλα", "αποτι", "θαταν",
            "εφόσων", "τοχω", "γιαυτον", "θασαι", "μακούς", "γιαυτα",
            "μαυτόν", "σαφήσω", "απαυτούς", "σαυτά", "θαχει", "γιαυτην",
            "τοξερα", "μαυτή", "τόνομα", "θαχεις", "σέχω", "μαυτά",
            "σέναν", "τοκανες"
        ),
        "cs" to setOf("dont", "its"),
        "nl" to setOf("fotos", "autos"),
        "it" to setOf("lho", "dacqua")
    )

    fun forLanguage(languageCode: String): Set<String> = REMOVELIST[languageCode] ?: emptySet()
}
