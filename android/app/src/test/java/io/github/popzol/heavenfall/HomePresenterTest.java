package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.HomeModel.Action;
import static io.github.popzol.heavenfall.HomeModel.Job;
import static io.github.popzol.heavenfall.HomeModel.Library;
import static io.github.popzol.heavenfall.HomeModel.Source;
import static io.github.popzol.heavenfall.HomeModel.Storage;
import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.popzol.heavenfall.HomePresenter.Command;

public class HomePresenterTest {
    private static final long GB = 1L << 30;
    private final HomeModel model = new HomeModel(Support.tuning());

    private static Installed installed(String version, boolean xx) {
        return Support.installed("beta", version, "s.hfpatch", sha('a'), xx, sha('b'), "tools-7", sha('b'));
    }

    /** Everything good: 0.2.3 installed, the library good, the latest known and the same, plenty of room. */
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
        in.availableMemoryMb = 6000;
        in.batteryPercent = 80;
        return in;
    }

    private HomePresenter.Screen screen(HomeModel.Input in) {
        return screen(in, JobProgress.IDLE, true, false);
    }

    private HomePresenter.Screen screen(HomeModel.Input in, JobProgress progress, boolean canInstall, boolean cancelling) {
        return HomePresenter.present(model.compute(in), in, progress, canInstall, cancelling);
    }

    private static JobProgress running(int permille, long msLeft, int step, int count) {
        return new JobProgress(JobProgress.Phase.RUNNING, permille, new Text("hf_step_set"), msLeft,
                UpdatePlan.StepKind.SET, step, count, null, null);
    }

    /** Set up opens the assistant and shows what is wrong first, with its advice; the setup is never "blocked". */
    @Test
    public void setUpOpensTheAssistantAndSaysWhatToDo() {
        HomeModel.Input in = good();
        in.storage = Storage.NO_FOLDER;
        HomePresenter.Screen s = screen(in);
        assertEquals("hf_btn_set_up", s.main.label.key);
        assertTrue(s.main.enabled);
        assertEquals(Command.OPEN_ASSISTANT, s.main.press());
        assertEquals(1, s.blocks.size());
        assertEquals("hf_advice_folder", s.blocks.get(0).advice.key);
        assertEquals(s.state, s.blocks.get(0).reason);
        assertNull(s.play);
    }

    /** Each action's big button starts what its label says, and Play starts the game. */
    @Test
    public void everyActionHasItsCommand() {
        HomeModel.Input install = good();
        install.installed = null;
        assertEquals(Command.START_JOB, screen(install).main.press());
        assertEquals("hf_btn_install", screen(install).main.label.key);

        HomeModel.Input update = good();
        update.latestMod = "v0.3.0";
        HomePresenter.Screen u = screen(update);
        assertEquals("hf_btn_update_to", u.main.label.key);
        assertEquals("0.3", u.main.label.args.get(0));
        assertEquals(Command.START_JOB, u.main.press());

        HomeModel.Input build = good();
        build.library = Library.NONE;
        assertEquals("hf_btn_build", screen(build).main.label.key);
        assertEquals(Command.START_JOB, screen(build).main.press());

        HomeModel.Input resume = good();
        resume.job = Job.HALF_DONE;
        assertEquals("hf_btn_resume", screen(resume).main.label.key);
        assertEquals(Command.RESUME_JOB, screen(resume).main.press());

        HomePresenter.Screen play = screen(good());
        assertEquals("hf_btn_play", play.main.label.key);
        assertEquals(Command.PLAY, play.main.press());
    }

    /** A blocked button shows the reason with its advice and takes no tap. */
    @Test
    public void aBlockedButtonShowsItsAdviceAndTakesNoTap() {
        HomeModel.Input in = good();
        in.installed = null;
        in.latestMod = null;       // offline
        in.batteryPercent = 3;
        HomePresenter.Screen s = screen(in);
        assertEquals("hf_btn_install", s.main.label.key);
        assertFalse(s.main.enabled);
        assertEquals(Command.NONE, s.main.press());
        assertFalse(s.blocks.isEmpty());
        for (HomeModel.Block b : s.blocks) {
            assertNotNull(b.reason);
            assertNotNull(b.advice);
        }
        assertEquals("hf_block_no_network", s.blocks.get(0).reason.key);
        assertEquals("hf_advice_connect", s.blocks.get(0).advice.key);
    }

    /** An unblocked button shows no block. */
    @Test
    public void anEnabledButtonShowsNoBlock() {
        assertTrue(screen(good()).blocks.isEmpty());
    }

    /** The Play entry sits beside Update (the installed game can start) and nowhere else. */
    @Test
    public void playBesideUpdateOnly() {
        HomeModel.Input update = good();
        update.latestMod = "v0.3.0";
        HomePresenter.Screen u = screen(update);
        assertNotNull(u.play);
        assertEquals("hf_btn_play", u.play.label.key);
        assertEquals(Command.PLAY, u.play.press());

        assertNull("Play is the big button then", screen(good()).play);
        HomeModel.Input install = good();
        install.installed = null;
        assertNull(screen(install).play);
        HomeModel.Input build = good();
        build.library = Library.OTHER_MOD;
        assertNull(screen(build).play);

        HomeModel.Input badLibrary = good();
        badLibrary.latestMod = "v0.3.0";
        badLibrary.library = Library.OTHER_MOD;
        assertNull("the installed game cannot start without its library", screen(badLibrary).play);
    }

    /** A running job hides the big button and shows the bar with the step, the time left and Cancel; the screen stays on. */
    @Test
    public void aRunningJobShowsTheBarNotTheButton() {
        HomeModel.Input in = good();
        in.job = Job.RUNNING;
        HomePresenter.Screen s = screen(in, running(420, 14 * 60_000L, 2, 4), true, false);
        assertNull(s.main);
        assertNull(s.play);
        assertNotNull(s.bar);
        assertEquals(420, s.bar.permille);
        assertEquals("hf_step_set", s.bar.step.key);
        assertEquals("hf_bar_step", s.bar.counter.key);
        assertEquals("2", s.bar.counter.args.get(0));
        assertEquals("4", s.bar.counter.args.get(1));
        assertEquals("hf_time_left_minutes", s.bar.timeLeft.key);
        assertEquals("14", s.bar.timeLeft.args.get(0));
        assertTrue(s.keepScreenOn);
        assertEquals("hf_btn_cancel", s.cancel.label.key);
        assertEquals(Command.CANCEL_JOB, s.cancel.press());
        assertNotNull("More stays", s.more);
    }

    /** Without a running job the bar and Cancel are gone and the screen may turn off. */
    @Test
    public void noJobNoBar() {
        HomePresenter.Screen s = screen(good());
        assertNull(s.bar);
        assertNull(s.cancel);
        assertFalse(s.keepScreenOn);
        assertNull(s.hint);
    }

    /** After Cancel is tapped the button says it is stopping and takes no second tap. */
    @Test
    public void cancelTakesOneTap() {
        HomeModel.Input in = good();
        in.job = Job.RUNNING;
        HomePresenter.Screen s = screen(in, running(100, 60_000, 1, 3), true, true);
        assertEquals("hf_btn_stopping", s.cancel.label.key);
        assertFalse(s.cancel.enabled);
        assertEquals(Command.NONE, s.cancel.press());
    }

    /** A job just asked for has no plan yet: no step counter and no time left, and the bar still shows. */
    @Test
    public void aJobWithoutAPlanShowsNoCounter() {
        HomeModel.Input in = good();
        in.job = Job.RUNNING;
        HomePresenter.Screen s = screen(in, JobProgress.starting(0), true, false);
        assertNotNull(s.bar);
        assertNull(s.bar.counter);
        assertNull(s.bar.timeLeft);
    }

    /**
     * F3: a failure before anything was touched (a download) is not a half-done job: the button stays the one that
     * starts the job (here Install), with the failure's reason and advice above it.
     */
    @Test
    public void aFailureShowsItsReasonAboveTheUsualButton() {
        UpdateException failure = new UpdateException(UpdateFailure.NETWORK, "x");
        JobProgress failed = JobProgress.failed(failure, 300);
        HomeModel.Input in = good();
        in.installed = null;
        in.job = PhoneFacts.effectiveJob(Job.NONE, Job.NONE);
        HomePresenter.Screen s = screen(in, failed, true, false);
        assertEquals(Job.NONE, in.job);
        assertEquals("hf_btn_install", s.main.label.key);
        assertEquals(Command.START_JOB, s.main.press());
        assertNotNull(s.failure);
        assertEquals(failure.reason, s.failure.reason);
        assertEquals(failure.advice, s.failure.advice);
        assertNull(s.bar);
    }

    /** The job as Home sees it: a running one, else what the last one left on disk; a stopped job invents none. */
    @Test
    public void theJobIsRunningOrWhatIsLeftOnDisk() {
        assertEquals(Job.HALF_DONE, PhoneFacts.effectiveJob(Job.NONE, Job.HALF_DONE));
        assertEquals(Job.NONE, PhoneFacts.effectiveJob(Job.NONE, Job.NONE));
        assertEquals(Job.NONE, PhoneFacts.effectiveJob(Job.NONE, null));
        assertEquals(Job.RUNNING, PhoneFacts.effectiveJob(Job.RUNNING, Job.HALF_DONE));
    }

    /**
     * F3: a half-done job never takes Play away from an installed game whose library is good, and offers Discard
     * beside Resume (Resume itself is never blocked: each step checks what it needs when it starts).
     */
    @Test
    public void halfDoneKeepsPlayAndOffersDiscard() {
        HomeModel.Input in = good();
        in.job = Job.HALF_DONE;
        in.batteryPercent = 3;
        in.charging = false;
        HomePresenter.Screen s = screen(in, JobProgress.IDLE, true, false);
        assertEquals("hf_btn_resume", s.main.label.key);
        assertTrue(s.main.enabled);
        assertNotNull("Play stays beside Resume", s.play);
        assertEquals(Command.PLAY, s.play.press());
        assertNotNull(s.discard);
        assertEquals(Command.DISCARD_JOB, s.discard.press());
        in.library = HomeModel.Library.OTHER_MOD;
        assertNull("no Play when the library is not good", screen(in, JobProgress.IDLE, true, false).play);
    }

    /** A job that may replace the app says so first (the app closes, the update goes on in the notifications). */
    @Test
    public void anUpdateThatReplacesTheAppIsAnnounced() {
        HomeModel.Input in = good();
        in.latestApp = "v9.0.0";
        HomePresenter.Screen s = screen(in, JobProgress.IDLE, true, false);
        assertEquals(Command.START_JOB, s.main.press());
        assertTrue(s.replacesApp);
        in.latestApp = in.appVersion;
        assertFalse(screen(in, JobProgress.IDLE, true, false).replacesApp);
    }

    /** No failure card when the job did not fail (the model's own state line says what is going on). */
    @Test
    public void noFailureCardWithoutAFailure() {
        HomeModel.Input in = good();
        in.job = Job.HALF_DONE;
        assertNull(screen(in, JobProgress.IDLE, true, false).failure);
    }

    /** The app's own update needs Android's permission to install: asked first, with the button's tap, only when it is due. */
    @Test
    public void installPermissionAskedOnlyForAnUpdateThatReplacesTheApp() {
        HomeModel.Input in = good();
        in.latestApp = "v1.1.0";
        HomePresenter.Screen update = screen(in, JobProgress.IDLE, false, false);
        assertEquals(Action.UPDATE, model.compute(in).action);
        assertEquals(Command.ASK_ALLOW_INSTALLS, update.main.press());
        assertEquals(Command.START_JOB, screen(in, JobProgress.IDLE, true, false).main.press());

        HomeModel.Input modOnly = good();
        modOnly.latestMod = "v0.3.0";
        assertEquals("the app is not replaced", Command.START_JOB,
                screen(modOnly, JobProgress.IDLE, false, false).main.press());

        HomeModel.Input build = good();
        build.latestApp = "v1.1.0";
        build.library = Library.NONE;
        build.latestMod = "v0.2.3";
        // the model offers the update first: Build is not what is asked here
        assertEquals(Action.UPDATE, model.compute(build).action);

        HomeModel.Input resume = good();
        resume.latestApp = "v1.1.0";
        resume.job = Job.HALF_DONE;
        assertEquals(Command.ASK_ALLOW_INSTALLS, screen(resume, JobProgress.IDLE, false, false).main.press());
        assertTrue(HomePresenter.asksToAllowInstalls(resume, false));
        assertFalse(HomePresenter.asksToAllowInstalls(resume, true));
    }

    /** A blocked Update takes no tap even when the permission is missing. */
    @Test
    public void blockedBeatsAskingPermission() {
        HomeModel.Input in = good();
        in.latestApp = "v1.1.0";
        in.freeBytes = 1;
        HomePresenter.Screen s = screen(in, JobProgress.IDLE, false, false);
        assertFalse(s.main.enabled);
        assertEquals(Command.NONE, s.main.press());
    }

    /** More is always a button and always opens the list. */
    @Test
    public void moreAlwaysOpensTheList() {
        HomeModel.Input running = good();
        running.job = Job.RUNNING;
        assertEquals(Command.OPEN_MORE, screen(running, JobProgress.IDLE, true, false).more.press());
        assertEquals(Command.OPEN_MORE, screen(good()).more.press());
    }

    /** The versions line shows the app and the installed mod, "-" for none. */
    @Test
    public void versionsLine() {
        Text t = screen(good()).versions;
        assertEquals("hf_home_versions", t.key);
        assertEquals("1.0.0", t.args.get(0));
        assertEquals("0.2.3", t.args.get(1));
        HomeModel.Input none = good();
        none.installed = null;
        assertEquals("-", screen(none).versions.args.get(1));
    }
}
