-keep public class com.urik.keyboard.UrikInputMethodService {
    public protected <methods>;
}

-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }

-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,Exception

-keep class **.R$raw {
    public static int third_party_licenses;
    public static int third_party_license_metadata;
}

-repackageclasses