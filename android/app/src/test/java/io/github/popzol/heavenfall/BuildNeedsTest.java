package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

/** The build's own numbers (config/build_tuning.json) and its named stops, as the player reads them. */
public class BuildNeedsTest {
    private static final String SAMPLE = "{\"format\": 1, \"translation\": {\"peak_mb\": 1600}, \"space\": {\"dump_mb\": 25,"
            + " \"sources_mb\": 360, \"objects_mb\": 160, \"zig_copies_mb\": 32, \"library_mb\": 130, \"cache_floor_mb\": 160}}";

    /** Catches a space need that is not the build's own sum (the library counts twice: built, then copied). */
    @Test
    public void theBuildsSum() throws Exception {
        BuildNeeds n = BuildNeeds.parse(SAMPLE);
        assertEquals(25 + 360 + 160 + 32 + 2 * 130 + 160, n.spaceMb);
        assertEquals(1600, n.translationPeakMb);
        assertEquals(160, n.cacheFloorMb);
        assertTrue(n.neverFits(1599));
        assertFalse(n.neverFits(1600));
        assertFalse("not known: no block", n.neverFits(-1));
        assertEquals(0, n.freeBytesNeeded(n.spaceMb << 20));
        assertEquals(n.spaceMb << 20, n.freeBytesNeeded(0));
        assertEquals(5L << 20, n.roomBytes(5L << 20, 160L << 20));
        assertFalse(BuildNeeds.NONE.known());
        assertEquals(0, BuildNeeds.NONE.freeBytesNeeded(0));
        assertFalse(BuildNeeds.NONE.neverFits(1));
    }

    /** Catches the app's copy of the build's file unreadable (Home would block nothing) or far off its sense. */
    @Test
    public void theToolsFileReads() {
        BuildNeeds n = Support.buildNeeds();
        assertTrue(n.spaceMb > 100 && n.spaceMb < 20_000);
        assertTrue(n.translationPeakMb > 100 && n.translationPeakMb < 16_000);
    }

    /** Catches a file the build would refuse taken by the app (a missing part, text for a number, another format). */
    @Test
    public void aBadFileIsRefused() {
        for (String bad : Arrays.asList("", "[]", SAMPLE.replace("\"format\": 1", "\"format\": 2"),
                SAMPLE.replace("\"library_mb\": 130", "\"library_mb\": \"130\""),
                SAMPLE.replace("\"peak_mb\": 1600", "\"peak_mb\": 0"), SAMPLE.replace("\"dump_mb\": 25, ", ""))) {
            try {
                BuildNeeds.parse(bad);
                fail("refused: " + bad);
            } catch (BuildNeeds.InvalidException expected) {
                // named
            }
        }
    }

    /**
     * Catches the build's new named stops shown as "the tool failed": the space it lacks (its number in the text), a
     * phone that can never hold the translation, and a compile Android stopped at the end of the build's retries.
     */
    @Test
    public void theBuildsNamedStops() {
        UpdateException space = BuildFailures.exception("space",
                "212 MB more free space needed (space: the build needs 997 MB; 700 MB free, 85 MB in the work folder)");
        assertEquals(UpdateFailure.BUILD_SPACE, space.failure);
        assertEquals("hf_fail_build_space_mb", space.reason.key);
        assertEquals(Arrays.asList("212"), space.reason.args);
        assertEquals("hf_advice_space", space.advice.key);

        UpdateException never = BuildFailures.exception("memory", "the translation of the game's code needs about 1600 MB"
                + " of memory and this device has 1480 MB in all (with its swap)");
        assertEquals(UpdateFailure.BUILD_MEMORY_NEVER, never.failure);
        assertEquals(Arrays.asList("1480", "1600"), never.reason.args);
        assertEquals("hf_advice_memory_never", never.advice.key);
        assertEquals(UpdateFailure.BUILD_MEMORY, BuildFailures.exception("memory", "recompile").failure);
        assertEquals(UpdateFailure.BUILD_MEMORY,
                BuildFailures.exception("memory", "a process was closed by Android (most likely for memory)").failure);

        UpdateException compile = BuildFailures.exception("compile", "the phone ran out of memory compiling part of the"
                + " game's code (f_00ce7920, about 201 MB, stopped by the system 3 times, the last with nothing else"
                + " compiling): close other apps or restart the phone, then build again (it goes on from where it stopped)");
        assertEquals(UpdateFailure.BUILD_COMPILE_MEMORY, compile.failure);
        assertEquals("hf_advice_compile_memory", compile.advice.key);
        assertEquals(UpdateFailure.BUILD_COMPILE,
                BuildFailures.exception("compile", "error in game/recomp/src/f_1.c: expected ';'").failure);
        assertEquals("the build's words stay in the log", "space: 5 MB more free space needed",
                BuildFailures.exception("space", "5 MB more free space needed").detail);
        assertEquals(UpdateFailure.BUILD_SPACE, BuildFailures.exception("space", "no number").failure);

        String phoneBuild = Support.readRepo("mh4n/tools/android/phone_build.py");
        assertTrue(phoneBuild.contains("@@error space {need} MB more free space needed"));
        assertTrue(phoneBuild.contains("@@error memory {never}"));
        String jobs = Support.readRepo("mh4n/tools/recomp/jobs.py");
        assertTrue(jobs.contains("f\"the phone ran out of memory compiling part of the game's code"));
        assertTrue(phoneBuild.contains("needs about {peak} MB of memory and this device has {total} MB "));
    }
}
