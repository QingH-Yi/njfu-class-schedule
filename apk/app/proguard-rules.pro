# Gson 反序列化模型不能混淆（只保留数据模型，其余交给 R8 收缩）
-keep class com.njfu.schedule.Course { *; }
-keep class com.njfu.schedule.Schedule { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
-keepattributes *Annotation*

# WebView JS 接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Jsoup
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# 广播接收器：系统通过 AndroidManifest 反射实例化，混淆类名后会失效
-keep class * extends android.content.BroadcastReceiver { <init>(); }

