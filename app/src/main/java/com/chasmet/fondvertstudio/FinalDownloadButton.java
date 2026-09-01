package com.chasmet.fondvertstudio;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

/**
 * Bouton final du montage. Il garde un libellé explicite même si l'activité
 * réutilise encore l'ancien texte « Télécharger le clip ».
 */
public final class FinalDownloadButton extends MaterialButton {
    private static final String OLD_LABEL = "TÉLÉCHARGER LE CLIP";
    private static final String NEW_LABEL = "TÉLÉCHARGER LA VIDÉO";

    public FinalDownloadButton(@NonNull Context context) {
        super(context);
    }

    public FinalDownloadButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FinalDownloadButton(@NonNull Context context, @Nullable AttributeSet attrs,
                               int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, TextView.BufferType type) {
        super.setText(normalizeLabel(text), type);
    }

    private static CharSequence normalizeLabel(CharSequence text) {
        if (text == null) return null;
        String value = text.toString();
        if (value.contains(OLD_LABEL)) {
            return value.replace(OLD_LABEL, NEW_LABEL);
        }
        return text;
    }
}
