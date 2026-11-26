package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.popzol.heavenfall.HomeModel.Job;
import io.github.popzol.heavenfall.HomeModel.Library;
import io.github.popzol.heavenfall.HomeModel.Source;
import io.github.popzol.heavenfall.HomeModel.Storage;

/**
 * The code library's build, the mod's maker and the game's start all read the game's dump and its update's: no screen
 * may offer one of them, in any state, without both being right (the job would only end in a toast).
 */
public class DumpsAlwaysRequiredTest {
    private static final Installed INSTALLED = Support.installed("beta", "0.2.3", "s", Support.sha('a'), false,
            Support.sha('b'), "t", Support.sha('b'));

    /** Whether the dumps are right and no screen at all may start anything that reads them. */
    private static void check(HomeModel.Input in, HomeModel.Output out, String where) {
        boolean dumps = in.storage == Storage.READY && in.game == Source.GOOD && in.update == Source.GOOD;
        HomePresenter.Screen home = HomePresenter.present(out, in, JobProgress.IDLE, true, false);
        if (!dumps) {
            if (home.main != null) {
                HomePresenter.Command c = home.main.press();
                assertTrue(where + ": " + c, c == HomePresenter.Command.NONE || c == HomePresenter.Command.OPEN_ASSISTANT);
            }
            assertTrue(where + ": Play without the dumps", home.play == null);
            assertFalse(where, out.canPlay);
            for (MorePresenter.Row r : MorePresenter.rows(out)) {
                MorePresenter.Command c = r.press();
                assertFalse(where + ": " + c, c == MorePresenter.Command.CONFIRM_REBUILD
                        || c == MorePresenter.Command.CONFIRM_REINSTALL);
            }
        }
    }

    /** Every state of the phone: a missing or wrong update dump never leaves Build, Reinstall, Install or Play offered. */
    @Test
    public void noScreenStartsWhatReadsADumpThatIsNotThere() {
        HomeModel model = new HomeModel(Support.tuning());
        int states = 0;
        for (Storage storage : Storage.values())
            for (Source game : Source.values())
                for (Source update : Source.values())
                    for (Installed installed : new Installed[] {null, INSTALLED})
                        for (Library library : Library.values())
                            for (Job job : Job.values())
                                for (String latestMod : new String[] {null, "v0.2.3", "v0.3"}) {
                                    HomeModel.Input in = new HomeModel.Input();
                                    in.storage = storage;
                                    in.game = game;
                                    in.update = update;
                                    in.installed = installed;
                                    in.library = library;
                                    in.job = job;
                                    in.appVersion = "1.0.0";
                                    in.latestApp = "v1.0.0";
                                    in.latestMod = latestMod;
                                    check(in, model.compute(in), storage + " " + game + " " + update + " " + installed
                                            + " " + library + " " + job + " " + latestMod);
                                    states++;
                                }
        assertTrue("every combination was looked at: " + states, states > 1000);
    }
}
