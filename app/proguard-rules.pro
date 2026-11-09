# Add project specific ProGuard rules here.
-keep class app.minerguru.model.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WorkManager creates its Room database by reflection. Room 2.6's consumer rule keeps the class
# but not its no-arg constructor under R8 full mode, so the release build crashed at start.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
