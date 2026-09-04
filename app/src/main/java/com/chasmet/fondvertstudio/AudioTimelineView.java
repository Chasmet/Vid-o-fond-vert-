package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.Locale;

/**
 * Timeline audio inspirée d'un éditeur vidéo : règle en secondes, forme d'onde défilante,
 * marqueur AUTO et tête de lecture fixe. Le glissement déplace la piste sous le curseur.
 */
public final class AudioTimelineView extends View {
    public interface OnPositionChangeListener {
        void onPositionChanged(int positionMs, boolean fromUser);
        void onScrubFinished(int positionMs);
    }

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final RectF rounded = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private final int minimumFlingVelocity;

    private float[] waveform = new float[0];
    private int durationMs;
    private int positionMs;
    private int detectedStartMs = -1;
    private float pixelsPerSecond;
    private float downX;
    private int downPositionMs;
    private boolean dragging;
    private VelocityTracker velocityTracker;
    private OnPositionChangeListener listener;

    public AudioTimelineView(Context context) {
        this(context, null);
    }

    public AudioTimelineView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AudioTimelineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        pixelsPerSecond = dp(56f);
        scroller = new OverScroller(context);
        minimumFlingVelocity = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        pixelsPerSecond = clamp(pixelsPerSecond * detector.getScaleFactor(),
                                dp(22f), dp(190f));
                        scroller.forceFinished(true);
                        invalidate();
                        return true;
                    }
                });
        setFocusable(true);
        setClickable(true);
        setContentDescription("Timeline audio. Glisse horizontalement pour choisir le départ");
        configurePaints();
    }

    private void configurePaints() {
        backgroundPaint.setColor(Color.rgb(13, 16, 22));
        trackPaint.setColor(Color.rgb(9, 86, 98));
        waveformPaint.setColor(Color.rgb(37, 221, 235));
        waveformPaint.setStrokeWidth(dp(1.5f));
        waveformPaint.setStrokeCap(Paint.Cap.ROUND);
        rulerPaint.setColor(Color.rgb(114, 122, 136));
        rulerPaint.setStrokeWidth(dp(1f));
        rulerTextPaint.setColor(Color.rgb(183, 190, 201));
        rulerTextPaint.setTextSize(dp(9f));
        rulerTextPaint.setTextAlign(Paint.Align.CENTER);
        playheadPaint.setColor(Color.WHITE);
        playheadPaint.setStrokeWidth(dp(2f));
        markerPaint.setColor(Color.rgb(255, 187, 46));
        markerPaint.setStrokeWidth(dp(1.5f));
        bubblePaint.setColor(Color.rgb(245, 247, 250));
        bubbleTextPaint.setColor(Color.rgb(12, 15, 20));
        bubbleTextPaint.setTextSize(dp(9f));
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setFakeBoldText(true);
    }

    public void setOnPositionChangeListener(OnPositionChangeListener listener) {
        this.listener = listener;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = Math.max(0, durationMs);
        setPositionInternal(positionMs, false);
        invalidate();
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setPositionMs(int positionMs) {
        setPositionInternal(positionMs, false);
    }

    public int getPositionMs() {
        return positionMs;
    }

    public void setDetectedStartMs(int detectedStartMs) {
        this.detectedStartMs = detectedStartMs < 0 ? -1
                : Math.min(Math.max(0, detectedStartMs), Math.max(0, durationMs));
        invalidate();
    }

    public void setWaveform(float[] waveform) {
        this.waveform = waveform == null ? new float[0] : waveform.clone();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float centerX = width * 0.5f;
        rounded.set(0f, 0f, width, height);
        canvas.drawRoundRect(rounded, dp(10f), dp(10f), backgroundPaint);

        float rulerBottom = dp(25f);
        float trackTop = dp(29f);
        float trackBottom = Math.max(trackTop + dp(28f), height - dp(10f));
        drawRuler(canvas, centerX, rulerBottom);
        drawAudioTrack(canvas, centerX, trackTop, trackBottom);
        drawDetectedMarker(canvas, centerX, trackTop, trackBottom);
        drawPlayhead(canvas, centerX, height);

        if (durationMs <= 0) {
            bubbleTextPaint.setColor(Color.rgb(154, 163, 177));
            bubbleTextPaint.setTextSize(dp(10f));
            canvas.drawText("IMPORTE UNE MUSIQUE POUR AFFICHER LA FORME D'ONDE",
                    centerX, (trackTop + trackBottom) * 0.5f + dp(4f), bubbleTextPaint);
            bubbleTextPaint.setColor(Color.rgb(12, 15, 20));
            bubbleTextPaint.setTextSize(dp(9f));
        }
    }

    private void drawRuler(Canvas canvas, float centerX, float rulerBottom) {
        if (durationMs <= 0) return;
        float secondsVisible = getWidth() / pixelsPerSecond;
        float majorSeconds = secondsVisible <= 4f ? 0.5f
                : secondsVisible <= 10f ? 1f
                : secondsVisible <= 24f ? 2f : 5f;
        float minorSeconds = majorSeconds / 4f;
        float leftTimeMs = positionMs - centerX * 1000f / pixelsPerSecond;
        float rightTimeMs = positionMs + centerX * 1000f / pixelsPerSecond;
        int firstTick = (int) Math.floor(leftTimeMs / (minorSeconds * 1000f));
        int lastTick = (int) Math.ceil(rightTimeMs / (minorSeconds * 1000f));
        int majorEvery = 4;
        for (int tick = firstTick; tick <= lastTick; tick++) {
            float timeMs = tick * minorSeconds * 1000f;
            if (timeMs < 0f || timeMs > durationMs) continue;
            float x = timeToX(timeMs, centerX);
            boolean major = Math.floorMod(tick, majorEvery) == 0;
            canvas.drawLine(x, rulerBottom - (major ? dp(8f) : dp(4f)),
                    x, rulerBottom, rulerPaint);
            if (major) {
                canvas.drawText(formatRuler(Math.round(timeMs)), x, dp(10f), rulerTextPaint);
            }
        }
    }

    private void drawAudioTrack(Canvas canvas, float centerX, float top, float bottom) {
        if (durationMs <= 0) return;
        float startX = timeToX(0f, centerX);
        float endX = timeToX(durationMs, centerX);
        rounded.set(Math.max(0f, startX), top, Math.min(getWidth(), endX), bottom);
        if (rounded.right > rounded.left) {
            canvas.drawRoundRect(rounded, dp(5f), dp(5f), trackPaint);
        }
        if (waveform.length == 0) {
            rulerTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("ANALYSE DE LA FORME D'ONDE…", (startX + endX) * 0.5f,
                    (top + bottom) * 0.5f + dp(3f), rulerTextPaint);
            return;
        }

        float middle = (top + bottom) * 0.5f;
        float maxHalfHeight = (bottom - top) * 0.43f;
        float step = Math.max(1f, dp(2f));
        float visibleStart = Math.max(0f, startX);
        float visibleEnd = Math.min(getWidth(), endX);
        for (float x = visibleStart; x <= visibleEnd; x += step) {
            float timeMs = xToTime(x, centerX);
            int index = Math.min(waveform.length - 1,
                    Math.max(0, Math.round(timeMs * (waveform.length - 1)
                            / Math.max(1f, durationMs))));
            float halfHeight = Math.max(dp(1.5f), waveform[index] * maxHalfHeight);
            canvas.drawLine(x, middle - halfHeight, x, middle + halfHeight, waveformPaint);
        }
    }

    private void drawDetectedMarker(Canvas canvas, float centerX, float top, float bottom) {
        if (detectedStartMs < 0 || durationMs <= 0) return;
        float x = timeToX(detectedStartMs, centerX);
        if (x < -dp(12f) || x > getWidth() + dp(12f)) return;
        canvas.drawLine(x, top, x, bottom, markerPaint);
        triangle.reset();
        triangle.moveTo(x - dp(5f), top);
        triangle.lineTo(x + dp(5f), top);
        triangle.lineTo(x, top + dp(7f));
        triangle.close();
        canvas.drawPath(triangle, markerPaint);
    }

    private void drawPlayhead(Canvas canvas, float centerX, float height) {
        canvas.drawLine(centerX, dp(18f), centerX, height - dp(4f), playheadPaint);
        String label = formatPrecise(positionMs);
        float bubbleWidth = dp(54f);
        rounded.set(centerX - bubbleWidth * 0.5f, dp(1f),
                centerX + bubbleWidth * 0.5f, dp(18f));
        canvas.drawRoundRect(rounded, dp(6f), dp(6f), bubblePaint);
        canvas.drawText(label, centerX, dp(13f), bubbleTextPaint);
        triangle.reset();
        triangle.moveTo(centerX - dp(4f), dp(18f));
        triangle.lineTo(centerX + dp(4f), dp(18f));
        triangle.lineTo(centerX, dp(23f));
        triangle.close();
        canvas.drawPath(triangle, bubblePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || durationMs <= 0) return false;
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
            downX = event.getX();
            downPositionMs = positionMs;
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(event);
            return true;
        }
        if (velocityTracker != null) velocityTracker.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress()) {
            int next = Math.round(downPositionMs
                    - (event.getX() - downX) * 1000f / pixelsPerSecond);
            setPositionInternal(next, true);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && velocityTracker != null
                    && !scaleDetector.isInProgress()) {
                velocityTracker.computeCurrentVelocity(1000);
                float velocityX = velocityTracker.getXVelocity();
                if (Math.abs(velocityX) >= minimumFlingVelocity) {
                    int velocityMs = Math.round(-velocityX * 1000f / pixelsPerSecond);
                    scroller.fling(positionMs, 0, velocityMs, 0,
                            0, Math.max(0, durationMs), 0, 0);
                    postInvalidateOnAnimation();
                }
            }
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            dragging = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (listener != null) listener.onScrubFinished(positionMs);
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            setPositionInternal(scroller.getCurrX(), true);
            postInvalidateOnAnimation();
        } else if (!dragging && listener != null) {
            // Aucun rappel continu : la fin du geste a déjà été signalée dans ACTION_UP.
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void setPositionInternal(int value, boolean fromUser) {
        int clamped = Math.max(0, Math.min(Math.max(0, durationMs), value));
        if (clamped == positionMs && !fromUser) return;
        positionMs = clamped;
        invalidate();
        if (fromUser && listener != null) listener.onPositionChanged(positionMs, true);
        setContentDescription("Départ audio " + formatPrecise(positionMs)
                + ". Glisse horizontalement pour ajuster");
    }

    private float timeToX(float timeMs, float centerX) {
        return centerX + (timeMs - positionMs) * pixelsPerSecond / 1000f;
    }

    private float xToTime(float x, float centerX) {
        return positionMs + (x - centerX) * 1000f / pixelsPerSecond;
    }

    private static String formatRuler(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return String.format(Locale.FRANCE, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String formatPrecise(int millis) {
        int safe = Math.max(0, millis);
        return String.format(Locale.FRANCE, "%02d:%02d.%d",
                safe / 60_000, (safe / 1000) % 60, (safe % 1000) / 100);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
