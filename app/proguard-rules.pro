-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_segmentation_subject.** { *; }
-dontwarn com.google.mlkit.**

-keep class com.chasmet.fondvertstudio.LocalWhisper { *; }

-keep interface com.chasmet.fondvertstudio.LocalWhisper$Progress { *; }
-keepclassmembers class * implements com.chasmet.fondvertstudio.LocalWhisper$Progress { public void update(int); }
