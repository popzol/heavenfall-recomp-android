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

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** F2 and F3: what a saved job may name, what is left half-done, and what Discard removes. */
public class JobStateTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private JobPaths.Places places;
    private File gameFolder;
    private File game;
    private File update;

    @Before
    public void setUp() throws IOException {
        File home = tmp.newFolder("private");
        places = new JobPaths.Places(new File(home, "downloads"), new File(home, "staging"), new File(home, "mod"),
                new File(home, "installed.json"), new File(home, "resume.json"), new File(home, "job.json"),
                new File(home, "lib"));
        gameFolder = tmp.newFolder("game folder");
        game = write(new File(gameFolder, "game.3ds"), "g");
        update = write(new File(gameFolder, "update.cia"), "u");
    }

    private static File write(File f, String text) throws IOException {
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private String job(String gamePath, String updatePath) throws Exception {
        JSONObject o = new JSONObject(new UpdateRequest("heavenfall_beta", new JobPaths(places, game, update, null),
                "1.0.0", false, false, false, 0).toJson());
        JSONObject dumps = o.getJSONObject("dumps");
        dumps.put("game", gamePath);
        dumps.put("update", updatePath);
        return o.toString();
    }

    /**
     * Catches a job.json that sends the job elsewhere: a dump outside the game folder (any file the app can read
     * would be fed to the mod's build), a folder, a path through "..", and paths for the launcher's own places.
     */
    @Test
    public void aSavedJobNamesOnlyDumpsOfTheGameFolder() throws Exception {
        File outside = write(new File(tmp.getRoot(), "elsewhere/game.3ds"), "x");
        File sub = write(new File(gameFolder, "sub/game.3ds"), "x");
        String up = update.getPath();
        assertNull(UpdateRequest.tryParse(job(outside.getPath(), up), places, gameFolder));
        assertNull(UpdateRequest.tryParse(job(sub.getPath(), up), places, gameFolder));
        assertNull(UpdateRequest.tryParse(job(gameFolder.getPath(), up), places, gameFolder));
        assertNull(UpdateRequest.tryParse(job(new File(gameFolder, "missing.3ds").getPath(), up), places, gameFolder));
        assertNull(UpdateRequest.tryParse(job(gameFolder.getPath() + "/../elsewhere/game.3ds", up), places, gameFolder));
        assertNull(UpdateRequest.tryParse(job("", up), places, gameFolder));
        assertNull("no game folder chosen now", UpdateRequest.tryParse(job(game.getPath(), up), places, null));

        JSONObject hostile = new JSONObject(job(game.getPath(), up));
        hostile.put("downloads", outside.getParent());
        hostile.put("mod_dir", outside.getParent());
        hostile.put("library_dir", outside.getParent());
        UpdateRequest r = UpdateRequest.tryParse(hostile.toString(), places, gameFolder);
        assertNotNull(r);
        assertEquals("the places are the launcher's, never the file's", places.downloads, r.paths.downloads);
        assertEquals(places.modDir, r.paths.modDir);
        assertEquals(places.libraryDir, r.paths.libraryDir);
        assertEquals(game.getCanonicalFile(), r.paths.gameDump);
    }

    /** F3: a download cut short is not a half-done job; a staging folder (the mod being made) is. */
    @Test
    public void halfDoneOnlyPastTheDownloads() throws Exception {
        assertEquals(HomeModel.Job.NONE, JobState.left(places, gameFolder, "1.0.0"));
        Records.write(places.jobFile, job(game.getPath(), update.getPath()));
        write(new File(places.downloads, "set.hfpatch.part"), "half");
        assertEquals(HomeModel.Job.NONE, JobState.left(places, gameFolder, "1.0.0"));
        write(new File(places.staging, "exefs/code.bin"), "c");
        assertEquals(HomeModel.Job.HALF_DONE, JobState.left(places, gameFolder, "1.0.0"));
        Files.write(places.jobFile.toPath(), "{\"format\":".getBytes(StandardCharsets.UTF_8));
        assertEquals("an unparsable job.json is no job (and no crash)", HomeModel.Job.NONE,
                JobState.left(places, gameFolder, "1.0.0"));
    }

    /** F3: Discard removes what the job left and never the installed mod, its record or the library. */
    @Test
    public void discardKeepsTheInstalledGame() throws Exception {
        Records.write(places.jobFile, job(game.getPath(), update.getPath()));
        Records.write(places.resumeFile, "{}");
        write(new File(places.downloads, "set.hfpatch.part"), "half");
        write(new File(places.downloads, "shared.bin"), "whole");
        write(new File(places.staging, "exefs/code.bin"), "new");
        File code = write(new File(places.modDir, "exefs/code.bin"), "installed");
        Records.write(places.installedFile, "{\"format\":2}");
        File lib = write(new File(places.libraryDir, "libmh4u_code_native.so"), "lib");
        assertTrue(JobState.discard(places));
        assertFalse(places.jobFile.exists());
        assertFalse(places.resumeFile.exists());
        assertFalse(places.staging.exists());
        String[] left = places.downloads.list();
        assertTrue(left == null || left.length == 0);
        assertTrue(code.isFile() && places.installedFile.isFile() && lib.isFile() && game.isFile());
        assertEquals(HomeModel.Job.NONE, JobState.left(places, gameFolder, "1.0.0"));
        assertTrue("discarding nothing is fine", JobState.discard(places));
    }
}
