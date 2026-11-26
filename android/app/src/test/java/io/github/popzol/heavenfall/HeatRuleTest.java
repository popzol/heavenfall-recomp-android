package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * The build's heat rule with the real table (the asset tuning.json): the compilers each thermal status and battery
 * temperature allow, the stricter wins, a level steps down at once and up one level after the hold, a wrong reading,
 * the oldest compilers kept, and the texts that no longer say "slower".
 */
public class HeatRuleTest {
    private static final HeatRule RULE = new HeatRule(Support.tuning().heat);
    private static final float COOL = 33f, NO_HEADROOM = Float.NaN, HOT_HEADROOM = 1.2f;
    private static final long S = 1000L;

    /**
     * Catches a table that drifted from the one asked for: none, light and moderate give every compiler, severe half and
     * critical or worse the wait; the battery gives all below 40, 75 % to 43, half to 45, a quarter to 47, the wait from 47.
     */
    @Test
    public void theTableAsAsked() {
        int[] byStatus = {100, 100, 100, 50, 0, 0, 0, 0};
        for (int s = 0; s < byStatus.length; s++) assertEquals("status " + s, byStatus[s], RULE.statusPercent(s, HOT_HEADROOM, COOL));
        float[] at = {39.9f, 40f, 42.9f, 43f, 44.9f, 45f, 46.9f, 47f, 52f};
        int[] share = {100, 75, 75, 50, 50, 25, 25, 0, 0};
        for (int i = 0; i < at.length; i++) assertEquals("battery " + at[i], share[i], RULE.batteryPercent(at[i]));
    }

    /**
     * Catches the jump from every compiler to two the moment the status said moderate (the Galaxy A52s' measurement:
     * status 2, headroom 0.92, battery 31.6 C, charging, whose build then ran a quarter of its speed to the end).
     */
    @Test
    public void moderateOnACoolBatteryKeepsEveryCompiler() {
        assertEquals(100, RULE.ceiling(2, 0.92f, 31.6f));
        assertEquals(8, HeatRule.compilers(RULE.ceiling(2, 0.92f, 31.6f), 8));
        assertEquals(100, RULE.ceiling(1, NO_HEADROOM, 38.8f));
    }

    /** Catches the battery or the status being ignored when the other one is fine: the stricter of the two wins. */
    @Test
    public void theStricterOfStatusAndBatteryWins() {
        assertEquals("severe with a battery at 41 (75 %): the status's half", 50, RULE.ceiling(3, HOT_HEADROOM, 41f));
        assertEquals("moderate with a battery at 46 (25 %)", 25, RULE.ceiling(2, HOT_HEADROOM, 46f));
        assertEquals("severe with a battery at 44 (half)", 50, RULE.ceiling(3, HOT_HEADROOM, 44f));
        assertEquals("none with a battery at 47: the wait", 0, RULE.ceiling(0, HOT_HEADROOM, 47f));
        assertEquals("critical with a cool battery: the wait", 0, RULE.ceiling(4, HOT_HEADROOM, 36f));
    }

    /** Catches a build left with no compiler at all while not paused (a share rounding to zero), and the shares not being what the table says of 8. */
    @Test
    public void atLeastOneCompilerUnlessItWaits() {
        assertEquals(8, HeatRule.compilers(100, 8));
        assertEquals(6, HeatRule.compilers(75, 8));
        assertEquals(4, HeatRule.compilers(50, 8));
        assertEquals(2, HeatRule.compilers(25, 8));
        assertEquals(1, HeatRule.compilers(25, 1));
        assertEquals(1, HeatRule.compilers(25, 2));
        assertEquals(0, HeatRule.compilers(0, 8));
    }

    /** Catches a level that waits for the hold when the phone gets hotter: stepping down is at once, even several levels. */
    @Test
    public void steppingDownIsImmediate() {
        HeatRule.Level l = RULE.step(HeatRule.start(), 0, 0, HOT_HEADROOM, 44f);
        assertEquals(50, l.percent);
        l = RULE.step(l, 5 * S, 0, HOT_HEADROOM, 47.5f);
        assertEquals(0, l.percent);
    }

    /**
     * Catches a level that steps up the moment the reading is under the threshold (flapping at 40 degrees), or that does
     * not need the battery a degree below it: 75 % at 41, then 40.5 (under 40 + 1: no), then 38.9 held for 30 s.
     */
    @Test
    public void steppingUpNeedsTheMarginAndThirtySeconds() {
        HeatRule.Level l = RULE.step(HeatRule.start(), 0, 0, HOT_HEADROOM, 41f);
        assertEquals(75, l.percent);
        l = RULE.step(l, 5 * S, 0, HOT_HEADROOM, 39.5f);  // under 40 but not by a degree
        assertEquals(75, l.percent);
        assertEquals(-1, l.risingSince);
        l = RULE.step(l, 10 * S, 0, HOT_HEADROOM, 38.9f);  // starts the hold
        assertEquals(75, l.percent);
        assertEquals(10 * S, l.risingSince);
        l = RULE.step(l, 39 * S, 0, HOT_HEADROOM, 38.9f);  // 29 s
        assertEquals(75, l.percent);
        l = RULE.step(l, 40 * S, 0, HOT_HEADROOM, 38.9f);  // 30 s
        assertEquals(100, l.percent);
    }

    /** Catches a reading wobbling over a threshold stepping the level up (the hold must start again each time it goes back). */
    @Test
    public void aWobbleDoesNotStepUp() {
        HeatRule.Level l = RULE.step(HeatRule.start(), 0, 0, HOT_HEADROOM, 41f);
        long t = 0;
        for (int i = 0; i < 20; i++) {
            t += 5 * S;
            l = RULE.step(l, t, 0, HOT_HEADROOM, i % 2 == 0 ? 38.9f : 40.2f);
            assertEquals("tick " + i, 75, l.percent);
        }
    }

    /** Catches a jump from a quarter straight to all: a level goes up one step, then waits its own 30 s for the next. */
    @Test
    public void stepsUpOneLevelAtATime() {
        HeatRule.Level l = RULE.step(HeatRule.start(), 0, 0, HOT_HEADROOM, 46f);
        assertEquals(25, l.percent);
        long t = 0;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < 40; i++) {  // a cool phone for 200 s
            t += 5 * S;
            int before = l.percent;
            l = RULE.step(l, t, 0, HOT_HEADROOM, 33f);
            if (l.percent != before) path.append(before).append(">").append(l.percent).append(' ');
        }
        assertEquals("25>50 50>75 75>100 ", path.toString());
    }

    /**
     * Catches the wrong-reading rule lost: a severe or worse status on a phone with a cool battery and a low (or unknown)
     * headroom counts as moderate, so every compiler; a hot headroom or a battery from 40 lets the status stand.
     */
    @Test
    public void aWrongReadingCountsAsModerate() {
        assertTrue(HeatRule.wrongReading(4, 0.5f, 35f));
        assertTrue(HeatRule.wrongReading(3, NO_HEADROOM, 35f));
        assertEquals(100, RULE.ceiling(4, 0.5f, 35f));
        assertEquals("a headroom near the limit: the status stands", 0, RULE.ceiling(4, 0.9f, 35f));
        assertEquals("a battery at 41: the status stands", 0, RULE.ceiling(4, 0.5f, 41f));
        assertFalse(HeatRule.wrongReading(2, 0.5f, 35f));
    }

    /**
     * Catches a build that goes on while the battery is still warm after a wait: from the wait it goes on only below 42,
     * to a quarter, and then up one level after each 30 s.
     */
    @Test
    public void afterTheWaitItGoesOnBelow42() {
        HeatRule.Level l = new HeatRule.Level(0, -1);
        long t = 0;
        for (int i = 0; i < 20; i++) {  // 100 s at 43.5 C: nothing
            t += 5 * S;
            l = RULE.step(l, t, 0, HOT_HEADROOM, 43.5f);
        }
        assertEquals(0, l.percent);
        l = RULE.step(l, t += 5 * S, 0, HOT_HEADROOM, 41.5f);
        l = RULE.step(l, t += 30 * S, 0, HOT_HEADROOM, 41.5f);
        assertEquals(25, l.percent);
    }

    /** Catches the build staying in the wait after the status fell from critical to severe on a cool phone: back to half. */
    @Test
    public void afterACriticalStatusFallsItGoesOnAtHalf() {
        HeatRule.Level l = new HeatRule.Level(0, -1);
        l = RULE.step(l, 0, 3, HOT_HEADROOM, 36f);
        l = RULE.step(l, 30 * S, 3, HOT_HEADROOM, 36f);
        assertEquals(25, l.percent);
        l = RULE.step(l, 31 * S, 3, HOT_HEADROOM, 36f);
        l = RULE.step(l, 61 * S, 3, HOT_HEADROOM, 36f);
        assertEquals(50, l.percent);
        l = RULE.step(l, 62 * S, 3, HOT_HEADROOM, 36f);
        l = RULE.step(l, 100 * S, 3, HOT_HEADROOM, 36f);
        assertEquals("severe stays at half", 50, l.percent);
    }

    /**
     * Catches the oldest compiles being the ones stopped (the old rule kept the newest, leaving six half-done compiles stuck
     * holding their memory): with two allowed, the two that started first go on, whatever their pids (a pid can wrap).
     */
    @Test
    public void theOldestCompilesKeepRunning() {
        int[] pids = {4100, 3990, 12, 4120, 4050};
        long[] starts = {300, 200, 100, 320, 250};  // pid 12 is the oldest (a wrapped pid), 3990 next
        Set<Integer> run = HeatRule.oldest(pids, starts, 2);
        assertEquals(2, run.size());
        assertTrue(run.contains(12) && run.contains(3990));
        assertEquals(5, HeatRule.oldest(pids, starts, 99).size());
        assertEquals(0, HeatRule.oldest(pids, starts, 0).size());
    }

    /** Catches an unreadable start time ranking as the oldest, and a stat line whose name has spaces and parentheses breaking the field. */
    @Test
    public void startTimeReadsTheTwentySecondField() {
        String stat = "4321 (zig (cc) x) S 4000 4000 4000 0 -1 4194560 100 0 0 0 5 2 0 0 20 0 1 0 987654 1000000 100 18446744073709551615";
        assertEquals(987654L, HeatRule.startTime(stat));
        assertEquals(Long.MAX_VALUE, HeatRule.startTime(""));
        assertEquals(Long.MAX_VALUE, HeatRule.startTime("1 (x) S 0"));
    }

    /**
     * Catches the build's state mixing up the two waits: the heat's wait shows as too hot, a low battery (15 % or less,
     * not charging) waits until it charges or passes 20 %, and a build with fewer compilers is simply running.
     */
    @Test
    public void theStateIsRunningUnlessItWaits() {
        assertEquals("running", HeatRule.state("running", 25, 80, true));
        assertEquals("running", HeatRule.state("running", 50, 50, false));
        assertEquals("paused-hot", HeatRule.state("running", 0, 80, true));
        assertEquals("paused-battery", HeatRule.state("running", 100, 15, false));
        assertEquals("paused-battery", HeatRule.state("paused-battery", 100, 18, false));
        assertEquals("running", HeatRule.state("paused-battery", 100, 21, false));
        assertEquals("running", HeatRule.state("paused-battery", 100, 10, true));
        assertEquals("paused-hot", HeatRule.state("paused-battery", 0, 10, true));
    }

    /**
     * Catches the "slower, the phone is warm" text coming back (the notification, the home screen and the builder
     * screen all show BuilderText's line) or a status called "warm" reaching the screens.
     */
    @Test
    public void noSlowerTextForAThrottledBuild() {
        String[] files = {"BuilderText", "BuilderService", "BuilderActivity", "HeavenfallBridge"};
        for (String f : files) {
            String s = Support.readRepo("android/app/src/main/java/com/mh4u/port/" + f + ".java");
            assertFalse(f + " names a warm status", s.contains("\"warm\""));
            assertFalse(f + " says slower", s.contains("Slower:") || s.contains("Más despacio"));
        }
        String text = Support.readRepo("android/app/src/main/java/com/mh4u/port/BuilderText.java");
        assertTrue("the pause texts stay", text.contains("paused-hot") && text.contains("paused-battery"));
    }

    /**
     * Catches the heat table living in a file the library's id covers: the id hashes mh4n's files (ID_FILES) and the heat
     * table is the app's tuning.json, so a change of the table never makes players build the library again.
     */
    @Test
    public void theHeatTableIsOutsideTheLibrarysId() {
        String build = Support.readRepo("mh4n/tools/build_game.py");
        int from = build.indexOf("ID_FILES = [");
        int to = build.indexOf("]", from);
        assertTrue(from > 0 && to > from);
        String ids = build.substring(from, to);
        assertFalse(ids.contains("tuning"));
        assertFalse(ids.contains("heavenfall"));
        String service = Support.readRepo("android/app/src/main/java/com/mh4u/port/BuilderService.java");
        assertFalse("no heat numbers in the service", service.contains("WARM_COMPILERS") || service.contains("BATTERY_HOT"));
    }
}
