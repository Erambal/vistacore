# Keep ExoPlayer classes
-keep class androidx.media3.** { *; }

# Keep all Gson-deserialized data classes and Gson internals
-keep class com.vistacore.launcher.** { *; }
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Conscrypt TLS provider (native JNI + reflection)
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Keep Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
