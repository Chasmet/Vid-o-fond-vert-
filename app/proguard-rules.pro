-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_segmentation_subject.** { *; }
-dontwarn com.google.mlkit.**

# ONNX Runtime recommande de conserver ses classes lorsque R8 est actif.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Le raccourci Téléchargements appelle ces membres du mode Clip via réflexion.
-keepclassmembers class com.chasmet.fondvertstudio.ClipMusicActivity {
    private void loadMusic(android.net.Uri);
    private androidx.camera.video.Recording activeRecording;
}
