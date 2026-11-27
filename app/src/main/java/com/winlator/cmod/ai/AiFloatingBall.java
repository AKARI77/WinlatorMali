package com.winlator.cmod.ai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The floating ball that sits over the Wine surface.
 *
 * Drawn rather than inflated, because it must be tiny, always on top, and must not steal focus from
 * the X server surface. Touch handling distinguishes a tap (open chat) from a drag (reposition), and
 * consumes every event it receives so a stray tap never lands on the game underneath.
 */
public class AiFloatingBall extends View {

    public interface Listener {
        void onTap();
        void onMoved(float x, float y);
    }

    private static final int BASE_RADIUS_DP = 22;
    private static final int TAP_SLOP_DP = 8;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF circle = new RectF();

    private Listener listener;
    private float radiusPx;
    private float tapSlopPx;

    private float downRawX, downRawY;
    private float startX, startY;
    private boolean dragging;
    private boolean busy;

    public AiFloatingBall(Context context) {
        super(context);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        radiusPx = BASE_RADIUS_DP * density;
        tapSlopPx = TAP_SLOP_DP * density;

        fillPaint.setColor(Color.parseColor("#E6202A38"));
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(Color.parseColor("#66FFFFFF"));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.5f * density);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(15f * density);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        setClickable(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        // The X server surface must keep the focus; the ball only reacts to touch.
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Shows a pulsing accent while a request is in flight. */
    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) (radiusPx * 2) + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        circle.set(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx);

        if (busy) {
            // Soft outer glow so the user can tell it is thinking.
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(Color.parseColor("#5580C0FF"));
            glow.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radiusPx + 5f * getResources().getDisplayMetrics().density, glow);
        }

        canvas.drawRoundRect(circle, radiusPx, radiusPx, fillPaint);
        canvas.drawRoundRect(circle, radiusPx, radiusPx, strokePaint);

        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float textY = cy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText("AI", cx, textY, textPaint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startX = getX();
                startY = getY();
                dragging = false;
                setAlpha(0.75f);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > tapSlopPx) dragging = true;
                if (dragging) {
                    moveTo(startX + dx, startY + dy);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                setAlpha(1f);
                if (dragging) {
                    if (listener != null) listener.onMoved(getX(), getY());
                }
                else {
                    if (listener != null) listener.onTap();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                setAlpha(1f);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /** Moves within the parent bounds, clamping so the ball cannot be dragged off screen. */
    private void moveTo(float x, float y) {
        View parent = (View) getParent();
        if (parent == null) return;
        float maxX = parent.getWidth() - getWidth();
        float maxY = parent.getHeight() - getHeight();
        setX(Math.max(0f, Math.min(x, Math.max(0f, maxX))));
        setY(Math.max(0f, Math.min(y, Math.max(0f, maxY))));
    }

    /** Places the ball at a saved position, clamped to the current parent size. */
    public void applyPosition(float x, float y) {
        post(() -> {
            View parent = (View) getParent();
            if (parent == null) return;
            float maxX = parent.getWidth() - getWidth();
            float maxY = parent.getHeight() - getHeight();
            if (x < 0 || y < 0 || x > maxX || y > maxY) {
                // First run or rotation: park it on the right edge, upper third.
                setX(Math.max(0f, maxX - 8f));
                setY(Math.max(0f, maxY / 3f));
            }
            else {
                setX(x);
                setY(y);
            }
        });
    }

    /** Attaches the ball to a container, creating the layout params it needs. */
    public void attachTo(FrameLayout parent) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(this, params);
    }
}
