# Add project specific ProGuard rules here.
# JNI entry points are resolved by name; keep them if minification is ever enabled.
-keepclasseswithmembernames class com.example.soundvisualizer.AudioEngine {
    native <methods>;
}
