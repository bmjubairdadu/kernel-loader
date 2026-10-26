# Kernel Loder - Maximum Security Hardening Rules
# R8: Aggressive Obfuscation + Code Flattening + Anti-Reverse Engineering

-allowaccessmodification
-repackageclasses ''
-overloadaggressively

# Rename SourceFile attribute to blank to completely wipe source file names from DEX
-renamesourcefileattribute ""

# Strip all line numbers, local variable tables, source attributes
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# --- libsu (root shell) reads config via reflection -> must survive ---
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# --- Coroutines internals ---
-dontwarn kotlinx.coroutines.**

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
-keep class com.kernelloader.security.SecurityShield { *; }
-dontwarn org.jetbrains.annotations.**

# Strip all logging and print statements from bytecode
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}