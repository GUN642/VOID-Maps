# MapLibre talks to its native core through JNI – keep the Java side intact.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
