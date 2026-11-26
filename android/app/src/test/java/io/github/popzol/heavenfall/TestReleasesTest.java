package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;

import org.junit.Test;

/**
 * The debug build's test releases, reached by name as the app does (the class is in src/debug: a release build has
 * none of its bytes, and these tests are skipped there).
 */
public class TestReleasesTest {
    private static final String CLASS = "io.github.popzol.heavenfall.app.TestReleases";

    private static UrlMap make(String base) throws Exception {
        Class<?> c;
        try {
            c = Class.forName(CLASS);
        } catch (ClassNotFoundException e) {
            assumeTrue("a release build has no test releases", false);
            return null;
        }
        return (UrlMap) c.getConstructor(String.class).newInstance(base);
    }

    /** GitHub's two address spaces map to the base URL's /api/ and /github/; nothing else is touched. */
    @Test
    public void theGitHubPrefixesMapToTheBase() throws Exception {
        UrlMap map = make("http://192.168.1.50:8000");
        assertEquals("http://192.168.1.50:8000/api/repos/o/r/releases/latest",
                map.map("https://api.github.com/repos/o/r/releases/latest"));
        assertEquals("http://192.168.1.50:8000/github/o/r/releases/download/v2/a.bin",
                map.map("https://github.com/o/r/releases/download/v2/a.bin"));
        assertEquals("http://192.168.1.50:8000/github/o/r/releases/latest/download/x.json",
                map.map("https://github.com/o/r/releases/latest/download/x.json"));
        assertEquals("https://example.org/x", map.map("https://example.org/x"));
        assertEquals("https://github.com.evil.example/x", map.map("https://github.com.evil.example/x"));
    }

    /** A trailing slash or a path in the base URL does not double the slashes. */
    @Test
    public void theBaseUrlIsTidied() throws Exception {
        assertEquals("http://h:1/api/x", make("http://h:1/").map("https://api.github.com/x"));
        assertEquals("http://h:1/test/api/x", make(" http://h:1/test// \n").map("https://api.github.com/x"));
    }

    /** Only the one host and port of the base URL is the test host. */
    @Test
    public void onlyTheBaseHostIsTheTestHost() throws Exception {
        UrlMap map = make("http://192.168.1.50:8000");
        assertTrue(map.isTestHost(new URL("http://192.168.1.50:8000/api/x")));
        assertFalse(map.isTestHost(new URL("http://192.168.1.50:8001/x")));
        assertFalse(map.isTestHost(new URL("http://192.168.1.51:8000/x")));
        assertFalse(map.isTestHost(new URL("https://192.168.1.50:8000/x")));
        assertFalse(map.isTestHost(new URL("https://github.com/x")));
        assertFalse(map.isTestHost(new URL("http://user@192.168.1.50:8000/x")));
    }

    /** A base URL that is not http(s) with a host is refused when the file is read. */
    @Test
    public void aBadBaseUrlIsRefused() throws Exception {
        for (String bad : new String[] {"", "ftp://h/x", "not a url", "http://", "http://u:p@h/x", "http://h/x?y=1"}) {
            try {
                make(bad);
                fail("should have been refused: " + bad);
            } catch (InvocationTargetException e) {
                assertTrue(e.getCause() instanceof IllegalArgumentException);
            }
        }
    }

    /** With a map in place a release is read from the test server and downloaded from it, rules unchanged. */
    @Test
    public void aTestServerServesAReleaseThroughTheSameRules() throws Exception {
        try (FakeServer server = new FakeServer()) {
            UrlMap map = make(server.base());
            assertNotNull(map);
            String repo = Support.REPO;
            server.json("/api/repos/" + repo + "/releases/latest", Support.release("v1.0",
                    Support.goodAsset(repo, "v1.0", "HeavenfallBeta-android-USA.hfpatch", 77, 'd')));
            ReleaseClient.Answer a = new ReleaseClient(map).latest(repo, "HeavenfallBeta-android-release.json",
                    java.util.Collections.singletonList("HeavenfallBeta-android-USA.hfpatch"));
            assertTrue(a.ok());
            assertEquals("https://github.com/" + repo + "/releases/download/v1.0/HeavenfallBeta-android-USA.hfpatch",
                    a.release.asset("HeavenfallBeta-android-USA.hfpatch").url);   // the JSON keeps GitHub's address
        }
    }
}
