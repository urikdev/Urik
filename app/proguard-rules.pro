-keep public class com.urik.keyboard.UrikInputMethodService {
    public protected <methods>;
}

-keep class com.urik.keyboard.data.database.LearnedWord { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

-keep class net.zetetic.database.** { *; }

-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep class **_HiltModules$** { *; }
-keep class **_HiltComponents$** { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <methods>;
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,Exception

-keep class **.R$raw {
    public static int third_party_licenses;
    public static int third_party_license_metadata;
}

-allowaccessmodification
-repackageclasses ''