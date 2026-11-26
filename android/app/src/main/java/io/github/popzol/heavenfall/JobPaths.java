package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;

import org.json.JSONObject;

/**
 * Where an update job reads and writes: the launcher's own places ({@link Places}, fixed by the app's code and never
 * read from a file) and the player's three dumps, which are only read. A job saved to {@code job.json} keeps only the
 * dumps' paths, and they are taken back only when each is a file right in the game folder the player chose.
 */
public final class JobPaths {
    /** The launcher's own places: the same for every job, made by the app's code (on the phone: {@code Locations}). */
    public static final class Places {
        /** The release files being downloaded (set, shared file, APK) and their ".part" files. */
        public final File downloads;
        /** The folder the mod is made in before it replaces the installed one (the mod folder's sibling). */
        public final File staging;
        /** The installed mod's folder. */
        public final File modDir;
        /** {@code installed.json} and {@code resume.json}. */
        public final File installedFile;
        public final File resumeFile;
        /** The job's own record ({@link UpdateRequest}), so the app can go on with it after it replaced itself. */
        public final File jobFile;
        /** The folder the code library is built into and the game loads it from. */
        public final File libraryDir;

        public Places(File downloads, File staging, File modDir, File installedFile, File resumeFile, File jobFile,
                      File libraryDir) {
            this.downloads = require(downloads, "downloads");
            this.staging = require(staging, "staging");
            this.modDir = require(modDir, "mod_dir");
            this.installedFile = require(installedFile, "installed_file");
            this.resumeFile = require(resumeFile, "resume_file");
            this.jobFile = require(jobFile, "job_file");
            this.libraryDir = require(libraryDir, "library_dir");
        }
    }

    public final Places places;
    public final File downloads;
    public final File staging;
    public final File modDir;
    public final File installedFile;
    public final File resumeFile;
    public final File jobFile;
    /** The player's game, the 1.3.0 update and (optional, null when none) MH XX. */
    public final File gameDump;
    public final File updateDump;
    public final File xxDump;
    public final File libraryDir;

    public JobPaths(Places places, File gameDump, File updateDump, File xxDump) {
        this.places = require(places, "places");
        this.downloads = places.downloads;
        this.staging = places.staging;
        this.modDir = places.modDir;
        this.installedFile = places.installedFile;
        this.resumeFile = places.resumeFile;
        this.jobFile = places.jobFile;
        this.libraryDir = places.libraryDir;
        this.gameDump = require(gameDump, "game_dump");
        this.updateDump = require(updateDump, "update_dump");
        this.xxDump = xxDump;
    }

    private static <T> T require(T f, String name) {
        if (f == null) throw new IllegalArgumentException("job paths: no " + name);
        return f;
    }

    /** The dumps, as {@code job.json} keeps them (nothing else of the paths is written). */
    JSONObject dumpsJson() {
        JSONObject o = new JSONObject();
        Json.put(o, "game", gameDump.getPath());
        Json.put(o, "update", updateDump.getPath());
        if (xxDump != null) Json.put(o, "xx", xxDump.getPath());
        return o;
    }

    /**
     * The paths of a saved job: the launcher's {@code places} and the dumps in {@code dumps}, or null when a dump is
     * missing or is not a file right in {@code gameFolder} (the folder the player chose now; null: none).
     */
    static JobPaths fromJson(JSONObject dumps, Places places, File gameFolder) {
        if (dumps == null || places == null || gameFolder == null) return null;
        File game = dump(Json.string(dumps, "game"), gameFolder);
        File update = dump(Json.string(dumps, "update"), gameFolder);
        String xxPath = Json.string(dumps, "xx");
        File xx = xxPath == null ? null : dump(xxPath, gameFolder);
        if (game == null || update == null || (xxPath != null && xx == null)) return null;
        return new JobPaths(places, game, update, xx);
    }

    /** {@code path} when it names a file right in {@code folder}, else null. */
    static File dump(String path, File folder) {
        if (path == null || path.isEmpty()) return null;
        try {
            File f = new File(path).getCanonicalFile();
            File parent = f.getParentFile();
            return f.isFile() && parent != null && parent.equals(folder.getCanonicalFile()) ? f : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
