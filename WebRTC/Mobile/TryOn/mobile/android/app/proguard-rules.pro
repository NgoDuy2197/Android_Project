# WorkManager (pulled in by ML Kit / Play services) creates its Room database
# reflectively; R8 otherwise strips the generated *_Impl constructor and the
# app crashes at startup in androidx.startup.InitializationProvider.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }

# ML Kit pose detection native bindings.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
