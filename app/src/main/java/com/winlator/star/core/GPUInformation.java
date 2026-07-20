package com.winlator.star.core;

import android.content.Context;

public abstract class GPUInformation {

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals("System"))
            return false;

        // Direct Vulkan ICD turnip (turnip-26.1.0) is NOT an adrenotools driver: it is loaded
        // as a plain system Vulkan ICD, so the native getRenderer() adrenotools probe cannot
        // describe it (and that very probe is what fails on Android < 11, hiding turnip-sdk36
        // from the picker on devices like the SD845/Adreno 630 / Android 10 reporter). Gate it
        // on the GPU family only (Turnip == Freedreno == Adreno) so it stays selectable exactly
        // where it works, without going through the failing hook probe.
        if (DefaultVersion.WRAPPER_TURNIP_ICD.equals(driverName))
            return isAdrenoGPU(context);

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }
    /**
     * Extract a short GPU model (e.g. {@code "Adreno 750"}, {@code "Mali-G715"}, {@code "Xclipse 920"})
     * from a raw Vulkan/GL renderer string such as
     * {@code "zink Vulkan 1.4(Wrapper(Adreno (TM) 750) (MESA_TURNIP))"} — what the guest reports via
     * {@code _MESA_DRV_GPU_NAME}. Used for the perf-HUD GPU-model row so it shows the chip, not the whole
     * driver string. Falls back to the trimmed input when no known vendor token is found, so unknown
     * GPUs still show something rather than blank.
     */
    public static String extractModelName(String raw) {
        if (raw == null) return null;
        String s = raw.replace("(TM)", "").replace("(R)", "").replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher m;

        m = java.util.regex.Pattern.compile("Adreno\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return "Adreno " + m.group(1);

        // ARM Immortalis-G### / Mali-<letter>### — keep the vendor-model form.
        m = java.util.regex.Pattern.compile("(Immortalis|Mali)[\\s-]*([A-Za-z]?\\d+[A-Za-z0-9]*)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) {
            String vendor = m.group(1);
            vendor = Character.toUpperCase(vendor.charAt(0)) + vendor.substring(1).toLowerCase();
            return vendor + "-" + m.group(2).toUpperCase();
        }

        m = java.util.regex.Pattern.compile("Xclipse\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return "Xclipse " + m.group(1);

        m = java.util.regex.Pattern.compile("PowerVR\\s+([A-Za-z0-9]+(?:\\s+[A-Za-z0-9]+)?)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return "PowerVR " + m.group(1);

        // Fallback for GPUs we have no explicit pattern for: strip the wrapper/driver scaffolding so a
        // raw "zink Vulkan X(Wrapper(<NAME>) (<DRIVER>))" never leaks "Wrapper" or the driver tag into
        // the HUD. Take the inner Wrapper(...) name when present, then drop MESA/driver tags and parens.
        java.util.regex.Matcher wrap = java.util.regex.Pattern.compile("(?i)wrapper\\(").matcher(s);
        if (wrap.find()) s = s.substring(wrap.end());
        s = s.replaceAll("(?i)\\(?\\bMESA[_A-Za-z0-9]*\\)?", " ") // MESA_TURNIP / (MESA...) driver tags
             .replaceAll("[()]", " ")
             .replaceAll("\\s+", " ")
             .trim();
        return s;
    }

    public native static String getVulkanVersion(String driverName, Context context);
    public native static int getVendorID(String driverName, Context context);
    public native static String getRenderer(String driverName, Context context);
    public native static String[] enumerateExtensions(String driverName, Context context);

    static {
        System.loadLibrary("winlator");
    }
}
