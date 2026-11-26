package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LauncherModTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File mod(String name, String... files) throws IOException {
        File folder = tmp.newFolder(name);
        for (String rel : files) {
            File f = new File(folder, rel);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), new byte[] {1});
        }
        return folder;
    }

    @Test
    public void theLaunchersNameAndItsLeftoversAreNeverTheGameFolderSyncsToTouch() {
        // Catches the sync of the game folder's mods/ replacing, removing or shadowing the launcher's mod through a mod
        // of the same name in any spelling, or through the folders the swap uses.
        for (String name : new String[] {"Heavenfall", "heavenfall", "HEAVENFALL", " Heavenfall ", ".heavenfall-staging",
                ".heavenfall-old"}) {
            assertTrue(name, LauncherMod.isReserved(name));
        }
        for (String name : new String[] {"Heavenfall2", "My mod", "", "0004000000126300", null}) {
            assertFalse(String.valueOf(name), LauncherMod.isReserved(name));
        }
    }

    @Test
    public void aModThatCarriesCodeIsFoundWhereverTheCodeIs() throws IOException {
        // Catches a game-folder mod with code (a replacement, a patch or an exheader, in its folder or its title folder)
        // loaded beside the launcher's: the code library is made from one code mod only.
        assertTrue(LauncherMod.carriesCode(mod("a", "exefs/code.ips")));
        assertTrue(LauncherMod.carriesCode(mod("b", "exefs/code.bps")));
        assertTrue(LauncherMod.carriesCode(mod("c", "exefs/code.bin")));
        assertTrue(LauncherMod.carriesCode(mod("d", "code.ips")));
        assertTrue(LauncherMod.carriesCode(mod("e", "exheader.bin")));
        assertTrue(LauncherMod.carriesCode(mod("f", "0004000000126300/exefs/code.ips")));
        assertTrue(LauncherMod.carriesCode(mod("g", "0004000E00126300/exefs/code.bin")));
    }

    @Test
    public void aModWithoutCodeIsLeftToTheSync() throws IOException {
        // Catches texture and music mods refused for no reason (the sync would lose everything that is not code).
        assertFalse(LauncherMod.carriesCode(mod("a", "romfs/data/x.arc")));
        assertFalse(LauncherMod.carriesCode(mod("b", "romfs_ext/data/x.ips")));
        assertFalse(LauncherMod.carriesCode(mod("c", "0004000000126300/romfs/data/x.arc")));
        assertFalse(LauncherMod.carriesCode(mod("d", "notes/code.bin.txt")));
        assertFalse(LauncherMod.carriesCode(new File(tmp.getRoot(), "no such folder")));
    }

    @Test
    public void installedRecordsAreWrittenWholeAndKeepTheLibraryRecord() throws IOException {
        // Catches a new mod's record that forgets the library built for the same code (a rebuild for nothing), a record
        // that is not written whole (a stop in the middle leaves a file the app reads as "nothing installed"), and
        // junk read as a record.
        String a = "a".repeat(64), b = "b".repeat(64), c = "c".repeat(64);
        Installed first = InstalledStore.after(null, "beta", "0.3.0", "set", a, false, b, b);
        assertEquals("", first.libraryId);
        Installed built = first.withLibrary("tools1", b, Support.LIBRARY_FILE);
        Installed next = InstalledStore.after(built, "beta", "0.3.1", "set2", c, true, b, b);
        assertEquals("tools1", next.libraryId);
        assertEquals(b, next.libraryCodeSha256);
        assertTrue(next.withXx);
        assertEquals("", InstalledStore.after(built, "other_edition", "1", "s", a, false, b, b).libraryId);
        File file = new File(tmp.getRoot(), "heavenfall/installed.json");
        InstalledStore.write(file, next);
        assertEquals(next.toJson(), InstalledStore.read(file).toJson());
        assertFalse(new File(file.getPath() + ".tmp").exists());
        Files.write(file.toPath(), "{\"format\": 9".getBytes(StandardCharsets.UTF_8));
        assertNull(InstalledStore.read(file));
        assertNull(InstalledStore.read(new File(tmp.getRoot(), "none.json")));
        assertNotNull(InstalledStore.sha256(file));
        assertEquals(64, InstalledStore.sha256(file).length());
    }
}
