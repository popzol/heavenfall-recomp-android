package io.github.popzol.heavenfall;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The build's heat rule, the mechanism only (the table is {@link Tuning.Heat}, in {@code tuning.json}): from the thermal
 * status, the thermal headroom and the battery's temperature, the share of the compilers that may run (a "level", in
 * percent; 0 = the build waits). The stricter of the status and the battery wins. A level goes down at once; it goes
 * back up one level at a time, only after the reading has stayed below that level's threshold (the battery by the
 * table's margin, the status one lower) for the table's hold, so a reading on a threshold does not flap. A status of
 * severe or worse on a phone its battery and headroom say is cool is a wrong reading, taken as moderate. When fewer
 * compilers may run, the oldest keep running ({@link #oldest}), so no compile is stuck for the whole build.
 * No Android classes here: the service ({@code BuilderService}) reads the phone and signals the processes.
 */
public final class HeatRule {
    /** PowerManager's thermal statuses this rule names: moderate, severe (and worse). */
    public static final int STATUS_MODERATE = 2, STATUS_SEVERE = 3;
    /** A battery under this (degrees C) with a headroom under HEADROOM_HOT is a cool phone: a high status is wrong. */
    public static final float BATTERY_COOL = 40f, HEADROOM_HOT = 0.85f;

    private final Tuning.Heat table;
    private final int[] levels;  // every level the table has, ascending (0 and 100 among them)

    public HeatRule(Tuning.Heat table) {
        this.table = table;
        TreeSet<Integer> all = new TreeSet<>();
        all.add(100);
        for (int p : table.statusPercent) all.add(p);
        for (int p : table.batteryPercent) all.add(p);
        levels = new int[all.size()];
        int i = 0;
        for (int p : all) levels[i++] = p;
    }

    /** What the rule is at one moment: the level in percent, and since when a higher one has been wanted (-1: none). */
    public static final class Level {
        public final int percent;
        public final long risingSince;

        public Level(int percent, long risingSince) {
            this.percent = percent;
            this.risingSince = risingSince;
        }
    }

    /** The first level of a build: every compiler. */
    public static Level start() {
        return new Level(100, -1);
    }

    /** A severe or worse thermal status on a phone its battery and headroom say is cool (NaN headroom agrees): a wrong reading. */
    public static boolean wrongReading(int status, float headroom, float batteryC) {
        boolean cool = batteryC < BATTERY_COOL && !(headroom >= HEADROOM_HOT);
        return status >= STATUS_SEVERE && cool;
    }

    /** The share the thermal status allows (a wrong reading counts as moderate). */
    public int statusPercent(int status, float headroom, float batteryC) {
        int s = wrongReading(status, headroom, batteryC) ? STATUS_MODERATE : status;
        int[] by = table.statusPercent;
        return by[Math.max(0, Math.min(s, by.length - 1))];
    }

    /** The share the battery's temperature allows (all when the phone gives none). */
    public int batteryPercent(float batteryC) {
        if (Float.isNaN(batteryC)) return 100;
        int share = 100;
        for (int i = 0; i < table.batteryC.length; i++) if (batteryC >= table.batteryC[i]) share = table.batteryPercent[i];
        return share;
    }

    /** The stricter of the two, at the reading itself: the level the phone calls for now. */
    public int ceiling(int status, float headroom, float batteryC) {
        return Math.min(statusPercent(status, headroom, batteryC), batteryPercent(batteryC));
    }

    /**
     * The level the reading allows for going back up from {@code current}: the battery counts by its temperature plus the
     * table's margin (it has to be that far below the threshold), and from a wait only below the table's resume temperature.
     */
    public int rising(int current, int status, float headroom, float batteryC) {
        int byStatus = statusPercent(status, headroom, batteryC);
        if (Float.isNaN(batteryC)) return byStatus;
        if (current <= 0 && batteryC >= table.batteryResumeC) return 0;
        return Math.min(byStatus, batteryPercent(batteryC + table.stepUpMarginC));
    }

    private int nextAbove(int percent) {
        for (int l : levels) if (l > percent) return l;
        return percent;
    }

    /**
     * The level after one reading at {@code nowMs}: down at once to what the reading calls for; up to the next level
     * only once the reading has allowed it for the table's hold (and then again for the next one).
     */
    public Level step(Level now, long nowMs, int status, float headroom, float batteryC) {
        int down = ceiling(status, headroom, batteryC);
        if (down < now.percent) return new Level(down, -1);
        int up = rising(now.percent, status, headroom, batteryC);
        if (up <= now.percent) return new Level(now.percent, -1);
        if (now.risingSince < 0) return new Level(now.percent, nowMs);
        if (nowMs - now.risingSince < table.stepUpHoldSeconds * 1000L) return now;
        return new Level(Math.min(up, nextAbove(now.percent)), nowMs);
    }

    /**
     * What the build is, from what it was: "running", "paused-hot" (the level is 0) or "paused-battery" (15 % of battery or
     * less and not charging: it waits until it charges or passes 20 %; the heat's wait comes first).
     */
    public static String state(String was, int percent, int batteryLevel, boolean charging) {
        if (percent <= 0) return "paused-hot";
        if (was.equals("paused-battery")) return charging || batteryLevel > 20 ? "running" : was;
        return !charging && batteryLevel <= 15 ? "paused-battery" : "running";
    }

    /** How many of {@code base} compilers a level allows: at least one while it is not a wait. */
    public static int compilers(int percent, int base) {
        return percent <= 0 ? 0 : Math.max(1, Math.min(base, (base * percent + 50) / 100));
    }

    /**
     * Which compilers go on when only {@code allowed} may: the oldest (the earliest start), so one half done is not left
     * stopped while newer ones run; a tie goes to the lower pid.
     */
    public static Set<Integer> oldest(int[] pids, long[] starts, int allowed) {
        Integer[] order = new Integer[pids.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> starts[a] != starts[b] ? Long.compare(starts[a], starts[b])
                                                                      : Integer.compare(pids[a], pids[b]));
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < order.length && i < allowed; i++) out.add(pids[order[i]]);
        return out;
    }

    /** A process's start time (the 22nd field of its /proc stat, in clock ticks after the boot); Long.MAX_VALUE when unreadable (the newest). */
    public static long startTime(String stat) {
        int close = stat == null ? -1 : stat.lastIndexOf(')');
        if (close < 0 || close + 2 > stat.length()) return Long.MAX_VALUE;
        String[] f = stat.substring(close + 2).trim().split(" ");
        try {
            return f.length > 19 ? Long.parseLong(f[19]) : Long.MAX_VALUE;
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
