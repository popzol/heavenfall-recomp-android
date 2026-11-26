package io.github.popzol.heavenfall;

import java.io.File;

import org.json.JSONObject;

/** Where an update job reads and writes: the folders and files it is given, nothing else is touched. */
public final class JobPaths {
    /** The release files being downloaded (set, shared file, APK) and their ".part" files. */
    public final File downloads;
    /** The folder the mod is made in before it replaces the installed one. */
    public final File staging;
    /** The installed mod's folder. */
    public final File modDir;
    /** {@code installed.json} and {@code resume.json}. */
    public final File installedFile;
    public final File resumeFile;
    /** The job's own record ({@link UpdateRequest}), so the app can go on with it after it replaced itself. */
    public final File jobFile;
    /** The player's game, the 1.3.0 update and (optional, null when none) MH XX. */
    public final File gameDump;
    public final File updateDump;
    public final File xxDump;
    /** The folder the code library is built into. */
    public final File libraryDir;

    public JobPaths(File downloads, File staging, File modDir, File installedFile, File resumeFile, File jobFile,
                    File gameDump, File updateDump, File xxDump, File libraryDir) {
        this.downloads = require(downloads, "downloads");
        this.staging = require(staging, "staging");
        this.modDir = require(modDir, "mod_dir");
        this.installedFile = require(installedFile, "installed_file");
        this.resumeFile = require(resumeFile, "resume_file");
        this.jobFile = require(jobFile, "job_file");
        this.gameDump = require(gameDump, "game_dump");
        this.updateDump = require(updateDump, "update_dump");
        this.xxDump = xxDump;
        this.libraryDir = require(libraryDir, "library_dir");
    }

    private static File require(File f, String name) {
        if (f == null) throw new IllegalArgumentException("job paths: no " + name);
        return f;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        Json.put(o, "downloads", downloads.getPath());
        Json.put(o, "staging", staging.getPath());
        Json.put(o, "mod_dir", modDir.getPath());
        Json.put(o, "installed_file", installedFile.getPath());
        Json.put(o, "resume_file", resumeFile.getPath());
        Json.put(o, "job_file", jobFile.getPath());
        Json.put(o, "game_dump", gameDump.getPath());
        Json.put(o, "update_dump", updateDump.getPath());
        if (xxDump != null) Json.put(o, "xx_dump", xxDump.getPath());
        Json.put(o, "library_dir", libraryDir.getPath());
        return o;
    }

    /** The paths in {@code o}, or null when one is missing. */
    static JobPaths fromJson(JSONObject o) {
        if (o == null) return null;
        String[] keys = {"downloads", "staging", "mod_dir", "installed_file", "resume_file", "job_file", "game_dump",
                "update_dump", "library_dir"};
        for (String key : keys) {
            if (Json.string(o, key) == null) return null;
        }
        String xx = Json.string(o, "xx_dump");
        return new JobPaths(new File(Json.string(o, "downloads")), new File(Json.string(o, "staging")),
                new File(Json.string(o, "mod_dir")), new File(Json.string(o, "installed_file")),
                new File(Json.string(o, "resume_file")), new File(Json.string(o, "job_file")),
                new File(Json.string(o, "game_dump")), new File(Json.string(o, "update_dump")),
                xx == null ? null : new File(xx), new File(Json.string(o, "library_dir")));
    }
}
