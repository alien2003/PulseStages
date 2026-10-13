# R8 rules for the PulseStages app (release builds are minified + resource-shrunk).
#
# Libraries ship their own consumer rules, which R8 applies automatically:
#  - kotlinx-serialization: keeps generated serializers of every retained @Serializable class
#    (only classes with *named* companion objects need extra rules - avoid those in this code base).
#  - Room: keeps generated *_Impl database/DAO classes.
#  - Hilt/Dagger and androidx.hilt: keep generated components, entry points and HiltWorkerFactory wiring.
#  - WorkManager: keeps ListenableWorker constructors used reflectively by the default factory.
# The rules below only add what those consumer rules do not cover.

# Keep generic signatures and annotations that kotlinx-serialization, Hilt and Room inspect.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Readable stack traces from release builds (mapping.txt is produced next to the APK).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Navigation-compose type-safe routes are @Serializable objects/classes: covered by the kotlinx-serialization
# consumer rules (META-INF/com.android.tools/r8/kotlinx-serialization-common.pro in kotlinx-serialization-core).
