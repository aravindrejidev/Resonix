# Resonix release-build keep rules.

# Keep every class with a native method, and keep the native methods
# themselves unrenamed. R8 cannot see into native-lib.cpp, so if it
# renames or strips NativeAudioEngine's external fun declarations,
# System.loadLibrary() will still succeed but every call from Kotlin
# into the Oboe engine will throw UnsatisfiedLinkError at runtime.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep the native bridge class itself (name + members) as an extra
# safeguard, since it is looked up by fully-qualified name from C++.
-keep class com.resonix.player.audio.NativeAudioEngine {
    *;
}
