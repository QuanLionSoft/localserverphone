# Gson uses generic signatures and runtime annotations for JSON adapters.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Netty dynamically discovers channel, buffer and logger implementations.
# Retain this infrastructure until release-device coverage justifies narrowing it.
-keep class io.netty.** { *; }
-keep class org.slf4j.simple.** { *; }

# Optional JDK providers referenced by Netty are unavailable on Android.
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.alpn.**
