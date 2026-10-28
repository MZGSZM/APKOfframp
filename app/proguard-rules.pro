# Room, WorkManager, OkHttp and kotlinx.serialization ship their own consumer rules.
# Keep the serializable reply classes for copyparty, which are only built by the serializer.
-keep class com.mzgszm.apkofframp.core.copyparty.** { *; }
