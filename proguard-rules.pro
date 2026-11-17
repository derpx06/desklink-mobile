# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in {SDKHOME}/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
# Plugins are listed by the KSP class index and built by reflection with their no-argument constructor
# (PluginFactory); their simple name is the plugin key (kept by -dontobfuscate). Everything else in the app
# is reached from code or the manifest, so R8 may shrink and optimise it.
-keep class * extends org.desklink.mobile.plugins.Plugin { <init>(); }

# The webrtc-sdk AAR ships no consumer rules, and its native code finds Java
# classes, fields and methods by name over JNI.
-keep class org.webrtc.** { *; }
# jni_zero is libwebrtc's JNI helper: the native library looks `org/jni_zero/JniInit` up by name at start-up
# (jni_zero.cc "Failed to find class", then SIGILL) and nothing in Java references the class, so R8 removed it
# and every release build aborted at the first WebRTC use. The whole package is 15 small classes.
-keep class org.jni_zero.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Release builds log nothing: every level carries device names, addresses and ids that end up in bug reports (what is worth
# keeping goes to the redacted crash reports instead). (Debug builds keep everything.)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}


# The update check verifies a signed manifest with BouncyCastle's Ed25519 (docs/UPDATES.md 2.6). With an empty
# DESKLINK_UPDATE_URL R8 can prove the check unreachable and drop it; keeping these makes the release dex always carry the
# verifier (release-required-classes.txt asserts it), so a build with a URL cannot ship without it.
-keep class org.bouncycastle.math.ec.rfc8032.Ed25519 { *; }
-keep class org.desklink.mobile.update.UpdateVerifier { *; }
