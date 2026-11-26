package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class InstalledTest {
    private static final LibraryFile FILE = new LibraryFile(sha('f'), 120, 7);

    private static Installed built() {
        return new Installed("beta", "0.2.3", "HeavenfallBeta-android-USA.hfpatch", sha('a'), false, sha('b'), sha('c'),
                "tools-7", sha('c'), FILE);
    }

    /** The record written after an install is the record read at the next start, the library's file with it. */
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
        assertEquals(sha('c'), back.modCodeSha256);
        assertEquals("tools-7", back.libraryId);
        assertEquals(sha('c'), back.libraryCodeSha256);
        assertEquals(sha('f'), back.libraryFile.sha256);
        assertEquals(120, back.libraryFile.size);
        assertEquals(7, back.libraryFile.modifiedMs);
        assertEquals(i.toJson(), back.toJson());
    }

    /** A mod with no library yet is written and read back with empty library fields. */
    @Test
    public void noLibraryYet() {
        Installed i = Installed.fresh("beta", "0.2.3", "s.hfpatch", sha('a'), true, sha('b'), sha('c'));
        Installed back = Installed.parse(i.toJson());
        assertEquals("", back.libraryId);
        assertEquals("", back.libraryCodeSha256);
        assertNull(back.libraryFile);
        assertTrue(back.withXx);
        assertEquals(HomeModel.Library.NONE, back.libraryState(false, null, "tools-7", false));
        assertEquals("a library with no record of its file is not one this app built", HomeModel.Library.UNKNOWN,
                back.libraryState(true, "tools-7", "tools-7", false));
    }

    /**
     * A half-written, edited or older record reads as "nothing installed" for tryParse (Home says Install: the
     * migration rule for the records of the app before the private storage) and as a named error for parse.
     */
    @Test
    public void damagedAndOldRecords() {
        String good = built().toJson();
        String[] bad = {"", "{", "[]", good.replace("\"format\":2", "\"format\":1"), good.replace(sha('a'), "xyz"),
                good.replace("\"xx\":false", "\"xx\":\"no\""), good.replace("\"edition\":\"beta\"", "\"edition\":\"\""),
                good.replace("\"library_id\":\"tools-7\"", "\"library_id\":\"\""),
                good.replace("\"library_sha256\":\"" + sha('f') + "\"", "\"library_sha256\":\"\""),
                good.replace("\"mod_code_sha256\":\"" + sha('c') + "\"", "\"mod_code_sha256\":\"\"")};
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

    /**
     * F1: the library is good only when it is the file the record names (its hash), built by these tools from the
     * installed mod's code; a library put in place by anything else is UNKNOWN (Home: Build).
     */
    @Test
    public void libraryStates() {
        Installed i = built();
        assertEquals(HomeModel.Library.NONE, i.libraryState(false, null, "tools-7", false));
        assertEquals("another file than the recorded one", HomeModel.Library.UNKNOWN,
                i.libraryState(true, "tools-7", "tools-7", false));
        assertEquals(HomeModel.Library.OTHER_TOOLS, i.libraryState(true, "tools-6", "tools-7", true));
        assertEquals(HomeModel.Library.OTHER_TOOLS, i.libraryState(true, null, "tools-7", true));
        assertEquals(HomeModel.Library.GOOD, i.libraryState(true, "tools-7", "tools-7", true));
        Installed newCode = i.applied("0.2.4", "s.hfpatch", sha('c'), false, sha('d'), sha('e'));
        assertEquals("a new mod code makes the library stale", HomeModel.Library.OTHER_MOD,
                newCode.libraryState(true, "tools-7", "tools-7", true));
        Installed sameCode = i.applied("0.2.4", "s.hfpatch", sha('c'), false, sha('b'), sha('c'));
        assertEquals("a new mod with the same code keeps the library", HomeModel.Library.GOOD,
                sameCode.libraryState(true, "tools-7", "tools-7", true));
        assertEquals("tools changed under a good record", HomeModel.Library.OTHER_TOOLS,
                i.libraryState(true, "tools-7", "tools-8", true));
        assertEquals(HomeModel.Library.GOOD,
                newCode.withLibrary("tools-7", sha('e'), FILE).libraryState(true, "tools-7", "tools-7", true));
        assertEquals(HomeModel.Library.UNKNOWN, Installed.libraryStateWithoutRecord(true));
        assertEquals(HomeModel.Library.NONE, Installed.libraryStateWithoutRecord(false));
    }

    /** The library's id, its code's digest and its file go together: a record with one of them only is refused. */
    @Test
    public void libraryFieldsTogether() {
        try {
            new Installed("beta", "1", "s", sha('a'), false, sha('b'), sha('c'), "tools-7", sha('c'), null);
            fail("an id with no file");
        } catch (Installed.InvalidException expected) {
            // named
        }
        try {
            new Installed("beta", "1", "s", sha('a'), false, sha('b'), sha('c'), "", "", FILE);
            fail("a file with no id");
        } catch (Installed.InvalidException expected) {
            // named
        }
    }

    /** Hashes in capitals are the same hashes. */
    @Test
    public void hashesAreLowerCased() {
        Installed i = Installed.fresh("beta", "1", "s", sha('A'), false, sha('B'), sha('C'));
        assertEquals(sha('a'), i.setSha256);
        assertEquals(sha('b'), i.codeSha256);
        assertEquals(sha('c'), i.modCodeSha256);
    }
}
