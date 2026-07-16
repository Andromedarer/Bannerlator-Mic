package com.winlator.star.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Shared live-metric collector for every performance HUD overlay. This is the single, device-complete
 * implementation of the sysfs / Android readers — no overlay should re-read sysfs itself, so the GPU
 * discovery / thermal / battery logic never diverges.
 *
 * <p>Ported and hardened from GameNative's {@code PerformanceHudView} readers, which are far more
 * device-complete than the two-fixed-path versions previously inlined in the classic overlays:
 * <ul>
 *   <li>GPU load — ~10 static candidates plus a dynamic {@code /sys/class/devfreq} +
 *       {@code /sys/devices/virtual/devfreq} walk for gpu/mali/g3d/kgsl nodes; handles gpubusy
 *       (busy/total), Mali gpuinfo (delta-ms/wall-ms, needs state between calls) and generic percent
 *       nodes. Discovery cached.</li>
 *   <li>CPU usage — {@code /proc/stat} aggregate delta with a scaling-frequency fallback for devices
 *       where {@code /proc/stat} is restricted.</li>
 *   <li>Thermal — prioritized CPU zone discovery (cpu-silicon &gt; cpu-0 &gt; cpu &gt; soc &gt;
 *       s5p-tmu &gt; cputop &gt; tsens &gt; cluster &gt; big/little) and a separate prioritized GPU zone
 *       discovery plus kgsl/mali direct paths. milli-°C normalization + 1..150 sanity clamp.</li>
 *   <li>Battery — % capacity, power W, runtime-left estimate (charge_counter / current_now, smoothed),
 *       battery temperature, plus the dual-battery current-sum for the classic overlays.</li>
 * </ul>
 *
 * <p>Every getter is null / absent-safe: nullable getters return {@code null} when a metric cannot be
 * read (the overlay hides that row, matching GameNative behavior); the legacy non-null getters return
 * 0 in that case. Not thread-safe; call from a single refresh thread per instance.
 */
public class HudMetrics {
    private final Context context;

    public HudMetrics(Context context) { this.context = context; }

    // =======================================================================
    // CPU usage
    // =======================================================================
    private Long lastCpuTotal = null;
    private Long lastCpuIdle = null;

    /**
     * Overall device CPU usage 0..100 computed from the /proc/stat delta since the last call, with a
     * scaling-frequency fallback. Returns null only when neither source is readable.
     */
    public Integer getCpuUsagePercent() {
        String line = readFirstLine("/proc/stat");
        if (line != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 5 && "cpu".equals(parts[0])) {
                long total = 0, idle = 0, iowait = 0;
                boolean ok = true;
                for (int i = 1; i < parts.length; i++) {
                    try {
                        long v = Long.parseLong(parts[i]);
                        total += v;
                        if (i == 4) idle = v;
                        else if (i == 5) iowait = v;
                    } catch (NumberFormatException e) { ok = false; break; }
                }
                if (ok) {
                    long idleTotal = idle + iowait;
                    Long prevTotal = lastCpuTotal, prevIdle = lastCpuIdle;
                    lastCpuTotal = total;
                    lastCpuIdle = idleTotal;
                    if (prevTotal != null && prevIdle != null) {
                        long dTotal = total - prevTotal;
                        long dIdle = idleTotal - prevIdle;
                        if (dTotal > 0) {
                            long usage = (Math.max(0, dTotal - dIdle) * 100L) / dTotal;
                            return clampPercent((int) usage);
                        }
                    }
                    // First sample seeds the delta — fall through to the frequency estimate so the
                    // very first read still returns something.
                }
            }
        }
        return readCpuUsagePercentFromFrequency();
    }

    private Integer readCpuUsagePercentFromFrequency() {
        long currentTotal = 0, maxTotal = 0;
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            Long cur = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            Long max = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (cur != null && max != null && max > 0L) {
                currentTotal += Math.max(0, Math.min(cur, max));
                maxTotal += max;
            }
        }
        if (maxTotal <= 0L) return null;
        return clampPercent((int) ((currentTotal * 100L) / maxTotal));
    }

    /** Legacy non-null accessor (0 when unavailable). Kept for {@code PerfHudView}. */
    public float getCPUUsage() {
        Integer p = getCpuUsagePercent();
        return p == null ? 0f : p;
    }

    // =======================================================================
    // GPU load
    // =======================================================================
    private List<String> gpuUsagePathsCache = null;
    private Long lastMaliGpuInfoMs = null;
    private long lastMaliGpuInfoWallMs = 0L;

    /** GPU utilisation 0..100, or null when no readable source is found on this device. */
    public Integer getGpuUsagePercent() {
        for (String path : discoverGpuUsagePaths()) {
            Integer p = readGpuUsageSample(path);
            if (p != null) return p;
        }
        return null;
    }

    /** Legacy non-null accessor (0 when unavailable). Kept for {@code PerfHudView}. */
    public int getGPULoad() {
        Integer p = getGpuUsagePercent();
        return p == null ? 0 : p;
    }

    private List<String> discoverGpuUsagePaths() {
        if (gpuUsagePathsCache != null) return gpuUsagePathsCache;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        String[] staticPaths = {
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/devfreq/gpu/load",
        };
        for (String p : staticPaths) {
            if (new File(p).canRead()) candidates.add(p);
        }

        File[] devfreqRoots = {
            new File("/sys/class/devfreq"),
            new File("/sys/devices/virtual/devfreq"),
        };
        String[] usageFiles = {
            "gpu_busy_percentage", "gpu_load", "utilisation", "utilization", "load", "gpuinfo",
        };
        String[] gpuTokens = {"gpu", "mali", "g3d", "kgsl"};
        for (File root : devfreqRoots) {
            if (!root.isDirectory()) continue;
            File[] nodeDirs = root.listFiles(File::isDirectory);
            if (nodeDirs == null) continue;
            for (File node : nodeDirs) {
                String nodePath = node.getPath().toLowerCase(Locale.US);
                boolean looksLikeGpu = false;
                for (String t : gpuTokens) {
                    if (nodePath.contains(t)) { looksLikeGpu = true; break; }
                }
                for (String fileName : usageFiles) {
                    File f = new File(node, fileName);
                    if (!f.canRead()) continue;
                    if (looksLikeGpu || fileName.equals("gpu_busy_percentage") || fileName.equals("gpuinfo")) {
                        candidates.add(f.getPath());
                    }
                }
            }
        }

        gpuUsagePathsCache = new ArrayList<>(candidates);
        return gpuUsagePathsCache;
    }

    private Integer readGpuUsageSample(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        switch (fileName) {
            case "gpubusy": {
                String raw = readFirstLine(path);
                if (raw == null) return null;
                String[] parts = raw.trim().split("\\s+");
                if (parts.length < 2) return null;
                Long busy = parseLong(parts[0]);
                Long total = parseLong(parts[1]);
                if (busy == null || total == null || total <= 0L) return null;
                return clampPercent((int) ((busy * 100L) / total));
            }
            case "gpuinfo": {
                // Mali multi-line node: the GPU busy time (ms) is the last token of line 1. Utilisation
                // is the delta over wall-clock delta between two reads, so it needs state.
                String line = readNthLine(path, 1);
                if (line == null) return null;
                String[] toks = line.trim().split("\\s+");
                Long gpuMs = parseLong(toks[toks.length - 1]);
                if (gpuMs == null) return null;
                long now = SystemClock.elapsedRealtime();
                Long prevMs = lastMaliGpuInfoMs;
                long prevWall = lastMaliGpuInfoWallMs;
                lastMaliGpuInfoMs = gpuMs;
                lastMaliGpuInfoWallMs = now;
                if (prevMs == null || prevWall <= 0L) return null;
                long wallDelta = now - prevWall;
                if (wallDelta <= 0L) return null;
                long gpuDelta = Math.max(0L, gpuMs - prevMs);
                return clampPercent((int) ((gpuDelta * 100L) / wallDelta));
            }
            default:
                return readPercentFromLine(path);
        }
    }

    private Integer readPercentFromLine(String path) {
        String raw = readFirstLine(path);
        if (raw == null) return null;
        for (String tok : raw.trim().split("\\s+")) {
            String digits = tok.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                Integer v = parseInt(digits);
                return v == null ? null : clampPercent(v);
            }
        }
        return null;
    }

    // =======================================================================
    // RAM
    // =======================================================================
    public float getRAMPercent() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0) return 0;
        return (mi.totalMem - mi.availMem) * 100f / mi.totalMem;
    }

    /** Used RAM as "x.xGB" (or "yMB" below 1 GB), matching GameNative's HUD text. */
    public String getUsedRamText() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "—";
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long usedBytes = Math.max(0L, mi.totalMem - mi.availMem);
        double usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0);
        if (usedGb >= 1.0) return String.format(Locale.US, "%.1fGB", usedGb);
        return (usedBytes / (1024L * 1024L)) + "MB";
    }

    // =======================================================================
    // Temperature
    // =======================================================================
    private List<String[]> thermalZonesCache = null; // each entry: {type, tempPath}

    /** Legacy non-null CPU-temperature accessor in °C (0 when unavailable). Kept for {@code PerfHudView}. */
    public float getTemperature() {
        Integer t = getCpuTempC();
        return t == null ? 0f : t;
    }

    /** Hottest representative CPU temperature in °C, or null when no CPU zone is readable. */
    public Integer getCpuTempC() {
        return readTemperatureC(discoverPrioritizedCpuTempPaths());
    }

    /** GPU temperature in °C, or null when no GPU sensor is readable. */
    public Integer getGpuTempC() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/sys/class/kgsl/kgsl-3d0/temp");
        paths.add("/sys/class/kgsl/kgsl-3d0/devfreq/temp");
        paths.add("/sys/class/misc/mali0/device/temp");
        paths.add("/sys/kernel/gpu/temp");
        paths.addAll(discoverPrioritizedGpuTempPaths());
        return readTemperatureC(paths);
    }

    private List<String> discoverPrioritizedCpuTempPaths() {
        return prioritizePaths(type -> {
            if (type.contains("cpu-silicon")) return 0;
            if (type.contains("cpu-0")) return 1;
            if (type.contains("cpu") && !type.contains("gpu")) return 2;
            if (type.contains("soc")) return 3;
            if (type.contains("s5p-tmu")) return 4;
            if (type.contains("cputop")) return 5;
            if (type.contains("tsens")) return 6;
            if (type.contains("cluster")) return 7;
            if (type.contains("big") || type.contains("little")) return 8;
            return null;
        });
    }

    private List<String> discoverPrioritizedGpuTempPaths() {
        return prioritizePaths(type -> {
            if (type.contains("gpu-silicon")) return 0;
            if (type.contains("gpu")) return 1;
            if (type.contains("g3d")) return 2;
            if (type.contains("kgsl")) return 3;
            if (type.contains("mali")) return 4;
            return null;
        });
    }

    private interface Ranker { Integer rank(String type); }

    private List<String> prioritizePaths(Ranker ranker) {
        List<String[]> zones = discoverAllThermalZones();
        ArrayList<int[]> order = new ArrayList<>(); // {index, rank}
        for (int i = 0; i < zones.size(); i++) {
            Integer r = ranker.rank(zones.get(i)[0]);
            if (r != null) order.add(new int[]{i, r});
        }
        // Sort by rank, then by path for determinism.
        order.sort((a, b) -> {
            if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
            return zones.get(a[0])[1].compareTo(zones.get(b[0])[1]);
        });
        ArrayList<String> result = new ArrayList<>();
        for (int[] e : order) result.add(zones.get(e[0])[1]);
        return result;
    }

    private List<String[]> discoverAllThermalZones() {
        if (thermalZonesCache != null) return thermalZonesCache;
        ArrayList<String[]> zones = new ArrayList<>();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        File[] thermalDirs = {
            new File("/sys/class/thermal"),
            new File("/sys/devices/virtual/thermal"),
        };
        for (File dir : thermalDirs) {
            File[] zoneDirs = dir.listFiles((d, name) -> name.startsWith("thermal_zone"));
            if (zoneDirs == null) continue;
            for (File zone : zoneDirs) {
                if (!zone.isDirectory()) continue;
                String type = readFirstLine(new File(zone, "type").getPath());
                if (type == null) continue;
                type = type.trim().toLowerCase(Locale.US);
                String tempPath = new File(zone, "temp").getPath();
                if (seenPaths.add(tempPath)) zones.add(new String[]{type, tempPath});
            }
        }
        thermalZonesCache = zones;
        return zones;
    }

    private Integer readTemperatureC(List<String> paths) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String path : paths) {
            if (!seen.add(path)) continue;
            Integer raw = readIntFromLine(path);
            if (raw == null) continue;
            // Round to nearest degree for milli-°C sources; sanity-clamp to reject offline sensors.
            int celsius = raw > 1000 ? (raw + 500) / 1000 : raw;
            if (celsius >= 1 && celsius <= 150) return celsius;
        }
        return null;
    }

    // =======================================================================
    // Battery
    // =======================================================================
    public static final class Battery {
        public final float watts;
        public final boolean charging;
        public final Integer percent;      // 0..100 or null
        public final Integer tempC;        // rounded °C or null
        public final String runtimeText;   // "LEFT 2h 5m" / "LEFT CHG" / null

        Battery(float watts, boolean charging) {
            this(watts, charging, null, null, null);
        }
        Battery(float watts, boolean charging, Integer percent, Integer tempC, String runtimeText) {
            this.watts = watts;
            this.charging = charging;
            this.percent = percent;
            this.tempC = tempC;
            this.runtimeText = runtimeText;
        }
    }

    /** power_supply current_now channels (µA) for the dual-battery sum. */
    private static final String[] CURRENT_CHANNELS = {
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/main/current_now",
    };

    private Double smoothedBatteryRuntimeHours = null;
    private static final double MAX_RUNTIME_HOURS = 72.0;
    private static final double RUNTIME_SMOOTHING_OLD_WEIGHT = 0.65;
    private static final double RUNTIME_SMOOTHING_NEW_WEIGHT = 0.35;

    /**
     * Legacy discharge-only power reading used by the classic overlays + {@code PerfHudView}:
     * watts are reported only while discharging (0 when charging). When {@code dualBattery} is set the
     * per-cell current channels are summed to correct devices that report only one cell's current.
     */
    public Battery getBattery(boolean dualBattery) {
        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging = false;
        int voltageMv = 0;
        if (status != null) {
            charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            voltageMv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        }
        long microAmps;
        if (dualBattery) {
            long sum = 0; int n = 0;
            for (String path : CURRENT_CHANNELS) {
                Long v = readLongFromLine(path);
                if (v != null) { sum += Math.abs(v); n++; }
            }
            microAmps = n > 0 ? -sum : readCurrentNowFallback();
        } else {
            microAmps = readCurrentNowFallback();
        }
        float watts = 0f;
        if (microAmps < 0) {
            watts = (Math.abs(microAmps) * (float) voltageMv) / 1_000_000_000.0f;
        }
        return new Battery(watts, charging);
    }

    /**
     * Full GameNative-parity battery snapshot for the GameNative-style HUD: %, power W (magnitude,
     * regardless of charge/discharge), smoothed runtime-left estimate, and temperature.
     */
    public Battery collectBattery() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return new Battery(0f, false);

        Integer percent = null;
        int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (cap >= 0 && cap <= 100) percent = cap;

        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (status == null) return new Battery(0f, false, percent, null, null);

        int st = status.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        long currentMicroAmps = Math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        long chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int voltageMv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

        Integer tempC = null;
        int rawTemp = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        if (rawTemp > 0) tempC = Math.round(rawTemp / 10f);

        float watts = 0f;
        if (currentMicroAmps > 0L && voltageMv > 0) {
            watts = (float) ((currentMicroAmps * (double) voltageMv) / 1_000_000_000.0);
        }

        String runtimeText;
        if (charging) {
            smoothedBatteryRuntimeHours = null;
            runtimeText = "LEFT CHG";
        } else if (currentMicroAmps <= 0L || chargeCounter <= 0L) {
            runtimeText = null;
        } else {
            double rawHours = (double) chargeCounter / (double) currentMicroAmps;
            if (!Double.isFinite(rawHours) || rawHours <= 0.0 || rawHours > MAX_RUNTIME_HOURS) {
                runtimeText = null;
            } else {
                double smoothed = smoothedBatteryRuntimeHours == null ? rawHours
                    : (smoothedBatteryRuntimeHours * RUNTIME_SMOOTHING_OLD_WEIGHT)
                        + (rawHours * RUNTIME_SMOOTHING_NEW_WEIGHT);
                smoothedBatteryRuntimeHours = smoothed;
                runtimeText = "LEFT " + formatRuntimeHours(smoothed);
            }
        }
        return new Battery(watts, charging, percent, tempC, runtimeText);
    }

    private static String formatRuntimeHours(double hours) {
        int totalMinutes = Math.max(1, (int) Math.round(hours * 60.0));
        int wholeHours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        if (wholeHours > 0 && minutes > 0) return wholeHours + "h " + minutes + "m";
        if (wholeHours > 0) return wholeHours + "h";
        return minutes + "m";
    }

    private long readCurrentNowFallback() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        return bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
    }

    // =======================================================================
    // Low-level helpers
    // =======================================================================
    private static int clampPercent(int v) { return v < 0 ? 0 : (v > 100 ? 100 : v); }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readNthLine(String path, int lineIndex) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = null;
            for (int i = 0; i <= lineIndex; i++) {
                line = r.readLine();
                if (line == null) return null;
            }
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long readLongFromLine(String path) {
        String line = readFirstLine(path);
        return line == null ? null : parseLong(line.trim());
    }

    private static Integer readIntFromLine(String path) {
        String line = readFirstLine(path);
        return line == null ? null : parseInt(line.trim());
    }

    private static Long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }
}
