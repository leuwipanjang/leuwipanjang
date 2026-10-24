# Proguard rules for Leuwipanjang

# Keep JSch and dependencies
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Keep Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep models for Gson serialization
-keep class id.biz.id.leuwipanjang.data.model.** { *; }
-keepclassmembers class id.biz.id.leuwipanjang.data.model.** { <fields>; }

# Keep AndroidX & Material
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }
