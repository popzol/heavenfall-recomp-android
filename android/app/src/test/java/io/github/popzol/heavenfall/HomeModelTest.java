package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.HomeModel.Action;
import static io.github.popzol.heavenfall.HomeModel.Job;
import static io.github.popzol.heavenfall.HomeModel.Library;
import static io.github.popzol.heavenfall.HomeModel.More;
import static io.github.popzol.heavenfall.HomeModel.Source;
import static io.github.popzol.heavenfall.HomeModel.Storage;
import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeModelTest {
    private static final long GB = 1L << 30;
    private static final long MB = 1L << 20;
    private final Tuning tuning = Support.tuning();
    private final BuildNeeds needs = Support.buildNeeds();
    private final HomeModel model = new HomeModel(tuning, needs);

    private static Installed installed(String version, boolean xx) {
        return Support.installed("beta", version, "s.hfpatch", sha('a'), xx, sha('b'), "tools-7", sha('b'));
    }

    /** Everything good: 0.2.3 installed, library good, the latest known and the same, plenty of room. */
    private static HomeModel.Input good() {
        HomeModel.Input in = new HomeModel.Input();
        in.game = Source.GOOD;
        in.update = Source.GOOD;
        in.installed = installed("0.2.3", false);
        in.library = Library.GOOD;
        in.appVersion = "1.0.0";
        in.latestApp = "v1.0.0";
        in.latestMod = "v0.2.3";
        in.freeBytes = 50 * GB;
        in.memoryInAllMb = 6000;
        in.batteryPercent = 80;
        return in;
    }

    private HomeModel.Output run(HomeModel.Input in) {
        return model.compute(in);
    }

    private static void assertState(String key, HomeModel.Output out) {
        assertEquals(key, out.state.key);
    }

    private static boolean blocked(HomeModel.Output out, String reasonKey) {
        for (HomeModel.Block b : out.blocks) {
            if (b.reason.key.equals(reasonKey)) return true;
        }
        return false;
    }

    @Test
    public void upToDatePlays() {
        HomeModel.Output out = run(good());
        assertEquals(Action.PLAY, out.action);
        assertState("hf_state_up_to_date", out);
        assertEquals("hf_btn_play", out.button.key);
        assertTrue(out.enabled);
        assertTrue(out.blocks.isEmpty());
        assertTrue(out.canPlay);
    }

    /** While a job runs the button is gone and only the harmless "More" entries stay. */
    @Test
    public void jobRunningHasNoButton() {
        HomeModel.Input in = good();
        in.job = Job.RUNNING;
        HomeModel.Output out = run(in);
        assertEquals(Action.NONE, out.action);
        assertNull(out.button);
        assertFalse(out.enabled);
        assertFalse(out.canPlay);
        assertState("hf_state_working", out);
        assertEquals(java.util.EnumSet.of(More.LANGUAGE, More.CREDITS, More.SHARE_LOG), out.more);
    }

    /** No permission: the one thing to fix is the permission, not the dumps behind it. */
    @Test
    public void noPermission() {
        HomeModel.Input in = good();
        in.storage = Storage.NO_PERMISSION;
        in.game = Source.MISSING;
        in.update = Source.MISSING;
        in.installed = null;
        HomeModel.Output out = run(in);
        assertEquals(Action.SET_UP, out.action);
        assertEquals("hf_btn_set_up", out.button.key);
        assertTrue("SET_UP opens the assistant: it always takes a tap", out.enabled);
        assertState("hf_problem_permission", out);
        assertEquals(1, out.problems.size());
        assertEquals("hf_advice_permission", out.problems.get(0).advice.key);
        assertFalse(out.more.contains(More.GAME_FOLDER));
    }

    @Test
    public void noFolder() {
        HomeModel.Input in = good();
        in.storage = Storage.NO_FOLDER;
        in.game = Source.MISSING;
        in.update = Source.MISSING;
        in.installed = null;
        HomeModel.Output out = run(in);
        assertEquals(Action.SET_UP, out.action);
        assertState("hf_problem_folder", out);
        assertEquals(1, out.problems.size());
        assertTrue(out.more.contains(More.GAME_FOLDER));
    }

    /** Each way the game dump can be wrong has its own state line and advice. */
    @Test
    public void gameDumpProblems() {
        String[][] cases = {{"MISSING", "game_missing"}, {"ENCRYPTED", "game_encrypted"}, {"WRONG_REGION", "game_region"},
                {"WRONG_VERSION", "game_version"}};
        for (String[] c : cases) {
            HomeModel.Input in = good();
            in.installed = null;
            in.game = Source.valueOf(c[0]);
            HomeModel.Output out = run(in);
            assertEquals(c[0], Action.SET_UP, out.action);
            assertState("hf_problem_" + c[1], out);
            assertEquals("hf_advice_" + c[1], out.problems.get(0).advice.key);
        }
    }

    /**
     * The update dump is a setup need, installed or not: the library's build and the game's start both read it. Without
     * it, or with a wrong one, no state offers Install, Update, Build, Resume or Play (the screen would only show a toast).
     */
    @Test
    public void updateDumpProblems() {
        String[][] cases = {{"MISSING", "update_missing"}, {"ENCRYPTED", "update_encrypted"},
                {"WRONG_REGION", "update_region"}, {"WRONG_VERSION", "update_version"}};
        for (String[] c : cases) {
            for (boolean installed : new boolean[] {false, true}) {
                HomeModel.Input in = good();
                if (!installed) in.installed = null;
                in.update = Source.valueOf(c[0]);
                HomeModel.Output out = run(in);
                assertEquals(c[0] + " installed=" + installed, Action.SET_UP, out.action);
                assertState("hf_problem_" + c[1], out);
                assertEquals("hf_advice_" + c[1], out.problems.get(0).advice.key);
                assertFalse(out.canPlay);
            }
            // each state that used to go on without it: a newer mod, a library to build, a job left half-done, offline
            for (int state = 0; state < 4; state++) {
                HomeModel.Input in = good();
                in.update = Source.valueOf(c[0]);
                if (state == 0) in.latestMod = "v0.3";
                if (state == 1) in.library = Library.NONE;
                if (state == 2) in.job = Job.HALF_DONE;
                if (state == 3) {
                    in.latestMod = null;
                    in.latestApp = null;
                }
                HomeModel.Output out = run(in);
                assertEquals(c[0] + " state " + state, Action.SET_UP, out.action);
                assertFalse(out.more.contains(More.REBUILD));
                assertFalse(out.more.contains(More.REINSTALL));
            }
        }
    }

    /** A first install with everything in place: Install, and what stops it is only room, battery, memory and network. */
    @Test
    public void installReady() {
        HomeModel.Input in = good();
        in.installed = null;
        in.library = Library.NONE;
        HomeModel.Output out = run(in);
        assertEquals(Action.INSTALL, out.action);
        assertState("hf_state_not_installed", out);
        assertEquals("hf_btn_install", out.button.key);
        assertTrue(out.enabled);
        assertTrue(out.includesBuild);
        assertFalse(out.canPlay);
    }

    @Test
    public void installBlocked() {
        HomeModel.Input in = good();
        in.installed = null;
        in.freeBytes = 100 * MB;
        in.batteryPercent = 10;
        in.memoryInAllMb = 100;
        HomeModel.Output out = run(in);
        assertEquals(Action.INSTALL, out.action);
        assertFalse(out.enabled);
        assertTrue(blocked(out, "hf_block_space"));
        assertTrue(blocked(out, "hf_block_battery"));
        assertTrue(blocked(out, "hf_block_memory_never"));
        for (HomeModel.Block b : out.blocks) assertTrue("every block has advice", b.advice.key.startsWith("hf_advice_"));
        HomeModel.Block space = out.blocks.get(0);
        assertEquals(2, space.reason.args.size());
        assertEquals("the larger of the download's room and the build's own",
                String.valueOf(Math.max(tuning.minFreeDownloadMb, needs.spaceMb)), space.reason.args.get(0));

        in.charging = true;
        assertFalse("charging lifts the battery rule", blocked(run(in), "hf_block_battery"));
    }

    /**
     * Catches a job started while the game is open in its own task (the player switched to Home from it): the job
     * replaces the mod's files and the library, and the app's own install would stop the hunt. Every button that
     * starts a job is blocked, named, until the game is closed.
     */
    @Test
    public void anOpenGameBlocksEveryJob() {
        HomeModel.Input update = good();
        update.latestMod = "v0.2.4";
        HomeModel.Input install = good();
        install.installed = null;
        HomeModel.Input build = good();
        build.library = Library.NONE;
        HomeModel.Input reinstall = good();
        reinstall.modCode = HomeModel.ModCode.CHANGED;
        HomeModel.Input resume = good();
        resume.job = Job.HALF_DONE;
        Action[] actions = {Action.UPDATE, Action.INSTALL, Action.BUILD, Action.REINSTALL, Action.RESUME};
        HomeModel.Input[] inputs = {update, install, build, reinstall, resume};
        for (int i = 0; i < inputs.length; i++) {
            assertTrue(actions[i] + " with the game closed", run(inputs[i]).enabled);
            inputs[i].gameOpen = true;
            HomeModel.Output out = run(inputs[i]);
            assertEquals(actions[i], out.action);
            assertFalse(actions[i] + " with the game open", out.enabled);
            assertTrue(actions[i] + " names the open game", blocked(out, "hf_fail_game_open"));
            assertEquals("hf_advice_close_game", out.blocks.get(0).advice.key);
        }
        HomeModel.Input playing = good();
        playing.gameOpen = true;
        assertTrue("Play is never blocked by the game being open", run(playing).enabled);
    }

    /** Offline: the first install cannot start (named), and while the check runs it says so. */
    @Test
    public void installOfflineAndChecking() {
        HomeModel.Input in = good();
        in.installed = null;
        in.latestMod = null;
        in.latestApp = null;
        HomeModel.Output out = run(in);
        assertEquals(Action.INSTALL, out.action);
        assertFalse(out.enabled);
        assertTrue(blocked(out, "hf_block_no_network"));
        assertEquals("hf_advice_connect", out.blocks.get(0).advice.key);

        in.checking = true;
        out = run(in);
        assertTrue(blocked(out, "hf_block_checking"));
        assertFalse(blocked(out, "hf_block_no_network"));
    }

    /**
     * Catches Home's space and memory before a build going by other numbers than the build's own (BuildNeeds, the
     * build_tuning.json phone_build.py checks itself against): the build's room counts its work folder and its
     * cache above the floor, as the build does; the memory is the phone's in all against the translation's peak (a
     * phone short of free memory now still builds).
     */
    @Test
    public void aBuildGoesByTheBuildsOwnNumbers() {
        HomeModel.Input in = good();
        in.library = Library.NONE;
        in.latestMod = null;
        in.latestApp = null;
        in.freeBytes = needs.spaceMb * MB - 1;
        assertTrue(blocked(run(in), "hf_block_space"));
        assertEquals(String.valueOf(needs.spaceMb), run(in).blocks.get(0).reason.args.get(0));
        in.freeBytes = needs.spaceMb * MB;
        assertTrue("the build's own need, not the download's or a guess", run(in).enabled);
        in.freeBytes = 10 * MB;
        in.builderWorkBytes = needs.spaceMb * MB;
        assertTrue("a build cut short: its work folder is room", run(in).enabled);
        in.builderWorkBytes = 0;
        in.objectCacheBytes = (needs.cacheFloorMb + needs.spaceMb) * MB;
        assertTrue("the cache above its floor is room", run(in).enabled);
        in.objectCacheBytes = needs.cacheFloorMb * MB;
        assertTrue(blocked(run(in), "hf_block_space"));
        in.freeBytes = 50 * GB;
        in.memoryInAllMb = needs.translationPeakMb - 1;
        HomeModel.Output out = run(in);
        assertTrue(blocked(out, "hf_block_memory_never"));
        assertEquals(String.valueOf(needs.translationPeakMb), out.blocks.get(0).reason.args.get(1));
        in.memoryInAllMb = needs.translationPeakMb;
        assertTrue(run(in).enabled);
        HomeModel none = new HomeModel(tuning, BuildNeeds.NONE);
        in.freeBytes = 1;
        in.memoryInAllMb = 1;
        assertTrue("the build's numbers unread: the build checks itself", none.compute(in).enabled);
    }

    /** An unknown reading (-1) never blocks: only a known low value does. */
    @Test
    public void unknownReadingsDoNotBlock() {
        HomeModel.Input in = good();
        in.installed = null;
        in.freeBytes = -1;
        in.batteryPercent = -1;
        in.memoryInAllMb = -1;
        assertTrue(run(in).enabled);
    }

    @Test
    public void modUpdate() {
        HomeModel.Input in = good();
        in.latestMod = "v0.3.0";
        HomeModel.Output out = run(in);
        assertEquals(Action.UPDATE, out.action);
        assertState("hf_state_update_available", out);
        assertEquals("0.3", out.state.args.get(0));
        assertEquals("hf_btn_update_to", out.button.key);
        assertEquals("0.3", out.button.args.get(0));
        assertEquals("0.3", out.targetVersion);
        assertTrue(out.includesBuild);
        assertTrue(out.enabled);
        assertTrue("the installed game still plays", out.canPlay);
    }

    /** Only the app is new: no build, and the smaller room rule applies. */
    @Test
    public void appOnlyUpdate() {
        HomeModel.Input in = good();
        in.latestApp = "v1.1.0";
        in.freeBytes = tuning.minFreeDownloadBytes() + (1L << 20);
        in.batteryPercent = 5;
        HomeModel.Output out = run(in);
        assertEquals(Action.UPDATE, out.action);
        assertState("hf_state_app_update", out);
        assertEquals("1.1", out.targetVersion);
        assertFalse(out.includesBuild);
        assertTrue("no build, so neither the build's room nor battery", out.enabled);
        in.freeBytes = tuning.minFreeDownloadBytes() - 1;
        assertTrue(blocked(run(in), "hf_block_space"));
    }

    /** A newer mod and a newer app together are one button, named for the mod's version. */
    @Test
    public void bothNew() {
        HomeModel.Input in = good();
        in.latestApp = "v1.1.0";
        in.latestMod = "v0.3.0";
        HomeModel.Output out = run(in);
        assertEquals(Action.UPDATE, out.action);
        assertEquals("0.3", out.targetVersion);
    }

    /** The installed version nobody can read is updated over, not trusted. */
    @Test
    public void unreadableInstalledVersion() {
        HomeModel.Input in = good();
        in.installed = installed("custom build", false);
        assertEquals(Action.UPDATE, run(in).action);
    }

    /** MH XX copied after the install offers the set that has it; not without it in the release, nor twice. */
    @Test
    public void xxCopiedAfterInstall() {
        HomeModel.Input in = good();
        in.xx = Source.GOOD;
        in.releaseHasXxSet = true;
        HomeModel.Output out = run(in);
        assertEquals(Action.UPDATE, out.action);
        assertState("hf_state_xx_found", out);
        assertTrue(out.includesBuild);

        in.releaseHasXxSet = false;
        assertEquals(Action.PLAY, run(in).action);

        in.releaseHasXxSet = true;
        in.installed = installed("0.2.3", true);
        assertEquals("already built with MH XX", Action.PLAY, run(in).action);
    }

    @Test
    public void xxProblemIsOnlyANote() {
        HomeModel.Input in = good();
        in.xx = Source.ENCRYPTED;
        HomeModel.Output out = run(in);
        assertEquals(Action.PLAY, out.action);
        assertEquals(1, out.notes.size());
        assertEquals("hf_problem_xx_encrypted", out.notes.get(0).key);
        in.xx = Source.MISSING;
        assertTrue("an absent optional dump says nothing", run(in).notes.isEmpty());
        in.installed = null;
        assertEquals("never a setup need", Action.INSTALL, run(in).action);
    }

    /** The library missing, made by other tools (with its id) or for another mod code: Build, each with its line. */
    @Test
    public void libraryStates() {
        HomeModel.Input in = good();
        in.library = Library.NONE;
        HomeModel.Output out = run(in);
        assertEquals(Action.BUILD, out.action);
        assertState("hf_state_build_needed", out);
        assertEquals("hf_btn_build", out.button.key);
        assertFalse("without a library the game cannot start", out.canPlay);

        in.library = Library.OTHER_TOOLS;
        in.libraryId = "pc-tools-3";
        out = run(in);
        assertState("hf_state_rebuild_other_tools", out);
        assertEquals("pc-tools-3", out.state.args.get(0));
        in.libraryId = "";
        assertState("hf_state_rebuild_other_tools_noid", run(in));

        in.library = Library.OTHER_MOD;
        out = run(in);
        assertEquals(Action.BUILD, out.action);
        assertState("hf_state_rebuild_other_mod", out);
    }

    /** A new mod and no library: one Update button that includes the build, not Build first. */
    @Test
    public void updateComesBeforeBuild() {
        HomeModel.Input in = good();
        in.library = Library.NONE;
        in.latestMod = "v0.3";
        HomeModel.Output out = run(in);
        assertEquals(Action.UPDATE, out.action);
        assertTrue(out.includesBuild);
        assertFalse(out.canPlay);
    }

    /** The build is local: no network needed, but room, battery and memory are. */
    @Test
    public void buildNeedsNoNetwork() {
        HomeModel.Input in = good();
        in.library = Library.NONE;
        in.latestMod = null;
        in.latestApp = null;
        HomeModel.Output out = run(in);
        assertEquals(Action.BUILD, out.action);
        assertTrue(out.enabled);
        in.freeBytes = 100 * MB;
        in.batteryPercent = 3;
        out = run(in);
        assertFalse(out.enabled);
        assertTrue(blocked(out, "hf_block_space"));
        assertTrue(blocked(out, "hf_block_battery"));
    }

    /** Offline never takes Play away, whatever the room, battery and memory are. */
    @Test
    public void offlineNeverBlocksPlay() {
        HomeModel.Input in = good();
        in.latestMod = null;
        in.latestApp = null;
        in.freeBytes = 10L << 20;
        in.batteryPercent = 2;
        in.memoryInAllMb = 50;
        HomeModel.Output out = run(in);
        assertEquals(Action.PLAY, out.action);
        assertState("hf_state_offline", out);
        assertTrue(out.enabled);
        assertTrue(out.blocks.isEmpty());

        in.checking = true;
        assertState("hf_state_checking", run(in));
        assertEquals(Action.PLAY, run(in).action);

        in.checking = false;
        in.latestApp = "v1.0.0";
        in.latestMod = null;
        assertState("hf_state_offline", run(in));
    }

    /**
     * F3: a job left half-done offers Resume and Discard, keeps Play while the installed game can start, and is never
     * blocked by the room, the battery or the memory (the steps left check what they need when they start).
     */
    @Test
    public void halfDone() {
        HomeModel.Input in = good();
        in.job = Job.HALF_DONE;
        HomeModel.Output out = run(in);
        assertEquals(Action.RESUME, out.action);
        assertEquals("hf_btn_resume", out.button.key);
        assertState("hf_state_half_done", out);
        assertTrue(out.enabled);
        assertTrue("an installed game with a good library keeps Play", out.canPlay);
        assertTrue(out.canDiscard);

        in.batteryPercent = 5;
        in.freeBytes = 1;
        in.memoryInAllMb = 1;
        out = run(in);
        assertTrue(out.enabled);
        assertTrue(out.blocks.isEmpty());

        in.library = Library.OTHER_MOD;
        assertFalse(run(in).canPlay);
        in.installed = null;
        in.library = Library.GOOD;
        assertFalse("nothing installed: nothing to play", run(in).canPlay);
    }

    /**
     * F2: the installed mod's code changed outside the app, or gone: Reinstall (the mod made again), never Build or
     * Play, and the rebuild entry of More is gone with it.
     */
    @Test
    public void aChangedModOffersReinstallOnly() {
        HomeModel.Input in = good();
        in.modCode = HomeModel.ModCode.CHANGED;
        HomeModel.Output out = run(in);
        assertEquals(Action.REINSTALL, out.action);
        assertEquals("hf_btn_reinstall", out.button.key);
        assertState("hf_state_mod_changed", out);
        assertFalse(out.canPlay);
        assertFalse(out.more.contains(HomeModel.More.REBUILD));
        in.modCode = HomeModel.ModCode.MISSING;
        assertState("hf_state_mod_missing", run(in));
        in.job = Job.HALF_DONE;
        assertEquals("before a half-done job: its build would be refused", Action.REINSTALL, run(in).action);
        in.latestMod = null;
        in.checking = false;
        assertTrue("reinstalling downloads: the network is needed", blocked(run(in), "hf_block_no_network"));
        assertTrue(StartChoice.forAction(Action.REINSTALL, in).forceMod);
    }

    /** F1: a library the app cannot tell it built (no record of its file) is built again. */
    @Test
    public void anUnknownLibraryIsBuiltAgain() {
        HomeModel.Input in = good();
        in.library = Library.UNKNOWN;
        HomeModel.Output out = run(in);
        assertEquals(Action.BUILD, out.action);
        assertState("hf_state_rebuild_unknown", out);
        assertFalse(out.canPlay);
    }

    /** A broken setup beats a half-done job: the folder has to be there before anything resumes. */
    @Test
    public void setupBeatsHalfDone() {
        HomeModel.Input in = good();
        in.job = Job.HALF_DONE;
        in.storage = Storage.NO_PERMISSION;
        assertEquals(Action.SET_UP, run(in).action);
    }

    @Test
    public void moreEntries() {
        HomeModel.Input in = good();
        assertEquals(java.util.EnumSet.allOf(More.class), run(in).more);
        in.latestMod = null;
        assertFalse("reinstall downloads", run(in).more.contains(More.REINSTALL));
        in = good();
        in.update = Source.MISSING;
        assertFalse("rebuild and reinstall read the dumps", run(in).more.contains(More.REINSTALL));
        assertFalse(run(in).more.contains(More.REBUILD));
        in = good();
        in.installed = null;
        in.library = Library.NONE;
        HomeModel.Output out = run(in);
        assertFalse(out.more.contains(More.REBUILD));
        assertFalse(out.more.contains(More.REINSTALL));
        assertTrue(out.more.contains(More.MH_XX));
        for (More m : More.values()) assertTrue(m.labelKey().startsWith("hf_more_"));
    }

    /** The checklist always lists the five lines, optional MH XX last, for the first-run assistant. */
    @Test
    public void checklist() {
        HomeModel.Output out = run(good());
        assertEquals(5, out.checks.size());
        for (HomeModel.Check c : out.checks.subList(0, 4)) {
            assertEquals(HomeModel.CheckStatus.OK, c.status);
            assertTrue(c.label.key, c.required);
        }
        HomeModel.Check xx = out.checks.get(4);
        assertEquals("hf_check_xx", xx.label.key);
        assertEquals(HomeModel.CheckStatus.MISSING, xx.status);
        assertFalse(xx.required);
        assertEquals("the assistant can say what to copy", "hf_advice_xx_missing", xx.problem.advice.key);
        assertTrue("and it stops nothing", run(good()).problems.isEmpty());
        HomeModel.Input in = good();
        in.xx = Source.WRONG_VERSION;
        HomeModel.Check bad = run(in).checks.get(4);
        assertEquals(HomeModel.CheckStatus.PROBLEM, bad.status);
        assertNotNull(bad.problem);
    }
}
