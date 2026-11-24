# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Moshi codegen adapters instantiated via reflection
-keepclassmembers class * {
    @com.squareup.moshi.JsonClass *;
}
-keep class com.example.network.*JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-keep @com.squareup.moshi.JsonClass class com.example.network.** { *; }

# Room entities and database constructors
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.local.** { *; }

# Suppress harmless R8 warnings for optional desktop/reflection refs
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

