package com.winlator.star.widget;

import android.content.Context;
import android.os.SystemClock;

import com.winlator.star.xenvironment.ImageFs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single authoritative FPS source shared by every in-game HUD overlay.
 *
 * <p>{@link #tick()} is invoked once per presented frame from the X-server epoll thread, so it is
 * counter math ONLY — no view access. Every overlay (classic {@code FrameRating}/
 * {@code FrameRatingHorizontal}, the GameHub-style {@code PerfHudView}, and the GameNative-style
 * {@code PerformanceHudView}) reads {@link #getCurrentFPS()} for the number it draws, which
 * guarantees they all show the identical value and there is one place per renderer to feed.
 *
 * <p>FPS math mirrors the historical per-overlay counters (500 ms compute window). Session
 * avg/min/max + length are ported from GameNative's {@code FrameRating}. Thread-safe: {@code tick()}
 * and the session getters are synchronized; {@code lastFPS} is volatile for lock-free reads from the
 * overlay refresh threads.
 */
public class FpsCounter {
    private static final int COMPUTE_WINDOW_MS = 500;
    private static final int READING_INTERVAL_MS = 1000;

    private long lastTime = 0;
    private int frameCount = 0;
    private volatile float lastFPS = 0;

    // ---- Session statistics (ported from GameNative FrameRating) ----------
    private long sessionStartTime = 0;
    private int readingCount = 0;
    private int maxFPS = 0;
    private int minFPS = Integer.MAX_VALUE;
    private long lastReadingTime = 0;
    private long fpsSum = 0;

    /** Count one presented frame. Cheap, thread-safe, no view access. */
    public synchronized void tick() {
        long now = SystemClock.elapsedRealtime();
        if (lastTime == 0) {
            lastTime = now;
            sessionStartTime = now;
        }
        if (now >= lastTime + COMPUTE_WINDOW_MS) {
            lastFPS = ((float) (frameCount * 1000) / (now - lastTime));

            // Session readings sampled at a coarser 1 s cadence for avg/min/max.
            if (lastReadingTime == 0 || now >= lastReadingTime + READING_INTERVAL_MS) {
                int currentFPS = Math.round(lastFPS);
                readingCount++;
                fpsSum += currentFPS;
                if (currentFPS > maxFPS) maxFPS = currentFPS;
                if (currentFPS > 1 && currentFPS < minFPS) minFPS = currentFPS;
                lastReadingTime = now;
            }

            lastTime = now;
            frameCount = 0;
        }
        frameCount++;
    }

    /** Most recent measured FPS. Lock-free (volatile). */
    public float getCurrentFPS() { return lastFPS; }

    public synchronized float getAvgFPS() {
        return readingCount == 0 ? 0f : (float) fpsSum / readingCount;
    }

    public synchronized int getMinFPS() {
        return minFPS == Integer.MAX_VALUE ? 0 : minFPS;
    }

    public synchronized int getMaxFPS() { return maxFPS; }

    public synchronized float getSessionLengthSec() {
        return sessionStartTime == 0 ? 0f : (SystemClock.elapsedRealtime() - sessionStartTime) / 1000.0f;
    }

    /** Clear all counters + session stats (called when the game window is unmapped). */
    public synchronized void reset() {
        lastTime = 0;
        frameCount = 0;
        lastFPS = 0;
        sessionStartTime = 0;
        readingCount = 0;
        maxFPS = 0;
        minFPS = Integer.MAX_VALUE;
        lastReadingTime = 0;
        fpsSum = 0;
    }

    /**
     * Persist a JSON summary of the current session to {@code $TMP/fps_session.json} on a background
     * thread. Ported from GameNative's FrameRating.writeSessionSummary. No-op if no readings yet.
     */
    public void writeSessionSummary(Context context) {
        final int rc;
        final long lengthMs;
        final int max;
        final int min;
        final float avg;
        synchronized (this) {
            if (readingCount == 0) return;
            rc = readingCount;
            lengthMs = sessionStartTime > 0 ? SystemClock.elapsedRealtime() - sessionStartTime : 0;
            max = maxFPS;
            min = minFPS == Integer.MAX_VALUE ? 0 : minFPS;
            avg = (float) fpsSum / readingCount;
        }
        final float lengthSec = lengthMs / 1000.0f;
        final ImageFs imageFs = ImageFs.find(context);
        final File out = new File(imageFs.getTmpDir(), "fps_session.json");
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                if (!out.exists()) out.createNewFile();
                String json = String.format(Locale.ENGLISH,
                    "{\n" +
                    "  \"length_sec\": %.2f,\n" +
                    "  \"avg_fps\": %.1f,\n" +
                    "  \"max_fps\": %d,\n" +
                    "  \"min_fps\": %d,\n" +
                    "  \"readings\": %d\n" +
                    "}\n",
                    lengthSec, avg, max, min, rc);
                try (FileWriter fw = new FileWriter(out, false)) {
                    fw.write(json);
                    fw.flush();
                }
            } catch (IOException ignored) {
            } finally {
                executor.shutdown();
            }
        });
    }
}
