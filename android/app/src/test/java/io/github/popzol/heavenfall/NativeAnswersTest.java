package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import io.github.popzol.heavenfall.HomeModel.Source;

/** The native layer's answers (the text jni/test/hf_test.c checks it writes) and what the screens make of them. */
public class NativeAnswersTest {
    private static final String SHA_A = "ab".repeat(32), SHA_B = "cd".repeat(32);

    private static String identity(String kind, String region, int encrypted, String revision) {
        return "rc=0\nkind=" + kind + "\nregion=" + region + "\nencrypted=" + encrypted
                + "\ntitle=0004000E00126300\nrevision=" + revision + "\n";
    }

    private static final String SET = "rc=0\nformat=4\nneeds_newer_app=0\nregion=usa\nedition=HeavenfallBeta\nversion=0.3.0\n"
            + "xx=1\nxx_title=0004000000197100\nxx_romfs_sha256=" + SHA_B + "\nshared=1\nshared_size=97200\nshared_sha256=" + SHA_A
            + "\nupdate=1\nupdate_title=0004000E00126300\nupdate_exefs_sha256=" + SHA_A + "\ncheats=0\nfiles=17\n";

    @Test
    public void anIdentityIsRead() {
        // Catches a reader that drops the revision (the update's exact identity), the encrypted flag or the kind.
        Identity id = Identity.parse(identity("update", "usa", 0, SHA_A));
        assertEquals(Identity.Kind.UPDATE, id.kind);
        assertEquals("usa", id.region);
        assertFalse(id.encrypted);
        assertEquals(SHA_A, id.revision);
        assertEquals("0004000E00126300", id.titleId);
        assertTrue(Identity.parse(identity("game", "eur", 1, "")).encrypted);
        assertEquals("", Identity.parse(identity("game", "eur", 1, "")).revision);
        assertEquals(Identity.Kind.OTHER, Identity.parse(identity("other", "unknown", 0, "")).kind);
    }

    @Test
    public void anAnswerThatIsNoAnswerIsRefused() {
        // Catches a native answer that is cut, repeated or of another shape being taken as a file's identity.
        for (String bad : new String[] {null, "", "rc=0\n", "rc=0\nkind=banana\nregion=usa\nencrypted=0\ntitle=1\nrevision=\n",
                identity("game", "usa", 0, "zz"), identity("game", "usa", 0, "") + "rc=1\n", "garbage\n"}) {
            try {
                Identity.parse(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void aSetsHeadIsRead() {
        // Catches the head's fields mixed up: the shared file's hash, the update it names, the need for MH XX.
        SetInfo info = SetInfo.parse(SET);
        assertTrue(info.usable());
        assertEquals(4, info.format);
        assertEquals("HeavenfallBeta", info.edition);
        assertEquals("0.3.0", info.version);
        assertEquals("usa", info.region);
        assertTrue(info.needsXx);
        assertEquals(SHA_B, info.xxRomfsSha256);
        assertTrue(info.needsShared);
        assertEquals(97200L, info.sharedSize);
        assertEquals(SHA_A, info.sharedSha256);
        assertEquals(SHA_A, info.updateExefsSha256);
        assertEquals(17, info.files);
    }

    @Test
    public void aNewerFormatIsTheAppMustBeUpdatedFirst() {
        // Catches a set of a newer format taken as damaged (the player would be told to download it again, for ever)
        // or as usable.
        SetInfo newer = SetInfo.parse("rc=0\nformat=5\nneeds_newer_app=1\nregion=unknown\nedition=\nversion=\nxx=0\n"
                + "xx_title=0000000000000000\nxx_romfs_sha256=\nshared=0\nshared_size=0\nshared_sha256=\nupdate=0\n"
                + "update_title=0000000000000000\nupdate_exefs_sha256=\ncheats=0\nfiles=0\n");
        assertTrue(newer.setNeedsNewerApp());
        assertFalse(newer.usable());
        assertFalse(SetInfo.parse(SET).setNeedsNewerApp());
        UpdatePlan.Input input = new UpdatePlan.Input();
        input.setNeedsNewerApp = newer.setNeedsNewerApp();
        assertTrue(input.setNeedsNewerApp);
    }

    @Test
    public void aConsoleSetOrAFileThatIsNoSetIsNotUsable() {
        // Catches a set of the 3DS app (no update named) or an unreadable head being applied.
        SetInfo console = SetInfo.parse(SET.replace("format=4", "format=3").replace("update=1", "update=0"));
        assertFalse(console.usable());
        SetInfo none = SetInfo.parse(SET.replace("rc=0", "rc=-6"));
        assertFalse(none.usable());
    }

    @Test
    public void theGameIsToldByRegionEncryptionAndKind() {
        // Catches the wrong region, an encrypted copy, MH XX or an update taken for the American game.
        assertEquals(Source.MISSING, SourceCheck.game(null, "usa"));
        assertEquals(Source.GOOD, SourceCheck.game(Identity.of(Identity.Kind.GAME, "usa", false, SHA_A), "usa"));
        assertEquals(Source.WRONG_REGION, SourceCheck.game(Identity.of(Identity.Kind.GAME, "eur", false, SHA_A), "usa"));
        assertEquals(Source.ENCRYPTED, SourceCheck.game(Identity.of(Identity.Kind.GAME, "usa", true, ""), "usa"));
        assertEquals(Source.WRONG_VERSION, SourceCheck.game(Identity.of(Identity.Kind.XX, "jpn", false, SHA_A), "usa"));
        assertEquals(Source.WRONG_VERSION, SourceCheck.game(Identity.of(Identity.Kind.UPDATE, "usa", false, SHA_A), "usa"));
        assertEquals(Source.WRONG_VERSION, SourceCheck.game(Identity.of(Identity.Kind.NONE, "unknown", false, ""), "usa"));
        assertEquals(Source.WRONG_VERSION, SourceCheck.game(Identity.of(Identity.Kind.OTHER, "unknown", false, ""), "usa"));
    }

    @Test
    public void theUpdateIsToldByRegionEncryptionAndTheExactRevision() {
        // Catches another update (the European one, another revision) taken for the one the set was made with, and the
        // base game taken for an update.
        Identity good = Identity.of(Identity.Kind.UPDATE, "usa", false, SHA_A);
        assertEquals(Source.MISSING, SourceCheck.update(null, "usa", ""));
        assertEquals(Source.GOOD, SourceCheck.update(good, "usa", ""));
        assertEquals(Source.GOOD, SourceCheck.update(good, "usa", SHA_A.toUpperCase()));
        assertEquals(Source.WRONG_VERSION, SourceCheck.update(good, "usa", SHA_B));
        assertEquals(Source.WRONG_REGION, SourceCheck.update(Identity.of(Identity.Kind.UPDATE, "eur", false, SHA_A), "usa", SHA_A));
        assertEquals(Source.ENCRYPTED, SourceCheck.update(Identity.of(Identity.Kind.UPDATE, "usa", true, ""), "usa", SHA_A));
        assertEquals(Source.WRONG_VERSION, SourceCheck.update(Identity.of(Identity.Kind.GAME, "usa", false, SHA_A), "usa", ""));
    }

    @Test
    public void mhXxIsToldByEncryptionAndTheExactRevision() {
        // Catches MH4U taken for MH XX, and an XX of another revision than the one the set was made with.
        Identity xx = Identity.of(Identity.Kind.XX, "jpn", false, SHA_B);
        assertEquals(Source.MISSING, SourceCheck.xx(null, ""));
        assertEquals(Source.GOOD, SourceCheck.xx(xx, ""));
        assertEquals(Source.GOOD, SourceCheck.xx(xx, SHA_B));
        assertEquals(Source.WRONG_VERSION, SourceCheck.xx(xx, SHA_A));
        assertEquals(Source.ENCRYPTED, SourceCheck.xx(Identity.of(Identity.Kind.XX, "jpn", true, ""), SHA_B));
        assertEquals(Source.WRONG_VERSION, SourceCheck.xx(Identity.of(Identity.Kind.GAME, "usa", false, SHA_B), ""));
    }

    @Test
    public void everyNativeCodeAtEveryStageHasAnExplanation() {
        // Catches a code the layer can return with no mapping (an empty line for the player) and the mapping's key set.
        int[] codes = {ApplyError.ARG, ApplyError.MEM, ApplyError.IO, ApplyError.PATH, ApplyError.SIZE, ApplyError.FORMAT,
                ApplyError.CANCEL, ApplyError.ENCRYPTED, ApplyError.GAME, ApplyError.NOPATCH, ApplyError.HASH,
                ApplyError.DAMAGED, ApplyError.NO_UPDATE, ApplyError.NOT_UPDATE, ApplyError.OTHER_UPDATE, ApplyError.NEWER,
                ApplyError.NOT_ANDROID, ApplyError.INCOMPLETE, ApplyError.NO_XX, ApplyError.NO_SHARED, ApplyError.READ,
                ApplyError.WRITE, ApplyError.SPACE, -999};
        for (int code : codes) {
            for (int stage = ApplyError.STAGE_SET; stage <= ApplyError.STAGE_WRITE; stage++) {
                ApplyError e = ApplyError.of(code, stage, 0);
                assertTrue(e.reason().key.startsWith("hf_") && e.advice().key.startsWith("hf_"));
                assertEquals(code == ApplyError.CANCEL, e.cancelled());
                assertTrue(e.logLine().contains("code " + code));
            }
        }
    }

    @Test
    public void aCodeIsBlamedOnTheRightFile() {
        // Catches a hash that differs blamed on the wrong file (the player would change the wrong one), and the codes
        // that mean "nothing was wrong with your files".
        assertEquals(ApplyError.Failure.GAME_DIFFERENT, ApplyError.of(ApplyError.HASH, ApplyError.STAGE_GAME, 0).failure);
        assertEquals(ApplyError.Failure.UPDATE_OTHER, ApplyError.of(ApplyError.HASH, ApplyError.STAGE_UPDATE, 0).failure);
        assertEquals(ApplyError.Failure.XX_DIFFERENT, ApplyError.of(ApplyError.HASH, ApplyError.STAGE_XX, 0).failure);
        assertEquals(ApplyError.Failure.SHARED_BAD, ApplyError.of(ApplyError.HASH, ApplyError.STAGE_SHARED, 0).failure);
        assertEquals(ApplyError.Failure.OUTPUT_DIFFERS, ApplyError.of(ApplyError.HASH, ApplyError.STAGE_WRITE, 0).failure);
        assertEquals(ApplyError.Failure.GAME_ENCRYPTED, ApplyError.of(ApplyError.ENCRYPTED, ApplyError.STAGE_GAME, 0).failure);
        assertEquals(ApplyError.Failure.UPDATE_ENCRYPTED, ApplyError.of(ApplyError.ENCRYPTED, ApplyError.STAGE_UPDATE, 0).failure);
        assertEquals(ApplyError.Failure.XX_ENCRYPTED, ApplyError.of(ApplyError.ENCRYPTED, ApplyError.STAGE_XX, 0).failure);
        assertEquals(ApplyError.Failure.UPDATE_MISSING, ApplyError.of(ApplyError.NO_UPDATE, ApplyError.STAGE_UPDATE, 0).failure);
        assertEquals(ApplyError.Failure.UPDATE_OTHER, ApplyError.of(ApplyError.OTHER_UPDATE, ApplyError.STAGE_UPDATE, 0).failure);
        assertEquals(ApplyError.Failure.UPDATE_NOT_UPDATE, ApplyError.of(ApplyError.NOT_UPDATE, ApplyError.STAGE_UPDATE, 0).failure);
        assertEquals(ApplyError.Failure.SET_DAMAGED, ApplyError.of(ApplyError.FORMAT, ApplyError.STAGE_SET, 0).failure);
        assertEquals(ApplyError.Failure.SET_DAMAGED, ApplyError.of(ApplyError.PATH, ApplyError.STAGE_SET, 0).failure);
        assertEquals(ApplyError.Failure.NEEDS_NEWER_APP, ApplyError.of(ApplyError.NEWER, ApplyError.STAGE_SET, 0).failure);
        assertEquals(ApplyError.Failure.SET_NOT_FOR_APP, ApplyError.of(ApplyError.NOT_ANDROID, ApplyError.STAGE_SET, 0).failure);
        assertEquals(ApplyError.Failure.NO_SPACE, ApplyError.of(ApplyError.SPACE, ApplyError.STAGE_WRITE, 28).failure);
        assertEquals(ApplyError.Failure.WRITE_FAILED, ApplyError.of(ApplyError.WRITE, ApplyError.STAGE_WRITE, 13).failure);
        assertEquals(ApplyError.Failure.READ_FAILED, ApplyError.of(ApplyError.READ, ApplyError.STAGE_GAME, 5).failure);
        assertEquals(ApplyError.Failure.NO_MEMORY, ApplyError.of(ApplyError.MEM, ApplyError.STAGE_SET, 0).failure);
        assertEquals(ApplyError.Failure.INTERNAL, ApplyError.of(-999, ApplyError.STAGE_SET, 0).failure);
        assertEquals(new Text("hf_apply_internal", "-999"), ApplyError.of(-999, ApplyError.STAGE_SET, 0).reason());
    }

    @Test
    public void aResultIsReadAndItsErrorMade() {
        // Catches a run that stopped (the staging folder must not be put in place) read as one that finished.
        String text = "rc=-11\nstage=1\nerrno=0\nsource=0\npath=romfs/data/a.arc\nfiles=3\nbytes=1234\ncode_sha256=\n";
        ApplyResult bad = ApplyResult.parse(text);
        assertFalse(bad.ok());
        assertEquals(ApplyError.Failure.GAME_DIFFERENT, bad.error().failure);
        assertEquals("romfs/data/a.arc", bad.path);
        ApplyResult good = ApplyResult.parse("rc=0\nstage=0\nerrno=0\nsource=-1\npath=\nfiles=17\nbytes=99\ncode_sha256=" + SHA_A + "\n");
        assertTrue(good.ok());
        assertNull(good.error());
        assertEquals(SHA_A, good.codeSha256);
        assertEquals(17, good.files);
    }
}
