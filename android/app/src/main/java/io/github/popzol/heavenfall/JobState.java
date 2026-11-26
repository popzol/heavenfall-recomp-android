package io.github.popzol.heavenfall;

import java.io.File;

/**
 * What an update job left on disk, for the home screen ({@link UpdatePlan#jobLeft}): the saved request, the resume
 * note, the ".part" downloads and the staging folder.
 */
public final class JobState {
    private JobState() {
    }

    /** The request saved when the last job started, or null when there is none (or it was finished). */
    public static UpdateRequest savedRequest(File jobFile) {
        return UpdateRequest.tryParse(UpdateCheck.readText(jobFile));
    }

    /** The resume note of the job, or null. */
    public static Resume note(JobPaths paths) {
        return Resume.tryParse(UpdateCheck.readText(paths.resumeFile));
    }

    /**
     * Whether a job was left half-done (a note the running app can go on with, a partial download, a staging
     * folder). A note the install never made good (the running app is older than its target) is forgotten here.
     */
    public static HomeModel.Job left(File jobFile, String runningApp) {
        UpdateRequest request = savedRequest(jobFile);
        if (request == null) return HomeModel.Job.NONE;
        JobPaths paths = request.paths;
        Resume note = note(paths);
        if (note != null && note.verdict(runningApp) == Resume.Verdict.DROP) {
            UpdateRunner.delete(paths.resumeFile);
            note = null;
        }
        return UpdatePlan.jobLeft(note, runningApp, hasPartial(paths.downloads), hasFiles(paths.staging));
    }

    private static boolean hasPartial(File dir) {
        String[] names = dir.list();
        if (names == null) return false;
        for (String name : names) {
            if (name.endsWith(".part")) return true;
        }
        return false;
    }

    private static boolean hasFiles(File dir) {
        String[] names = dir.list();
        return names != null && names.length > 0;
    }
}
