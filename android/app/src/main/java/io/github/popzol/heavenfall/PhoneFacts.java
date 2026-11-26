package io.github.popzol.heavenfall;

import java.io.File;

/**
 * What the phone says, read off the main thread by the Android side, in plain values: the permission, the game
 * folder and what is in it, {@code installed.json}, the installed mod's code, the code library, free space, memory,
 * battery, the job and the app's version. {@link #applyTo} puts them into the home model's input; the latest versions
 * and the check's state (filled by the check at open) are never touched.
 */
public final class PhoneFacts {
    public boolean permission;
    /** The game folder the player chose; null when none (or it is gone). */
    public File folder;
    public DumpScan.Found found = DumpScan.Found.NONE;
    /** What installed.json says, null when nothing is installed. */
    public Installed installed;
    /** The installed mod's code digest ({@link ModCode#digest}), null when the mod or its code is not there. */
    public String modCodeOnDisk;
    /** The code library in the app's private storage, as the builder sees it. */
    public LibraryBuilder.State library = new LibraryBuilder.State(false, "", null);
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
        return installed != null
                ? installed.libraryState(library.present, library.id, wantedId, library.matches(installed))
                : Installed.libraryStateWithoutRecord(library.present);
    }

    /** The installed mod's code against the record (GOOD when nothing is installed: there is nothing to compare). */
    public HomeModel.ModCode modCode() {
        return modCode(installed, modCodeOnDisk);
    }

    public static HomeModel.ModCode modCode(Installed installed, String onDisk) {
        if (installed == null) return HomeModel.ModCode.GOOD;
        if (onDisk == null) return HomeModel.ModCode.MISSING;
        return onDisk.equals(installed.modCodeSha256) ? HomeModel.ModCode.GOOD : HomeModel.ModCode.CHANGED;
    }

    public void applyTo(HomeModel.Input in) {
        in.storage = storage();
        boolean ready = in.storage == HomeModel.Storage.READY;
        in.game = ready ? found.game : HomeModel.Source.MISSING;
        in.update = ready ? found.update : HomeModel.Source.MISSING;
        in.xx = ready ? found.xx : HomeModel.Source.MISSING;
        in.installed = installed;
        in.modCode = modCode();
        in.library = library();
        in.libraryId = library.id;
        in.appVersion = appVersion;
        in.freeBytes = freeBytes;
        in.availableMemoryMb = availableMemoryMb;
        in.batteryPercent = batteryPercent;
        in.charging = charging;
        in.job = job;
    }

    /**
     * The job as the home screen sees it: a running one; else what the last job left on disk ({@link JobState#left}).
     * A job that failed or was cancelled before it touched anything (a download, a declined APK install) leaves no
     * half-done job: the home screen shows its failure under the usual button, which goes on from the bytes there.
     */
    public static HomeModel.Job effectiveJob(HomeModel.Job fromService, HomeModel.Job leftOnDisk) {
        if (fromService != HomeModel.Job.NONE) return fromService;
        return leftOnDisk == null ? HomeModel.Job.NONE : leftOnDisk;
    }
}
