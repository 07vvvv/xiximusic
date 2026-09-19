# =====================================================================
# 嘻嘻音乐 XixiMusic —— R8 / ProGuard 规则
# 目标：release 尽量小，同时不破坏 Media3 / Retrofit / Gson / DataStore / Coil
# =====================================================================

# --- 通用：保留行号便于崩溃定位，保留注解与签名 ---
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# --- 泛型与反射相关（Gson / Retrofit 必需）---
-keepattributes Exceptions,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# =====================================================================
# Media3 / ExoPlayer
# =====================================================================
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# MediaSessionService 通过反射由系统/清单实例化
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class * extends androidx.media3.session.MediaLibraryService { *; }
-keep class * extends androidx.media3.exoplayer.ExoPlayer { *; }

# =====================================================================
# Retrofit
# =====================================================================
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Retrofit 注解与泛型签名
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# =====================================================================
# OkHttp（含 logging-interceptor，仅 debug 依赖）
# =====================================================================
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# =====================================================================
# Gson + 本项目数据模型（反射反序列化，必须保留字段名）
# =====================================================================
-keep class com.google.gson.** { *; }
-keep interface com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class com.xixi.music.data.model.** { *; }
-keepclassmembers class com.xixi.music.data.model.** {
    <fields>;
    <init>(...);
}
# 泛型 TypeToken 匿名子类
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# =====================================================================
# DataStore
# =====================================================================
-keep class androidx.datastore.** { *; }
-keep interface androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# =====================================================================
# Coil
# =====================================================================
-keep class coil.** { *; }
-keep interface coil.** { *; }
-dontwarn coil.**
-dontwarn okio.**
# Coil 的 OkHttp 集成与硬件位图
-keep class coil.util.** { *; }
-keep class coil.decode.** { *; }
-keep class coil.memory.** { *; }

# =====================================================================
# Kotlin / 协程
# =====================================================================
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.debug.**

# =====================================================================
# Android 组件（清单中声明的类名不能被混淆）
# =====================================================================
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Activity
-keep public class * extends androidx.fragment.app.Fragment

# 自定义 View / Compose Preview
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 枚举（Gson / 播放模式序列化）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# =====================================================================
# 移除日志（release 减小体积并避免泄漏）
# =====================================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
