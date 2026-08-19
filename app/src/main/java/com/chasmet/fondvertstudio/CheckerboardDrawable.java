package com.chasmet.fondvertstudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class CheckerboardDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int cellSize;
    private final int lightColor;
    private final int darkColor;

    public CheckerboardDrawable(int cellSizePx) {
        cellSize = Math.max(8, cellSizePx);
        lightColor = Color.rgb(216, 220, 227);
        darkColor = Color.rgb(174, 181, 192);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        int width = getBounds().width();
        int height = getBounds().height();
        for (int y = 0; y < height; y += cellSize) {
            for (int x = 0; x < width; x += cellSize) {
                boolean light = ((x / cellSize) + (y / cellSize)) % 2 == 0;
                paint.setColor(light ? lightColor : darkColor);
                canvas.drawRect(x, y, Math.min(x + cellSize, width),
                        Math.min(y + cellSize, height), paint);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
