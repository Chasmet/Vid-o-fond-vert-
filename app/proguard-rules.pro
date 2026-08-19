-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_segmentation_subject.** { *; }
-dontwarn com.google.mlkit.**

# Le raccourci Téléchargements appelle ces membres du mode Clip via réflexion.
-keepclassmembers class com.chasmet.fondvertstudio.ClipMusicActivity {
    private void loadMusic(android.net.Uri);
    private androidx.camera.video.Recording activeRecording;
}
