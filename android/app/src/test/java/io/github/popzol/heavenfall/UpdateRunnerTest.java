package io.github.popzol.heavenfall;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


/** The whole job against a local server and stand-ins for the phone's parts (the installer, the applier, the builder). */
public class UpdateRunnerTest {
    private static final String CODE = Support.sha('c');
    private static final String LIB_ID = "tools-1";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeServer server;
    private Editions editions;
    private Editions.Edition beta;
    private JobPaths paths;
    private final List<JobProgress> seen = new ArrayList<>();
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    // the phone's parts
    private final List<String> calls = new ArrayList<>();
    private UpdateException applyFails;
    private UpdateException buildFails;
    private UpdateException installFails;
    private String appliedCode = CODE;
    private boolean libraryPresent;
    private String libraryId = "";
    private File installedApk;

    @Before
    public void start() throws IOException {
        server = new FakeServer();
        editions = Support.editions();
        beta = editions.first();
        File root = tmp.newFolder("job");
        paths = new JobPaths(new File(root, "downloads"), new File(root, "staging"), new File(root, "mod"),
                new File(root, "installed.json"), new File(root, "resume.json"), new File(root, "job.json"),
                new File(root, "game.cci"), new File(root, "update.cia"), null, new File(root, "lib"));
    }

    @After
    public void stop() {
        server.close();
    }

    // ---- the releases

    private static byte[] bytes(int n, int seed) {
        return DownloaderTest.data(n, seed);
    }

    /** Publishes {@code repo}'s latest release {@code tag} with these files. */
    private void publish(String repo, String tag, Map<String, byte[]> assets) {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : assets.entrySet()) {
            String url = Support.url(repo, tag, e.getKey());
            list.add(Support.asset(e.getKey(), String.valueOf(e.getValue().length),
                    "\"sha256:" + DownloaderTest.sha(e.getValue()) + "\"", url));
            server.file("/github/" + repo + "/releases/download/" + tag + "/" + e.getKey(), e.getValue());
            files.put(repo + "/" + tag + "/" + e.getKey(), e.getValue());
        }
        server.json("/api/repos/" + repo + "/releases/latest", Support.release(tag, list.toArray(new String[0])));
    }

    private Map<String, byte[]> modFiles(boolean xx) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put(beta.setName("usa", false), bytes(300_000, 21));
        if (xx) m.put(beta.setName("usa", true), bytes(310_000, 22));
        m.put(beta.sharedFile, bytes(500_000, 23));
        return m;
    }

    private void publishMod(String tag, boolean xx) {
        publish(beta.repository, tag, modFiles(xx));
    }

    private void publishApp(String tag) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put(editions.app().apk, bytes(200_000, 24));
        publish(editions.app().repository, tag, m);
    }

    // ---- the parts

    private UpdateRunner runner() {
        ModApplier applier = (p, set, shared, xx, control, progress) -> {
            calls.add("apply " + set.getName() + (shared != null ? " +shared" : ""));
            progress.fraction(0.5);
            try {
                if (!p.staging.isDirectory()) Files.createDirectories(p.staging.toPath());
                Files.write(new File(p.staging, "half.bin").toPath(), new byte[] {1});
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            if (applyFails != null) throw applyFails;
            progress.fraction(1.0);
            return new ModApplier.Applied(appliedCode);
        };
        LibraryBuilder builder = new LibraryBuilder() {
            @Override
            public State state() {
                return new State(libraryPresent, libraryId);
            }

            @Override
            public String wantedId() {
                return LIB_ID;
            }

            @Override
            public String build(JobPaths p, Control control, Fraction progress) throws UpdateException {
                calls.add("build");
                progress.fraction(0.5);
                if (buildFails != null) throw buildFails;
                libraryPresent = true;
                libraryId = LIB_ID;
                return LIB_ID;
            }
        };
        AppInstaller installer = (apk, control, waiting) -> {
            calls.add("install " + apk.getName());
            installedApk = apk;
            installedApkBytes = readQuietly(apk);
            if (installFails != null) throw installFails;
        };
        UpdateRunner.Parts parts = new UpdateRunner.Parts(new ReleaseClient(server.map()), new Downloader(server.map(),
                d -> Long.MAX_VALUE), installer, applierOverride != null ? applierOverride : applier, builder, editions,
                Support.tuning(), null);
        return new UpdateRunner(parts, seen::add);
    }

    /** What a job ended as, and every request the server saw: a failure then says what the server was asked and what it answered. */
    private String why(JobProgress end) {
        return end + " requests=" + server.requests;
    }

    private ModApplier applierOverride;
    private byte[] installedApkBytes;

    private static byte[] readQuietly(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    private UpdateRequest request(String appVersion, boolean xx) {
        return new UpdateRequest("heavenfall_beta", paths, appVersion, xx, false, false, 0);
    }

    private JobProgress run(UpdateRequest r) {
        return runner().run(r, new Control());
    }

    private void writeInstalled(String modTag, boolean xx, boolean libraryGood) throws IOException {
        Installed i = new Installed("heavenfall_beta", modTag, beta.setName("usa", xx), Support.sha('a'), xx, CODE,
                libraryGood ? LIB_ID : "", libraryGood ? CODE : "");
        Files.write(paths.installedFile.toPath(), i.toJson().getBytes(StandardCharsets.UTF_8));
        libraryPresent = libraryGood;
        libraryId = libraryGood ? LIB_ID : "";
    }

    private static void assertMonotonic(List<JobProgress> list) {
        int last = 0;
        for (JobProgress p : list) {
            if (p.phase == JobProgress.Phase.FAILED || p.phase == JobProgress.Phase.CANCELLED) continue;
            assertTrue("the bar went back: " + last + " -> " + p.permille, p.permille >= last);
            last = p.permille;
        }
    }

    // ---- the cases

    /** A first install: the set and the shared file, applying, building; installed.json says what was made. */
    @Test
    public void aFreshInstallDoesEveryStep() throws Exception {
        publishMod("v0.3", false);
        JobProgress end = run(request("1.0.0", false));
        assertEquals(why(end), JobProgress.Phase.DONE, end.phase);
        assertEquals(1000, end.permille);
        assertMonotonic(seen);
        assertEquals(java.util.Arrays.asList("apply " + beta.setName("usa", false) + " +shared", "build"), calls);
        Installed i = Installed.parse(new String(Files.readAllBytes(paths.installedFile.toPath()), StandardCharsets.UTF_8));
        assertEquals("v0.3", i.modVersion);
        assertEquals(beta.setName("usa", false), i.setName);
        assertEquals(DownloaderTest.sha(files.get(beta.repository + "/v0.3/" + i.setName)), i.setSha256);
        assertFalse(i.withXx);
        assertEquals(CODE, i.codeSha256);
        assertEquals(LIB_ID, i.libraryId);
        assertEquals(HomeModel.Library.GOOD, i.libraryState(true, LIB_ID, LIB_ID));
        assertEquals("downloads are removed once applied", 0, paths.downloads.list().length);
        assertFalse(paths.staging.exists());
        assertFalse("a finished job leaves no saved request", paths.jobFile.exists());
        assertFalse(paths.resumeFile.exists());
        java.util.Set<UpdatePlan.StepKind> kinds = new java.util.HashSet<>();
        for (JobProgress p : seen) if (p.step != null) kinds.add(p.step);
        assertTrue(kinds.containsAll(java.util.Arrays.asList(UpdatePlan.StepKind.SET, UpdatePlan.StepKind.SHARED,
                UpdatePlan.StepKind.APPLY, UpdatePlan.StepKind.BUILD)));
    }

    /** MH XX copied and the release has the set with it: that set goes in, and the record says so. */
    @Test
    public void theSetWithMhXxIsUsedWhenAsked() throws Exception {
        publishMod("v0.3", true);
        JobProgress end = run(request("1.0.0", true));
        assertEquals(why(end), JobProgress.Phase.DONE, end.phase);
        assertEquals("apply " + beta.setName("usa", true) + " +shared", calls.get(0));
        Installed i = Installed.parse(new String(Files.readAllBytes(paths.installedFile.toPath()), StandardCharsets.UTF_8));
        assertTrue(i.withXx);
    }

    /** MH XX asked for but the release has no such set: the plain set goes in, as the home screen promised. */
    @Test
    public void withoutAnXxSetThePlainOneIsUsed() throws Exception {
        publishMod("v0.3", false);
        JobProgress end = run(request("1.0.0", true));
        assertEquals(JobProgress.Phase.DONE, end.phase);
        assertEquals("apply " + beta.setName("usa", false) + " +shared", calls.get(0));
    }

    /** Applying fails: the staging folder goes, the record is not written, the downloads stay and the next run reuses them. */
    @Test
    public void aFailedApplyLeavesThingsReadyToTryAgain() throws Exception {
        publishMod("v0.3", false);
        applyFails = new UpdateException(UpdateFailure.APPLY_FAILED, "boom");
        JobProgress end = run(request("1.0.0", false));
        assertEquals(JobProgress.Phase.FAILED, end.phase);
        assertEquals("hf_fail_apply_failed", end.failureReason.key);
        assertNotNull(end.failureAdvice);
        assertFalse(paths.staging.exists());
        assertFalse("no record before the mod was applied", paths.installedFile.exists());
        assertTrue(paths.jobFile.exists());
        String setPath = "/github/" + beta.repository + "/releases/download/v0.3/" + beta.setName("usa", false);
        assertEquals(1, server.count(setPath));
        applyFails = null;
        seen.clear();
        JobProgress again = run(request("1.0.0", false));
        assertEquals(why(again), JobProgress.Phase.DONE, again.phase);
        assertEquals("the verified download is not fetched again", 1, server.count(setPath));
        assertTrue(paths.installedFile.isFile());
    }

    /** Everything current and the library good: nothing is downloaded and the job is done at once. */
    @Test
    public void upToDateDoesNothing() throws Exception {
        publishMod("v0.3", false);
        publishApp("v1.0.0");
        writeInstalled("v0.3", false, true);
        JobProgress end = run(request("1.0.0", false));
        assertEquals(JobProgress.Phase.DONE, end.phase);
        assertTrue(calls.isEmpty());
        for (String r : server.requests) assertFalse(r, r.contains("/releases/download/"));
    }

    /** A new mod with the same code keeps its library: the build step is dropped after applying. */
    @Test
    public void aNewModWithTheSameCodeNeedsNoBuild() throws Exception {
        publishMod("v0.3", false);
        writeInstalled("v0.2", false, true);
        JobProgress end = run(request("1.0.0", false));
        assertEquals(why(end), JobProgress.Phase.DONE, end.phase);
        assertEquals(java.util.Arrays.asList("apply " + beta.setName("usa", false) + " +shared"), calls);
        Installed i = Installed.parse(new String(Files.readAllBytes(paths.installedFile.toPath()), StandardCharsets.UTF_8));
        assertEquals("v0.3", i.modVersion);
        assertEquals(LIB_ID, i.libraryId);
    }

    /** A new mod with other code builds again, and the record then names the library. */
    @Test
    public void aNewModWithOtherCodeBuilds() throws Exception {
        publishMod("v0.3", false);
        writeInstalled("v0.2", false, true);
        appliedCode = Support.sha('d');
        JobProgress end = run(request("1.0.0", false));
        assertEquals(why(end), JobProgress.Phase.DONE, end.phase);
        assertEquals(java.util.Arrays.asList("apply " + beta.setName("usa", false) + " +shared", "build"), calls);
        Installed i = Installed.parse(new String(Files.readAllBytes(paths.installedFile.toPath()), StandardCharsets.UTF_8));
        assertEquals(Support.sha('d'), i.libraryCodeSha256);
    }

    /** A build that fails after applying leaves the new mod recorded and the library not good: the home offers Build. */
    @Test
    public void aFailedBuildLeavesTheModRecorded() throws Exception {
        publishMod("v0.3", false);
        buildFails = new UpdateException(UpdateFailure.BUILD_MEMORY, "killed");
        JobProgress end = run(request("1.0.0", false));
        assertEquals(JobProgress.Phase.FAILED, end.phase);
        assertEquals("hf_fail_build_memory", end.failureReason.key);
        assertEquals(UpdatePlan.StepKind.BUILD, end.step);
        Installed i = Installed.parse(new String(Files.readAllBytes(paths.installedFile.toPath()), StandardCharsets.UTF_8));
        assertEquals("v0.3", i.modVersion);
        assertTrue(i.libraryState(libraryPresent, libraryId, LIB_ID) != HomeModel.Library.GOOD);
    }

    /** The mod's release cannot be had and nothing is installed: the network's reason, not a vague one. */
    @Test
    public void noReleaseAndNothingInstalledSaysWhy() throws Exception {
        JobProgress end = run(request("1.0.0", false));   // the fake server knows no release: 404
        assertEquals(JobProgress.Phase.FAILED, end.phase);
        assertEquals("hf_fail_server", end.failureReason.key);
        assertEquals(java.util.Arrays.asList("404"), end.failureReason.args);
    }

    /** The app's update: the APK is downloaded, the note is written before it installs, and this job ends there. */
    @Test
    public void anAppUpdateWritesTheNoteAndEndsTheJob() throws Exception {
        publishApp("v1.1.0");
        publishMod("v0.3", false);
        writeInstalled("v0.2", false, true);
        JobProgress end = run(request("1.0.0", false));
        assertEquals(why(end), JobProgress.Phase.RESTARTING, end.phase);
        assertEquals(java.util.Arrays.asList("install " + editions.app().apk), calls);
        assertArrayEquals(files.get(editions.app().repository + "/v1.1.0/" + editions.app().apk), installedApkBytes);
        Resume note = Resume.tryParse(new String(Files.readAllBytes(paths.resumeFile.toPath()), StandardCharsets.UTF_8));
        assertNotNull(note);
        assertEquals("v1.1.0", note.appTarget);
        assertEquals("v0.3", note.modTarget);
        assertTrue("the bar goes on from here", note.basePermille > 0);
        // the new app starts: the saved request goes on from the note, without the APK step
        assertEquals(HomeModel.Job.HALF_DONE, JobState.left(paths.jobFile, "1.1.0"));
        UpdateRequest saved = JobState.savedRequest(paths.jobFile);
        assertNotNull(saved);
        seen.clear();
        calls.clear();
        JobProgress done = runner().run(saved.resumed(note, "1.1.0"), new Control());
        assertEquals(done.toString(), JobProgress.Phase.DONE, done.phase);
        assertTrue(seen.get(0).permille >= note.basePermille);
        assertEquals(java.util.Arrays.asList("apply " + beta.setName("usa", false) + " +shared"), calls);
        assertFalse(paths.resumeFile.exists());
        assertFalse("the downloaded APK is gone", new File(paths.downloads, editions.app().apk).exists());
    }

    /** A note whose install never happened (the app is still the old one) is forgotten, not resumed. */
    @Test
    public void aNoteThatWasNeverInstalledIsDropped() throws Exception {
        publishApp("v1.1.0");
        publishMod("v0.3", false);
        writeInstalled("v0.2", false, true);
        installFails = new UpdateException(UpdateFailure.SELF_ABORTED, "no");
        JobProgress end = run(request("1.0.0", false));
        assertEquals(JobProgress.Phase.FAILED, end.phase);
        assertEquals("hf_fail_self_aborted", end.failureReason.key);
        assertFalse("a refused install leaves no note", paths.resumeFile.exists());
        assertTrue("nothing after the APK ran", calls.size() == 1);
        // a note left by an install that did not happen (the process died) is dropped by the home's check
        Files.write(paths.resumeFile.toPath(), new Resume("heavenfall_beta", "v1.1.0", "v0.3", false, 100).toJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(HomeModel.Job.NONE, JobState.left(paths.jobFile, "1.0.0"));
        assertFalse(paths.resumeFile.exists());
    }

    /** A cancel in the middle of a download keeps the part; the next run asks only for the rest. */
    @Test
    public void cancelInTheMiddleOfADownloadResumes() throws Exception {
        publishMod("v0.3", false);
        String setName = beta.setName("usa", false);
        byte[] set = files.get(beta.repository + "/v0.3/" + setName);
        Control control = new Control();
        server.route("/github/" + beta.repository + "/releases/download/v0.3/" + setName, ex -> {
            ex.sendResponseHeaders(200, set.length);
            java.io.OutputStream out = ex.getResponseBody();
            out.write(set, 0, set.length / 2);
            out.flush();
            try {
                Thread.sleep(300);   // the client has read the first half by now, and waits for the rest
                control.cancel();
                Thread.sleep(300);
                out.write(set, set.length / 2, set.length - set.length / 2);
            } catch (IOException | InterruptedException ignored) {
                // the client has gone
            }
        });
        JobProgress end = runner().run(request("1.0.0", false), control);
        assertEquals(why(end), JobProgress.Phase.CANCELLED, end.phase);
        File part = Downloader.partOf(paths.downloads, setName);
        assertTrue(part.isFile());
        assertEquals(HomeModel.Job.HALF_DONE, JobState.left(paths.jobFile, "1.0.0"));
        long have = part.length();
        server.file("/github/" + beta.repository + "/releases/download/v0.3/" + setName, set);
        JobProgress again = run(request("1.0.0", false));
        assertEquals(why(again), JobProgress.Phase.DONE, again.phase);
        assertTrue("the rest was asked for by range: " + have + " in " + server.ranges, server.ranges.contains("bytes=" + have + "-"));
    }

    /** A job that never started its plan still ends in a snapshot the screens can show. */
    @Test
    public void idleSnapshotIsQuiet() {
        assertEquals(JobProgress.Phase.IDLE, JobProgress.IDLE.phase);
        assertFalse(JobProgress.IDLE.active());
        assertTrue(JobProgress.starting(10).active());
        assertNull(JobProgress.IDLE.failureReason);
        JobProgress interrupted = JobProgress.starting(300).interrupted();
        assertEquals(JobProgress.Phase.FAILED, interrupted.phase);
        assertEquals("hf_fail_interrupted", interrupted.failureReason.key);
    }

    /** The request is saved whole and read back (the app goes on with it after replacing itself). */
    @Test
    public void theRequestRoundTrips() {
        UpdateRequest r = new UpdateRequest("heavenfall_beta", new JobPaths(new File("d"), new File("s"), new File("m"),
                new File("i"), new File("r"), new File("j"), new File("g"), new File("u"), new File("x"), new File("l")),
                "1.2.3", true, false, true, 250);
        UpdateRequest back = UpdateRequest.tryParse(r.toJson());
        assertNotNull(back);
        assertEquals(r.toJson(), back.toJson());
        assertEquals(new File("x"), back.paths.xxDump);
        assertNull(UpdateRequest.tryParse("{\"format\": 1}"));
        assertNull(UpdateRequest.tryParse("nonsense"));
    }
}
