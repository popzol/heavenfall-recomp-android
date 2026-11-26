package io.github.popzol.heavenfall;

import org.json.JSONObject;

/**
 * The launcher's numbers (the asset {@code heavenfall/tuning.json}): the free space a download needs, the battery a
 * build needs, and the speeds the single progress bar's weights and the build's time left come from. Nothing here is
 * in code: the file is the only place. What a build needs in space and memory is the build's own
 * ({@link BuildNeeds}, {@code config/build_tuning.json}), and how many compile at once is the build's (the cores the
 * ceiling, the memory available all along the compile).
 */
public final class Tuning {
    public static final String ASSET_PATH = "heavenfall/tuning.json";
    public static final int FORMAT = 2;
    private static final long MB = 1024L * 1024L;

    /** The file does not hold a tuning this app understands. */
    public static final class InvalidException extends RuntimeException {
        InvalidException(String message) {
            super(message);
        }
    }

    public final long minFreeDownloadMb;      // free space to download and apply a mod (the build's own is BuildNeeds)
    public final int minBatteryPercent;       // below it, not charging, a build does not start
    public final long downloadKibPerSecond;   // the bar's weight of a download
    public final long applyKibPerSecond;      // the bar's weight of applying a set (bytes of the set and the shared file)
    public final long appInstallSeconds;      // the bar's weight of installing the APK, after its download
    public final long buildSeconds;           // the bar's weight of building the library
    public final long buildBeforeCompileSeconds;   // a build's time before its compile (the tools, the translation)
    public final long buildUnitMs;            // a build's time per unit compiled, with its compilers together
    public final long buildAfterCompileSeconds;    // a build's time after the last unit (the link, the install)
    public final long buildUnits;             // the units a whole build compiles
    public final Heat heat;                   // the compilers a build may run by the phone's heat

    private Tuning(JSONObject o) {
        minFreeDownloadMb = range(o, "min_free_download_mb", 1, 1L << 24);
        minBatteryPercent = (int) range(o, "min_battery_percent", 0, 100);
        JSONObject expected = Json.child(o, "expected");
        if (expected == null) throw new InvalidException("tuning.json: no expected");
        downloadKibPerSecond = positive(expected, "download_kib_s");
        applyKibPerSecond = positive(expected, "apply_kib_s");
        appInstallSeconds = positive(expected, "app_install_s");
        buildSeconds = positive(expected, "build_s");
        buildBeforeCompileSeconds = range(expected, "build_before_compile_s", 0, 1L << 20);
        buildUnitMs = range(expected, "build_unit_ms", 0, 1L << 24);
        buildAfterCompileSeconds = range(expected, "build_after_compile_s", 0, 1L << 20);
        buildUnits = range(expected, "build_units", 1, 1L << 20);
        JSONObject h = Json.child(o, "heat");
        if (h == null) throw new InvalidException("tuning.json: no heat");
        heat = new Heat(h);
    }

    /**
     * The heat table ({@code "heat"}): the share of the compilers (percent, 0 = the build waits) a thermal status allows
     * ({@code status_percent}, by status 0..n; a higher status takes the last) and a battery temperature allows
     * ({@code battery_c} / {@code battery_percent}: from that temperature up; below the first, all); the stricter wins.
     * Stepping up a level needs the battery to have stayed {@code step_up_margin_c} below its threshold (a status one
     * lower) for {@code step_up_hold_s}; a build that waited for the heat goes on only below {@code battery_resume_c}.
     */
    public static final class Heat {
        public final int[] statusPercent, batteryC, batteryPercent;
        public final int batteryResumeC, stepUpMarginC, stepUpHoldSeconds;

        private Heat(JSONObject h) {
            statusPercent = percents(h, "status_percent", 1, 8);
            batteryPercent = percents(h, "battery_percent", 0, 16);
            batteryC = list(h, "battery_c", 0, 16, 0, 100);
            if (batteryC.length != batteryPercent.length)
                throw new InvalidException("tuning.json: heat battery_c and battery_percent differ in length");
            for (int i = 1; i < batteryC.length; i++)
                if (batteryC[i] <= batteryC[i - 1]) throw new InvalidException("tuning.json: heat battery_c is not ascending");
            batteryResumeC = (int) range(h, "battery_resume_c", 0, 100);
            stepUpMarginC = (int) range(h, "step_up_margin_c", 0, 20);
            stepUpHoldSeconds = (int) range(h, "step_up_hold_s", 0, 600);
        }

        private static int[] percents(JSONObject h, String key, int minLength, int maxLength) {
            int[] v = list(h, key, minLength, maxLength, 0, 100);
            for (int i = 1; i < v.length; i++)
                if (v[i] > v[i - 1]) throw new InvalidException("tuning.json: heat " + key + " rises");
            return v;
        }

        private static int[] list(JSONObject h, String key, int minLength, int maxLength, int min, int max) {
            org.json.JSONArray a = Json.array(h, key);
            if (a == null || a.length() < minLength || a.length() > maxLength)
                throw new InvalidException("tuning.json: heat " + key + " is not a list of " + minLength + " to " + maxLength);
            int[] out = new int[a.length()];
            for (int i = 0; i < out.length; i++) {
                Object v = a.opt(i);
                if (!(v instanceof Integer) || (Integer) v < min || (Integer) v > max)
                    throw new InvalidException("tuning.json: heat " + key + " has an entry that is not a number in range");
                out[i] = (Integer) v;
            }
            return out;
        }
    }

    private static long positive(JSONObject o, String key) {
        return range(o, key, 1, Long.MAX_VALUE);
    }

    private static long range(JSONObject o, String key, long min, long max) {
        Long v = Json.integer(o, key);
        if (v == null || v < min || v > max) throw new InvalidException("tuning.json: " + key + " is not a number in range");
        return v;
    }

    public static Tuning parse(String json) {
        JSONObject root = Json.object(json);
        if (root == null) throw new InvalidException("tuning.json is not a JSON object");
        Long format = Json.integer(root, "format");
        if (format == null || format != FORMAT) throw new InvalidException("tuning.json: format " + format + " is not " + FORMAT);
        return new Tuning(root);
    }

    public long minFreeDownloadBytes() {
        return minFreeDownloadMb * MB;
    }
}
