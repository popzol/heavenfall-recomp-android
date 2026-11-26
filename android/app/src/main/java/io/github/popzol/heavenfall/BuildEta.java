package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library build's time left, learnt from the build as it runs. The builder's log counts the units with their
 * object against all the units ({@code @@progress compile DONE TOTAL}, every 3 s: the objects the objects' cache gave
 * count as done), and says once, after it looked every unit up in the cache, how many it took from there and how many
 * it compiles ({@code objects' cache DIR: N objects from it, M to compile}, {@link #cache}). A rebuild for a new mod
 * version takes nearly all from the cache and compiles a few; a first build compiles them all.
 *
 * <ul>
 *   <li>Before the compile (the tools, the extraction, the translation to C): the time those take, less the time they
 *       have run, plus the units to compile at the expected time each, plus the link. The units to compile are the
 *       expected total less the objects the builder keeps ({@link #start}: its cache and its work folder), so a
 *       rebuild is told minutes and a first build the whole; a cache of other tools' objects is told as a rebuild
 *       until the cache's line says otherwise.</li>
 *   <li>Compiling, before the cache's line (the units being looked up): the units without their object, no more
 *       than the expected total less the objects kept, at the expected time each, plus the link.</li>
 *   <li>After it, before {@link #MIN_UNITS} units compiled: the units left to compile at the expected time each, plus
 *       the link; from then on the pace of the latest half of the units compiled times the units left.</li>
 *   <li>Every unit done (the link and the install): the link's expected time less the time since.</li>
 * </ul>
 * The expected times are {@code tuning.json}'s ({@code build_before_compile_s}, {@code build_unit_ms},
 * {@code build_after_compile_s}, {@code build_units}).
 */
public final class BuildEta {
    /** Units this run must have compiled before its own pace is used. */
    public static final int MIN_UNITS = 4;
    private static final Pattern CACHE_LINE = Pattern.compile("^objects' cache .*: (\\d+) objects from it, (\\d+) to compile");

    private final long beforeMs;
    private final long unitMs;
    private final long afterMs;
    private final long expectedUnits;
    private final List<long[]> samples = new ArrayList<>();   // (time in ms, units done), since the pace's start
    private long startedMs = -1;
    private long kept;
    private long baseline = -1;    // the units done when the pace's count starts
    private long done = -1;
    private long total;
    private long toCompile = -1;   // the cache's line: the units this run compiles (-1: not said yet)
    private boolean compiling;
    private long allDoneMs = -1;

    public BuildEta(long beforeCompileMs, long unitMs, long afterCompileMs, long expectedUnits) {
        this.beforeMs = Math.max(0, beforeCompileMs);
        this.unitMs = Math.max(0, unitMs);
        this.afterMs = Math.max(0, afterCompileMs);
        this.expectedUnits = Math.max(0, expectedUnits);
    }

    public static BuildEta of(Tuning t) {
        return new BuildEta(t.buildBeforeCompileSeconds * 1000L, t.buildUnitMs, t.buildAfterCompileSeconds * 1000L,
                t.buildUnits);
    }

    /** The build starts; the builder keeps {@code objectsKept} objects of earlier runs (0 for none). */
    public void start(long nowMs, long objectsKept) {
        startedMs = nowMs;
        kept = Math.max(0, objectsKept);
    }

    /** One reading of the build: its stage and, in "compile", the units done of the total. */
    public void sample(long nowMs, String stage, long done, long total) {
        if (startedMs < 0) startedMs = nowMs;
        if ("install".equals(stage)) {
            if (allDoneMs < 0) allDoneMs = nowMs;
            return;
        }
        if (!"compile".equals(stage) || total <= 0 || done < 0) return;
        compiling = true;
        this.total = total;
        this.done = done;
        if (done >= total && allDoneMs < 0) allDoneMs = nowMs;
        // (the pace starts at the cache's line: before it, the units done are the cache's, not compiled ones)
        if (baseline >= 0 && done > samples.get(samples.size() - 1)[1]) samples.add(new long[] {nowMs, done});
    }

    /**
     * The cache's line: {@code hits} units came from the objects' cache and {@code toCompile} are compiled. Every unit
     * done from here on is a compiled one.
     */
    public void cache(long nowMs, long hits, long toCompile) {
        if (startedMs < 0) startedMs = nowMs;
        if (hits < 0 || toCompile < 0) return;
        this.toCompile = toCompile;
        compiling = true;
        samples.clear();
        long at = Math.max(hits, done);
        baseline = at;
        samples.add(new long[] {nowMs, at});
        if (toCompile == 0 && allDoneMs < 0) allDoneMs = nowMs;
    }

    /** The time left at {@code nowMs}, in milliseconds; -1 before {@link #start} and every sample. */
    public long msLeft(long nowMs) {
        if (startedMs < 0) return -1;
        if (allDoneMs >= 0) return Math.max(0, afterMs - (nowMs - allDoneMs));
        if (!compiling) {
            long units = Math.max(0, expectedUnits - kept);
            return Math.max(0, beforeMs - (nowMs - startedMs)) + units * unitMs + afterMs;
        }
        if (baseline < 0) {
            // looking the units up in the cache: those without their object yet, no more than the builder keeps
            // objects for, at the expected time each
            long units = Math.min(Math.max(0, total - done), Math.max(0, expectedUnits - kept));
            return units * unitMs + afterMs;
        }
        long[] last = samples.get(samples.size() - 1);
        long compiled = last[1] - baseline;
        long left = Math.max(0, toCompile - compiled);
        if (compiled < MIN_UNITS) return left * unitMs + afterMs;
        long from = baseline + compiled / 2;
        long[] start = samples.get(0);
        for (long[] s : samples) {
            if (s[1] >= from) {
                start = s;
                break;
            }
        }
        long units = last[1] - start[1];
        long ms = nowMs - start[0];
        if (units <= 0 || ms <= 0) return left * unitMs + afterMs;
        return Math.round(ms / (double) units * left) + afterMs;
    }

    /** The cache's line of the builder's log as {hits, to compile}, or null for any other line. */
    public static long[] cacheLine(String line) {
        if (line == null) return null;
        Matcher m = CACHE_LINE.matcher(line.trim());
        if (!m.find()) return null;
        try {
            return new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
