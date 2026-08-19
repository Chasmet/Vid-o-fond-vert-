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
import java.nio.ByteOrder;

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

        if (pixelStride == 4) {
            ByteBuffer ordered = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int baseOffset = ordered.position();
            boolean deviceUsesRgba = deviceUsesRgbaOrder(
                    ordered, baseOffset, rowStride, width, height);
            for (int y = 0; y < height; y++) {
                int rowOffset = baseOffset + y * rowStride;
                int targetOffset = y * width;
                for (int x = 0; x < width; x++) {
                    int packed = ordered.getInt(rowOffset + x * 4);
                    int channel0 = packed & 0xFF;
                    int channel1 = (packed >>> 8) & 0xFF;
                    int channel2 = (packed >>> 16) & 0xFF;
                    int channel3 = (packed >>> 24) & 0xFF;
                    int alpha;
                    int red;
                    int green;
                    int blue;
                    if (deviceUsesRgba) {
                        red = channel0;
                        green = channel1;
                        blue = channel2;
                        alpha = channel3;
                    } else {
                        alpha = channel0;
                        red = channel1;
                        green = channel2;
                        blue = channel3;
                    }
                    pixels[targetOffset + x] = Color.argb(alpha, red, green, blue);
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        }

        int baseOffset = buffer.position();
        for (int y = 0; y < height; y++) {
            int rowOffset = baseOffset + y * rowStride;
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

    /**
     * CameraX documents A-R-G-B bytes for this output format, but a small number
     * of vendor camera stacks expose R-G-B-A. The alpha channel is opaque for a
     * camera frame, so sampling both ends lets us correct the order safely.
     */
    private static boolean deviceUsesRgbaOrder(ByteBuffer buffer, int baseOffset,
                                               int rowStride, int width, int height) {
        int samples = 0;
        int opaqueFirst = 0;
        int opaqueLast = 0;
        int gridX = Math.max(1, width / 8);
        int gridY = Math.max(1, height / 8);
        for (int y = gridY / 2; y < height && samples < 64; y += gridY) {
            for (int x = gridX / 2; x < width && samples < 64; x += gridX) {
                int offset = baseOffset + y * rowStride + x * 4;
                if (offset + 3 >= buffer.limit()) {
                    continue;
                }
                if ((buffer.get(offset) & 0xFF) >= 252) {
                    opaqueFirst++;
                }
                if ((buffer.get(offset + 3) & 0xFF) >= 252) {
                    opaqueLast++;
                }
                samples++;
            }
        }
        return samples > 0 && opaqueLast > opaqueFirst + Math.max(4, samples / 5);
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
        int[] sourcePixels = new int[width * height];
        int[] outputPixels = new int[width * height];
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height);

        float[] refinedMask = refineMask(mask, maskWidth, maskHeight);
        float edge0 = clamp(threshold - softness, 0f, 1f);
        float edge1 = clamp(threshold + softness, edge0 + 0.001f, 1f);
        float xScale = width <= 1 ? 0f : (float) (maskWidth - 1) / (width - 1);
        float yScale = height <= 1 ? 0f : (float) (maskHeight - 1) / (height - 1);
        for (int y = 0; y < height; y++) {
            float sourceY = y * yScale;
            int y0 = Math.min(maskHeight - 1, (int) sourceY);
            int y1 = Math.min(maskHeight - 1, y0 + 1);
            float fy = sourceY - y0;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float sourceX = x * xScale;
                int x0 = Math.min(maskWidth - 1, (int) sourceX);
                int x1 = Math.min(maskWidth - 1, x0 + 1);
                float fx = sourceX - x0;
                float top = refinedMask[y0 * maskWidth + x0] * (1f - fx)
                        + refinedMask[y0 * maskWidth + x1] * fx;
                float bottom = refinedMask[y1 * maskWidth + x0] * (1f - fx)
                        + refinedMask[y1 * maskWidth + x1] * fx;
                float confidence = top * (1f - fy) + bottom * fy;
                float alpha = smoothStep(edge0, edge1, confidence);
                int original = sourcePixels[row + x];
                int originalAlpha = Color.alpha(original);
                int finalAlpha = Math.round(originalAlpha * alpha);
                int detailed = original;
                if (confidence > 0.78f && x > 0 && x < width - 1
                        && y > 0 && y < height - 1) {
                    detailed = sharpenPixel(sourcePixels, width, x, y, original);
                }
                outputPixels[row + x] = (detailed & 0x00FFFFFF) | (finalAlpha << 24);
            }
        }
        return Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888);
    }

    public static Bitmap createAlphaMask(float[] mask, int width, int height,
                                         float threshold, float softness) {
        float[] refined = refineMask(mask, width, height);
        int[] pixels = new int[width * height];
        float edge0 = clamp(threshold - softness, 0f, 1f);
        float edge1 = clamp(threshold + softness, edge0 + 0.001f, 1f);
        for (int index = 0; index < refined.length; index++) {
            int alpha = Math.round(255f * smoothStep(edge0, edge1, refined[index]));
            pixels[index] = (alpha << 24) | 0x00FFFFFF;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int sharpenPixel(int[] pixels, int width, int x, int y, int center) {
        int left = pixels[y * width + x - 1];
        int right = pixels[y * width + x + 1];
        int top = pixels[(y - 1) * width + x];
        int bottom = pixels[(y + 1) * width + x];
        int red = sharpenChannel(Color.red(center), Color.red(left), Color.red(right),
                Color.red(top), Color.red(bottom));
        int green = sharpenChannel(Color.green(center), Color.green(left), Color.green(right),
                Color.green(top), Color.green(bottom));
        int blue = sharpenChannel(Color.blue(center), Color.blue(left), Color.blue(right),
                Color.blue(top), Color.blue(bottom));
        return Color.argb(Color.alpha(center), red, green, blue);
    }

    private static int sharpenChannel(int center, int left, int right, int top, int bottom) {
        float detail = 4f * center - left - right - top - bottom;
        return clampInt(Math.round(center + 0.16f * detail), 0, 255);
    }

    private static float[] refineMask(float[] source, int width, int height) {
        float[] horizontal = new float[source.length];
        float[] output = new float[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int left = row + Math.max(0, x - 1);
                int center = row + x;
                int right = row + Math.min(width - 1, x + 1);
                horizontal[center] = (source[left] + 2f * source[center]
                        + source[right]) * 0.25f;
            }
        }
        for (int y = 0; y < height; y++) {
            int previousRow = Math.max(0, y - 1) * width;
            int row = y * width;
            int nextRow = Math.min(height - 1, y + 1) * width;
            for (int x = 0; x < width; x++) {
                output[row + x] = (horizontal[previousRow + x]
                        + 2f * horizontal[row + x]
                        + horizontal[nextRow + x]) * 0.25f;
            }
        }
        for (int index = 0; index < output.length; index++) {
            float original = source[index];
            if (original > 0.04f && original < 0.96f) {
                output[index] = clamp(original + 0.85f * (original - output[index]),
                        0f, 1f);
            } else {
                output[index] = original;
            }
        }
        return output;
    }

    public static Bitmap scaleDown(Bitmap source, int maxDimension) {
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest <= maxDimension) {
            return source;
        }
        float scale = (float) maxDimension / largest;
        int width = Math.max(2, Math.round(source.getWidth() * scale));
        int height = Math.max(2, Math.round(source.getHeight() * scale));
        return Bitmap.createScaledBitmap(source, width, height, true);
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
            if (background.getWidth() == targetWidth && background.getHeight() == targetHeight) {
                canvas.drawBitmap(background, 0f, 0f, null);
            } else {
                Bitmap cropped = centerCrop(background, targetWidth, targetHeight);
                canvas.drawBitmap(cropped, 0f, 0f, null);
                cropped.recycle();
            }
        } else {
            canvas.drawColor(color);
        }
        if (cutout.getWidth() == targetWidth && cutout.getHeight() == targetHeight) {
            canvas.drawBitmap(cutout, 0f, 0f, null);
        } else {
            Bitmap subject = centerCrop(cutout, targetWidth, targetHeight);
            canvas.drawBitmap(subject, 0f, 0f, null);
            subject.recycle();
        }
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

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
