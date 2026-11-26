package io.github.popzol.heavenfall;

import org.json.JSONObject;

/**
 * What the code library's build needs, from the build's own numbers ({@code config/build_tuning.json}, the file
 * {@code phone_build.py} checks itself against at its start; the APK carries it in {@code assets/builder/tools.zip}).
 * The home screen's space and memory blocks before a build read these, so the app and the build go by one source:
 * <ul>
 *   <li>space: the extracted executable, the C, the objects and Zig's copies of them, the library twice and the least
 *   the objects' cache keeps (the build's {@code space_needed_mb}); the builder's work folder counts as room (a run
 *   writes over it or uses it again), and so does the cache above its floor (the build drops its oldest objects
 *   first);</li>
 *   <li>memory: the translation's peak, against the phone's memory in all with its swap (a phone with less can never
 *   build; one with less free now still can: Android stops apps in the background).</li>
 * </ul>
 * {@link #NONE} when the file cannot be read: nothing is checked before the build, which checks itself.
 */
public final class BuildNeeds {
    /** The file in the tools' package. */
    public static final String FILE = "config/build_tuning.json";
    public static final BuildNeeds NONE = new BuildNeeds(-1, -1, 0);
    private static final long MB = 1024L * 1024L;

    /** Free space a whole build needs, in MB (-1: not known). */
    public final long spaceMb;
    /** The translation's peak memory, in MB (-1: not known). */
    public final long translationPeakMb;
    /** The least the objects' cache keeps, in MB. */
    public final long cacheFloorMb;

    BuildNeeds(long spaceMb, long translationPeakMb, long cacheFloorMb) {
        this.spaceMb = spaceMb;
        this.translationPeakMb = translationPeakMb;
        this.cacheFloorMb = cacheFloorMb;
    }

    public static final class InvalidException extends Exception {
        InvalidException(String message) {
            super(message);
        }
    }

    /** The numbers of {@code build_tuning.json} (format 1); a missing or senseless number is an error. */
    public static BuildNeeds parse(String json) throws InvalidException {
        JSONObject o = Json.object(json);
        if (o == null) throw new InvalidException("build_tuning.json: not a JSON object");
        Long format = Json.integer(o, "format");
        if (format == null || format != 1) throw new InvalidException("build_tuning.json: not format 1");
        JSONObject space = Json.child(o, "space");
        JSONObject translation = Json.child(o, "translation");
        if (space == null || translation == null) throw new InvalidException("build_tuning.json: no space or translation");
        long need = 0;
        for (String part : new String[] {"dump_mb", "sources_mb", "objects_mb", "zig_copies_mb", "cache_floor_mb"}) {
            need += mb(space, part);
        }
        need += 2 * mb(space, "library_mb");
        return new BuildNeeds(need, mb(translation, "peak_mb"), mb(space, "cache_floor_mb"));
    }

    private static long mb(JSONObject o, String key) throws InvalidException {
        Object v = o.opt(key);
        if (!(v instanceof Number)) throw new InvalidException("build_tuning.json: no number " + key);
        double d = ((Number) v).doubleValue();
        if (!(d > 0 && d < 1_000_000)) throw new InvalidException("build_tuning.json: " + key + " out of range");
        return (long) Math.ceil(d);
    }

    public boolean known() {
        return spaceMb > 0;
    }

    /** The room the builder's own files give back: its work folder, and the objects' cache above its floor. */
    public long roomBytes(long workBytes, long cacheBytes) {
        return Math.max(0, workBytes) + Math.max(0, cacheBytes - cacheFloorMb * MB);
    }

    /** The free bytes a build needs beside {@code roomBytes} (0 when not known). */
    public long freeBytesNeeded(long roomBytes) {
        return known() ? Math.max(0, spaceMb * MB - Math.max(0, roomBytes)) : 0;
    }

    /** A phone with this much memory in all (with its swap; -1 not known) can never build. */
    public boolean neverFits(long memoryInAllMb) {
        return translationPeakMb > 0 && memoryInAllMb > 0 && memoryInAllMb < translationPeakMb;
    }
}
