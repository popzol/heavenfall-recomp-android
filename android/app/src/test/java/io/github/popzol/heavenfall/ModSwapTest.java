package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ModSwapTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File mods, installed, staging, old;

    @Before
    public void folders() throws IOException {
        mods = tmp.newFolder("mods");
        installed = new File(mods, "Heavenfall");
        staging = new File(mods, ".heavenfall-staging");
        old = new File(mods, ".heavenfall-old");
    }

    private static void mod(File folder, String content) throws IOException {
        File code = new File(folder, "exefs/code.bin");
        assertTrue(code.getParentFile().isDirectory() || code.getParentFile().mkdirs());
        Files.write(code.toPath(), content.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder, "exheader.bin").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** What a mod folder holds: its code's text, or null when the folder is not there. */
    private static String code(File folder) throws IOException {
        File code = new File(folder, "exefs/code.bin");
        return folder.isDirectory() ? new String(Files.readAllBytes(code.toPath()), StandardCharsets.UTF_8) : null;
    }

    /** The folders are in one of the states a stop at any moment may leave: a whole mod and nothing else of it. */
    private void assertWhole(String expectedCode) throws IOException {
        assertEquals(expectedCode, code(installed));
        assertTrue(new File(installed, "exheader.bin").isFile());
        assertFalse(old.exists());
    }

    @Test
    public void aFirstInstallPutsTheStagingFolderInPlace() throws IOException {
        // Catches a first install (no mod yet) that fails for lack of an old folder, or leaves the staging folder.
        ModSwap swap = new ModSwap(installed, staging, old);
        mod(swap.begin(), "new");
        assertTrue(swap.stagingLeft());
        swap.commit();
        assertWhole("new");
        assertFalse(swap.stagingLeft());
    }

    @Test
    public void aNewModReplacesTheOldOne() throws IOException {
        // Catches the new mod put beside the old one, or files of the old mod left in the new one.
        mod(installed, "old");
        Files.write(new File(installed, "only_in_old.txt").toPath(), new byte[1]);
        ModSwap swap = new ModSwap(installed, staging, old);
        mod(swap.begin(), "new");
        swap.commit();
        assertWhole("new");
        assertFalse(new File(installed, "only_in_old.txt").exists());
    }

    @Test
    public void aStopBetweenTheStepsLeavesTheOldModOrTheNewOne() throws IOException {
        // Catches a swap that, stopped between its renames (no mod folder at all) or before the old folder is deleted,
        // leaves nothing, a mix, or a leftover that blocks the next run.
        for (String stopAt : Arrays.asList("start", "old aside", "new in place", "done")) {
            tmp.delete();
            tmp.create();
            folders();
            mod(installed, "old");
            ModSwap crashing = new ModSwap(installed, staging, old, name -> {
                if (name.equals(stopAt)) throw new IOException("the app was killed at " + name);
            });
            mod(crashing.begin(), "new");
            try {
                crashing.commit();
                assertEquals("it must have stopped at " + stopAt, "done", stopAt);
            } catch (IOException expected) {
                assertEquals("the app was killed at " + stopAt, expected.getMessage());
            }
            // the next start: a new object over the same folders
            ModSwap next = new ModSwap(installed, staging, old);
            next.recover();
            String code = code(installed);
            assertTrue("after a stop at " + stopAt + " the mod must be the old or the new, was " + code,
                    "old".equals(code) || "new".equals(code));
            assertTrue(new File(installed, "exheader.bin").isFile());
            assertFalse(old.exists());
            assertEquals(stopAt.equals("new in place") || stopAt.equals("done") ? "new" : "old", code);
        }
    }

    @Test
    public void recoveryTellsWhatItDid() throws IOException {
        // Catches a recovery that does the wrong one of its two repairs for a state: an old mod put back when the new
        // one is already in place (the new mod lost), or an old one deleted when it is the only mod.
        ModSwap swap = new ModSwap(installed, staging, old);
        assertEquals(ModSwap.Recovery.NOTHING, swap.recover());
        mod(old, "old");
        assertEquals(ModSwap.Recovery.OLD_RESTORED, swap.recover());
        assertWhole("old");
        mod(old, "older");
        assertEquals(ModSwap.Recovery.OLD_REMOVED, swap.recover());
        assertWhole("old");
    }

    @Test
    public void aStagingFolderLeftByAStoppedRunIsTheHomeScreensHalfDoneJobNotRecoverysToDelete() throws IOException {
        // Catches a recovery at start-up that deletes the staging folder (the home screen would never offer to resume)
        // or one that lets a half-made staging folder in as the mod.
        mod(installed, "old");
        mod(staging, "half");
        ModSwap swap = new ModSwap(installed, staging, old);
        assertEquals(ModSwap.Recovery.NOTHING, swap.recover());
        assertTrue(swap.stagingLeft());
        assertEquals("old", code(installed));
        assertEquals(HomeModel.Job.HALF_DONE, UpdatePlan.jobLeft(null, "1.0", false, swap.stagingLeft()));
        // the next run starts with an empty one
        File fresh = swap.begin();
        assertEquals(0, fresh.list().length);
        swap.discardStaging();
        assertFalse(swap.stagingLeft());
        assertEquals(HomeModel.Job.NONE, UpdatePlan.jobLeft(null, "1.0", false, swap.stagingLeft()));
    }

    @Test
    public void aRunAfterAStopFinishesTheSwap() throws IOException {
        // Catches begin() not recovering first: a run that starts when the old mod is still aside would find no mod.
        mod(old, "old");
        ModSwap swap = new ModSwap(installed, staging, old);
        mod(swap.begin(), "new");
        assertEquals("old", code(installed));
        swap.commit();
        assertWhole("new");
    }

    @Test
    public void commitWithoutAStagingFolderChangesNothing() throws IOException {
        // Catches a commit with nothing to put in place that moves the installed mod away.
        mod(installed, "old");
        try {
            new ModSwap(installed, staging, old).commit();
            fail("no staging folder");
        } catch (IOException expected) {
            assertWhole("old");
        }
    }

    @Test
    public void theThreeFoldersMustShareOneFolderAndBeThree() {
        // Catches a swap built over folders on two file systems (the rename would copy, and a stop would leave half).
        for (File[] bad : new File[][] {{installed, installed, old}, {installed, staging, staging},
                {installed, new File(tmp.getRoot(), "elsewhere/staging"), old}}) {
            try {
                new ModSwap(bad[0], bad[1], bad[2]);
                fail("accepted " + Arrays.toString(bad));
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void theFoldersAreNamedForTheModsFolderAndTheModListLeavesTheLeftoversOut() {
        // Catches a staging or old folder the game would load as a mod, or in another folder than the game reads.
        File home = new File(tmp.getRoot(), "home");
        assertEquals(new File(home, "user/load/mods/Heavenfall"), LauncherMod.modDir(home));
        List<String> dotted = new ArrayList<>();
        for (File f : Arrays.asList(LauncherMod.stagingDir(home), LauncherMod.oldDir(home))) {
            assertEquals(LauncherMod.modsFolder(home), f.getParentFile());
            dotted.add(f.getName());
            assertTrue("mods/mod_list.cpp leaves out a name starting with '.'", f.getName().startsWith("."));
        }
        assertEquals(3, new java.util.HashSet<>(Arrays.asList(LauncherMod.NAME, dotted.get(0), dotted.get(1))).size());
    }
}
