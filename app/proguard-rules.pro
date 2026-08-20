# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keep class com.facetec.sdk*, * { *; }
-keep class com.acesso.acessobio_android.** { *; }

-dontwarn com.google.crypto.tink.subtle.Ed25519Sign$KeyPair
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign
-dontwarn com.google.crypto.tink.subtle.Ed25519Verify
-dontwarn com.google.crypto.tink.subtle.X25519
-dontwarn com.google.crypto.tink.subtle.XChaCha20Poly1305
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn kotlinx.parcelize.Parcelize
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
# Uncomment this to preserve the line number information for
# debugging stack traces.
-keep class kotlin.coroutines.**
-keep class kotlinx.coroutines.**

-keep class com.acesso.acessobio_android.** { *; }
-keep class io.unico.** { *; }

-keep class br.com.makrosystems.haven.** { *; }
-keep class HavenSDK.**{ *; }
-keep class HavenSDK** { *; }