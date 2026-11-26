package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class InstalledTest {
    private static Installed built() {
        return new Installed("beta", "0.2.3", "HeavenfallBeta-android-USA.hfpatch", sha('a'), false, sha('b'), "tools-7", sha('b'));
    }

    /** The record written after an install is the record read at the next start. */
    @Test
    public void roundTrip() {
        Installed i = built();
        Installed back = Installed.parse(i.toJson());
        assertEquals("beta", back.edition);
        assertEquals("0.2.3", back.modVersion);
        assertEquals("HeavenfallBeta-android-USA.hfpatch", back.setName);
        assertEquals(sha('a'), back.setSha256);
        assertFalse(back.withXx);
        assertEquals(sha('b'), back.codeSha256);
        assertEquals("tools-7", back.libraryId);
        assertEquals(sha('b'), back.libraryCodeSha256);
        assertEquals(i.toJson(), back.toJson());
    }

    /** A mod with no library yet is written and read back with empty library fields. */
    @Test
    public void noLibraryYet() {
        Installed i = Installed.fresh("beta", "0.2.3", "s.hfpatch", sha('a'), true, sha('b'));
        Installed back = Installed.parse(i.toJson());
        assertEquals("", back.libraryId);
        assertEquals("", back.libraryCodeSha256);
        assertTrue(back.withXx);
        assertEquals(HomeModel.Library.NONE, back.libraryState(false, null, "tools-7"));
    }

    /** A half-written or edited file must read as "nothing installed" for tryParse, and as a named error for parse. */
    @Test
    public void damagedRecords() {
        String good = built().toJson();
        String[] bad = {"", "{", "[]", good.replace("\"format\":1", "\"format\":2"), good.replace(sha('a'), "xyz"),
                good.replace("\"xx\":false", "\"xx\":\"no\""), good.replace("\"edition\":\"beta\"", "\"edition\":\"\""),
                good.replace("\"library_id\":\"tools-7\"", "\"library_id\":\"\"")};
        for (String text : bad) {
            assertNull("tryParse: " + text, Installed.tryParse(text));
            try {
                Installed.parse(text);
                fail("parse must refuse: " + text);
            } catch (Installed.InvalidException expected) {
                // named
            }
        }
        assertNull(Installed.tryParse(null));
    }

    /** The four library states: nothing, other tools, other mod code, good. */
    @Test
    public void libraryStates() {
        Installed i = built();
        assertEquals(HomeModel.Library.NONE, i.libraryState(false, null, "tools-7"));
        assertEquals(HomeModel.Library.OTHER_TOOLS, i.libraryState(true, "tools-6", "tools-7"));
        assertEquals(HomeModel.Library.OTHER_TOOLS, i.libraryState(true, null, "tools-7"));
        assertEquals(HomeModel.Library.GOOD, i.libraryState(true, "tools-7", "tools-7"));
        Installed newCode = i.applied("0.2.4", "s.hfpatch", sha('c'), false, sha('d'));
        assertEquals("a new mod code makes the library stale", HomeModel.Library.OTHER_MOD,
                newCode.libraryState(true, "tools-7", "tools-7"));
        Installed sameCode = i.applied("0.2.4", "s.hfpatch", sha('c'), false, sha('b'));
        assertEquals("a new mod with the same code keeps the library", HomeModel.Library.GOOD,
                sameCode.libraryState(true, "tools-7", "tools-7"));
        assertEquals("tools changed under a good record", HomeModel.Library.OTHER_TOOLS,
                i.libraryState(true, "tools-7", "tools-8"));
        assertEquals(HomeModel.Library.GOOD, newCode.withLibrary("tools-7").libraryState(true, "tools-7", "tools-7"));
        assertEquals(HomeModel.Library.OTHER_TOOLS, Installed.libraryStateWithoutRecord(true));
        assertEquals(HomeModel.Library.NONE, Installed.libraryStateWithoutRecord(false));
    }

    /** Hashes in capitals are the same hashes. */
    @Test
    public void hashesAreLowerCased() {
        Installed i = new Installed("beta", "1", "s", sha('A'), false, sha('B'), "", "");
        assertEquals(sha('a'), i.setSha256);
        assertEquals(sha('b'), i.codeSha256);
    }
}
