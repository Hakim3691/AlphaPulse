# C-27 — release shrinking rules.
#
# R8 renames and removes everything it can prove is unreferenced. That is the
# point: the shipped APK must not carry dead code, unused resources or debug
# symbols. These rules are deliberately limited to the two places where a name is
# looked up outside the Java call graph.
#
# Every application class is reached statically (Compose, Room's generated DAOs,
# OkHttp, Moshi's built-in Map adapter), so none of them is kept by name. Adding a
# blanket keep of the application package here would silently disable the shrinking
# this file exists to protect, and BuildContractBddSpec fails if it returns.

# ONNX Runtime's Java API is reached from its native library by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# OkHttp probes for optional TLS providers that nothing in this module ships:
# BouncyCastle, Conscrypt and OpenJSSE are named by its platform detection code,
# and no dependency provides them. R8 full mode treats a missing class as a hard
# error - "Missing classes detected while running R8" - which is exactly what
# failed v1.0.20's `:app:minifyReleaseWithR8` while every other gate passed. They
# are optional at runtime (OkHttp falls back to the platform TLS stack), so a
# warning is correct here and a keep rule is not: keeping classes that are not
# on the classpath would only hide the next real missing-class error.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# The Cactus Needle 3 engine is invoked through JNI: the native side resolves the
# declaring class and each external method by name, so both must survive renaming.
-keep class com.aistudio.alphapulse.domain.decision.Needle3Jni { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Keep annotations (Room/Moshi read them at runtime where generated code asks).
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# The native model files are assets, not resources; nothing may strip or rename them.
-keepdirectories assets
