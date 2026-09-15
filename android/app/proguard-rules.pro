# OkHttp 的可选依赖在 Android 上并不存在，R8 会因缺类报错，直接放行。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.slf4j.**

# OkHttp 内部有反射读取的属性
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * extends java.lang.Enum { *; }

# JSON 相关：org.json 是 framework 自带，无需 keep。
# 本项目的 Kotlin 数据类不做反射序列化，也不需要额外规则。
