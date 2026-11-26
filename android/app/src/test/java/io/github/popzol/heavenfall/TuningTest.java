package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
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

    /** The tuning file's fields all reach the model, and the build needs more room than a download. */
    @Test
    public void sampleReads() {
        Tuning t = Support.tuning();
        assertTrue(t.minFreeBuildMb >= t.minFreeDownloadMb);
        assertEquals(t.minFreeBuildMb * 1024 * 1024, t.minFreeBuildBytes());
        assertTrue(t.minBatteryPercent > 0 && t.minBatteryPercent < 100);
        assertTrue(t.downloadKibPerSecond > 0 && t.applyKibPerSecond > 0 && t.buildSeconds > 0);
    }

    /** More memory never means fewer jobs, the lowest tier is one job, and a tier starts exactly at its memory. */
    @Test
    public void jobsByMemory() {
        Tuning t = Support.tuning();
        assertEquals(1, t.compileJobs(0));
        assertEquals(1, t.compileJobs(1199));
        assertEquals(2, t.compileJobs(1200));
        assertEquals(3, t.compileJobs(2500));
        assertEquals(6, t.compileJobs(100000));
        int last = 0;
        for (int mb = 0; mb < 8000; mb += 50) {
            int jobs = t.compileJobs(mb);
            assertTrue(jobs >= last);
            last = jobs;
        }
    }

    /** A tuning that would hide a mistake (an unordered tier, a zero speed, text for a number) is refused. */
    @Test
    public void brokenTuning() {
        refused("{}");
        refused("[]");
        refused(with("\"format\": 1", "\"format\": 3"));
        refused(with("\"min_free_download_mb\": 700", "\"min_free_download_mb\": \"700\""));
        refused(with("\"min_free_download_mb\": 700", "\"min_free_download_mb\": 9000"));
        refused(with("\"min_battery_percent\": 25", "\"min_battery_percent\": 250"));
        refused(with("\"download_kib_s\": 3000", "\"download_kib_s\": 0"));
        refused(with("{\"min_available_mb\": 0, \"jobs\": 1}", "{\"min_available_mb\": 10, \"jobs\": 1}"));
        refused(with("{\"min_available_mb\": 2000, \"jobs\": 3}", "{\"min_available_mb\": 1000, \"jobs\": 3}"));
        refused(with("{\"min_available_mb\": 3000, \"jobs\": 4}", "{\"min_available_mb\": 3000, \"jobs\": 1}"));
        refused(with("\"compile_jobs_by_memory\"", "\"other\""));
    }
}
