# 测试通过 Activity 访问公开桥接方法，保留这些测试入口，不保留库实现。
-keepclassmembers class com.logan.multiurlmanager.consumer.ConsumerActivity { public *; }
# AndroidJUnitRunner 在应用之外引用共享 tracing 类；避免测试 APK 排除共享依赖后找不到它。
-keep class androidx.tracing.Trace { *; }
# 仪器运行器使用共享 Kotlin runtime；这些 keep 仅用于验收工程，不进入库 consumer rules。
-keep class kotlin.** { *; }
