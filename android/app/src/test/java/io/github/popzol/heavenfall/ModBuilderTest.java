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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The job around the native layer, with a stand-in for the layer: what is put in place, and when. */
public class ModBuilderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File installed, staging, old;
    private ModSwap swap;
    private final List<Integer> closed = new ArrayList<>();

    @Before
    public void folders() throws IOException {
        File mods = tmp.newFolder("mods");
        installed = new File(mods, "Heavenfall");
        staging = new File(mods, ".heavenfall-staging");
        old = new File(mods, ".heavenfall-old");
        swap = new ModSwap(installed, staging, old);
        write(new File(installed, "exefs/code.bin"), "old code");
        write(new File(installed, "exheader.bin"), "old header");
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private ModBuilder.Input input(int fd) {
        return () -> new ModBuilder.Handle() {
            @Override
            public int fd() {
                return fd;
            }

            @Override
            public void close() {
                closed.add(fd);
            }
        };
    }

    private ModBuilder.Request request(boolean withXx) {
        return new ModBuilder.Request(input(10), input(11), input(12), withXx ? input(13) : null, input(14), swap, true);
    }

    /** A stand-in for libheavenfall.so: writes the files the real one would, then answers `answer`. */
    private final class Fake implements ModBuilder.Backend {
        String codeText = "new code";
        String answer;           // null: a success whose hash is that of codeText
        boolean writeFiles = true;
        long[] progress = {0, 50, 100};
        int[] seenFds;

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
            seenFds = new int[] {setFd, gameFd, updateFd, xxFd, sharedFd};
            File out = new File(new String(outDir, StandardCharsets.UTF_8));
            try {
                if (writeFiles) {
                    write(new File(out, "exefs/code.bin"), codeText);
                    write(new File(out, "exheader.bin"), "new header");
                    write(new File(out, "romfs/data/a.arc"), "new file");
                }
                for (long done : progress) {
                    if (sink.update(done, 100)) return result(ApplyError.CANCEL, ApplyError.STAGE_WRITE, "");
                }
                if (answer != null) return answer;
                return result(0, 0, InstalledStore.sha256(new File(out, "exefs/code.bin")));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String result(int rc, int stage, String sha) {
        return "rc=" + rc + "\nstage=" + stage + "\nerrno=0\nsource=-1\npath=\nfiles=3\nbytes=30\ncode_sha256=" + sha + "\n";
    }

    private void assertOldModIntact() throws IOException {
        assertEquals("old code", read(new File(installed, "exefs/code.bin")));
        assertFalse(new File(installed, "romfs").exists());
        assertFalse("the staging folder is thrown away", staging.exists());
        assertFalse(old.exists());
    }

    @Test
    public void aSuccessfulRunReplacesTheModAndTellsTheCodeHash() throws Exception {
        // Catches the new mod not put in place, the old one's files left in it, or the code's hash (what the library is
        // built from) wrong; and the descriptors: -1 for a file not given, every opened one closed.
        Fake fake = new Fake();
        List<String> told = new ArrayList<>();
        ModBuilder.Made made = ModBuilder.apply(fake, request(false), (done, total) -> told.add(done + "/" + total), null);
        assertEquals("new code", read(new File(installed, "exefs/code.bin")));
        assertEquals("new file", read(new File(installed, "romfs/data/a.arc")));
        assertFalse(staging.exists());
        assertFalse(old.exists());
        assertEquals(InstalledStore.sha256(new File(installed, "exefs/code.bin")), made.codeSha256);
        assertEquals(3, made.files);
        assertEquals("[0/100, 50/100, 100/100]", told.toString());
        assertEquals("[10, 11, 12, -1, 14]", java.util.Arrays.toString(fake.seenFds));
        assertEquals("[10, 11, 12, 14]", closed.toString());
    }

    @Test
    public void mhXxIsPassedWhenGiven() throws Exception {
        // Catches the XX descriptor lost on its way to the layer.
        Fake fake = new Fake();
        ModBuilder.apply(fake, request(true), null, null);
        assertEquals("[10, 11, 12, 13, 14]", java.util.Arrays.toString(fake.seenFds));
        assertEquals("[10, 11, 12, 13, 14]", closed.toString());
    }

    @Test
    public void aFailureLeavesTheOldModAndSaysWhy() throws Exception {
        // Catches a layer that stopped (a game with one changed byte) putting its half-made folder in place, or its
        // reason lost on the way to the player.
        Fake fake = new Fake();
        fake.answer = result(ApplyError.HASH, ApplyError.STAGE_GAME, "");
        try {
            ModBuilder.apply(fake, request(false), null, null);
            fail("must fail");
        } catch (ModBuilder.Failed e) {
            assertEquals(ApplyError.Failure.GAME_DIFFERENT, e.error.failure);
            assertEquals("hf_apply_game_different", e.error.reason().key);
        }
        assertOldModIntact();
        assertEquals("[10, 11, 12, 14]", closed.toString());
    }

    @Test
    public void aCancelFromTheSinkStopsItAndLeavesTheOldMod() throws Exception {
        // Catches the player's cancel not reaching the layer, or a cancelled run replacing the mod.
        AtomicBoolean cancel = new AtomicBoolean();
        Fake fake = new Fake();
        try {
            ModBuilder.apply(fake, request(false), (done, total) -> {
                if (done >= 50) cancel.set(true);
            }, cancel::get);
            fail("must be cancelled");
        } catch (ModBuilder.Failed e) {
            assertTrue(e.error.cancelled());
        }
        assertOldModIntact();
    }

    @Test
    public void aCancelThatCameAfterTheLayerFinishedStillStopsBeforeTheSwap() throws Exception {
        // Catches a cancel at the very end being ignored: the mod would be swapped in after the player said stop.
        AtomicBoolean cancel = new AtomicBoolean();
        Fake fake = new Fake();
        fake.progress = new long[0];
        try {
            ModBuilder.apply(fake, request(false), null, () -> {
                cancel.set(true);
                return true;
            });
            fail("must be cancelled");
        } catch (ModBuilder.Failed e) {
            assertTrue(e.error.cancelled());
        }
        assertOldModIntact();
    }

    @Test
    public void aCodeFileThatIsNotWhatTheLayerHashedIsRefused() throws Exception {
        // Catches a storage that wrote other bytes than the layer hashed (or a layer that hashed nothing) being put in
        // place: the code library would be built from the wrong code.
        Fake fake = new Fake();
        fake.answer = result(0, 0, "ab".repeat(32));
        try {
            ModBuilder.apply(fake, request(false), null, null);
            fail("must be refused");
        } catch (ModBuilder.Failed e) {
            assertEquals(ApplyError.Failure.OUTPUT_DIFFERS, e.error.failure);
        }
        assertOldModIntact();
        fake.answer = result(0, 0, "");
        try {
            ModBuilder.apply(fake, request(false), null, null);
            fail("no hash: must be refused");
        } catch (ModBuilder.Failed e) {
            assertEquals(ApplyError.Failure.OUTPUT_DIFFERS, e.error.failure);
        }
        assertOldModIntact();
    }

    @Test
    public void aSuccessWithoutTheExecutableOrExheaderIsRefused() throws Exception {
        // Catches a "success" that wrote nothing (a layer that skipped its files) replacing a working mod with nothing.
        Fake fake = new Fake();
        fake.writeFiles = false;
        fake.answer = result(0, 0, "ab".repeat(32));
        try {
            ModBuilder.apply(fake, request(false), null, null);
            fail("must be refused");
        } catch (ModBuilder.Failed e) {
            assertEquals(ApplyError.Failure.OUTPUT_DIFFERS, e.error.failure);
        }
        assertOldModIntact();
    }

    @Test
    public void aFileThatCannotBeOpenedStopsBeforeAnythingIsMade() throws Exception {
        // Catches a vanished game file (the card was taken out) failing as a native error, or after the staging folder
        // is made; and the files already opened left open.
        ModBuilder.Input gone = () -> {
            throw new IOException("the card is out");
        };
        ModBuilder.Request r = new ModBuilder.Request(input(10), gone, input(12), null, null, swap, true);
        try {
            ModBuilder.apply(new Fake(), r, null, null);
            fail("must fail");
        } catch (ModBuilder.Failed e) {
            assertEquals(ApplyError.Failure.READ_FAILED, e.error.failure);
            assertEquals(ApplyError.STAGE_GAME, e.error.stage);
        }
        assertEquals("[10]", closed.toString());
        assertFalse(staging.exists());
        assertEquals("old code", read(new File(installed, "exefs/code.bin")));
    }

    @Test
    public void anAnswerThatIsNoAnswerIsAnInternalFailureNotACrash() throws Exception {
        // Catches a native layer that answered with garbage taking the app down, or replacing the mod.
        Fake fake = new Fake();
        fake.answer = "this is not key=value lines";
        try {
            ModBuilder.apply(fake, request(false), null, null);
            fail("must fail");
        } catch (ModBuilder.Failed e) {
            assertEquals(ApplyError.Failure.INTERNAL, e.error.failure);
        }
        assertOldModIntact();
    }

    @Test
    public void aSwapThatFailsIsReportedAndTheOldModComesBack() throws Exception {
        // Catches a swap stopped between its renames (a crash) leaving the run without a mod once the next start recovers.
        ModSwap crashing = new ModSwap(installed, staging, old, name -> {
            if (name.equals("old aside")) throw new IOException("stopped");
        });
        ModBuilder.Request r = new ModBuilder.Request(input(10), input(11), input(12), null, null, crashing, true);
        try {
            ModBuilder.apply(new Fake(), r, null, null);
            fail("must fail");
        } catch (ModBuilder.Failed e) {
            assertEquals(ApplyError.Failure.SWAP_FAILED, e.error.failure);
        }
        new ModSwap(installed, staging, old).recover();
        assertEquals("old code", read(new File(installed, "exefs/code.bin")));
        assertFalse(old.exists());
    }

    @Test
    public void aLeftoverStagingFolderDoesNotReachTheNewMod() throws Exception {
        // Catches the files of a half-made earlier run (a file the new mod does not have) staying in the new mod.
        write(new File(staging, "romfs/stale.bin"), "from the run that was cut");
        ModBuilder.apply(new Fake(), request(false), null, null);
        assertFalse(new File(installed, "romfs/stale.bin").exists());
        assertTrue(new File(installed, "romfs/data/a.arc").isFile());
    }
}
