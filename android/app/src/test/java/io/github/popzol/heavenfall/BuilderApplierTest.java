package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The job's applier over the mod's maker: what it puts in place, and the words of what it could not. */
public class BuilderApplierTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File home;
    private JobPaths paths;

    @Before
    public void folders() throws IOException {
        home = tmp.newFolder("home");
        File mod = LauncherMod.modDir(home);
        write(new File(mod, "exefs/code.bin"), "old code");
        paths = new JobPaths(new JobPaths.Places(new File(home, "dl"), LauncherMod.stagingDir(home), mod,
                new File(home, "installed.json"), new File(home, "resume.json"), new File(home, "job.json"),
                new File(home, "lib")), tmp.newFile("game"), tmp.newFile("update"), null);
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static ModBuilder.Input input(File f) {
        return () -> new ModBuilder.Handle() {
            @Override
            public int fd() {
                return 7;
            }

            @Override
            public void close() {
            }
        };
    }

    /** A layer that writes a new code file, then answers with the given code (0 for a success). */
    private static final class Layer implements ModBuilder.Backend {
        final int rc;
        final int stage;
        boolean sawShared;

        Layer(int rc, int stage) {
            this.rc = rc;
            this.stage = stage;
        }

        @Override
        public String identify(int fd) {
            return "";
        }

        @Override
        public String setInfo(int fd) {
            return "";
        }

        @Override
        public String apply(int setFd, int gameFd, int updateFd, int xxFd, int sharedFd, byte[] outDir, boolean verify,
                            ModBuilder.ProgressSink sink) {
            sawShared = sharedFd >= 0;
            File out = new File(new String(outDir, StandardCharsets.UTF_8));
            try {
                write(new File(out, "exefs/code.bin"), "new code");
                write(new File(out, "exheader.bin"), "new header");
                sink.update(100, 100);
                String sha = rc == 0 ? InstalledStore.sha256(new File(out, "exefs/code.bin")) : "";
                return "rc=" + rc + "\nstage=" + stage + "\nerrno=0\nsource=-1\npath=\nfiles=1\nbytes=8\ncode_sha256=" + sha
                        + "\n";
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private ModApplier.Applied apply(Layer layer) throws Exception {
        return new BuilderApplier(layer, BuilderApplierTest::input).apply(paths, tmp.newFile(), null, false, new Control(),
                f -> {
                });
    }

    private File code() {
        return new File(LauncherMod.modDir(home), "exefs/code.bin");
    }

    @Test
    public void theNewModGoesToTheFolderTheGameReadsAndItsCodeHashIsTold() throws Exception {
        // Catches the mod made somewhere the game's mod list does not look, or the hash the library is built from lost.
        Layer layer = new Layer(0, 0);
        ModApplier.Applied applied = apply(layer);
        assertEquals("new code", read(code()));
        assertEquals(InstalledStore.sha256(code()), applied.codeSha256);
        assertEquals("the mod's code digest is the one of the folder put in place", ModCode.digest(paths.modDir),
                applied.modCodeSha256);
        assertFalse(LauncherMod.stagingDir(home).exists());
        assertFalse(LauncherMod.oldDir(home).exists());
        assertFalse(layer.sawShared);
    }

    @Test
    public void aSetOfANewerAppIsTheJobsOwnRefusal() throws Exception {
        // Catches a set made for a newer app ending as a failed build (with no way to the app's update) in the job.
        try {
            apply(new Layer(ApplyError.NEWER, ApplyError.STAGE_SET));
            fail();
        } catch (UpdateException e) {
            assertNull(e.failure);
            assertEquals(UpdatePlan.Refusal.NEEDS_NEWER_APP.reasonKey(), e.reason.key);
        }
        assertEquals("old code", read(code()));
    }

    @Test
    public void aCancelIsACancelAndTheOldModStays() throws Exception {
        // Catches the player's cancel shown as a failure with a reason, or the old mod replaced by a half one.
        try {
            apply(new Layer(ApplyError.CANCEL, ApplyError.STAGE_WRITE));
            fail();
        } catch (UpdateException e) {
            assertTrue(e.cancelled());
        }
        assertFalse(LauncherMod.stagingDir(home).exists());
        assertEquals("old code", read(code()));
    }

    @Test
    public void aGameThatDoesNotMatchNamesItsOwnReason() throws Exception {
        // Catches every failure of the layer reaching the player as one generic text.
        try {
            apply(new Layer(ApplyError.ENCRYPTED, ApplyError.STAGE_GAME));
            fail();
        } catch (UpdateException e) {
            assertEquals(UpdateFailure.APPLY_FAILED, e.failure);
            assertNotNull(e.reason);
            assertEquals(ApplyError.Failure.GAME_ENCRYPTED.reasonKey, e.reason.key);
            assertEquals(ApplyError.Failure.GAME_ENCRYPTED.adviceKey, e.advice.key);
        }
    }
}
