# Consumer ProGuard/R8 rules for :core:calling
#
# No FIRST-PARTY keep rules are needed. This module has no first-party reflection,
# no serialization plugin, and no dynamic class loading of its own types. The two
# `javaClass.simpleName` uses in FlashCallSession are log strings only — an obfuscated
# name there costs a reader nothing and breaks nothing.
#
# The bundled WebRTC IS different, and unlike Room or SQLCipher it ships NO consumer
# rules of its own (verified: io.github.webrtc-sdk:android contains no proguard.txt).
# libwebrtc's JNI is bidirectional — native C++ resolves org.webrtc classes, methods
# and fields BY NAME through GetMethodID/GetFieldID, and holds Java objects it was
# handed as long handles. R8 sees none of that: the classes look unreachable or safely
# renameable, so a consumer who enables minification gets a release-only crash
# (NoSuchMethodError / UnsatisfiedLinkError inside PeerConnectionFactory init) that
# never reproduces in debug. Hence the blunt keep — the reflection here is native, so
# there is no narrower rule that is honest.
# OPT-O22-06: narrow WebRTC keep rules to JNI entry points and active classes, allowing R8 to prune dead codecs/capturers.
-keep class org.webrtc.** {
    @org.webrtc.CalledByNative *;
    @org.webrtc.CalledByNativeUnchecked *;
}
-keep class org.webrtc.PeerConnectionFactory* { *; }
-keep class org.webrtc.PeerConnection* { *; }
-keep class org.webrtc.AudioSource* { *; }
-keep class org.webrtc.AudioTrack* { *; }
-keep class org.webrtc.VideoSource* { *; }
-keep class org.webrtc.VideoTrack* { *; }
-keep class org.webrtc.VideoFrame* { *; }
-keep class org.webrtc.SurfaceViewRenderer* { *; }
-keep class org.webrtc.EglBase* { *; }
-keep class org.webrtc.Logging* { *; }

# Native method names can never be renamed: the symbol in the .so is matched by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# webrtc-kmp's own Kotlin wrappers (com.shepeliev.webrtckmp.**) need nothing: they are
# ordinary reachable code, `api()`-exposed to consumers (ADR-025), so R8 keeps whatever
# the consumer actually calls. If a future change adds reflective access to a
# first-party type, add a matching -keep here.
