# Keep ExoPlayer classes
-keep class androidx.media3.** { *; }

# Keep Gson models
-keep class com.vistacore.launcher.iptv.models.** { *; }

# Keep app update models (deserialized by Gson)
-keep class com.vistacore.launcher.system.AppUpdateManager$GitHubRelease { *; }
-keep class com.vistacore.launcher.system.AppUpdateManager$GitHubAsset { *; }
-keep class com.vistacore.launcher.system.AppUpdateManager$UpdateInfo { *; }

# Keep Conscrypt TLS provider (native JNI + reflection)
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Keep Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
