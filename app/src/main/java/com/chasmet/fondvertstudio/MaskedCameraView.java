package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Affiche le flux caméra et son masque avec le compositeur matériel Android.
 * Le processeur ne fabrique plus un bitmap Full HD détouré à chaque frame.
 */
public final class MaskedCameraView extends View {
    private final Paint cameraPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Matrix drawMatrix = new Matrix();
    private Bitmap source;
    private Bitmap maskBitmap;
    private float[] mask;
    private int maskWidth;
    private int maskHeight;
    private float threshold;
    private float softness;

    public MaskedCameraView(Context context) {
        this(context, null);
    }

    public MaskedCameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaskedCameraView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public void setFrame(Bitmap newSource, float[] newMask, int newMaskWidth,
                         int newMaskHeight, float newThreshold, float newSoftness) {
        source = newSource;
        mask = newMask;
        maskWidth = newMaskWidth;
        maskHeight = newMaskHeight;
        threshold = newThreshold;
        softness = newSoftness;
        rebuildMaskBitmap();
        invalidate();
    }

    public void updateEdgeSettings(float newThreshold, float newSoftness) {
        threshold = newThreshold;
        softness = newSoftness;
        rebuildMaskBitmap();
        invalidate();
    }

    public void clearFrame() {
        source = null;
        mask = null;
        recycleMaskBitmap();
        invalidate();
    }

    private void rebuildMaskBitmap() {
        recycleMaskBitmap();
        if (mask == null || maskWidth <= 0 || maskHeight <= 0) return;
        maskBitmap = BitmapUtils.createAlphaMask(mask, maskWidth, maskHeight,
                threshold, softness);
    }

    private void recycleMaskBitmap() {
        if (maskBitmap != null && !maskBitmap.isRecycled()) maskBitmap.recycle();
        maskBitmap = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap frame = source;
        Bitmap alpha = maskBitmap;
        if (frame == null || alpha == null || frame.isRecycled() || alpha.isRecycled()) return;

        float scale = Math.max((float) getWidth() / frame.getWidth(),
                (float) getHeight() / frame.getHeight());
        float left = (getWidth() - frame.getWidth() * scale) * 0.5f;
        float top = (getHeight() - frame.getHeight() * scale) * 0.5f;
        drawMatrix.reset();
        drawMatrix.postScale(scale, scale);
        drawMatrix.postTranslate(left, top);

        int layer = canvas.saveLayer(new RectF(0, 0, getWidth(), getHeight()), null);
        canvas.drawBitmap(frame, drawMatrix, cameraPaint);

        RectF destination = new RectF(left, top,
                left + frame.getWidth() * scale,
                top + frame.getHeight() * scale);
        canvas.drawBitmap(alpha, null, destination, maskPaint);
        canvas.restoreToCount(layer);
    }

    @Override
    protected void onDetachedFromWindow() {
        recycleMaskBitmap();
        super.onDetachedFromWindow();
    }
}
