package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** What may start the game, and how a job that ended is told. */
public class StartRulesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * F1 and F2: catches a start with a mod changed or gone since it was made, with no library, a library put in
     * place of the built one, or one built by other tools or from other code.
     */
    @Test
    public void theGameStartsOnlyWithTheRecordedModAndLibrary() throws IOException {
        File lib = tmp.newFile("libmh4u_code_native.so");
        Files.write(lib.toPath(), new byte[] {1, 2, 3});
        Installed i = Installed.fresh("beta", "0.2.3", "s", sha('a'), false, sha('b'), sha('c'))
                .withLibrary("tools-7", sha('c'), LibraryFile.of(lib));
        LibraryBuilder.State good = new LibraryBuilder.State(true, "tools-7", lib);
        assertNull(StartCheck.check(i, sha('c'), good, "tools-7"));
        assertEquals(StartCheck.Problem.NOT_INSTALLED, StartCheck.check(null, sha('c'), good, "tools-7"));
        assertEquals(StartCheck.Problem.MOD_CHANGED, StartCheck.check(i, sha('d'), good, "tools-7"));
        assertEquals(StartCheck.Problem.MOD_MISSING, StartCheck.check(i, null, good, "tools-7"));
        assertEquals(StartCheck.Problem.NO_LIBRARY,
                StartCheck.check(i, sha('c'), new LibraryBuilder.State(false, null, null), "tools-7"));
        assertEquals(StartCheck.Problem.OTHER_TOOLS, StartCheck.check(i, sha('c'), good, "tools-8"));
        Installed newCode = i.applied("0.2.4", "s", sha('a'), false, sha('d'), sha('e'));
        assertEquals(StartCheck.Problem.OTHER_MOD, StartCheck.check(newCode, sha('e'), good, "tools-7"));
        Files.write(lib.toPath(), new byte[] {7, 7, 7, 7});
        assertEquals(StartCheck.Problem.LIBRARY_UNKNOWN, StartCheck.check(i, sha('c'), good, "tools-7"));
        for (StartCheck.Problem p : StartCheck.Problem.values()) assertTrue(p.text.key.startsWith("hf_state_"));
    }

    /** F4: catches a release build that lets a caller naming the shell as its referrer skip the entry's checks. */
    @Test
    public void theShellStartsTheGameInDebugBuildsOnly() {
        assertTrue(EntryRule.mayStart(true, null, false));
        assertTrue(EntryRule.mayStart(true, "other.app", false));
        assertFalse(EntryRule.mayStart(false, EntryRule.SHELL, false));
        assertTrue(EntryRule.mayStart(false, EntryRule.SHELL, true));
        assertFalse(EntryRule.mayStart(false, "other.app", true));
        assertFalse(EntryRule.mayStart(false, null, true));
    }

    /**
     * Catches a job that ends with no word (the app's screen gone after it replaced itself and nothing says what
     * happened): every end says what happened, and the replacing app's own hand-over says nothing.
     */
    @Test
    public void everyEndIsTold() {
        assertArrayEquals(new String[] {UpdateTexts.DONE_TITLE, UpdateTexts.DONE_TEXT},
                UpdateTexts.ended(new JobProgress(JobProgress.Phase.DONE, 1000, new Text("hf_state_job_done"), 0, null,
                        0, 0, null, null)));
        UpdateException failure = new UpdateException(UpdateFailure.NETWORK, "x");
        assertEquals(UpdateTexts.FAILED_TITLE, UpdateTexts.ended(JobProgress.failed(failure, 10))[0]);
        UpdateException cancel = new UpdateException(UpdateFailure.CANCELLED, "x");
        assertArrayEquals(new String[] {UpdateTexts.CANCELLED_TITLE, UpdateTexts.CANCELLED_TEXT},
                UpdateTexts.ended(JobProgress.failed(cancel, 10)));
        assertNull(UpdateTexts.ended(JobProgress.IDLE));
        assertNull(UpdateTexts.ended(JobProgress.starting(0)));
        assertNull(UpdateTexts.ended(new JobProgress(JobProgress.Phase.RESTARTING, 500, new Text("x"), 0, null, 0, 0,
                null, null)));
    }
}
