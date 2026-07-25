package com.winlator.star.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shared touch + lock behaviour for every in-game performance HUD (classic FrameRating(+Horizontal),
 * GameHub PerfHudView, GameNative PerformanceHudView, and the Fusion HUD). One place so the four
 * overlays can't drift:
 *
 * <ul>
 *   <li><b>tap</b> → {@link Callbacks#onTap()} (orientation-toggle for the first three; size-cycle for
 *       Fusion) — a no-op that only re-flashes the lock badge while locked.</li>
 *   <li><b>drag</b> → moves the host view and, on release, {@link Callbacks#onMoved} to persist the
 *       position — disabled (frozen) while locked.</li>
 *   <li><b>long-press</b> → toggles {@code hudLocked}, persisted via {@link Callbacks#onLockChanged}
 *       and kept in sync with the "Lock in place" menu chip. Works locked or unlocked (so a long-press
 *       always unlocks).</li>
 * </ul>
 *
 * <p>On every lock/unlock (and on a blocked gesture while locked) a small padlock badge fades in →
 * holds → fades out in a corner of the panel. The fade is driven by a self-reposting Runnable that
 * invalidates only while visible — nothing runs on the present path, nothing animates at rest.
 *
 * <p>All methods are Main-thread only (touch + draw), matching the epoll-thread rule: the views feed
 * this from {@code onTouchEvent}/{@code dispatchDraw}, never from a present tick.
 */
public class HudLockController {

    public interface Callbacks {
        /** Unlocked single tap: orientation-toggle (classic/gamehub/gamenative) or size-cycle (fusion). */
        void onTap();
        /** Drag released (unlocked): persist the new (x, y). */
        void onMoved(float x, float y);
        /** Lock toggled by long-press: persist {@code hudLocked} + sync the menu chip. */
        void onLockChanged(boolean locked);
    }

    // Fade timing (ms): in → hold → out. Lock/unlock hold ~1.5 s; a blocked-gesture re-flash is shorter.
    private static final long FADE_IN_MS = 160;
    private static final long HOLD_TOGGLE_MS = 1500;
    private static final long HOLD_REFLASH_MS = 750;
    private static final long FADE_OUT_MS = 420;
    private static final long FRAME_MS = 32;

    private final View view;
    private final Callbacks cb;
    private final GestureDetector detector;

    private boolean locked = false;

    // Drag state
    private float downRawX, downRawY, startX, startY;
    private boolean dragging = false;

    // Fade state
    private long fadeStart = 0;
    private long holdMs = HOLD_TOGGLE_MS;
    private boolean fadeShowLocked = false;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HudLockController(Context context, View view, Callbacks cb) {
        this.view = view;
        this.cb = cb;
        this.detector = new GestureDetector(context, new Listener());
        this.detector.setIsLongpressEnabled(true);
        iconFill.setStyle(Paint.Style.FILL);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Set lock state from config (applyConfig) WITHOUT firing the persist callback. */
    public void setLocked(boolean locked) { this.locked = locked; }

    public boolean isLocked() { return locked; }

    /** Feed from the host view's onTouchEvent. Always consumes (mirrors the legacy manual handlers). */
    public boolean onTouchEvent(MotionEvent event) {
        detector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging && !locked) cb.onMoved(view.getX(), view.getY());
            dragging = false;
        }
        return true;
    }

    private final class Listener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            downRawX = e.getRawX();
            downRawY = e.getRawY();
            startX = view.getX();
            startY = view.getY();
            dragging = false;
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            // Long-press always toggles — this is the only gesture that still works while locked.
            locked = !locked;
            cb.onLockChanged(locked);
            flash(locked, HOLD_TOGGLE_MS);
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (locked) {
                flash(true, HOLD_REFLASH_MS);   // intentionally locked — remind the user
            } else {
                cb.onTap();
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (locked) {
                if (!dragging) flash(true, HOLD_REFLASH_MS);
                dragging = true;   // mark so the blocked drag doesn't fall through to a tap
                return true;
            }
            view.setX(startX + (e2.getRawX() - downRawX));
            view.setY(startY + (e2.getRawY() - downRawY));
            dragging = true;
            return true;
        }
    }

    // ---- Fade badge -------------------------------------------------------
    private void flash(boolean showLocked, long hold) {
        fadeShowLocked = showLocked;
        holdMs = hold;
        fadeStart = SystemClock.elapsedRealtime();
        view.removeCallbacks(fadeTick);
        view.post(fadeTick);
    }

    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            view.invalidate();
            if (currentAlpha() > 0f) view.postDelayed(this, FRAME_MS);
        }
    };

    private float currentAlpha() {
        if (fadeStart == 0) return 0f;
        long e = SystemClock.elapsedRealtime() - fadeStart;
        if (e < 0) return 0f;
        if (e < FADE_IN_MS) return e / (float) FADE_IN_MS;
        long holdEnd = FADE_IN_MS + holdMs;
        if (e < holdEnd) return 1f;
        long outEnd = holdEnd + FADE_OUT_MS;
        if (e < outEnd) return 1f - (e - holdEnd) / (float) FADE_OUT_MS;
        return 0f;
    }

    private static int withAlpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * Draw the padlock badge in the top-right of the panel if a fade is in progress. Call from the
     * host's onDraw (plain View) or dispatchDraw (ViewGroup), after the content.
     */
    public void drawBadge(Canvas canvas) {
        float a = currentAlpha();
        if (a <= 0f) return;
        float d = view.getResources().getDisplayMetrics().density;
        float size = 15f * d;
        float margin = 4f * d;
        float right = view.getWidth() - margin;
        float left = right - size;
        float top = margin;
        float bottom = top + size;

        // Rounded translucent backing so the glyph reads on any game frame.
        bgPaint.setColor(Color.argb(Math.round(160 * a), 0, 0, 0));
        canvas.drawRoundRect(left - 3f * d, top - 3f * d, right + 3f * d, bottom + 3f * d,
            4f * d, 4f * d, bgPaint);

        int glyph = withAlpha(0xFFFFFFFF, a);
        iconFill.setColor(glyph);
        iconStroke.setColor(glyph);
        iconStroke.setStrokeWidth(1.7f * d);

        float cx = (left + right) / 2f;
        float bodyTop = top + size * 0.46f;
        float bodyLeft = left + size * 0.14f;
        float bodyRight = right - size * 0.14f;
        // Body
        canvas.drawRoundRect(bodyLeft, bodyTop, bodyRight, bottom, 2.4f * d, 2.4f * d, iconFill);
        // Keyhole
        iconFill.setColor(Color.argb(Math.round(160 * a), 0, 0, 0));
        float kh = (bodyTop + bottom) / 2f;
        canvas.drawCircle(cx, kh, 1.6f * d, iconFill);
        iconFill.setColor(glyph);

        // Shackle
        float r = size * 0.2f;
        float shackleTop = top + size * 0.08f;
        RectF arc = new RectF(cx - r, shackleTop, cx + r, shackleTop + 2f * r);
        Path p = new Path();
        p.addArc(arc, 180f, 180f);                 // top ∩
        if (fadeShowLocked) {
            // Closed: both legs reach the body.
            p.moveTo(cx - r, shackleTop + r); p.lineTo(cx - r, bodyTop);
            p.moveTo(cx + r, shackleTop + r); p.lineTo(cx + r, bodyTop);
            canvas.drawPath(p, iconStroke);
        } else {
            // Open: hinge the whole shackle up-right off the right leg so the left side lifts away.
            p.moveTo(cx - r, shackleTop + r); p.lineTo(cx - r, bodyTop - size * 0.05f);
            p.moveTo(cx + r, shackleTop + r); p.lineTo(cx + r, bodyTop);
            canvas.save();
            canvas.rotate(-26f, cx + r, bodyTop);
            canvas.drawPath(p, iconStroke);
            canvas.restore();
        }
    }
}
