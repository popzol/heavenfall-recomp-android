package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** F1: the library the game loads is the file the last build left (its record), checked before every start. */
public class LibraryFileTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Catches a library put in place of the built one and taken as good, and a check that hashes the whole file when
     * nothing changed (one stat is enough: only the app writes its private storage).
     */
    @Test
    public void theRecordedFileOnly() throws IOException {
        File lib = tmp.newFile("libmh4u_code_native.so");
        Files.write(lib.toPath(), new byte[] {1, 2, 3, 4});
        LibraryFile record = LibraryFile.of(lib);
        assertEquals(LibraryFile.Check.SAME_STAT, record.check(lib));
        assertTrue(lib.setLastModified(record.modifiedMs + 5_000));
        assertEquals("the same bytes at another time: hashed and kept", LibraryFile.Check.SAME_HASH, record.check(lib));
        assertTrue(record.matches(lib));
        Files.write(lib.toPath(), new byte[] {9, 9, 9, 9});
        assertTrue(lib.setLastModified(record.modifiedMs + 9_000));
        assertEquals(LibraryFile.Check.DIFFERENT, record.check(lib));
        assertFalse(record.matches(lib));
        Files.write(lib.toPath(), new byte[] {1, 2, 3});
        assertEquals("another size: nothing read", LibraryFile.Check.DIFFERENT, record.check(lib));
        Files.delete(lib.toPath());
        assertEquals(LibraryFile.Check.MISSING, record.check(lib));
        assertEquals(LibraryFile.Check.MISSING, record.check(null));
    }
}
