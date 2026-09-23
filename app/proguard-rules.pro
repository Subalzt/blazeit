# --- Ktor ---------------------------------------------------------------
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# Ktor discovers engines via ServiceLoader.
-keep class io.ktor.server.cio.** { *; }
-keep class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }

# --- kotlinx.serialization ----------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.periy.bridge.** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class dev.periy.bridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- SLF4J ---------------------------------------------------------------
-dontwarn org.slf4j.**
-keep class org.slf4j.simple.** { *; }
