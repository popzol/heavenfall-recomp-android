package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.Support.REPO;
import static io.github.popzol.heavenfall.Support.goodAsset;
import static io.github.popzol.heavenfall.Support.release;
import static io.github.popzol.heavenfall.Support.sha;
import static io.github.popzol.heavenfall.UpdatePlan.StepKind;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UpdatePlanTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String APP_REPO = "popzol/heavenfall-recomp-android";
    private final Tuning tuning = Support.tuning();

    private static ReleaseRules.Asset asset(String repo, String tag, String name, long size) {
        ReleaseRules.Result r = ReleaseRules.parse(release(tag, goodAsset(repo, tag, name, size, 'e')), repo,
                Arrays.asList(name));
        assertTrue(r.ok());
        return r.release.asset(name);
    }

    private static Installed installed(String version, boolean xx) {
        return Support.installed("beta", version, "s.hfpatch", sha('a'), xx, sha('b'), "tools-7", sha('b'));
    }

    /** An installed 0.2.3 with a good library, an app 1.0.0, a release of 0.3 with a set and a shared file. */
    private static UpdatePlan.Input base() {
        UpdatePlan.Input in = new UpdatePlan.Input();
        in.appVersion = "1.0.0";
        in.installed = installed("0.2.3", false);
        in.latestMod = "v0.3";
        in.setAsset = asset(REPO, "v0.3", "HeavenfallBeta-android-USA.hfpatch", 40L << 20);
        in.setNamesShared = true;
        in.sharedAsset = asset(REPO, "v0.3", "HeavenfallBeta-3ds-shared.hfpack", 300L << 20);
        return in;
    }

    private static List<StepKind> kinds(UpdatePlan p) {
        List<StepKind> list = new ArrayList<>();
        for (UpdatePlan.Step s : p.steps()) list.add(s.kind);
        return list;
    }

    private UpdatePlan make(UpdatePlan.Input in) {
        return UpdatePlan.make("beta", in, tuning);
    }

    /**
     * The order a plan starts with: the APK's download, its install, set, shared file, apply, build; before the APK is
     * downloaded the install is first and everything after it runs in the new app (the runner places it later).
     */
    @Test
    public void everythingInOrder() {
        UpdatePlan.Input in = base();
        in.latestApp = "v1.1.0";
        in.appAsset = asset(APP_REPO, "v1.1.0", "Heavenfall-android.apk", 150L << 20);
        UpdatePlan p = make(in);
        assertEquals(UpdatePlan.Status.STEPS, p.status);
        assertEquals(Arrays.asList(StepKind.APP, StepKind.APP_INSTALL, StepKind.SET, StepKind.SHARED, StepKind.APPLY,
                StepKind.BUILD), kinds(p));
        assertFalse(p.steps().get(0).afterRestart);
        assertFalse(p.steps().get(1).afterRestart);
        for (UpdatePlan.Step s : p.steps().subList(2, 6)) assertTrue(s.kind + " runs after the restart", s.afterRestart);
        assertEquals(2, p.stepsBeforeRestart().size());
        assertEquals(4, p.stepsAfterRestart().size());
        assertTrue("the build waits to see the applied code", p.steps().get(5).conditional);
        assertEquals("0.3", Version.parse(p.modTarget).toString());
    }

    /** Without a newer APK, the same steps run in this process, from the set. */
    @Test
    public void noAppStep() {
        UpdatePlan p = make(base());
        assertEquals(Arrays.asList(StepKind.SET, StepKind.SHARED, StepKind.APPLY, StepKind.BUILD), kinds(p));
        for (UpdatePlan.Step s : p.steps()) assertFalse(s.afterRestart);
        assertNull("no note without an app step", p.resumeNote());
    }

    /** The app's release carries no APK: the app is not "newer" for the plan. */
    @Test
    public void appTagWithoutApk() {
        UpdatePlan.Input in = base();
        in.latestApp = "v1.1.0";
        assertFalse(make(in).has(StepKind.APP));
    }

    /** A set that names no shared file has no shared step. */
    @Test
    public void noSharedFile() {
        UpdatePlan.Input in = base();
        in.setNamesShared = false;
        in.sharedAsset = null;
        assertEquals(Arrays.asList(StepKind.SET, StepKind.APPLY, StepKind.BUILD), kinds(make(in)));
    }

    /** The note left before the APK installs, and what the restarted app does with it. */
    @Test
    public void resumeAfterRestart() {
        UpdatePlan.Input in = base();
        in.latestApp = "v1.1.0";
        in.appAsset = asset(APP_REPO, "v1.1.0", "Heavenfall-android.apk", 150L << 20);
        in.xx = true;
        in.installed = installed("0.2.3", true);
        UpdatePlan p = make(in);
        Resume note = p.resumeNote();
        assertNotNull(note);
        assertEquals("beta", note.edition);
        assertEquals("v1.1.0", note.appTarget);
        assertEquals("v0.3", note.modTarget);
        assertTrue(note.withXx);
        assertTrue("the bar was past the APK's share", note.basePermille > 0 && note.basePermille < 999);

        Resume read = Resume.tryParse(note.toJson());
        assertNotNull(read);
        assertEquals(note.toJson(), read.toJson());

        assertEquals("the new app carries on", Resume.Verdict.GO, note.verdict("1.1.0"));
        assertEquals("a newer app too", Resume.Verdict.GO, note.verdict("v1.2"));
        assertEquals("the install did not happen: forget the note", Resume.Verdict.DROP, note.verdict("1.0.0"));
        assertEquals(Resume.Verdict.DROP, note.verdict("junk"));

        UpdatePlan rest = p.afterRestart(read);
        assertEquals(Arrays.asList(StepKind.SET, StepKind.SHARED, StepKind.APPLY, StepKind.BUILD), kinds(rest));
        assertEquals(note.basePermille, rest.basePermille);
        assertEquals("the bar does not go back", note.basePermille, rest.permille(0, 0.0));
        assertEquals(1000, rest.permille(4, 0.0));
        assertEquals("the plan made by the new app has no APK either", Arrays.asList(StepKind.SET, StepKind.SHARED,
                StepKind.APPLY, StepKind.BUILD), kinds(make(withApp(in, "1.1.0"))));
    }

    private UpdatePlan appPlan() {
        UpdatePlan.Input in = base();
        in.latestApp = "v1.1.0";
        in.appAsset = asset(APP_REPO, "v1.1.0", "Heavenfall-android.apk", 150L << 20);
        return make(in);
    }

    /**
     * Where the APK's install goes: LAST keeps the mod and the build in this app, AFTER_MOD leaves the build to the
     * new app, FIRST leaves everything to it; the steps after the install are flagged and the note is written in
     * every case, with the bar where the install starts.
     */
    @Test
    public void theInstallGoesWhereItIsPlaced() {
        UpdatePlan p = appPlan();
        UpdatePlan last = p.withInstall(UpdatePlan.InstallAt.LAST);
        assertEquals(Arrays.asList(StepKind.APP, StepKind.SET, StepKind.SHARED, StepKind.APPLY, StepKind.BUILD,
                StepKind.APP_INSTALL), kinds(last));
        for (UpdatePlan.Step s : last.steps()) assertFalse(s.kind + " runs here", s.afterRestart);
        assertFalse("nothing is left for the new app", last.stepsAfterInstall());
        assertTrue("the build still waits to see the applied code", last.steps().get(4).conditional);

        UpdatePlan mod = p.withInstall(UpdatePlan.InstallAt.AFTER_MOD);
        assertEquals(Arrays.asList(StepKind.APP, StepKind.SET, StepKind.SHARED, StepKind.APPLY, StepKind.APP_INSTALL),
                kinds(mod));
        assertFalse("the new app plans the build again", mod.has(StepKind.BUILD));

        UpdatePlan first = p.withInstall(UpdatePlan.InstallAt.FIRST);
        assertEquals(kinds(p), kinds(first));
        assertTrue(first.stepsAfterInstall());

        for (UpdatePlan plan : new UpdatePlan[] {last, mod, first}) {
            Resume note = plan.resumeNote();
            assertNotNull("the new app always says how the update ended", note);
            assertEquals(note.basePermille, plan.permille(plan.indexOf(StepKind.APP_INSTALL), 0.0));
        }
        assertTrue("the later the install, the further the bar is when it comes",
                last.resumeNote().basePermille > mod.resumeNote().basePermille
                        && mod.resumeNote().basePermille > first.resumeNote().basePermille);
        UpdatePlan noApp = make(base());
        assertSame("a plan with no APK has no install to place", noApp, noApp.withInstall(UpdatePlan.InstallAt.LAST));
    }

    private static UpdatePlan.Input withApp(UpdatePlan.Input in, String version) {
        in.appVersion = version;
        return in;
    }

    /** Notes that are not ours (damaged, another format, a bad version) are not read. */
    @Test
    public void badResumeNotes() {
        for (String text : new String[] {null, "", "{}", "[]", "{\"format\":2,\"edition\":\"beta\",\"app_target\":\"1\",\"xx\":false,\"base_permille\":1}",
                "{\"format\":1,\"edition\":\"beta\",\"app_target\":\"x\",\"xx\":false,\"base_permille\":1}",
                "{\"format\":1,\"edition\":\"beta\",\"app_target\":\"1\",\"xx\":false,\"base_permille\":1000}",
                "{\"format\":1,\"edition\":\"\",\"app_target\":\"1\",\"xx\":false,\"base_permille\":1}"}) {
            assertNull("note: " + text, Resume.tryParse(text));
        }
    }

    /**
     * Which interrupted jobs the home screen sees as half-done (F3): the app replaced itself, or the mod was being made.
     * A ".part" download (a failed download, a declined APK install) is not: nothing installed was touched.
     */
    @Test
    public void jobLeft() {
        Resume note = new Resume("beta", "1.1", "0.3", false, 100);
        assertEquals(HomeModel.Job.HALF_DONE, UpdatePlan.jobLeft(note, "1.1", false, false));
        assertEquals("a note the install never fulfilled is not a job", HomeModel.Job.NONE,
                UpdatePlan.jobLeft(note, "1.0", false, false));
        assertEquals(HomeModel.Job.HALF_DONE, UpdatePlan.jobLeft(null, "1.0", true, false));
        assertEquals(HomeModel.Job.NONE, UpdatePlan.jobLeft(null, "1.0", false, false));
    }

    /** What each interrupted step leaves, so the next start knows what to redo. */
    @Test
    public void interruptedSteps() {
        UpdatePlan.Input in = base();
        in.latestApp = "v1.1.0";
        in.appAsset = asset(APP_REPO, "v1.1.0", "Heavenfall-android.apk", 1L << 20);
        UpdatePlan p = make(in);
        assertEquals(UpdatePlan.Leftover.PARTIAL_DOWNLOAD, p.steps().get(0).leftover);
        assertEquals(UpdatePlan.Leftover.NOTHING, p.steps().get(1).leftover);
        assertEquals(UpdatePlan.Leftover.PARTIAL_DOWNLOAD, p.steps().get(2).leftover);
        assertEquals(UpdatePlan.Leftover.PARTIAL_DOWNLOAD, p.steps().get(3).leftover);
        assertEquals(UpdatePlan.Leftover.STAGING_DISCARDED, p.steps().get(4).leftover);
        assertEquals(UpdatePlan.Leftover.BUILD_CACHE, p.steps().get(5).leftover);
        for (StepKind k : StepKind.values()) assertTrue(k.labelKey().startsWith("hf_step_"));
    }

    /** The bar rises with the steps, never decreases, starts at the base and reaches 1000 only at the end. */
    @Test
    public void barIsMonotone() {
        UpdatePlan.Input in = base();
        in.latestApp = "v1.1.0";
        in.appAsset = asset(APP_REPO, "v1.1.0", "Heavenfall-android.apk", 150L << 20);
        UpdatePlan p = make(in);
        int last = -1;
        for (int i = 0; i < p.steps().size(); i++) {
            for (double f = 0; f <= 1.0; f += 0.1) {
                int v = p.permille(i, f);
                assertTrue("never back: step " + i + " at " + f, v >= last);
                assertTrue(v < 1000);
                last = v;
            }
            if (i + 1 < p.steps().size()) {
                assertEquals("a finished step is where the next one starts", p.permille(i, 1.0), p.permille(i + 1, 0.0));
            }
        }
        assertEquals(1000, p.permille(p.steps().size(), 0.0));
        assertEquals(0, p.permille(0, 0.0));
        assertEquals("a fraction past the end is held", p.permille(0, 1.0), p.permille(0, 7.0));
        assertEquals(p.totalMs(), p.remainingMs(0, 0.0));
        assertEquals(0, p.remainingMs(p.steps().size(), 0.0));
        assertTrue(p.remainingMs(1, 0.5) < p.remainingMs(1, 0.0));
    }

    /** A bar that begins past zero (a resume) still ends at the top. */
    @Test
    public void barFromABase() {
        UpdatePlan.Input in = base();
        in.basePermille = 400;
        UpdatePlan p = make(in);
        assertEquals(400, p.permille(0, 0.0));
        assertTrue(p.permille(2, 0.5) > 400);
        assertEquals(1000, p.permille(4, 0.0));
    }

    /** A mod that is not newer and a good library: nothing to do. */
    @Test
    public void upToDate() {
        UpdatePlan.Input in = base();
        in.latestMod = "v0.2.3";
        UpdatePlan p = make(in);
        assertEquals(UpdatePlan.Status.UP_TO_DATE, p.status);
        assertTrue(p.steps().isEmpty());
    }

    /** The library missing alone is a build with no download, and not a conditional one. */
    @Test
    public void libraryOnly() {
        UpdatePlan.Input in = base();
        in.latestMod = "v0.2.3";
        in.setAsset = null;
        in.sharedAsset = null;
        in.library = HomeModel.Library.OTHER_MOD;
        UpdatePlan p = make(in);
        assertEquals(Arrays.asList(StepKind.BUILD), kinds(p));
        assertFalse(p.steps().get(0).conditional);
        assertNull(p.modTarget);
    }

    /** A new mod with a library that was good: the build is kept only as conditional, and drops when the code is the same. */
    @Test
    public void buildDecidedAfterApply() throws IOException {
        UpdatePlan p = make(base());
        assertTrue(p.steps().get(p.steps().size() - 1).conditional);
        File lib = tmp.newFile("libmh4u_code_native.so");
        Files.write(lib.toPath(), new byte[] {1, 2, 3});
        Installed good = Installed.fresh("beta", "0.2.3", "s.hfpatch", sha('a'), false, sha('b'), sha('b'))
                .withLibrary("tools-7", sha('b'), LibraryFile.of(lib));
        LibraryBuilder.State onDisk = new LibraryBuilder.State(true, "tools-7", lib);
        Installed sameCode = good.applied("0.3", "s.hfpatch", sha('c'), false, sha('b'), sha('b'));
        assertFalse(UpdatePlan.buildStillNeeded(sameCode, onDisk, "tools-7"));
        UpdatePlan without = p.withoutBuild();
        assertEquals(Arrays.asList(StepKind.SET, StepKind.SHARED, StepKind.APPLY), kinds(without));
        Installed newCode = good.applied("0.3", "s.hfpatch", sha('c'), false, sha('d'), sha('d'));
        assertTrue(UpdatePlan.buildStillNeeded(newCode, onDisk, "tools-7"));
        assertTrue("other tools mean a build too", UpdatePlan.buildStillNeeded(sameCode, onDisk, "tools-8"));
        assertTrue(UpdatePlan.buildStillNeeded(sameCode, new LibraryBuilder.State(false, null, null), "tools-7"));
        // F1: a library put in place of the built one (same id beside it) is not the recorded file: built again
        Files.write(lib.toPath(), new byte[] {9, 9, 9, 9});
        assertTrue(UpdatePlan.buildStillNeeded(sameCode, onDisk, "tools-7"));
    }

    /** A first install always builds. */
    @Test
    public void freshInstallBuilds() {
        UpdatePlan.Input in = base();
        in.installed = null;
        in.library = HomeModel.Library.NONE;
        UpdatePlan p = make(in);
        assertEquals(Arrays.asList(StepKind.SET, StepKind.SHARED, StepKind.APPLY, StepKind.BUILD), kinds(p));
        assertFalse(p.steps().get(3).conditional);
        in.library = HomeModel.Library.GOOD;
        assertFalse("even a library found on the phone", make(in).steps().get(3).conditional);
    }

    /** MH XX copied (or removed) after the install makes the set again at the same version. */
    @Test
    public void xxChangesTheSet() {
        UpdatePlan.Input in = base();
        in.latestMod = "v0.2.3";
        in.xx = true;
        UpdatePlan p = make(in);
        assertEquals(StepKind.SET, p.steps().get(0).kind);
        assertTrue(p.withXx);
        in.installed = installed("0.2.3", true);
        assertEquals(UpdatePlan.Status.UP_TO_DATE, make(in).status);
        in.xx = false;
        assertEquals("MH XX going away is a new set too", StepKind.SET, make(in).steps().get(0).kind);
    }

    /** More > Reinstall and Rebuild force their steps. */
    @Test
    public void forcedSteps() {
        UpdatePlan.Input in = base();
        in.latestMod = "v0.2.3";
        in.forceMod = true;
        assertEquals(StepKind.SET, make(in).steps().get(0).kind);
        in.forceMod = false;
        in.setAsset = null;
        in.forceBuild = true;
        assertEquals(Arrays.asList(StepKind.BUILD), kinds(make(in)));
    }

    /** Every reason a plan cannot be made is named. */
    @Test
    public void refusals() {
        UpdatePlan.Input in = base();
        in.latestMod = "latest";
        assertEquals(UpdatePlan.Refusal.BAD_TAG, make(in).refusal);
        in = base();
        in.latestApp = "nope";
        assertEquals(UpdatePlan.Refusal.BAD_TAG, make(in).refusal);
        in = base();
        in.latestMod = null;
        in.installed = null;
        assertEquals(UpdatePlan.Refusal.NO_RELEASE, make(in).refusal);
        in = base();
        in.setAsset = null;
        UpdatePlan p = make(in);
        assertEquals(UpdatePlan.Status.REFUSED, p.status);
        assertEquals(UpdatePlan.Refusal.NO_SET, p.refusal);
        in = base();
        in.sharedAsset = null;
        assertEquals(UpdatePlan.Refusal.NO_SHARED, make(in).refusal);
        in = base();
        in.setNeedsNewerApp = true;
        assertEquals(UpdatePlan.Refusal.NEEDS_NEWER_APP, make(in).refusal);
        in.latestApp = "v1.1.0";
        in.appAsset = asset(APP_REPO, "v1.1.0", "Heavenfall-android.apk", 1L << 20);
        assertEquals("the newer app brings the format", UpdatePlan.Status.STEPS, make(in).status);
        for (UpdatePlan.Refusal r : UpdatePlan.Refusal.values()) {
            assertTrue(r.reasonKey().startsWith("hf_refusal_"));
            assertTrue(r.adviceKey().startsWith("hf_advice_"));
        }
    }

    /** The weights follow the tuning file: a bigger download weighs more, and no step weighs nothing. */
    @Test
    public void weights() {
        UpdatePlan p = make(base());
        UpdatePlan.Step set = p.steps().get(0);
        UpdatePlan.Step shared = p.steps().get(1);
        assertTrue(shared.weightMs > set.weightMs);
        for (UpdatePlan.Step s : p.steps()) assertTrue(s.weightMs >= 1);
        assertEquals(tuning.buildSeconds * 1000L, p.steps().get(3).weightMs);
        assertEquals(p.steps().get(0).weightMs + p.steps().get(1).weightMs + p.steps().get(2).weightMs
                + p.steps().get(3).weightMs, p.totalMs());
    }
}
