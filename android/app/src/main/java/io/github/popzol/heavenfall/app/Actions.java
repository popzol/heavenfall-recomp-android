package io.github.popzol.heavenfall.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import com.mh4u.port.HeavenfallBridge;
import com.mh4u.port.HeavenfallScreens;

import io.github.popzol.heavenfall.DumpScan;
import io.github.popzol.heavenfall.Editions;
import io.github.popzol.heavenfall.JobPaths;
import io.github.popzol.heavenfall.NotificationAsk;
import io.github.popzol.heavenfall.PhoneFacts;
import io.github.popzol.heavenfall.ScreenTexts;
import io.github.popzol.heavenfall.StartChoice;
import io.github.popzol.heavenfall.UpdateRequest;

/** What the buttons of the screens do outside the screens: start the job, ask for the install permission, play. */
final class Actions {
    private Actions() {
    }

    /**
     * Starts the update job for {@code choice}: the game, its update and (when wanted) MH XX are the files found in the
     * game folder, and the library is built in that folder. False when the files are not there or the job did not start
     * (the progress then says why). With the app in front, as Android needs for a foreground service.
     */
    static boolean start(Activity a, PhoneFacts facts, StartChoice choice) {
        DumpScan.Found found = facts.found;
        if (facts.folder == null || found.gameFile == null || found.updateFile == null) {
            Toast.makeText(a, Texts.resolve(a, "hf_fail_build_game_files") + " " + Texts.resolve(a, "hf_advice_game_files"),
                    Toast.LENGTH_LONG).show();
            return false;
        }
        Editions.Edition edition = Config.editions(a).first();
        JobPaths paths = Locations.paths(a, found.gameFile, found.updateFile, choice.xx ? found.xxFile : null, facts.folder);
        try {
            return UpdateService.start(a, new UpdateRequest(edition.id, paths, facts.appVersion, choice.xx, choice.forceMod,
                    choice.forceBuild, 0));
        } catch (IllegalArgumentException e) {
            // the request is not valid (the app's version could not be read): nothing starts
            Toast.makeText(a, Texts.resolve(a, "hf_fail_unexpected"), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Asks the player to allow installs, with the sentence that says why; the settings open on "Open settings". */
    static void askAllowInstalls(Activity a) {
        HeavenfallScreens.notice(a, Texts.resolve(a, ScreenTexts.ALLOW_TITLE), Texts.resolve(a, ScreenTexts.ALLOW_TEXT),
                new HeavenfallScreens.Choice(Texts.resolve(a, ScreenTexts.ALLOW), true,
                        () -> a.startActivity(SelfUpdate.allowInstallsIntent(a))),
                new HeavenfallScreens.Choice(Texts.resolve(a, ScreenTexts.NOT_NOW), false, null));
    }

    private static final String NOTIFY_PREFS = "heavenfall_notify";
    private static final String NOTIFY_ASKED = "asked";
    private static final int NOTIFY_REQUEST = 4711;

    /**
     * Android 13 and later: when the first job runs, says why the job's notification is useful and asks Android to
     * show notifications ({@link NotificationAsk}); once, whatever the answer. The job runs the same without it.
     */
    static void askNotifications(Activity a) {
        if (Build.VERSION.SDK_INT < NotificationAsk.FIRST_SDK) return;
        boolean granted = a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean asked = a.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE).getBoolean(NOTIFY_ASKED, false);
        if (!NotificationAsk.shouldAsk(Build.VERSION.SDK_INT, granted, asked)) return;
        a.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(NOTIFY_ASKED, true).apply();
        HeavenfallScreens.notice(a, Texts.resolve(a, ScreenTexts.NOTIFY_TITLE), Texts.resolve(a, ScreenTexts.NOTIFY_TEXT),
                new HeavenfallScreens.Choice(Texts.resolve(a, ScreenTexts.ALLOW), true,
                        () -> a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_REQUEST)),
                new HeavenfallScreens.Choice(Texts.resolve(a, ScreenTexts.NOT_NOW), false, null));
    }

    /**
     * Plays: the game's small files are made from the two dumps when they are not current, then the entry flow of the
     * game folder runs its own checks and starts the game (it shows its page when something is wrong).
     */
    static void play(Activity a, PhoneFacts facts) {
        DumpScan.Found found = facts.found;
        if (HeavenfallBridge.gameStarted()) {   // the game runs in this process already (Home came over it): back to it
            a.finish();
            return;
        }
        Thread t = new Thread(() -> {
            Locations.recoverMod(a);   // the game reads the mod: a swap cut short is put right first
            String problem = found.gameFile != null && found.updateFile != null
                    ? HeavenfallScreens.prepareGame(a, found.gameFile, found.updateFile) : null;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (problem != null) Toast.makeText(a, problem, Toast.LENGTH_LONG).show();
                else a.startActivity(HeavenfallScreens.playIntent(a));
            });
        }, "heavenfall-play");
        t.setDaemon(true);
        t.start();
    }

    /** The game folder chooser: the choice is kept, then {@code done} runs on the main thread. */
    static void chooseFolder(Activity a, Runnable done) {
        HeavenfallScreens.chooseFolder(a, Texts.resolve(a, ScreenTexts.FOLDER_TITLE), Texts.resolve(a, ScreenTexts.FOLDER_HERE),
                Texts.resolve(a, ScreenTexts.FOLDER_LOOK), dir -> {
                    Thread t = new Thread(() -> {
                        boolean saved = HeavenfallScreens.saveFolder(a, dir);
                        a.runOnUiThread(() -> {
                            if (!saved) Toast.makeText(a, Texts.resolve(a, ScreenTexts.FOLDER_FAILED), Toast.LENGTH_LONG).show();
                            done.run();
                        });
                    }, "heavenfall-folder");
                    t.setDaemon(true);
                    t.start();
                });
    }
}
