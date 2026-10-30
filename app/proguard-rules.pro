# OPT-O20-05: Strip verbose and debug logging in release builds to reduce DEX size,
# eliminate string allocations, and prevent continuous background file log I/O.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
