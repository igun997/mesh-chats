# Kotlinx serialization: keep serializers for @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    public static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# Room generated code is kept by its own consumer rules.

# libsignal: the native library resolves Java classes, methods, and fields by
# name across the JNI boundary (621+ native methods plus JNI callbacks into
# Java). R8 renaming or stripping any of them silently breaks the protocol at
# runtime, so keep the whole package including descriptor classes. libsignal
# ships no consumer rules, so these must live here.
-keep,includedescriptorclasses class org.signal.libsignal.** { *; }
-keepclasseswithmembernames class org.signal.libsignal.** {
    native <methods>;
}
-dontwarn org.signal.libsignal.**

# SQLCipher ships its own consumer ProGuard rules (net.zetetic.database.**);
# no additional rules are required here.

# Bouncy Castle: keep the provider and its algorithm SPI implementations, which
# are loaded reflectively by name. Ignore optional/absent JCA/JCE references.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
