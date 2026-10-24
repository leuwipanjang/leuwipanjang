# Proguard rules for LeuwiPanjang SSH

# Keep JSch and dependencies
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Keep Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep models for Gson serialization
-keep class com.leuwipanjang.ssh.data.model.** { *; }
-keepclassmembers class com.leuwipanjang.ssh.data.model.** { <fields>; }

# Keep AndroidX & Material
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }
