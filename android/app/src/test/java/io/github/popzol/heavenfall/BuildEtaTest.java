package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The build's time left, learnt from its own objects (done against the total), and a rebuild told from a first one. */
public class BuildEtaTest {
    private static final long S = 1000;
    private static final long MIN = 60 * S;

    // a phone like the first one measured: 3.5 min of tools and translation, 13 s an object, 40 s to link, 224 objects
    private static BuildEta eta() {
        return new BuildEta(210 * S, 13 * S, 40 * S, 224);
    }

    /** Catches a first build told as minutes, and nothing shown before the first reading. */
    @Test
    public void aFirstBuildBeforeItsFirstObject() {
        BuildEta e = eta();
        assertEquals(-1, e.msLeft(0));
        e.start(0, 0);
        assertEquals((210 + 224 * 13 + 40) * S, e.msLeft(0));
        assertEquals("the tools' time runs down", (224 * 13 + 40) * S, e.msLeft(210 * S));
        assertEquals("a slow start is no negative time", (224 * 13 + 40) * S, e.msLeft(400 * S));
    }

    /**
     * Catches a rebuild that reuses most objects told as a whole build ("about 38 min" at its first second): the
     * objects kept in the work folder count before the first reading, and the first reading's done objects after.
     */
    @Test
    public void aRebuildIsToldFromItsObjects() {
        BuildEta e = eta();
        e.start(0, 220);
        assertEquals((210 + 4 * 13 + 40) * S, e.msLeft(0));
        assertTrue(e.msLeft(0) < 6 * MIN);
        e.sample(30 * S, "compile", 200, 224);
        assertEquals("the build's own count of up-to-date objects", (24 * 13 + 40) * S, e.msLeft(30 * S));
    }

    /** Catches an estimate that never learns this phone's pace (a phone twice as slow as the tuning is told so). */
    @Test
    public void thePaceIsLearnt() {
        BuildEta e = eta();
        e.start(0, 0);
        long t = 210 * S;
        e.sample(t, "compile", 0, 224);
        for (int done = 1; done <= 20; done++) e.sample(t + done * 26 * S, "compile", done, 224);
        long now = t + 20 * 26 * S;
        assertEquals("26 s an object, from the latest half", (204 * 26 + 40) * S, e.msLeft(now));
        e.sample(now, "compile", 20, 224);
        assertEquals("a repeated reading changes nothing", (204 * 26 + 40) * S, e.msLeft(now));
    }

    /** Catches the link phase (all objects done) shown as no time, or as the compile's time. */
    @Test
    public void theLinkAfterTheLastObject() {
        BuildEta e = eta();
        e.start(0, 0);
        e.sample(10 * S, "compile", 224, 224);
        assertEquals(40 * S, e.msLeft(10 * S));
        assertEquals(10 * S, e.msLeft(40 * S));
        assertEquals(0, e.msLeft(90 * S));
        BuildEta i = eta();
        i.start(0, 0);
        i.sample(5 * S, "install", 0, 0);
        assertEquals(40 * S, i.msLeft(5 * S));
    }

    /** Catches a tuning that makes a first build look short or a rebuild look like a first build. */
    @Test
    public void theTuningGivesAFirstBuildAndARebuildApart() {
        Tuning t = Support.tuning();
        BuildEta first = BuildEta.of(t);
        first.start(0, 0);
        BuildEta again = BuildEta.of(t);
        again.start(0, t.buildObjects);
        assertTrue(first.msLeft(0) > 20 * MIN && first.msLeft(0) < 120 * MIN);
        assertTrue(again.msLeft(0) < first.msLeft(0) / 4);
    }
}
