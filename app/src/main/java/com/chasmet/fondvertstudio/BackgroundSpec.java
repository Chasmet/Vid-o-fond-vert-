package com.chasmet.fondvertstudio;

import android.graphics.Color;
import android.net.Uri;

public final class BackgroundSpec {
    public enum Type {
        TRANSPARENT,
        COLOR,
        IMAGE,
        VIDEO
    }

    private Type type = Type.TRANSPARENT;
    private int color = Color.TRANSPARENT;
    private Uri uri;

    public Type getType() {
        return type;
    }

    public int getColor() {
        return color;
    }

    public Uri getUri() {
        return uri;
    }

    public void setTransparent() {
        type = Type.TRANSPARENT;
        color = Color.TRANSPARENT;
        uri = null;
    }

    public void setColor(int value) {
        type = Type.COLOR;
        color = value;
        uri = null;
    }

    public void setImage(Uri value) {
        type = Type.IMAGE;
        uri = value;
    }

    public void setVideo(Uri value) {
        type = Type.VIDEO;
        uri = value;
    }
}
