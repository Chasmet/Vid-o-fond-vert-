package com.chasmet.fondvertstudio;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class BitmapUtils {
    private BitmapUtils() {
    }

    public static Bitmap fromRgbaImageProxy(@NonNull ImageProxy imageProxy) {
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            int targetOffset = y * width;
            for (int x = 0; x < width; x++) {
                int offset = rowOffset + x * pixelStride;
                int a = buffer.get(offset) & 0xFF;
                int r = buffer.get(offset + 1) & 0xFF;
                int g = buffer.get(offset + 2) & 0xFF;
                int b = buffer.get(offset + 3) & 0xFF;
                pixels[targetOffset + x] = Color.argb(a, r, g, b);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    public static Bitmap rotateAndMirror(Bitmap source, int degrees, boolean mirror) {
        if (degrees == 0 && !mirror) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        if (mirror) {
            matrix.postScale(-1f, 1f);
        }
        Bitmap transformed = Bitmap.createBitmap(source, 0, 0,
                source.getWidth(), source.getHeight(), matrix, true);
        if (transformed != source) {
            source.recycle();
        }
        return transformed;
    }

    public static Bitmap applyMask(Bitmap source, float[] mask, int maskWidth,
                                   int maskHeight, float threshold, float softness) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);

        float edge0 = clamp(threshold - softness, 0f, 1f);
        float edge1 = clamp(threshold + softness, edge0 + 0.001f, 1f);
        for (int y = 0; y < height; y++) {
            int maskY = Math.min(maskHeight - 1, y * maskHeight / height);
            int row = y * width;
            int maskRow = maskY * maskWidth;
            for (int x = 0; x < width; x++) {
                int maskX = Math.min(maskWidth - 1, x * maskWidth / width);
                float confidence = mask[maskRow + maskX];
                float alpha = smoothStep(edge0, edge1, confidence);
                int original = pixels[row + x];
                int originalAlpha = Color.alpha(original);
                int finalAlpha = Math.round(originalAlpha * alpha);
                pixels[row + x] = (original & 0x00FFFFFF) | (finalAlpha << 24);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    public static Bitmap centerCrop(Bitmap source, int targetWidth, int targetHeight) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        float scale = Math.max((float) targetWidth / source.getWidth(),
                (float) targetHeight / source.getHeight());
        float scaledWidth = source.getWidth() * scale;
        float scaledHeight = source.getHeight() * scale;
        RectF destination = new RectF(
                (targetWidth - scaledWidth) / 2f,
                (targetHeight - scaledHeight) / 2f,
                (targetWidth + scaledWidth) / 2f,
                (targetHeight + scaledHeight) / 2f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, destination, paint);
        return output;
    }

    public static Bitmap composite(Bitmap cutout, Bitmap background, int color,
                                   int targetWidth, int targetHeight) {
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        if (background != null) {
            Bitmap cropped = centerCrop(background, targetWidth, targetHeight);
            canvas.drawBitmap(cropped, 0f, 0f, null);
            cropped.recycle();
        } else {
            canvas.drawColor(color);
        }
        Bitmap subject = centerCrop(cutout, targetWidth, targetHeight);
        canvas.drawBitmap(subject, 0f, 0f, null);
        subject.recycle();
        return output;
    }

    public static Bitmap decodeUri(Context context, Uri uri, int maxDimension) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream stream = resolver.openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            if (bitmap == null) {
                throw new IOException("Image illisible");
            }
            return bitmap;
        }
    }

    public static int[] fitInside(int width, int height, int maxWidth, int maxHeight) {
        float scale = Math.min(1f, Math.min((float) maxWidth / width,
                (float) maxHeight / height));
        int outWidth = makeEven(Math.max(2, Math.round(width * scale)));
        int outHeight = makeEven(Math.max(2, Math.round(height * scale)));
        return new int[]{outWidth, outHeight};
    }

    private static int makeEven(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float t = clamp((value - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
