package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.Support.REPO;
import static io.github.popzol.heavenfall.Support.asset;
import static io.github.popzol.heavenfall.Support.goodAsset;
import static io.github.popzol.heavenfall.Support.release;
import static io.github.popzol.heavenfall.Support.sha;
import static io.github.popzol.heavenfall.Support.url;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ReleaseRulesTest {
    private static final String SET = "HeavenfallBeta-android-USA.hfpatch";
    private static final String SET_XX = "HeavenfallBeta-android-USA-XX.hfpatch";
    private static final String SHARED = "HeavenfallBeta-3ds-shared.hfpack";
    private static final List<String> WANTED = Arrays.asList(SET, SET_XX, SHARED);

    private static ReleaseRules.Result parse(String json) {
        return ReleaseRules.parse(json, REPO, WANTED);
    }

    private static ReleaseRules.Refusal refusal(String json) {
        ReleaseRules.Result r = parse(json);
        assertFalse("must be refused: " + json, r.ok());
        return r.refusal;
    }

    private static String setWith(String size, String digest, String url) {
        return asset(SET, size, digest, url);
    }

    @Test
    public void goodRelease() {
        ReleaseRules.Result r = parse(release("v0.2.3", goodAsset(REPO, "v0.2.3", SET, 1000, 'a'),
                goodAsset(REPO, "v0.2.3", SHARED, 5000, 'b')));
        assertTrue(r.ok());
        assertEquals("v0.2.3", r.release.tag);
        assertEquals("0.2.3", r.release.version.toString());
        assertEquals(1000, r.release.asset(SET).size);
        assertEquals(sha('a'), r.release.asset(SET).sha256);
        assertTrue(r.release.has(SHARED));
        assertFalse("not in the release, not invented", r.release.has(SET_XX));
    }

    /** A tag without the "v" is still a release; one that is no version is refused by name. */
    @Test
    public void tagWithoutV() {
        assertTrue(parse(release("0.2.3", goodAsset(REPO, "0.2.3", SET, 1, 'a'))).ok());
        assertEquals(ReleaseRules.Refusal.BAD_TAG, refusal(release("latest")));
        assertEquals(ReleaseRules.Refusal.BAD_TAG, refusal(release("v1.2-beta")));
    }

    /** An answer that is not a release at all (an HTML error page, an array, a number for the tag). */
    @Test
    public void notARelease() {
        assertEquals(ReleaseRules.Refusal.BAD_ANSWER, refusal("<html>rate limited</html>"));
        assertEquals(ReleaseRules.Refusal.BAD_ANSWER, refusal("[]"));
        assertEquals(ReleaseRules.Refusal.BAD_ANSWER, refusal("{\"assets\": []}"));
        assertEquals(ReleaseRules.Refusal.BAD_ANSWER, refusal("{\"tag_name\": 5, \"assets\": []}"));
        assertEquals(ReleaseRules.Refusal.BAD_ANSWER, refusal("{\"tag_name\": \"v1\"}"));
        assertEquals(ReleaseRules.Refusal.BAD_ANSWER, refusal(null));
    }

    /** Without a digest the download cannot be checked: absent, null (old assets), another algorithm, short or non-hex. */
    @Test
    public void digestMissing() {
        String url = url(REPO, "v1", SET);
        assertEquals(ReleaseRules.Refusal.NO_DIGEST, refusal(release("v1", setWith("10", null, url))));
        assertEquals(ReleaseRules.Refusal.NO_DIGEST, refusal(release("v1", setWith("10", "null", url))));
        assertEquals(ReleaseRules.Refusal.NO_DIGEST, refusal(release("v1", setWith("10", "\"sha1:" + sha('a') + "\"", url))));
        assertEquals(ReleaseRules.Refusal.NO_DIGEST,
                refusal(release("v1", setWith("10", "\"sha256:" + sha('a').substring(1) + "\"", url))));
        assertEquals(ReleaseRules.Refusal.NO_DIGEST,
                refusal(release("v1", setWith("10", "\"sha256:" + sha('a').substring(1) + "g\"", url))));
        assertEquals(ReleaseRules.Refusal.NO_DIGEST, refusal(release("v1", setWith("10", "12", url))));
    }

    /** A digest in capitals is the same digest: the app compares lower case. */
    @Test
    public void digestCaseIsNormalised() {
        String url = url(REPO, "v1", SET);
        ReleaseRules.Result r = parse(release("v1", setWith("10", "\"sha256:" + sha('A') + "\"", url)));
        assertTrue(r.ok());
        assertEquals(sha('a'), r.release.asset(SET).sha256);
    }

    /** No size, a zero size, a size as text or with a fraction cannot bound a download. */
    @Test
    public void sizeRules() {
        String url = url(REPO, "v1", SET);
        String digest = "\"sha256:" + sha('a') + "\"";
        for (String size : new String[] {null, "0", "-5", "\"10\"", "1.5", "null"}) {
            assertEquals("size " + size, ReleaseRules.Refusal.NO_SIZE, refusal(release("v1", setWith(size, digest, url))));
        }
    }

    /** The URL must be this repository's own release download on github.com over https, ending in the asset's name. */
    @Test
    public void urlOnAnotherHost() {
        String digest = "\"sha256:" + sha('a') + "\"";
        String[] bad = {
            "https://evil.example/popzol/4u-heavenfall-mod-beta/releases/download/v1/" + SET,
            "http://github.com/" + REPO + "/releases/download/v1/" + SET,
            "https://github.com.evil.example/" + REPO + "/releases/download/v1/" + SET,
            "https://github.com@evil.example/" + REPO + "/releases/download/v1/" + SET,
            "https://github.com/other/repo/releases/download/v1/" + SET,
            "https://github.com/" + REPO + "2/releases/download/v1/" + SET,
            "https://github.com/" + REPO + "/releases/latest/" + SET,
            "https://github.com/" + REPO + "/releases/download/v1/../../x/" + SET,
            "https://github.com/" + REPO + "/releases/download/v1/%2e%2e/" + SET,
            "https://github.com/" + REPO + "/releases/download/v1/other.hfpatch",
            "https://github.com/" + REPO + "/releases/download/v1/" + SET + "?x=1",
            "https://github.com/" + REPO + "/releases/download/v1/ " + SET,
            "https://objects.githubusercontent.com/" + SET,
            "",
        };
        for (String u : bad) {
            assertEquals("url " + u, ReleaseRules.Refusal.BAD_URL, refusal(release("v1", setWith("10", digest, u))));
        }
        assertEquals(ReleaseRules.Refusal.BAD_URL, refusal(release("v1", setWith("10", digest, null))));
    }

    /** The repository of the check is the one the release is asked from: another repository's files are not taken. */
    @Test
    public void urlOfAnotherRepository() {
        String json = release("v1", goodAsset("popzol/4u-heavenfall-mod", "v1", SET, 10, 'a'));
        assertEquals(ReleaseRules.Refusal.BAD_URL, refusal(json));
        assertTrue(ReleaseRules.parse(json, "popzol/4u-heavenfall-mod", WANTED).ok());
    }

    /** A file with a near name is not the wanted one: it is left alone, so the wanted one is simply missing. */
    @Test
    public void nearNamesAreNotTaken() {
        String[] near = {"heavenfallbeta-android-USA.hfpatch", SET + ".sha256", "x" + SET, "HeavenfallBeta-android-EUR.hfpatch",
                "HeavenfallBeta-android-USA.hfpatch ", "HeavenfallBeta-android-USA-xx.hfpatch"};
        for (String name : near) {
            ReleaseRules.Result r = parse(release("v1", goodAsset(REPO, "v1", name, 10, 'a')));
            assertTrue("a near name is no error: " + name, r.ok());
            assertNull(r.release.asset(SET));
            assertFalse(r.release.has(name));
            ReleaseRules.Result need = r.release.require(SET);
            assertEquals(ReleaseRules.Refusal.MISSING, need.refusal);
            assertEquals(SET, need.detail);
        }
    }

    /** Assets the app does not want are not judged: a broken extra file must not stop an update. */
    @Test
    public void unwantedAssetsAreIgnored() {
        ReleaseRules.Result r = parse(release("v1", asset("notes.txt", null, null, null),
                asset("Heavenfall-android.apk", "0", "null", "http://x"), goodAsset(REPO, "v1", SET, 10, 'a')));
        assertTrue(r.ok());
        assertTrue(r.release.has(SET));
    }

    /** Two assets with the wanted name leave it open which one is meant: refuse. */
    @Test
    public void duplicateAsset() {
        String a = goodAsset(REPO, "v1", SET, 10, 'a');
        String b = goodAsset(REPO, "v1", SET, 10, 'b');
        ReleaseRules.Result r = parse(release("v1", a, b));
        assertEquals(ReleaseRules.Refusal.DUPLICATE, r.refusal);
        assertEquals(SET, r.detail);
    }

    /** The refusal names the broken asset, so the log can say which file. */
    @Test
    public void refusalNamesTheAsset() {
        ReleaseRules.Result r = parse(release("v1", goodAsset(REPO, "v1", SET, 10, 'a'),
                asset(SHARED, "10", null, url(REPO, "v1", SHARED))));
        assertEquals(ReleaseRules.Refusal.NO_DIGEST, r.refusal);
        assertEquals(SHARED, r.detail);
        assertNull("no partial release", r.release);
    }

    /** The release file of the fallback has the same shape and the same rules as the API's answer. */
    @Test
    public void fallbackFileSameShape() {
        String fallback = "{\"tag_name\": \"0.3\", \"body\": \"\", \"assets\": [" + goodAsset(REPO, "0.3", SET, 77, 'c') + "]}";
        ReleaseRules.Result r = parse(fallback);
        assertTrue(r.ok());
        assertEquals(77, r.release.asset(SET).size);
        assertNotNull(r.release.version);
    }

    /** Every refusal has its texts: a screen never gets a key without a string. */
    @Test
    public void refusalsHaveKeys() {
        for (ReleaseRules.Refusal r : ReleaseRules.Refusal.values()) {
            assertTrue(r.reasonKey().startsWith("hf_refusal_"));
            assertTrue(r.adviceKey().startsWith("hf_advice_"));
        }
    }
}
