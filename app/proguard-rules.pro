# PageTime R8/ProGuard rules.
#
# Release builds run R8 in full mode (AGP 8 default). The default Android
# optimize config (proguard-android-optimize.txt) already covers the basics
# (manifest components, attributes); everything below protects the libraries
# that ship no consumer rules of their own.
#
# R8 optimization and obfuscation are disabled on purpose. The build
# environment caps memory at 4 GB (see gradle.properties) and R8's
# optimization/obfuscation passes on this dependency set (material-icons-
# extended, Compose, Room, Readium) get OOM-killed before finishing.
# Shrinking stays enabled -- removing unused classes/methods is where the
# APK size win comes from; we only skip the memory-hungry passes.
-dontoptimize
-dontobfuscate

# --- Readium EPUB toolkit (org.readium.kotlin-toolkit) ---
# Readium publishes its AARs without consumer ProGuard rules and its own
# test app ships with minify disabled, so there is no official keep-list.
# The toolkit is reflection-heavy (kotlin-reflect runtime dep, generated
# kotlinx.serialization lookups) and the reader is the core of this app:
# keep the whole toolkit unminified and unrenamed so EPUB parsing and page
# rendering cannot break on stripped members.
-keep class org.readium.r2.** { *; }

# --- kotlinx.serialization ---
# Json.decodeFromString<T>() with a reified type parameter locates the
# generated <Class>$$serializer and its Companion reflectively at runtime.
# R8 strips those unless told to keep them. The org.readium.r2 keep above
# already covers Readium's serializable models; these rules are the general
# safety net for any @Serializable type in the app.
-keep,includedescriptorclasses class com.pagetime.app.**$$serializer { *; }
-keepclassmembers class com.pagetime.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.pagetime.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.* {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.* {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- FSRS (io.github.open-spaced-repetition) ---
# The library is compiled with Lombok and its generated code references
# lombok.Generated, which is not on the runtime classpath. Missing it is
# harmless (it is a source-only annotation).
-dontwarn lombok.Generated

# --- kotlin-reflect (runtime dependency of readium-shared) ---
# kotlin-reflect does not ship consumer rules either. Its JVM internals are
# reached reflectively through the public KClass/KType APIs; keep them so
# runtime type introspection inside Readium keeps working after shrinking.
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
