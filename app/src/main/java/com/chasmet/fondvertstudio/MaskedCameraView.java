package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Affiche le flux caméra et son masque avec le compositeur matériel Android.
 * Le processeur ne fabrique plus un bitmap Full HD détouré à chaque frame.
 */
public final class MaskedCameraView extends View {
    public interface TransformListener {
        void onTransformChanged(float scale, float centerX, float centerY,
                                boolean gestureFinished);
    }

    private final Paint cameraPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private Bitmap source;
    private Bitmap maskBitmap;
    private float[] mask;
    private int maskWidth;
    private int maskHeight;
    private float threshold;
    private float softness;
    private float subjectScale = SubjectTransformTimeline.DEFAULT_SCALE;
    private float subjectCenterX = SubjectTransformTimeline.DEFAULT_CENTER_X;
    private float subjectCenterY = SubjectTransformTimeline.DEFAULT_CENTER_Y;
    private float previousFocusX;
    private float previousFocusY;
    private float previousDistance;
    private boolean gestureMoved;
    private TransformListener transformListener;

    public MaskedCameraView(Context context) {
        this(context, null);
    }

    public MaskedCameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaskedCameraView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setClickable(true);
        setFocusable(true);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public void setTransformListener(TransformListener listener) {
        transformListener = listener;
    }

    public void setSubjectTransform(float scale, float centerX, float centerY) {
        subjectScale = clamp(scale, SubjectTransformTimeline.MIN_SCALE,
                SubjectTransformTimeline.MAX_SCALE);
        subjectCenterX = clamp(centerX, 0f, 1f);
        subjectCenterY = clamp(centerY, 0f, 1f);
        invalidate();
    }

    public void resetSubjectTransform() {
        setSubjectTransform(SubjectTransformTimeline.DEFAULT_SCALE,
                SubjectTransformTimeline.DEFAULT_CENTER_X,
                SubjectTransformTimeline.DEFAULT_CENTER_Y);
        notifyTransform(true);
    }

    public float getSubjectScale() {
        return subjectScale;
    }

    public float getSubjectCenterX() {
        return subjectCenterX;
    }

    public float getSubjectCenterY() {
        return subjectCenterY;
    }

    public void setFrame(Bitmap newSource, float[] newMask, int newMaskWidth,
                         int newMaskHeight, float newThreshold, float newSoftness) {
        setSource(newSource);
        mask = newMask;
        maskWidth = newMaskWidth;
        maskHeight = newMaskHeight;
        threshold = newThreshold;
        softness = newSoftness;
        rebuildMaskBitmap();
        invalidate();
    }

    public void setSource(Bitmap newSource) {
        source = newSource;
        invalidate();
    }

    public void setMask(Bitmap newMaskBitmap, float[] newMask, int newMaskWidth,
                        int newMaskHeight, float newThreshold, float newSoftness) {
        recycleMaskBitmap();
        maskBitmap = newMaskBitmap;
        mask = newMask;
        maskWidth = newMaskWidth;
        maskHeight = newMaskHeight;
        threshold = newThreshold;
        softness = newSoftness;
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

        float baseScale = Math.max((float) getWidth() / frame.getWidth(),
                (float) getHeight() / frame.getHeight());
        float scale = baseScale * subjectScale;
        float drawnWidth = frame.getWidth() * scale;
        float drawnHeight = frame.getHeight() * scale;
        float left = subjectCenterX * getWidth() - drawnWidth * 0.5f;
        float top = subjectCenterY * getHeight() - drawnHeight * 0.5f;
        RectF destination = new RectF(left, top,
                left + drawnWidth, top + drawnHeight);

        int layer = canvas.saveLayer(new RectF(0, 0, getWidth(), getHeight()), null);
        canvas.drawBitmap(frame, null, destination, cameraPaint);
        canvas.drawBitmap(alpha, null, destination, maskPaint);
        canvas.restoreToCount(layer);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                previousFocusX = event.getX();
                previousFocusY = event.getY();
                previousDistance = 0f;
                gestureMoved = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                previousFocusX = focusX(event);
                previousFocusY = focusY(event);
                previousDistance = pointerDistance(event);
                gestureMoved = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                float focusX = focusX(event);
                float focusY = focusY(event);
                float centerPixelsX = subjectCenterX * getWidth()
                        + focusX - previousFocusX;
                float centerPixelsY = subjectCenterY * getHeight()
                        + focusY - previousFocusY;

                if (event.getPointerCount() >= 2) {
                    float distance = pointerDistance(event);
                    if (previousDistance > 0f && distance > 0f) {
                        float oldScale = subjectScale;
                        float newScale = clamp(oldScale * distance / previousDistance,
                                SubjectTransformTimeline.MIN_SCALE,
                                SubjectTransformTimeline.MAX_SCALE);
                        float ratio = newScale / oldScale;
                        centerPixelsX = focusX + (centerPixelsX - focusX) * ratio;
                        centerPixelsY = focusY + (centerPixelsY - focusY) * ratio;
                        subjectScale = newScale;
                    }
                    previousDistance = distance;
                } else {
                    previousDistance = 0f;
                }

                subjectCenterX = clamp(centerPixelsX / getWidth(), 0f, 1f);
                subjectCenterY = clamp(centerPixelsY / getHeight(), 0f, 1f);
                previousFocusX = focusX;
                previousFocusY = focusY;
                gestureMoved = true;
                invalidate();
                notifyTransform(false);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int remainingIndex = event.getActionIndex() == 0 ? 1 : 0;
                if (remainingIndex < event.getPointerCount()) {
                    previousFocusX = event.getX(remainingIndex);
                    previousFocusY = event.getY(remainingIndex);
                }
                previousDistance = 0f;
                return true;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (!gestureMoved) {
                    performClick();
                }
                notifyTransform(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                notifyTransform(true);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void notifyTransform(boolean gestureFinished) {
        if (transformListener != null) {
            transformListener.onTransformChanged(subjectScale, subjectCenterX,
                    subjectCenterY, gestureFinished);
        }
    }

    private static float focusX(MotionEvent event) {
        float total = 0f;
        for (int index = 0; index < event.getPointerCount(); index++) {
            total += event.getX(index);
        }
        return total / Math.max(1, event.getPointerCount());
    }

    private static float focusY(MotionEvent event) {
        float total = 0f;
        for (int index = 0; index < event.getPointerCount(); index++) {
            total += event.getY(index);
        }
        return total / Math.max(1, event.getPointerCount());
    }

    private static float pointerDistance(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return 0f;
        }
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onDetachedFromWindow() {
        recycleMaskBitmap();
        super.onDetachedFromWindow();
    }
}
