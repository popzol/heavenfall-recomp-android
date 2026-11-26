package io.github.popzol.heavenfall;

/**
 * What a tap that starts the update job asks for: whether the set with MH XX is wanted and whether the mod or the
 * library is made again even when they are current. The job asks GitHub and makes the plan itself, so this is all
 * the screen decides.
 */
public final class StartChoice {
    public final boolean xx;
    public final boolean forceMod;
    public final boolean forceBuild;

    private StartChoice(boolean xx, boolean forceMod, boolean forceBuild) {
        this.xx = xx;
        this.forceMod = forceMod;
        this.forceBuild = forceBuild;
    }

    /** Install, Update to X and Build, from the big button. */
    public static StartChoice forAction(HomeModel.Action action, HomeModel.Input in) {
        if (action == HomeModel.Action.BUILD) return new StartChoice(keepsXx(in), false, false);
        boolean xx = in.xx == HomeModel.Source.GOOD;
        // an update that does not change the mod (only the app) keeps what is installed: its MH XX is not dropped
        // because the dump is not at hand
        if (!xx && in.installed != null && in.installed.withXx && !modChanges(in)) xx = true;
        return new StartChoice(xx, false, false);
    }

    /** More > Reinstall: the mod made again from the latest release, with MH XX when its dump is good. */
    public static StartChoice reinstall(HomeModel.Input in) {
        return new StartChoice(in.xx == HomeModel.Source.GOOD, true, false);
    }

    /** More > Build the game's code again: the mod stays as it is. */
    public static StartChoice rebuild(HomeModel.Input in) {
        return new StartChoice(keepsXx(in), false, true);
    }

    private static boolean keepsXx(HomeModel.Input in) {
        return in.installed != null && in.installed.withXx;
    }

    private static boolean modChanges(HomeModel.Input in) {
        return in.installed == null || (in.latestMod != null && Version.updateTo(in.latestMod, in.installed.modVersion));
    }
}
