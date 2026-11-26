package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The launcher's mod put right once per process, before anything reads it. */
public class ModRecoveryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void processStarts() {
        ModRecovery.forget();
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aSwapCutBetweenItsRenamesIsPutBackOnceAndNeverTouchedAfter() throws Exception {
        // Catches a phone that died mid-swap leaving the game with no mod at all, and the recovery running again later
        // (while a job is swapping a new mod in).
        File home = tmp.newFolder("home");
        write(new File(LauncherMod.oldDir(home), "exefs/code.bin"), "old code");
        assertFalse(LauncherMod.modDir(home).exists());
        assertEquals(ModSwap.Recovery.OLD_RESTORED, ModRecovery.once(home));
        assertTrue(new File(LauncherMod.modDir(home), "exefs/code.bin").isFile());

        write(new File(LauncherMod.oldDir(home), "x"), "the old folder of a swap running now");
        assertEquals(ModSwap.Recovery.NOTHING, ModRecovery.once(home));
        assertTrue(LauncherMod.oldDir(home).exists());
    }

    @Test
    public void aHalfDoneJobsStagingFolderIsLeftForTheJob() throws Exception {
        // Catches the recovery deleting the staging folder a half-done job goes on from.
        File home = tmp.newFolder("home");
        write(new File(LauncherMod.modDir(home), "exefs/code.bin"), "code");
        write(new File(LauncherMod.stagingDir(home), "exefs/code.bin"), "half");
        assertEquals(ModSwap.Recovery.NOTHING, ModRecovery.once(home));
        assertTrue(LauncherMod.stagingDir(home).isDirectory());
    }

    @Test
    public void theModsAreWhereTheGameAndTheLibraryBuildLook() {
        // Catches the mod, its staging and its old folder in different parents (a swap would then copy), or outside the
        // user folder the library build is given.
        File home = new File("h");
        assertEquals(new File(home, "user"), LauncherMod.userFolder(home));
        assertEquals(new File(home, "user/load/mods"), LauncherMod.modsFolder(home));
        assertEquals(LauncherMod.modsFolder(home), LauncherMod.modDir(home).getParentFile());
        assertEquals(LauncherMod.modsFolder(home), LauncherMod.stagingDir(home).getParentFile());
        assertEquals(LauncherMod.modsFolder(home), LauncherMod.oldDir(home).getParentFile());
    }
}
