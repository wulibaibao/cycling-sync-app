# ── 入口类必须保留 ───────────────────────────────────────────────
# Manifest 里引用的类被裁剪/改名会导致启动崩溃；
# 崩溃堆栈里也要能看到真实类名才好排查。
-keep class com.wulibaibao.cyclicsync.MainActivity { *; }
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable

# ── kotlinx.serialization ────────────────────────────────────────
# @Serializable 类靠反射生成 serializer，混淆后无法反序列化。
-keep,includedescriptorclasses class com.wulibaibao.cyclicsync.data.**$$serializer { *; }
-keepclassmembers class com.wulibaibao.cyclicsync.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.wulibaibao.cyclicsync.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.json.** { *; }
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**

# ── 堆栈里保留真实类名 ───────────────────────────────────────────
# 崩溃时堆栈全靠这些名字定位，必须全部保留（不是只保留名字，
# 方法名也要 —— obfuscated 方法名在堆栈里完全没法读）。
-keep class com.wulibaibao.cyclicsync.** { *; }
-keep class com.wulibaibao.cyclicsync.data.** { *; }
-keep class com.wulibaibao.cyclicsync.platform.** { *; }
-keep class com.wulibaibao.cyclicsync.ui.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ── 依赖库的常见警告 ─────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn sun.misc.**
-dontwarn org.conscrypt.**