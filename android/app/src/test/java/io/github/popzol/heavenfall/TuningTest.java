package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TuningTest {
    private static String with(String from, String to) {
        String json = Support.tuningJson();
        assertTrue("the sample lost " + from, json.contains(from));
        return json.replace(from, to);
    }

    private static void refused(String json) {
        try {
            Tuning.parse(json);
            fail("must be refused: " + json);
        } catch (Tuning.InvalidException expected) {
            // named
        }
    }

    /** The tuning file's fields all reach the model. */
    @Test
    public void sampleReads() {
        Tuning t = Support.tuning();
        assertTrue(t.minFreeDownloadMb > 0);
        assertTrue(t.appLeaveQuietSeconds >= 0 && t.appLeaveWaitMaxSeconds >= 60);
        assertTrue(t.minBatteryPercent > 0 && t.minBatteryPercent < 100);
        assertTrue(t.downloadKibPerSecond > 0 && t.applyKibPerSecond > 0 && t.buildSeconds > 0);
        assertTrue(t.buildUnits > 0 && t.buildUnitMs > 0);
    }

    /**
     * Catches a second source for what a build needs (the space, the memory, the compile jobs): those are the build's
     * own numbers (BuildNeeds) and the build's own rule, never the launcher's guesses.
     */
    @Test
    public void theBuildsNeedsAreNotHere() {
        String json = Support.tuningJson();
        for (String key : new String[] {"min_free_build_mb", "min_available_memory_mb", "compile_jobs_by_memory"}) {
            assertFalse(key, json.contains(key));
        }
    }

    /** A tuning that would hide a mistake (a zero speed, text for a number, a number out of range) is refused. */
    @Test
    public void brokenTuning() {
        refused("{}");
        refused("[]");
        refused(with("\"format\": 2", "\"format\": 3"));
        refused(with("\"min_free_download_mb\": 700", "\"min_free_download_mb\": \"700\""));
        refused(with("\"min_battery_percent\": 25", "\"min_battery_percent\": 250"));
        refused(with("\"download_kib_s\": 3000", "\"download_kib_s\": 0"));
        refused(with("\"build_units\"", "\"other\""));
        refused(with("\"build_unit_ms\"", "\"build_object_s\""));
    }

    /**
     * Catches a heat table that would hide a mistake: no table, a battery list out of order or of another length than its
     * shares, a share that rises with the heat, a share over 100, text for a number.
     */
    @Test
    public void brokenHeatTable() {
        refused(with("\"heat\"", "\"warmth\""));
        refused(with("[40, 43, 45, 47]", "[43, 40, 45, 47]"));
        refused(with("[40, 43, 45, 47]", "[40, 43, 45]"));
        refused(with("[75, 50, 25, 0]", "[50, 75, 25, 0]"));
        refused(with("[100, 100, 100, 50, 0, 0, 0]", "[100, 100, 100, 50, 120, 0, 0]"));
        refused(with("[100, 100, 100, 50, 0, 0, 0]", "[100, 100, 100, 50, \"0\", 0, 0]"));
        refused(with("\"step_up_hold_s\": 30", "\"step_up_hold_s\": 3000"));
    }
}
