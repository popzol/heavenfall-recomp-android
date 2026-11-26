package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UpdateCheckTest {
    private FakeServer server;
    private final Editions editions = Support.editions();
    private final Editions.Edition beta = editions.first();

    @Before
    public void start() throws IOException {
        server = new FakeServer();
    }

    @After
    public void stop() {
        server.close();
    }

    private void publish(String repo, String tag, String... names) {
        String[] assets = new String[names.length];
        for (int i = 0; i < names.length; i++) assets[i] = Support.goodAsset(repo, tag, names[i], 1000 + i, 'e');
        server.json("/api/repos/" + repo + "/releases/latest", Support.release(tag, assets));
    }

    private HomeModel.Input check() {
        HomeModel.Input in = new HomeModel.Input();
        UpdateCheck.Latest latest = UpdateCheck.fetch(new ReleaseClient(server.map()), editions, beta);
        UpdateCheck.fill(latest, editions, in);
        return in;
    }

    /** Both releases found: the home screen's input gets their tags and whether the XX set is there. */
    @Test
    public void fillsTheHomeInput() {
        publish(editions.app().repository, "v1.2.0", editions.app().apk);
        publish(beta.repository, "v0.4", beta.setName("usa", false), beta.setName("usa", true), beta.sharedFile);
        HomeModel.Input in = check();
        assertEquals("v1.2.0", in.latestApp);
        assertEquals("v0.4", in.latestMod);
        assertTrue(in.releaseHasXxSet);
    }

    /** A release without the XX set says so; one without the APK is not "a newer app". */
    @Test
    public void missingFilesAreNotAnnounced() {
        publish(editions.app().repository, "v1.2.0");   // no APK
        publish(beta.repository, "v0.4", beta.setName("usa", false), beta.sharedFile);
        HomeModel.Input in = check();
        assertNull(in.latestApp);
        assertEquals("v0.4", in.latestMod);
        assertFalse(in.releaseHasXxSet);
    }

    /** Offline: nothing is known, which never takes Play away (the home model's rule). */
    @Test
    public void offlineLeavesEverythingUnknown() {
        HomeModel.Input in = check();   // the server answers 404 to everything
        assertNull(in.latestApp);
        assertNull(in.latestMod);
        assertFalse(in.releaseHasXxSet);
        in.game = HomeModel.Source.GOOD;
        in.update = HomeModel.Source.GOOD;
        in.installed = Support.installed("beta", "v0.4", "s", Support.sha('a'), false, Support.sha('b'), "t", Support.sha('b'));
        in.library = HomeModel.Library.GOOD;
        assertEquals(HomeModel.Action.PLAY, new HomeModel(Support.tuning()).compute(in).action);
    }

    private static Matcher placeholders(String text) {
        return Pattern.compile("%\\d+\\$[sd]").matcher(text);
    }

    private static int count(String text) {
        Matcher m = placeholders(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** Each failure's strings exist in both languages and take as many arguments as the failure passes. */
    @Test
    public void everyFailureHasItsTexts() {
        for (String file : new String[] {"src/main/res/values/strings_heavenfall.xml",
                "src/main/res/values-es/strings_heavenfall.xml"}) {
            String xml = Support.read(file);
            for (UpdateFailure f : UpdateFailure.values()) {
                UpdateException e = new UpdateException(f, "7");
                for (Text t : new Text[] {e.reason, e.advice}) {
                    Matcher m = Pattern.compile("<string name=\"" + t.key + "\">([^<]*)</string>").matcher(xml);
                    assertTrue(file + " lacks " + t.key, m.find());
                    assertEquals(file + " " + t.key, t.args.size(), count(m.group(1)));
                }
            }
        }
    }

    /** Where the job files go is chosen by the paths, and a request names no machine path of its own. */
    @Test
    public void downloadedNamesCannotEscapeTheFolder() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dl");
        try {
            ReleaseRules.Asset evil = new ReleaseRules.Asset("../evil.bin", 10, Support.sha('a'),
                    "https://github.com/o/r/releases/download/v1/../evil.bin");
            try {
                new Downloader(UrlMap.NONE).download(evil, dir.toFile(), f -> { }, new Control());
                org.junit.Assert.fail("a name with a path must be refused");
            } catch (UpdateException e) {
                assertEquals(UpdateFailure.UNTRUSTED_ADDRESS, e.failure);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(dir);
        }
    }
}
