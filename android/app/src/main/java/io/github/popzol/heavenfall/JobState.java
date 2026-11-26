package io.github.popzol.heavenfall;

import java.io.File;

/**
 * What an update job left on disk, for the home screen ({@link UpdatePlan#jobLeft}): the saved request, the resume
 * note and the staging folder; and Discard, which drops all of it. Every place is the launcher's own
 * ({@link JobPaths.Places}); only the dumps come from the saved request.
 */
public final class JobState {
    private JobState() {
    }

    /** The request saved when the last job started, or null when there is none (or it was finished, or unusable). */
    public static UpdateRequest savedRequest(JobPaths.Places places, File gameFolder) {
        return UpdateRequest.read(places, gameFolder);
    }

    /** The resume note of the job, or null. */
    public static Resume note(JobPaths.Places places) {
        return Resume.tryParse(Records.read(places.resumeFile));
    }

    /**
     * Whether a job was left half-done: a saved request the app can go on with and, past the downloads, a resume note
     * the running app can go on with (the app replaced itself) or a staging folder (the mod was being made). A ".part"
     * download alone is not: the installed mod was not touched, and the next job goes on with it. A note the install
     * never made good (the running app is older than its target) is forgotten here.
     */
    public static HomeModel.Job left(JobPaths.Places places, File gameFolder, String runningApp) {
        UpdateRequest request = savedRequest(places, gameFolder);
        if (request == null) return HomeModel.Job.NONE;
        Resume note = note(places);
        if (note != null && note.verdict(runningApp) == Resume.Verdict.DROP) {
            UpdateRunner.delete(places.resumeFile);
            note = null;
        }
        return UpdatePlan.jobLeft(note, runningApp, hasFiles(places.staging));
    }

    /**
     * Discard (the home screen, after its question): an unfinished update is dropped and the installed version stays.
     * Its request, its resume note, its downloads (whole and ".part") and its staging folder are deleted; the installed
     * mod, {@code installed.json} and the code library are not touched. Never while a job runs (the caller checks).
     * True when nothing of it is left.
     */
    public static boolean discard(JobPaths.Places places) {
        boolean ok = Records.delete(places.jobFile);
        ok &= Records.delete(places.resumeFile);
        ModSwap.deleteTree(places.staging);
        ok &= !places.staging.exists();
        File[] downloads = places.downloads.listFiles();
        if (downloads != null) {
            for (File f : downloads) {
                ModSwap.deleteTree(f);
                ok &= !f.exists();
            }
        }
        return ok;
    }

    private static boolean hasFiles(File dir) {
        String[] names = dir.list();
        return names != null && names.length > 0;
    }
}
