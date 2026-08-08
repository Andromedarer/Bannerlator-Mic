package com.winlator.star.display;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Version-A "game on the TV, handheld as the controller" spike.
 *
 * When Android exposes a secondary presentation-capable display (wired USB-C→HDMI / DeX, or a
 * system wireless-display / Cast virtual display), we lift the existing {@code XServerView} out of
 * the phone's game host and drop it into an {@link Presentation} on that display. The game keeps
 * rendering to its single {@code SurfaceView} — the surface just now lives on the TV — so no host
 * renderer changes are needed (the {@code SurfaceHolder} callbacks fire destroy→create on the move
 * and the renderer re-inits onto the new surface).
 *
 * Input stays on the phone: the {@link Presentation} is created NOT_FOCUSABLE / NOT_TOUCH_MODAL so
 * it never steals focus, and physical controller events still route to the (phone-side) focused
 * activity. On a handheld like the AYANEO the built-in pad drives the game with no extra plumbing.
 *
 * This is a deliberately AUTO-swapping test with no UI: connect a display and the game jumps to it,
 * disconnect and it jumps back. It proves the mechanic before we build the real in-game toggle and
 * the phone-side touchpad/keyboard input modes (the "Version A" production feature).
 */
public class ExternalDisplayController {
    private static final String TAG = "ExtDisplaySwap";

    private final Activity activity;
    private final View gameView;          // the XServerView (owns the render SurfaceView)
    private final ViewGroup internalHost; // FLXServerDisplay on the phone

    private final DisplayManager displayManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private GamePresentation presentation;
    private boolean gameOnExternal = false;
    private boolean enabled = true;

    public ExternalDisplayController(Activity activity, View gameView, ViewGroup internalHost) {
        this.activity = activity;
        this.gameView = gameView;
        this.internalHost = internalHost;
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { update(); }

        @Override public void onDisplayRemoved(int displayId) {
            if (presentation != null && presentation.getDisplay().getDisplayId() == displayId) dismiss();
            update();
        }

        @Override public void onDisplayChanged(int displayId) { update(); }
    };

    public void start() {
        if (displayManager == null) return;
        displayManager.registerDisplayListener(displayListener, mainHandler);
        update();
    }

    public void stop() {
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
        dismiss();
        moveGameToInternal(); // make sure the game is back on the phone as we tear down
    }

    /** Re-evaluate on resume in case a display was (un)plugged while we were away. */
    public void onResume() { update(); }

    private Display findPresentationDisplay() {
        if (displayManager == null) return null;
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : displays) {
            // "HiddenDisplay" is a virtual overlay some OEMs expose; never target it.
            if (d != null && !"HiddenDisplay".equals(d.getName())) return d;
        }
        return null;
    }

    private void update() {
        Display target = enabled ? findPresentationDisplay() : null;
        if (target == null) {
            moveGameToInternal();
            dismiss();
            return;
        }
        boolean needsNew = presentation == null
                || presentation.getDisplay().getDisplayId() != target.getDisplayId();
        if (needsNew) {
            dismiss();
            GamePresentation p = new GamePresentation(activity, target);
            try {
                p.show();
            } catch (WindowManager.InvalidDisplayException e) {
                Log.w(TAG, "presentation display went away before show()", e);
                return;
            }
            presentation = p;
        }
        moveGameToExternal();
    }

    private void moveGameToExternal() {
        if (presentation == null) return;
        FrameLayout root = presentation.getRoot();
        if (root == null) return;
        ViewGroup parent = (ViewGroup) gameView.getParent();
        if (parent == root) return;
        if (parent != null) parent.removeView(gameView);
        gameView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(gameView);
        if (!gameOnExternal) {
            gameOnExternal = true;
            toast("Game moved to external display — use the handheld as the controller");
        }
    }

    private void moveGameToInternal() {
        ViewGroup parent = (ViewGroup) gameView.getParent();
        if (parent == internalHost) { gameOnExternal = false; return; }
        if (parent != null) parent.removeView(gameView);
        gameView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // keep the game behind the on-phone overlays / dialog host (they were added after it)
        internalHost.addView(gameView, 0);
        if (gameOnExternal) {
            gameOnExternal = false;
            toast("Game returned to the phone screen");
        }
    }

    private void dismiss() {
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
            presentation = null;
        }
    }

    private void toast(final String msg) {
        mainHandler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }

    /** A bare Presentation whose sole content is a full-screen FrameLayout we reparent the game into. */
    private static class GamePresentation extends Presentation {
        private FrameLayout root;

        GamePresentation(Context outerContext, Display display) { super(outerContext, display); }

        FrameLayout getRoot() { return root; }

        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            root = new FrameLayout(getContext());
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Don't take focus/touch from the phone — the phone stays the input device.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            setContentView(root);
        }
    }
}
