package com.mh4u.port;

import android.app.Activity;
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
        /** The stage's own count (in "compile": the objects done of the total; up to date ones are done), 0 when none. */
        public final long done, total;

        BuildStatus(String status, String stage, int percent, String errorStage, String errorText, boolean active,
                    long done, long total) {
            this.status = status;
            this.stage = stage;
            this.percent = percent;
            this.errorStage = errorStage;
            this.errorText = errorText;
            this.active = active;
            this.done = done;
            this.total = total;
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
        return new BuildStatus(j.status, j.stage, BuilderText.percent(j), j.errorStage, j.errorText, j.active(), j.done,
                j.total);
    }

    /** The objects an earlier build left in the builder's work folder (phone_build.py's game/recomp/obj), 0 for none. */
    public static long objectsKept(Context c) {
        String[] names = new File(BuilderService.work(c), "game/recomp/obj").list();
        long n = 0;
        if (names != null) for (String name : names) if (name.endsWith(".o")) n++;
        return n;
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

    // ---- the ways around the launcher: upstream's pages that start the game or build the library are put through it

    /**
     * The extra the app's own relaunches carry (PhoenixActivity: Save and restart, the game's start without the entry,
     * the builder's restart): the launcher's Home then starts the game itself when it would say Play, as the entry did.
     */
    public static final String EXTRA_AUTO_PLAY = "io.github.popzol.heavenfall.AUTO_PLAY";

    /** The game was started in this process (it is running, or was). */
    public static boolean gameStarted() {
        return GameActivity.entered;
    }

    /** What the player is told when a page that built the code library (upstream's builder) leads to the launcher. */
    public static String buildInLauncher() {
        return Look.es()
                ? "Heavenfall crea el código del juego desde su pantalla de inicio, con su mod: Crear, o Más > Crear de nuevo."
                : "Heavenfall builds the game's code from its home screen, with its mod: Build, or More > Build again.";
    }

    /**
     * Upstream's builder cannot build here: it would make a library without the launcher's mod, or none. Every way to
     * it (the game folder page's button, the error screen's, its notification) ends here: the launcher's home opens
     * (its job builds the library, under one bar) and {@code from} closes.
     */
    public static void toLauncher(Activity from) {
        android.widget.Toast.makeText(from, buildInLauncher(), android.widget.Toast.LENGTH_LONG).show();
        Intent home = new Intent(from, io.github.popzol.heavenfall.app.HomeActivity.class);
        // (over a running game Home opens above it, never clears it: Play there only goes back to the game)
        if (!gameStarted()) home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        from.startActivity(home);
        from.finish();
    }
}
