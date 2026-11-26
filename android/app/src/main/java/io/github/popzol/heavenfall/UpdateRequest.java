package io.github.popzol.heavenfall;

import java.io.File;

import org.json.JSONObject;

/**
 * What the player asked for with "Update to X", Install, Resume, Reinstall or Build: the edition, where things are,
 * whether MH XX goes in, and whether the mod or the library is made again even when they are current. The job asks
 * GitHub for the latest releases itself and makes the plan from them, so a job started again after the app replaced
 * itself (the request is kept in {@link JobPaths#jobFile}) chooses its steps from what is found then.
 *
 * <p>The saved request holds no path of the launcher's: a job read back gets the places from the app's code, and
 * only the dumps' paths from the file, each taken only when it is a file right in the game folder ({@link JobPaths}).
 */
public final class UpdateRequest {
    /** 2: only the dumps' paths are kept (1 kept every path: read as no saved job). */
    public static final int FORMAT = 2;

    public final String editionId;
    public final JobPaths paths;
    /** The running app's version. */
    public final String appVersion;
    /** The player copied MH XX and wants the set with it (used when the release carries that set). */
    public final boolean xx;
    /** More > Reinstall and More > Rebuild. */
    public final boolean forceMod;
    public final boolean forceBuild;
    /** Where the bar starts (0..999): after the app replaced itself, where it stood. */
    public final int basePermille;

    public UpdateRequest(String editionId, JobPaths paths, String appVersion, boolean xx, boolean forceMod,
                         boolean forceBuild, int basePermille) {
        if (editionId == null || editionId.isEmpty()) throw new IllegalArgumentException("update request: no edition");
        if (paths == null) throw new IllegalArgumentException("update request: no paths");
        if (!Version.isValid(appVersion)) throw new IllegalArgumentException("update request: the app version is not a version");
        if (basePermille < 0 || basePermille > 999) throw new IllegalArgumentException("update request: base out of range");
        this.editionId = editionId;
        this.paths = paths;
        this.appVersion = appVersion;
        this.xx = xx;
        this.forceMod = forceMod;
        this.forceBuild = forceBuild;
        this.basePermille = basePermille;
    }

    /**
     * The same request going on after an interruption, in the app that is running now ({@code runningApp}): from
     * {@code note} when there is one (the bar's place and the MH XX choice of the interrupted job), else from the start.
     * The mod and the library are not forced again.
     */
    public UpdateRequest resumed(Resume note, String runningApp) {
        return new UpdateRequest(editionId, paths, runningApp, note != null ? note.withXx : xx, false, false,
                note != null ? note.basePermille : 0);
    }

    /** Saves the request in {@link JobPaths#jobFile}: a job that was interrupted can be started again from it. */
    public void save() throws UpdateException {
        UpdateRunner.writeText(paths.jobFile, toJson());
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        Json.put(o, "format", FORMAT);
        Json.put(o, "edition", editionId);
        Json.put(o, "app_version", appVersion);
        Json.put(o, "xx", xx);
        Json.put(o, "force_mod", forceMod);
        Json.put(o, "force_build", forceBuild);
        Json.put(o, "base_permille", basePermille);
        Json.put(o, "dumps", paths.dumpsJson());
        return o.toString();
    }

    /**
     * The request in {@code json} with the launcher's {@code places} and the dumps it names in {@code gameFolder}, or
     * null when it is missing, not one this app understands, or a dump is not a file right in the game folder.
     */
    public static UpdateRequest tryParse(String json, JobPaths.Places places, File gameFolder) {
        JSONObject o = Json.object(json);
        if (o == null) return null;
        Long format = Json.integer(o, "format");
        Long base = Json.integer(o, "base_permille");
        Boolean xx = Json.bool(o, "xx");
        Boolean forceMod = Json.bool(o, "force_mod");
        Boolean forceBuild = Json.bool(o, "force_build");
        if (format == null || format != FORMAT || base == null || xx == null || forceMod == null || forceBuild == null) {
            return null;
        }
        JobPaths paths = JobPaths.fromJson(Json.child(o, "dumps"), places, gameFolder);
        if (paths == null) return null;
        try {
            return new UpdateRequest(Json.string(o, "edition"), paths, Json.string(o, "app_version"), xx, forceMod,
                    forceBuild, base.intValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@link #tryParse} of the saved request in {@code places.jobFile}. */
    public static UpdateRequest read(JobPaths.Places places, File gameFolder) {
        return tryParse(Records.read(places.jobFile), places, gameFolder);
    }
}
