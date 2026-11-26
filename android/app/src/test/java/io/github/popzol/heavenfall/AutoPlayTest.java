package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Coming back to the app asked to start the game: only when the home screen would say Play. */
public class AutoPlayTest {
    private static HomeModel.Output out(boolean good) {
        HomeModel.Input in = new HomeModel.Input();
        in.storage = HomeModel.Storage.READY;
        in.game = HomeModel.Source.GOOD;
        in.update = HomeModel.Source.GOOD;
        in.installed = new Installed("beta", "0.2.3", "s", Support.sha('a'), false, Support.sha('b'), "t", Support.sha('b'));
        in.library = good ? HomeModel.Library.GOOD : HomeModel.Library.NONE;
        in.appVersion = "1.0.0";
        in.latestApp = "v1.0.0";
        in.latestMod = "v0.2.3";
        return new HomeModel(Support.tuning()).compute(in);
    }

    @Test
    public void startsOnlyWhenAskedAndPlayIsOffered() {
        // Catches a relaunch that lands on Home and needs one more tap, or one that starts the game without being able.
        assertTrue(AutoPlay.start(true, out(true)));
        assertFalse(AutoPlay.start(true, out(false)));
        assertFalse(AutoPlay.start(false, out(true)));
        assertFalse(AutoPlay.start(true, null));
    }
}
