package com.mh4u.port;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;

/**
 * What the launcher's own classes (io.github.popzol.heavenfall.app) use of this package's: upstream's builder
 * service and the app's folder are package-private, and this one file opens them. Nothing here changes how they work.
 */
public final class HeavenfallBridge {
    private HeavenfallBridge() {}

    /** The build's state as the update job needs it. */
    public static final class BuildStatus {
        /** idle, running, warm, paused-hot, paused-battery, done, failed, cancelled. */
        public final String status;
        /** The build's stage: tools, extract, recompile, compile, install. */
        public final String stage;
        /** 0..100 over the whole build, -1 when not known. */
        public final int percent;
        /** For a failed build: the stage that failed and the tool's own words. */
        public final String errorStage;
        public final String errorText;
        public final boolean active;

        BuildStatus(String status, String stage, int percent, String errorStage, String errorText, boolean active) {
            this.status = status;
            this.stage = stage;
            this.percent = percent;
            this.errorStage = errorStage;
            this.errorText = errorText;
            this.active = active;
        }
    }

    /** The app's own folder (settings, log, user folder), as the game and the screens use it. */
    public static File home(Context c) {
        return AppHome.home(c);
    }

    /** Settings > Check for updates (on unless the player turned it off). */
    public static boolean updatesOn(Context c) {
        return Notices.updatesOn(c);
    }

    /** The code library's file name inside the library folder. */
    public static String libraryFileName() {
        return GameFolderActivity.LIBRARY;
    }

    /** The id of the tools this app builds with, "" when the app has none. */
    public static String wantedId(Context c) {
        return GameFolderActivity.wantedId(c);
    }

    /** A build is running, or its thread is still finishing, or a clear is going on. */
    public static boolean buildBusy() {
        return BuilderService.busy();
    }

    public static BuildStatus buildStatus() {
        BuilderService.Job j = BuilderService.job;
        return new BuildStatus(j.status, j.stage, BuilderText.percent(j), j.errorStage, j.errorText, j.active());
    }

    /** The build's state goes back to idle after its end was read (the next start begins clean anyway). */
    public static void buildAcknowledged() {
        BuilderService.Job j = BuilderService.job;
        if (!j.active()) j.status = "idle";
    }

    public static void listen(Runnable r) {
        BuilderService.listen(r);
    }

    public static void unlisten(Runnable r) {
        BuilderService.unlisten(r);
    }

    /**
     * Starts the build as upstream's screen does (a foreground service). {@code mod} is the code mod's folder and
     * {@code modFlag} the option of phone_build.py that takes it ({@code --mod}, {@code --game-mods},
     * {@code --user-mods}); null for none.
     */
    public static void startBuild(Context c, File game, File update, File dest, String modFlag, File mod) {
        Intent run = new Intent(c, BuilderService.class)
                .putExtra(BuilderService.EXTRA_GAME, game.getAbsolutePath())
                .putExtra(BuilderService.EXTRA_UPDATE, update.getAbsolutePath())
                .putExtra(BuilderService.EXTRA_DEST, dest.getAbsolutePath());
        if (mod != null) {
            run.putExtra(BuilderService.EXTRA_MOD_FLAG, modFlag == null ? "--mod" : modFlag)
               .putExtra(BuilderService.EXTRA_MOD, mod.getAbsolutePath());
        }
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(run);
        else c.startService(run);
    }

    /** Stops the running build; what it compiled stays and the next build goes on from there. */
    public static void cancelBuild(Context c) {
        c.startService(new Intent(c, BuilderService.class).setAction(BuilderService.ACTION_CANCEL));
    }
}
