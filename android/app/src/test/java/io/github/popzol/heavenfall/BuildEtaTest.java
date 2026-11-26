package io.github.popzol.heavenfall;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The build's time left, learnt from its own units (done against the total, the objects' cache's line), and a rebuild
 * told from a first one.
 */
public class BuildEtaTest {
    private static final long S = 1000;
    private static final long MIN = 60 * S;
    private static final long UNIT = 1650;
    private static final long UNITS = 1742;

    // a phone like the first one measured: 3.5 min of tools and translation, 1.65 s a unit, 40 s to link
    private static BuildEta eta() {
        return new BuildEta(210 * S, UNIT, 40 * S, UNITS);
    }

    /** Catches a first build told as minutes, and a time shown before the build started. */
    @Test
    public void aFirstBuildBeforeItsFirstUnit() {
        BuildEta e = eta();
        assertEquals(-1, e.msLeft(0));
        e.start(0, 0);
        assertEquals(210 * S + UNITS * UNIT + 40 * S, e.msLeft(0));
        assertEquals("the tools' time runs down", UNITS * UNIT + 40 * S, e.msLeft(210 * S));
        assertEquals("a slow start is no negative time", UNITS * UNIT + 40 * S, e.msLeft(400 * S));
    }

    /**
     * Catches a rebuild that reuses nearly every object told as a whole build ("about 38 min" at its first second):
     * the objects the builder keeps count before the compile and while the units are looked up in the cache, and the
     * cache's line says what is left.
     */
    @Test
    public void aRebuildIsToldFromTheCache() {
        BuildEta e = eta();
        e.start(0, UNITS);
        assertEquals((210 + 40) * S, e.msLeft(0));
        assertTrue(e.msLeft(0) < 5 * MIN);
        e.sample(200 * S, "compile", 0, UNITS + 1);
        assertEquals("looking the units up: no whole build told", 40 * S, e.msLeft(200 * S));
        e.cache(205 * S, UNITS - 1, 1);
        assertEquals(UNIT + 40 * S, e.msLeft(205 * S));
        e.sample(206 * S, "compile", UNITS, UNITS + 1);
        assertEquals("the one unit compiled: the link", 40 * S, e.msLeft(206 * S));
    }

    /** Catches a guess from a cache of other objects (other tools, another mod) kept after the cache's line. */
    @Test
    public void theCachesLineCorrectsTheGuess() {
        BuildEta e = eta();
        e.start(0, UNITS);
        e.sample(200 * S, "compile", 300, UNITS + 1);
        e.cache(230 * S, 1000, 743);
        assertEquals(743 * UNIT + 40 * S, e.msLeft(230 * S));
        BuildEta none = eta();
        none.start(0, 0);
        none.sample(210 * S, "compile", 0, UNITS + 1);
        assertEquals("an empty cache: every unit", UNITS * UNIT + 40 * S, none.msLeft(210 * S));
    }

    /** Catches an estimate that never learns this phone's pace, or learns it from the cache's objects. */
    @Test
    public void thePaceIsLearntFromTheUnitsCompiled() {
        BuildEta e = eta();
        e.start(0, 0);
        e.sample(210 * S, "compile", 500, UNITS + 1);   // (the cache's objects, placed while looking up)
        e.cache(220 * S, 500, 1243);
        long t = 220 * S;
        for (int i = 1; i <= 20; i++) e.sample(t + i * 2500, "compile", 500 + i, UNITS + 1);
        long now = t + 20 * 2500;
        assertEquals("2.5 s a unit, from the latest half of the units compiled", 1223 * 2500 + 40 * S, e.msLeft(now));
        e.sample(now, "compile", 520, UNITS + 1);
        assertEquals("a repeated reading changes nothing", 1223 * 2500 + 40 * S, e.msLeft(now));
    }

    /** Catches the link phase shown as no time, or as the compile's time. */
    @Test
    public void theLinkAfterTheLastUnit() {
        BuildEta e = eta();
        e.start(0, 0);
        e.cache(10 * S, UNITS, 0);
        assertEquals(40 * S, e.msLeft(10 * S));
        assertEquals(10 * S, e.msLeft(40 * S));
        assertEquals(0, e.msLeft(90 * S));
        BuildEta i = eta();
        i.start(0, 0);
        i.sample(5 * S, "install", 0, 0);
        assertEquals(40 * S, i.msLeft(5 * S));
    }

    /** Catches the cache's line missed (its words as recomp/build.py prints them) or another line taken for it. */
    @Test
    public void theCachesLineIsRead() {
        assertArrayEquals(new long[] {1741, 1},
                BuildEta.cacheLine("objects' cache /data/user/0/x/files/builder/object-cache: 1741 objects from it, 1 to compile"));
        assertArrayEquals(new long[] {0, 1742}, BuildEta.cacheLine("objects' cache C:\\w\\object-cache: 0 objects from it, "
                + "1742 to compile (3 of its entries didn't match their sums: dropped)"));
        assertNull(BuildEta.cacheLine("objects' cache: 152 MB"));
        assertNull(BuildEta.cacheLine("  compiled f_00ce7920 (12 KiB of C): 1.6 s, 74 MB, alone"));
        assertNull(BuildEta.cacheLine(null));
        String build = Support.readRepo("mh4n/tools/recomp/build.py");
        assertTrue("the line the tools print", build.contains("objects from it, {len(todo)} to compile"));
    }

    /** Catches a tuning that makes a first build look short or a rebuild look like a first build. */
    @Test
    public void theTuningGivesAFirstBuildAndARebuildApart() {
        Tuning t = Support.tuning();
        BuildEta first = BuildEta.of(t);
        first.start(0, 0);
        BuildEta again = BuildEta.of(t);
        again.start(0, t.buildUnits);
        assertTrue(first.msLeft(0) > 20 * MIN && first.msLeft(0) < 120 * MIN);
        assertTrue(again.msLeft(0) < first.msLeft(0) / 4);
    }
}
