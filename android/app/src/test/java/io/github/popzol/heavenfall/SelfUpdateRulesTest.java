package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class SelfUpdateRulesTest {
    private static final String PKG = "io.github.popzol.heavenfall";

    private static Set<String> set(String... values) {
        return new HashSet<>(java.util.Arrays.asList(values));
    }

    private static UpdateFailure check(String apkPkg, long apkCode, Set<String> apkSigners, Set<String> own) {
        return SelfUpdateRules.check(PKG, 10000, own, apkPkg, apkCode, apkSigners);
    }

    /** Only this app, newer, signed by the very same certificates may replace it. */
    @Test
    public void theGoodApkPasses() {
        assertNull(check(PKG, 10001, set("a"), set("a")));
        assertNull(check(PKG, 10001, set("a", "b"), set("b", "a")));
    }

    @Test
    public void anotherAppIsRefused() {
        assertEquals(UpdateFailure.SELF_OTHER_APP, check(PKG + ".test", 10001, set("a"), set("a")));
        assertEquals(UpdateFailure.SELF_UNREADABLE, check(null, 10001, set("a"), set("a")));
    }

    /** The same version or an older one is refused (a downgrade is never an update). */
    @Test
    public void notNewerIsRefused() {
        assertEquals(UpdateFailure.SELF_NOT_NEWER, check(PKG, 10000, set("a"), set("a")));
        assertEquals(UpdateFailure.SELF_NOT_NEWER, check(PKG, 9999, set("a"), set("a")));
    }

    /** Another certificate, an extra one, a missing one, or none at all: refused before Android is asked. */
    @Test
    public void otherSignersAreRefused() {
        assertEquals(UpdateFailure.SELF_OTHER_SIGNER, check(PKG, 10001, set("x"), set("a")));
        assertEquals(UpdateFailure.SELF_OTHER_SIGNER, check(PKG, 10001, set("a", "x"), set("a")));
        assertEquals(UpdateFailure.SELF_OTHER_SIGNER, check(PKG, 10001, set("a"), set("a", "b")));
        assertEquals(UpdateFailure.SELF_OTHER_SIGNER, check(PKG, 10001, Collections.<String>emptySet(), set("a")));
        assertEquals(UpdateFailure.SELF_OTHER_SIGNER, check(PKG, 10001, null, set("a")));
        assertEquals(UpdateFailure.SELF_OTHER_SIGNER, check(PKG, 10001, Collections.<String>emptySet(),
                Collections.<String>emptySet()));
    }

    @Test
    public void certificateHashIsSha256Hex() {
        // SHA-256 of the empty input
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SelfUpdateRules.certificateSha256(new byte[0]));
        assertTrue(Json.isSha256(SelfUpdateRules.certificateSha256(new byte[] {1, 2, 3})));
    }

    /** Every install result has its failure, the unknown ones the generic one. */
    @Test
    public void everyStatusHasAFailure() {
        assertEquals(UpdateFailure.SELF_BLOCKED, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE_BLOCKED));
        assertEquals(UpdateFailure.SELF_ABORTED, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE_ABORTED));
        assertEquals(UpdateFailure.SELF_INVALID, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE_INVALID));
        assertEquals(UpdateFailure.SELF_CONFLICT, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE_CONFLICT));
        assertEquals(UpdateFailure.SELF_STORAGE, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE_STORAGE));
        assertEquals(UpdateFailure.SELF_INCOMPATIBLE, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE_INCOMPATIBLE));
        assertEquals(UpdateFailure.SELF_NO_ANSWER, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE_TIMEOUT));
        assertEquals(UpdateFailure.SELF_FAILED, SelfUpdateRules.forStatus(SelfUpdateRules.STATUS_FAILURE));
        assertEquals(UpdateFailure.SELF_FAILED, SelfUpdateRules.forStatus(99));
    }

    @Test
    public void buildStagesHaveFailures() {
        assertEquals(UpdateFailure.BUILD_SPACE, BuildFailures.forStage("space"));
        assertEquals(UpdateFailure.BUILD_MEMORY, BuildFailures.forStage("memory"));
        assertEquals(UpdateFailure.BUILD_TOOLS, BuildFailures.forStage("download"));
        assertEquals(UpdateFailure.BUILD_GAME_FILES, BuildFailures.forStage("extract"));
        assertEquals(UpdateFailure.BUILD_COMPILE, BuildFailures.forStage("link"));
        assertEquals(UpdateFailure.BUILD_INSTALL, BuildFailures.forStage("dest"));
        assertEquals(UpdateFailure.BUILD_FAILED, BuildFailures.forStage("something new"));
        assertEquals(UpdateFailure.BUILD_FAILED, BuildFailures.forStage(null));
    }
}
