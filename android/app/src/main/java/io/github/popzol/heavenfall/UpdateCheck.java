package io.github.popzol.heavenfall;

import java.io.File;

/**
 * Asks for the app's latest release and the edition's, and turns the answers into what the home screen
 * ({@link HomeModel.Input}) and the update plan ({@link UpdatePlan.Input}) need. A release that cannot be had or
 * does not pass the rules is simply "not known" here (with the reason kept); a job that needs it says why.
 */
public final class UpdateCheck {
    /** The only region of the game the app supports. */
    public static final String REGION = "usa";

    private UpdateCheck() {
    }

    /** The two releases as found. */
    public static final class Latest {
        public final Editions.Edition edition;
        public final ReleaseClient.Answer app;   // the APK's release
        public final ReleaseClient.Answer mod;   // the edition's release

        Latest(Editions.Edition edition, ReleaseClient.Answer app, ReleaseClient.Answer mod) {
            this.edition = edition;
            this.app = app;
            this.mod = mod;
        }

        /** The APK of the app's latest release, or null. */
        public ReleaseRules.Asset apk(Editions.App appInfo) {
            return app.ok() ? app.release.asset(appInfo.apk) : null;
        }

        /** Whether the mod's release carries the set with MH XX. */
        public boolean modHasXxSet() {
            return mod.ok() && edition.setName(REGION, true) != null && mod.release.has(edition.setName(REGION, true));
        }
    }

    /** Asks for both releases (never throws: a failed one is in its answer). */
    public static Latest fetch(ReleaseClient client, Editions editions, Editions.Edition edition) {
        return new Latest(edition, client.latestApp(editions.app()), client.latestEdition(edition));
    }

    /**
     * Fills {@code latestApp}, {@code latestMod} and {@code releaseHasXxSet} of the home screen's input. A release
     * that was not had leaves its field null (not known): the home screen never loses Play for that.
     */
    public static void fill(Latest latest, Editions editions, HomeModel.Input in) {
        in.latestApp = latest.apk(editions.app()) != null ? latest.app.release.tag : null;
        in.latestMod = latest.mod.ok() ? latest.mod.release.tag : null;
        in.releaseHasXxSet = latest.modHasXxSet();
    }

    /**
     * The plan's input from what is found, what is installed and what the player asked for. {@code xx} is whether
     * the player wants the set with MH XX; it is used only when the release carries that set.
     */
    public static UpdatePlan.Input planInput(Latest latest, Editions editions, UpdateRequest request,
                                             Installed installed, HomeModel.Library library) {
        UpdatePlan.Input in = new UpdatePlan.Input();
        in.appVersion = request.appVersion;
        ReleaseRules.Asset apk = latest.apk(editions.app());
        if (apk != null) {
            in.latestApp = latest.app.release.tag;
            in.appAsset = apk;
        }
        in.installed = installed;
        in.library = library;
        in.forceMod = request.forceMod;
        in.forceBuild = request.forceBuild;
        in.basePermille = request.basePermille;
        if (latest.mod.ok()) {
            ReleaseRules.Release release = latest.mod.release;
            Editions.Edition edition = latest.edition;
            in.latestMod = release.tag;
            in.xx = request.xx && latest.modHasXxSet();
            String setName = edition.setName(REGION, in.xx);
            in.setAsset = setName == null ? null : release.asset(setName);
            in.setNamesShared = edition.sharedFile != null;
            in.sharedAsset = edition.sharedFile == null ? null : release.asset(edition.sharedFile);
        } else {
            // the mod's release is not known: nothing about the mod is asked for beyond what is installed
            in.xx = installed != null ? installed.withXx : request.xx;
        }
        return in;
    }

    /** {@code installed.json} at {@code file}, or null when it is missing or cannot be read. */
    public static Installed readInstalled(File file) {
        return Installed.tryParse(readText(file));
    }

    static String readText(File file) {
        return Records.read(file);
    }
}
