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
-keep class org.desklink.mobile.** {*;}

# The webrtc-sdk AAR ships no consumer rules, and its native code finds Java
# classes, fields and methods by name over JNI.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Release builds drop debug and verbose logging.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

