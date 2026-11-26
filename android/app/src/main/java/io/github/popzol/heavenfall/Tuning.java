package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The launcher's numbers (the asset {@code heavenfall/tuning.json}): the free space an install and a build need, the
 * battery and memory a build needs, how many compile jobs run at each amount of free memory, and the speeds the
 * single progress bar's weights come from. Nothing here is in code: the file is the only place.
 */
public final class Tuning {
    public static final String ASSET_PATH = "heavenfall/tuning.json";
    public static final int FORMAT = 1;
    private static final long MB = 1024L * 1024L;

    /** The file does not hold a tuning this app understands. */
    public static final class InvalidException extends RuntimeException {
        InvalidException(String message) {
            super(message);
        }
    }

    private static final class Tier {
        final long minAvailableMb;
        final int jobs;

        Tier(long minAvailableMb, int jobs) {
            this.minAvailableMb = minAvailableMb;
            this.jobs = jobs;
        }
    }

    public final long minFreeDownloadMb;      // free space to download and apply a mod without building
    public final long minFreeBuildMb;         // free space to download, apply and build
    public final int minBatteryPercent;       // below it, not charging, nothing heavy starts
    public final long minAvailableMemoryMb;   // below it a build does not start
    public final long downloadKibPerSecond;   // the bar's weight of a download
    public final long applyKibPerSecond;      // the bar's weight of applying a set (bytes of the set and the shared file)
    public final long appInstallSeconds;      // the bar's weight of installing the APK, after its download
    public final long buildSeconds;           // the bar's weight of building the library
    private final List<Tier> tiers;

    private Tuning(JSONObject o) {
        minFreeDownloadMb = range(o, "min_free_download_mb", 1, 1L << 24);
        minFreeBuildMb = range(o, "min_free_build_mb", 1, 1L << 24);
        if (minFreeBuildMb < minFreeDownloadMb) throw new InvalidException("tuning.json: min_free_build_mb is below min_free_download_mb");
        minBatteryPercent = (int) range(o, "min_battery_percent", 0, 100);
        minAvailableMemoryMb = range(o, "min_available_memory_mb", 0, 1L << 24);
        JSONObject expected = Json.child(o, "expected");
        if (expected == null) throw new InvalidException("tuning.json: no expected");
        downloadKibPerSecond = positive(expected, "download_kib_s");
        applyKibPerSecond = positive(expected, "apply_kib_s");
        appInstallSeconds = positive(expected, "app_install_s");
        buildSeconds = positive(expected, "build_s");
        JSONArray array = Json.array(o, "compile_jobs_by_memory");
        if (array == null || array.length() == 0) throw new InvalidException("tuning.json: no compile_jobs_by_memory");
        List<Tier> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof JSONObject)) throw new InvalidException("tuning.json: a tier is not an object");
            long from = range((JSONObject) item, "min_available_mb", 0, Long.MAX_VALUE);
            long jobs = range((JSONObject) item, "jobs", 1, 64);
            if (i == 0 && from != 0) throw new InvalidException("tuning.json: the first tier starts above 0");
            if (i > 0 && (from <= list.get(i - 1).minAvailableMb || jobs < list.get(i - 1).jobs)) {
                throw new InvalidException("tuning.json: the tiers must rise in memory and in jobs");
            }
            list.add(new Tier(from, (int) jobs));
        }
        tiers = Collections.unmodifiableList(list);
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

    /** Compile jobs to run with this much memory available (the caller keeps it under the phone's core count). */
    public int compileJobs(long availableMemoryMb) {
        int jobs = tiers.get(0).jobs;
        for (Tier t : tiers) {
            if (availableMemoryMb >= t.minAvailableMb) jobs = t.jobs;
        }
        return jobs;
    }

    public long minFreeDownloadBytes() {
        return minFreeDownloadMb * MB;
    }

    public long minFreeBuildBytes() {
        return minFreeBuildMb * MB;
    }
}
