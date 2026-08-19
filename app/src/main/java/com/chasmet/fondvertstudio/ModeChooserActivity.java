package com.chasmet.fondvertstudio;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public final class ModeChooserActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_chooser);

        MaterialButton classicButton = findViewById(R.id.classicModeButton);
        MaterialButton musicButton = findViewById(R.id.musicModeButton);

        classicButton.setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)));
        musicButton.setOnClickListener(v ->
                startActivity(new Intent(this, ClipTimelineActivity.class)));
    }
}
