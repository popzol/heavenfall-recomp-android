package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** F8: the launcher's records are written whole (temp, sync, rename, folder sync) and read back with a size cap. */
public class RecordsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Catches a record written in place (a cut leaves half of it), a temporary file left behind, a missing folder. */
    @Test
    public void writtenWholeIntoANewOrOldFile() throws IOException {
        File f = new File(tmp.getRoot(), "a/b/installed.json");
        Records.write(f, "first");
        assertEquals("first", text(f));
        Records.write(f, "second, longer");
        assertEquals("second, longer", text(f));
        assertEquals(Arrays.asList("installed.json"), Arrays.asList(f.getParentFile().list()));
        assertEquals("second, longer", Records.read(f));
    }

    /** Catches a write that fails half-way and leaves the old record damaged or gone. */
    @Test
    public void aFailedWriteKeepsTheOldRecord() throws IOException {
        File f = new File(tmp.getRoot(), "job.json");
        Records.write(f, "old");
        File blocker = new File(tmp.getRoot(), "job.json.tmp");
        assertTrue(blocker.mkdir());
        Files.write(new File(blocker, "x").toPath(), new byte[] {1});
        try {
            Records.write(f, "new");
            fail("the temporary file could not be made");
        } catch (IOException expected) {
            // named
        }
        assertEquals("old", text(f));
    }

    /** Catches a rename whose folder entry is never synced (a power cut could lose the new record). */
    @Test
    public void theFolderIsSyncedAfterTheRename() throws IOException {
        List<File> synced = new ArrayList<>();
        Records.setFolderSync(synced::add);
        try {
            File f = new File(tmp.getRoot(), "resume.json");
            Records.write(f, "x");
            assertEquals(Arrays.asList(f.getAbsoluteFile().getParentFile()), synced);
            Records.setFolderSync(dir -> {
                throw new IOException("no folder sync on this system");
            });
            Records.write(f, "y");
            assertEquals("a folder that cannot be synced is no failure", "y", Records.read(f));
        } finally {
            Records.setFolderSync(null);
        }
    }

    /** Catches a huge or foreign file read whole (out of memory) and a missing one that throws. */
    @Test
    public void missingOrOversizeReadsAsNone() throws IOException {
        assertNull(Records.read(new File(tmp.getRoot(), "none")));
        assertNull(Records.read(tmp.getRoot()));
        assertNull(Records.read(null));
        File big = tmp.newFile("big");
        Files.write(big.toPath(), new byte[Records.MAX_BYTES + 1]);
        assertNull(Records.read(big));
        assertTrue(Records.delete(big));
        assertTrue("a missing record is deleted already", Records.delete(big));
    }

    /**
     * Catches a record nested so deep that the JSON parser's recursion overflows the stack (a crash at every start),
     * and a check that counts brackets inside strings.
     */
    @Test
    public void deepNestingIsNoRecordAndNoCrash() {
        StringBuilder deep = new StringBuilder("{\"a\":");
        for (int i = 0; i < 200_000; i++) deep.append('[');
        assertNull(Json.object(deep.toString()));
        assertNull(Installed.tryParse(deep.toString()));
        assertFalse(Json.deeperThan("{\"a\":\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"}", 3));
        assertFalse(Json.deeperThan("{\"a\":\"\\\"[[[[\"}", 1));
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < Json.MAX_DEPTH; i++) ok.append('[');
        assertFalse(Json.deeperThan(ok.toString(), Json.MAX_DEPTH));
        assertTrue(Json.deeperThan(ok + "[", Json.MAX_DEPTH));
    }
}
