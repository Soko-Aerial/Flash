# OPT-O20-05: drop `android.util.Log` debug/verbose calls in release builds.
#
# This prunes calls made by *dependencies* (the app's own logging never uses Log.d/Log.v:
# :core:common's PlatformLogSink writes through Log.println, and the rotating file sink is a
# separate path). So it saves DEX size and the cost of those calls in third-party code; it does
# NOT stop Flash's own file logging, and it is deliberately silent about Log.i/w/e, which are
# what a release build still needs when a problem is reported.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# ERROR-1048: R8 9.3.16 moved this Compose method into a merged class and emitted
# a branch Android 13 cannot verify. Keep the facade and this method out of optimization.
-keep,allowshrinking,allowobfuscation class com.transfer.flash.ui.chat.FlashConversationScreenKt {
    public static void FlashConversationScreen(...);
}
