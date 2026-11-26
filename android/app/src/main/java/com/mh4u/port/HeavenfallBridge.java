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
        /** idle, running, paused-hot, paused-battery, done, failed, cancelled. */
        public final String status;
        /** The build's stage: tools, extract, recompile, compile, install. */
        public final String stage;
        /** 0..100 over the whole build, -1 when not known. */
        public final int percent;
        /** For a failed build: the stage that failed and the tool's own words. */
        public final String errorStage;
        public final String errorText;
        public final boolean active;
        /** The stage's own count (in "compile": the units with their object of all the units), 0 when none. */
        public final long done, total;
        /** The log's line of the objects' cache: units it gave, units to compile; -1 until the build printed it. */
        public final long cacheHits, toCompile;

        BuildStatus(String status, String stage, int percent, String errorStage, String errorText, boolean active,
                    long done, long total, long cacheHits, long toCompile) {
            this.status = status;
            this.stage = stage;
            this.percent = percent;
            this.errorStage = errorStage;
            this.errorText = errorText;
            this.active = active;
            this.done = done;
            this.total = total;
            this.cacheHits = cacheHits;
            this.toCompile = toCompile;
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
        long[] cache = cacheLine;
        return new BuildStatus(j.status, j.stage, BuilderText.percent(j), j.errorStage, j.errorText, j.active(), j.done,
                j.total, cache == null ? -1 : cache[0], cache == null ? -1 : cache[1]);
    }

    private static volatile long[] cacheLine;

    /** A line of the running build's log (the service's reader calls it for every line but its @@ ones). */
    static void builderLine(String line) {
        long[] c = io.github.popzol.heavenfall.BuildEta.cacheLine(line);
        if (c != null) cacheLine = c;
    }

    /** The objects' cache, beside the work folder in the app's private files (phone_build.py's default). */
    static File objectCache(Context c) {
        return new File(BuilderTools.root(c), "object-cache");
    }

    /**
     * The objects the builder keeps for a next build: its objects' cache's (kept across builds, mod versions and
     * "start over") or, more, the work folder's of a build cut short; 0 for none.
     */
    public static long objectsKept(Context c) {
        long work = 0, cached = 0;
        String[] names = new File(BuilderService.work(c), "game/recomp/obj").list();
        if (names != null) for (String name : names) if (name.endsWith(".o")) work++;
        File[] shards = new File(objectCache(c), "objects").listFiles(File::isDirectory);
        if (shards != null) {
            for (File shard : shards) {
                String[] objects = shard.list();
                if (objects != null) for (String name : objects) if (name.endsWith(".o")) cached++;
            }
        }
        return Math.max(work, cached);
    }

    /** The bytes of the builder's work folder (a build cut short; a whole one deletes it), 0 for none. */
    public static long workBytes(Context c) {
        return BuilderTools.sizeOf(BuilderService.work(c));
    }

    /** The bytes of the objects' cache. */
    public static long cacheBytes(Context c) {
        return BuilderTools.sizeOf(objectCache(c));
    }

    /** A build was cut short (cancelled, failed or stopped): its work folder is there for the next run to go on. */
    public static boolean buildCutShort(Context c) {
        String[] names = BuilderService.work(c).list();
        return names != null && names.length > 0;
    }

    /**
     * Home's Discard of a build cut short: upstream's "start over" without the tools (the work folder, the run's
     * record, a library copy cut short in {@code libraryDir}), as its own screen does it: no run starts meanwhile. The
     * objects' cache and the tools stay. Not on the main thread (thousands of files); false while a build runs.
     */
    public static boolean discardBuild(Context c, File libraryDir) {
        if (BuilderService.busy()) return false;
        BuilderService.clearing = true;
        Context app = c.getApplicationContext();
        try {
            BuilderService.startOver(app, libraryDir.getAbsolutePath(), false);
        } finally {
            BuilderService.clearing = false;
            BuilderService.forget(app);
        }
        return true;
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
        cacheLine = null;
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
