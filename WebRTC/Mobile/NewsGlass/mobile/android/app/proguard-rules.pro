# WorkManager creates its Room database reflectively; keep it from R8.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }
-keep class com.dsoft.news_glass.FetchWorker { <init>(...); }