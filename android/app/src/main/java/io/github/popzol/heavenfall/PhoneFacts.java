package io.github.popzol.heavenfall;

import java.io.File;

/**
 * What the phone says, read off the main thread by the Android side, in plain values: the permission, the game
 * folder and what is in it, {@code installed.json}, the code library, free space, memory, battery, the job and the
 * app's version. {@link #applyTo} puts them into the home model's input; the latest versions and the check's state
 * (filled by the check at open) are never touched.
 */
public final class PhoneFacts {
    public boolean permission;
    /** The game folder the player chose; null when none (or it is gone). */
    public File folder;
    public DumpScan.Found found = DumpScan.Found.NONE;
    /** What installed.json says, null when nothing is installed. */
    public Installed installed;
    public boolean libraryPresent;
    /** The id found beside the library, "" for none. */
    public String libraryIdOnDisk = "";
    /** The id of the tools this app builds with. */
    public String wantedId = "";
    public long freeBytes = -1;
    public long availableMemoryMb = -1;
    public int batteryPercent = -1;
    public boolean charging;
    public HomeModel.Job job = HomeModel.Job.NONE;
    public String appVersion = "";

    public HomeModel.Storage storage() {
        if (!permission) return HomeModel.Storage.NO_PERMISSION;
        return folder == null ? HomeModel.Storage.NO_FOLDER : HomeModel.Storage.READY;
    }

    public HomeModel.Library library() {
        return installed != null ? installed.libraryState(libraryPresent, libraryIdOnDisk, wantedId)
                : Installed.libraryStateWithoutRecord(libraryPresent);
    }

    public void applyTo(HomeModel.Input in) {
        in.storage = storage();
        boolean ready = in.storage == HomeModel.Storage.READY;
        in.game = ready ? found.game : HomeModel.Source.MISSING;
        in.update = ready ? found.update : HomeModel.Source.MISSING;
        in.xx = ready ? found.xx : HomeModel.Source.MISSING;
        in.installed = installed;
        in.library = library();
        in.libraryId = libraryIdOnDisk;
        in.appVersion = appVersion;
        in.freeBytes = freeBytes;
        in.availableMemoryMb = availableMemoryMb;
        in.batteryPercent = batteryPercent;
        in.charging = charging;
        in.job = job;
    }

    /**
     * The job as the home screen sees it: a running one; else one left half-done; else, when the last job in this
     * process failed or was cancelled and its request is still saved, a half-done one (so the failure always leaves
     * Resume, even when it stopped before it wrote anything).
     */
    public static HomeModel.Job effectiveJob(HomeModel.Job fromService, JobProgress last, boolean requestSaved) {
        if (fromService != HomeModel.Job.NONE) return fromService;
        boolean stopped = last != null && (last.phase == JobProgress.Phase.FAILED
                || last.phase == JobProgress.Phase.CANCELLED);
        return stopped && requestSaved ? HomeModel.Job.HALF_DONE : HomeModel.Job.NONE;
    }
}
