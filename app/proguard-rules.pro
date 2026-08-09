# --- LiteRT / TensorFlow Lite ---
# The runtime resolves delegates and ops through JNI; stripping or renaming these
# classes breaks interpreter construction at runtime with no compile-time signal.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# GPU delegate factory is looked up reflectively by name.
-keep class org.tensorflow.lite.gpu.GpuDelegate { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory** { *; }

# --- kotlinx.serialization ---
# Sidecar model_config.json parsing must survive R8; the plugin injects most rules,
# these cover reflective companion lookup on older AGP/R8 combinations.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.deepmost.rabbitav.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- osmdroid ---
-dontwarn org.osmdroid.**

# --- libyuv binding (thin JNI wrapper) ---
-keep class io.github.crow_misia.libyuv.** { *; }
