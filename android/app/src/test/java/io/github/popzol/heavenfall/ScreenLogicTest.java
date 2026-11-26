package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.HomeModel.Library;
import static io.github.popzol.heavenfall.HomeModel.Source;
import static io.github.popzol.heavenfall.HomeModel.Storage;
import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

/** The small pure parts of the screens: the facts into the model's input, what a tap asks the job for, the time, the language. */
public class ScreenLogicTest {
    private static Installed installed(boolean xx, String libraryId, String libraryCode) {
        return Support.installed("beta", "0.2.3", "s", sha('a'), xx, sha('b'), libraryId, libraryCode);
    }

    // ---- PhoneFacts

    /** No permission, then no folder, then ready: the order the model's setup reads. */
    @Test
    public void storageFollowsPermissionThenFolder() {
        PhoneFacts f = new PhoneFacts();
        assertEquals(Storage.NO_PERMISSION, f.storage());
        f.permission = true;
        assertEquals(Storage.NO_FOLDER, f.storage());
        f.folder = new File("x");
        assertEquals(Storage.READY, f.storage());
    }

    /** What is in the folder counts only when the folder is readable: without the permission nothing is found. */
    @Test
    public void dumpsCountOnlyWithAReadableFolder() {
        PhoneFacts f = new PhoneFacts();
        f.found = new DumpScan.Found(Source.GOOD, new File("g"), Source.GOOD, new File("u"), Source.GOOD, new File("x"));
        HomeModel.Input in = new HomeModel.Input();
        f.applyTo(in);
        assertEquals(Source.MISSING, in.game);
        assertEquals(Source.MISSING, in.update);
        assertEquals(Source.MISSING, in.xx);
        f.permission = true;
        f.folder = new File("x");
        f.applyTo(in);
        assertEquals(Source.GOOD, in.game);
        assertEquals(Source.GOOD, in.update);
        assertEquals(Source.GOOD, in.xx);
    }

    /** The library: none, built by other tools, for another mod, good. */
    @Test
    public void libraryStates() {
        PhoneFacts f = new PhoneFacts();
        f.wantedId = "tools-7";
        assertEquals(Library.NONE, f.library());
        f.library = new LibraryBuilder.State(true, "tools-7", null);
        assertEquals("a library with no record is not one this app built", Library.UNKNOWN, f.library());
        f.installed = installed(false, "tools-7", sha('b'));
        assertEquals("a library that is not the recorded file", Library.UNKNOWN, f.library());
    }

    /** The facts fill the phone's side of the input and leave the check's answers alone. */
    @Test
    public void applyToKeepsTheChecksAnswers() {
        PhoneFacts f = new PhoneFacts();
        f.permission = true;
        f.folder = new File("x");
        f.freeBytes = 123;
        f.memoryInAllMb = 456;
        f.batteryPercent = 77;
        f.charging = true;
        f.appVersion = "1.2.3";
        f.job = HomeModel.Job.HALF_DONE;
        f.installed = installed(false, "", "");
        HomeModel.Input in = new HomeModel.Input();
        in.latestApp = "v9";
        in.latestMod = "v8";
        in.releaseHasXxSet = true;
        in.checking = true;
        f.applyTo(in);
        assertEquals("v9", in.latestApp);
        assertEquals("v8", in.latestMod);
        assertTrue(in.releaseHasXxSet);
        assertTrue(in.checking);
        assertEquals(123, in.freeBytes);
        assertEquals(456, in.memoryInAllMb);
        assertEquals(77, in.batteryPercent);
        assertTrue(in.charging);
        assertEquals("1.2.3", in.appVersion);
        assertEquals(HomeModel.Job.HALF_DONE, in.job);
    }

    // ---- StartChoice

    private static HomeModel.Input input(boolean installedWithXx, boolean hasInstalled, Source xx, String latestMod) {
        HomeModel.Input in = new HomeModel.Input();
        in.xx = xx;
        in.installed = hasInstalled ? installed(installedWithXx, "t", sha('b')) : null;
        in.latestMod = latestMod;
        return in;
    }

    /** Install and Update ask for the set with MH XX when its dump is good, and for the set without otherwise. */
    @Test
    public void installAsksForXxWhenItsDumpIsGood() {
        assertTrue(StartChoice.forAction(HomeModel.Action.INSTALL, input(false, false, Source.GOOD, "v0.3")).xx);
        assertFalse(StartChoice.forAction(HomeModel.Action.INSTALL, input(false, false, Source.ENCRYPTED, "v0.3")).xx);
        assertFalse(StartChoice.forAction(HomeModel.Action.INSTALL, input(false, false, Source.MISSING, "v0.3")).xx);
        assertTrue("MH XX copied after the install", StartChoice.forAction(HomeModel.Action.UPDATE,
                input(false, true, Source.GOOD, "v0.2.3")).xx);
    }

    /** An update of the app alone keeps the mod's MH XX even when its dump is not at hand: nothing is dropped silently. */
    @Test
    public void anAppOnlyUpdateKeepsTheInstalledXx() {
        assertTrue(StartChoice.forAction(HomeModel.Action.UPDATE, input(true, true, Source.MISSING, "v0.2.3")).xx);
        assertFalse("a new mod needs the dump to carry MH XX over",
                StartChoice.forAction(HomeModel.Action.UPDATE, input(true, true, Source.MISSING, "v0.3.0")).xx);
    }

    /** Build changes nothing of the mod: it keeps whatever is installed. */
    @Test
    public void buildKeepsTheMod() {
        StartChoice withXx = StartChoice.forAction(HomeModel.Action.BUILD, input(true, true, Source.MISSING, "v0.2.3"));
        assertTrue(withXx.xx);
        assertFalse(withXx.forceMod);
        assertFalse(withXx.forceBuild);
        assertFalse(StartChoice.forAction(HomeModel.Action.BUILD, input(false, true, Source.GOOD, "v0.2.3")).xx);
    }

    /** Reinstall forces the mod only, and Build again forces the library only. */
    @Test
    public void reinstallAndRebuildForceOneThing() {
        StartChoice reinstall = StartChoice.reinstall(input(false, true, Source.GOOD, "v0.2.3"));
        assertTrue(reinstall.forceMod);
        assertFalse(reinstall.forceBuild);
        assertTrue(reinstall.xx);
        StartChoice rebuild = StartChoice.rebuild(input(true, true, Source.MISSING, "v0.2.3"));
        assertTrue(rebuild.forceBuild);
        assertFalse(rebuild.forceMod);
        assertTrue("the mod stays as it is", rebuild.xx);
    }

    // ---- TimeLeft and Languages

    @Test
    public void timeLeft() {
        assertNull(TimeLeft.of(0));
        assertNull(TimeLeft.of(-5));
        assertEquals("hf_time_left_under_minute", TimeLeft.of(1).key);
        assertEquals("hf_time_left_under_minute", TimeLeft.of(60_000).key);
        assertEquals("2", TimeLeft.of(60_001).args.get(0));
        assertEquals("hf_time_left_minutes", TimeLeft.of(59 * 60_000L).key);
        assertEquals("59", TimeLeft.of(59 * 60_000L).args.get(0));
        Text hours = TimeLeft.of(135 * 60_000L);
        assertEquals("hf_time_left_hours", hours.key);
        assertEquals("2", hours.args.get(0));
        assertEquals("15", hours.args.get(1));
    }

    @Test
    public void languages() {
        assertEquals("", Languages.normalize(null));
        assertEquals("", Languages.normalize("fr"));
        assertEquals("es", Languages.normalize("es"));
        assertEquals("hf_lang_auto", Languages.label("").key);
        assertEquals("hf_lang_en", Languages.label("en").key);
        assertEquals("hf_lang_es", Languages.label("es").key);
        assertEquals(3, Languages.CODES.length);
    }
}
