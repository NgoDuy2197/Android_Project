# MediaPipe GenAI (on-device LLM) loads native/JNI pieces by name — keep them.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
