package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.Support.REPO;
import static io.github.popzol.heavenfall.Support.goodAsset;
import static io.github.popzol.heavenfall.Support.release;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReleaseClientTest {
    private static final String NAME = "HeavenfallBeta-android-USA.hfpatch";
    private static final String API = "/api/repos/" + REPO + "/releases/latest";
    private static final String FILE = "/github/" + REPO + "/releases/latest/download/HeavenfallBeta-android-release.json";
    private static final List<String> WANTED = Arrays.asList(NAME);

    private FakeServer server;

    @Before
    public void start() throws IOException {
        server = new FakeServer();
    }

    @After
    public void stop() {
        server.close();
    }

    private ReleaseClient client() {
        return new ReleaseClient(server.map());
    }

    private static String good(String tag) {
        return release(tag, goodAsset(REPO, tag, NAME, 1234, 'a'));
    }

    private ReleaseClient.Answer ask() {
        return client().latest(REPO, "HeavenfallBeta-android-release.json", WANTED);
    }

    /** The API's answer is used when it is a release, and the release file is not asked. */
    @Test
    public void theApiAnswersFirst() {
        server.json(API, good("v1.2"));
        ReleaseClient.Answer a = ask();
        assertTrue(a.ok());
        assertEquals("v1.2", a.release.tag);
        assertFalse(a.viaReleaseFile);
        assertEquals(0, server.count(FILE));
    }

    /** A refused API (a rate limit is a 403) falls back to the release file attached to the latest release. */
    @Test
    public void aRefusedApiFallsBackToTheReleaseFile() {
        server.status(API, 403);
        server.json(FILE, good("v1.3"));
        ReleaseClient.Answer a = ask();
        assertTrue(a.ok());
        assertEquals("v1.3", a.release.tag);
        assertTrue(a.viaReleaseFile);
    }

    /** The release file goes through a redirect to the release's storage, as GitHub does. */
    @Test
    public void theReleaseFileMayRedirect() {
        server.status(API, 429);
        server.redirect(FILE, server.base() + "/github/storage/release.json");
        server.json("/github/storage/release.json", good("v1.4"));
        ReleaseClient.Answer a = ask();
        assertTrue(a.ok());
        assertEquals("v1.4", a.release.tag);
    }

    /** An API answer the rules refuse (no checksum) is not trusted: the release file is asked. */
    @Test
    public void anAnswerTheRulesRefuseFallsBack() {
        server.json(API, release("v1.5", Support.asset(NAME, "1234", null, Support.url(REPO, "v1.5", NAME))));
        server.json(FILE, good("v1.5"));
        ReleaseClient.Answer a = ask();
        assertTrue(a.ok());
        assertTrue(a.viaReleaseFile);
    }

    /** Both refused by the rules: the failure says which rule, with the refusal's own texts. */
    @Test
    public void bothRefusedByTheRulesNamesTheRule() {
        String bad = release("v1.6", Support.asset(NAME, "1234", null, Support.url(REPO, "v1.6", NAME)));
        server.json(API, bad);
        server.json(FILE, bad);
        ReleaseClient.Answer a = ask();
        assertFalse(a.ok());
        assertNotNull(a.failure);
        assertEquals("hf_refusal_no_digest", a.failure.reason.key);
    }

    /** A release whose asset URL is another repository's is refused even from the release file. */
    @Test
    public void anAssetUrlOfAnotherRepositoryIsRefused() {
        String evil = release("v1.7", Support.asset(NAME, "1234", "\"sha256:" + Support.sha('a') + "\"",
                Support.url("evil/repo", "v1.7", NAME)));
        server.json(API, evil);
        server.json(FILE, evil);
        ReleaseClient.Answer a = ask();
        assertFalse(a.ok());
        assertEquals("hf_refusal_bad_url", a.failure.reason.key);
    }

    /** Neither source answers: a failure, not a crash, and nothing of a release. */
    @Test
    public void nothingAnswersIsAFailure() {
        ReleaseClient.Answer a = ask();   // both 404 on the fake server
        assertFalse(a.ok());
        assertNull(a.release);
        assertEquals(UpdateFailure.SERVER, a.failure.failure);
    }

    @Test
    public void aDeadServerIsANetworkFailure() {
        UrlMap map = server.map();
        server.close();
        ReleaseClient.Answer a = new ReleaseClient(map).latest(REPO, "HeavenfallBeta-android-release.json", WANTED);
        assertFalse(a.ok());
        assertEquals(UpdateFailure.NETWORK, a.failure.failure);
    }

    /** A huge answer is cut off instead of read into memory. */
    @Test
    public void anAnswerOverTheLimitIsRefused() {
        StringBuilder big = new StringBuilder("{\"tag_name\": \"v1\", \"assets\": [], \"body\": \"");
        for (int i = 0; i < ReleaseClient.MAX_BODY_BYTES / 10 + 10; i++) big.append("0123456789");
        big.append("\"}");
        server.json(API, big.toString());
        server.json(FILE, big.toString());
        ReleaseClient.Answer a = ask();
        assertFalse(a.ok());
        assertEquals("hf_refusal_bad_answer", a.failure.reason.key);
    }

    /** The app's own release is asked for its APK, an edition's for its sets and shared file. */
    @Test
    public void appAndEditionAskForTheirOwnFiles() {
        Editions editions = Support.editions();
        Editions.App app = editions.app();
        server.json("/api/repos/" + app.repository + "/releases/latest",
                release("v2.0", goodAsset(app.repository, "v2.0", app.apk, 9_000_000, 'b')));
        ReleaseClient.Answer a = client().latestApp(app);
        assertTrue(a.ok());
        assertTrue(a.release.has(app.apk));
        Editions.Edition beta = editions.first();
        server.json("/api/repos/" + beta.repository + "/releases/latest",
                release("v3.0", goodAsset(beta.repository, "v3.0", beta.sharedFile, 5000, 'c')));
        ReleaseClient.Answer m = client().latestEdition(beta);
        assertTrue(m.ok());
        assertTrue(m.release.has(beta.sharedFile));
    }

    /** The requests carry the generic client name and nothing about the player or the phone. */
    @Test
    public void requestsAreGeneric() {
        String[] seen = new String[2];
        server.route(API, ex -> {
            seen[0] = ex.getRequestHeaders().getFirst("User-Agent");
            seen[1] = String.valueOf(ex.getRequestHeaders().keySet()).toLowerCase();
            byte[] b = good("v1.8").getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        });
        assertTrue(ask().ok());
        assertEquals("Heavenfall-Android", seen[0]);
        assertFalse(seen[1].contains("authorization") || seen[1].contains("cookie"));
    }
}
