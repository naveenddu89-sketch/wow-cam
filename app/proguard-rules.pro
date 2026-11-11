# wow cam is a pure Android/Kotlin application with no reflection-based model layer
# and no native libraries. CameraX ships its own consumer ProGuard rules, so no broad
# keep rules are required. The rules below only preserve useful crash diagnostics.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations used by AndroidX/CameraX at runtime.
-keepattributes *Annotation*

# Filter and timer selections are persisted as enum names. Keeping these enums
# guarantees that name()/valueOf() stay stable across R8 obfuscation.
-keepclassmembers enum com.wowcam.app.effect.FilterPreset { *; }
-keepclassmembers enum com.wowcam.app.MainActivity$TimerMode { *; }
