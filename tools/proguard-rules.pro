# OBD-II & E-GMP Telemetry for Gen5W - R8 Shrinking & API 19 (KitKat Dalvik) Rules
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Gen5W runs Android 4.4.2 KitKat (API 19). Folding SDK_INT == 19 eliminates any
# accidental post-KitKat branches at compile/shrink time.
-assumevalues class android.os.Build$VERSION {
    public static final int SDK_INT return 19..19;
}

# Preserve all application classes and Gen5W BLE client SDK classes
-keep class obd.app.** { *; }
-keep class gen5w.ble.** { *; }
