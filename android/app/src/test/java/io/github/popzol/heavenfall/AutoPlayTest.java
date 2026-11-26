package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Coming back to the app asked to start the game: only when the home screen would say Play. */
public class AutoPlayTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static HomeModel.Output out(boolean good) {
        HomeModel.Input in = new HomeModel.Input();
        in.storage = HomeModel.Storage.READY;
        in.game = HomeModel.Source.GOOD;
        in.update = HomeModel.Source.GOOD;
        in.installed = Support.installed("beta", "0.2.3", "s", Support.sha('a'), false, Support.sha('b'), "t", Support.sha('b'));
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

    /**
     * F6: catches a game started by any app that sends Home the auto-play extra: only the token the app wrote into
     * its private storage just before relaunching starts it, once, within a minute.
     */
    @Test
    public void onlyTheAppsOwnFreshTokenStartsTheGame() throws IOException {
        File dir = tmp.newFolder("private");
        String token = AutoPlay.issue(dir, 1_000);
        assertTrue(AutoPlay.consume(dir, token, 2_000));
        assertFalse("a token is used once", AutoPlay.consume(dir, token, 2_000));

        token = AutoPlay.issue(dir, 1_000);
        assertFalse(AutoPlay.consume(dir, token + "0", 2_000));
        assertFalse("a wrong guess spends the token", AutoPlay.consume(dir, token, 2_000));
        token = AutoPlay.issue(dir, 1_000);
        assertFalse("too old", AutoPlay.consume(dir, token, 1_000 + AutoPlay.MAX_AGE_MS + 1));
        token = AutoPlay.issue(dir, 5_000);
        assertFalse("from the future", AutoPlay.consume(dir, token, 1_000));
        assertFalse("no token file", AutoPlay.consume(dir, token, 5_000));
        AutoPlay.issue(dir, 1_000);
        assertFalse("no token in the intent", AutoPlay.consume(dir, null, 1_000));
        assertFalse(new File(dir, AutoPlay.TOKEN_FILE).exists());
        Files.write(new File(dir, AutoPlay.TOKEN_FILE).toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
        assertFalse("an unparsable token file is no start", AutoPlay.consume(dir, "garbage", 1_000));
        assertNotEquals(AutoPlay.issue(dir, 1), AutoPlay.issue(dir, 1));
    }
}
