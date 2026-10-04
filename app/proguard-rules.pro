# Kernel Loder - release protection rules
# R8: obfuscate + shrink + optimize. Keeps reflection-sensitive code alive.

-allowaccessmodification
-repackageclasses ''

# --- libsu (root shell) reads config via reflection -> must survive ---
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# --- Coroutines internals ---
-dontwarn kotlinx.coroutines.**

# --- Compose runtime ships its own AAPT rules; nothing extra needed ---

# --- Protection extras ---------------------------------------------------
# Rename the SourceFile attribute so original .kt file names never leak into
# stack traces / crash reports. (R8 does NOT support -dontattributes, so this
# is the supported way to hide source-file names.)
-renamesourcefileattribute SourceFile

# Strip debug info R8 does not need -> smaller dex, less reversible
-keepattributes Exceptions,InnerClasses,Signature,*Annotation*

# Reflection / JSON / enum access that must survive shrinking
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
-keep class com.kernelloader.update.AppUpdateChecker$** { *; }
-keep class com.kernelloader.driver.OtaDriverStore$** { *; }
-dontwarn org.jetbrains.annotations.**

# Strip verbose logging from release builds: less surface for logcat-based
# reversing, no internal details leak at runtime.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}