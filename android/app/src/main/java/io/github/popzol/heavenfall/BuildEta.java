package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.List;

/**
 * The library build's time left, learnt from the build as it runs. The builder's log counts the compiled objects
 * against their total ({@code @@progress compile DONE TOTAL}); objects a run finds up to date (a rebuild, or a run that
 * goes on from a cut one) are done when it starts, and only the others are compiled.
 *
 * <ul>
 *   <li>Before the compile (the tools, the extraction, the translation to C): the time those take, less the time they
 *       have run, plus the objects to compile at the expected time each, plus the link. The objects to compile are the
 *       expected total less the objects the last run left in the work folder ({@link #start}): a rebuild that keeps
 *       most of them is told minutes, a first build the whole.</li>
 *   <li>Compiling, before {@link #MIN_OBJECTS} objects of this run: the objects left at the expected time each, plus
 *       the link.</li>
 *   <li>From then on: the pace of the latest half of this run's objects times the objects left, plus the link.</li>
 *   <li>Every object done (the link and the install): the link's expected time less the time since.</li>
 * </ul>
 * The expected times are {@code tuning.json}'s ({@code build_before_compile_s}, {@code build_object_s},
 * {@code build_after_compile_s}, {@code build_objects}).
 */
public final class BuildEta {
    /** Objects this run must have compiled before its own pace is used. */
    public static final int MIN_OBJECTS = 4;

    private final long beforeMs;
    private final long objectMs;
    private final long afterMs;
    private final long expectedObjects;
    private final List<long[]> samples = new ArrayList<>();   // (time in ms, objects done), in this run's compile
    private long startedMs = -1;
    private long kept;
    private long baseline = -1;
    private long total;
    private long allDoneMs = -1;

    public BuildEta(long beforeCompileMs, long objectMs, long afterCompileMs, long expectedObjects) {
        this.beforeMs = Math.max(0, beforeCompileMs);
        this.objectMs = Math.max(0, objectMs);
        this.afterMs = Math.max(0, afterCompileMs);
        this.expectedObjects = Math.max(0, expectedObjects);
    }

    public static BuildEta of(Tuning t) {
        return new BuildEta(t.buildBeforeCompileSeconds * 1000L, t.buildObjectSeconds * 1000L,
                t.buildAfterCompileSeconds * 1000L, t.buildObjects);
    }

    /** The build starts; {@code objectsKept} objects of an earlier run are in its work folder (0 for none). */
    public void start(long nowMs, long objectsKept) {
        startedMs = nowMs;
        kept = Math.max(0, objectsKept);
    }

    /** One reading of the build: its stage and, in "compile", the objects done of the total. */
    public void sample(long nowMs, String stage, long done, long total) {
        if (startedMs < 0) startedMs = nowMs;
        if ("install".equals(stage)) {
            if (allDoneMs < 0) allDoneMs = nowMs;
            return;
        }
        if (!"compile".equals(stage) || total <= 0 || done < 0) return;
        this.total = total;
        if (done >= total && allDoneMs < 0) allDoneMs = nowMs;
        if (baseline < 0) {
            baseline = done;
            samples.add(new long[] {nowMs, done});
        } else if (done > samples.get(samples.size() - 1)[1]) {
            samples.add(new long[] {nowMs, done});
        }
    }

    /** The time left at {@code nowMs}, in milliseconds; -1 before {@link #start} and every sample. */
    public long msLeft(long nowMs) {
        if (startedMs < 0) return -1;
        if (allDoneMs >= 0) return Math.max(0, afterMs - (nowMs - allDoneMs));
        if (baseline < 0) {
            long objects = Math.max(0, expectedObjects - kept);
            return Math.max(0, beforeMs - (nowMs - startedMs)) + objects * objectMs + afterMs;
        }
        long[] last = samples.get(samples.size() - 1);
        long left = total - last[1];
        long thisRun = last[1] - baseline;
        if (thisRun < MIN_OBJECTS) return left * objectMs + afterMs;
        long from = baseline + thisRun / 2;
        long[] start = samples.get(0);
        for (long[] s : samples) {
            if (s[1] >= from) {
                start = s;
                break;
            }
        }
        long objects = last[1] - start[1];
        long ms = nowMs - start[0];
        if (objects <= 0 || ms <= 0) return left * objectMs + afterMs;
        return Math.round(ms / (double) objects * left) + afterMs;
    }
}
