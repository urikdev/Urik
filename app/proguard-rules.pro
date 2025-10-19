-keep class com.urik.keyboard.UrikInputMethodService { *; }

-keep class com.urik.keyboard.data.database.LearnedWord { *; }

-keep interface com.urik.keyboard.data.database.LearnedWordDao { *; }

-keep class net.zetetic.database.** { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class **.R$raw {
    public static int third_party_licenses;
    public static int third_party_license_metadata;
}