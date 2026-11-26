package io.github.popzol.heavenfall;

/**
 * Where the app's own install goes in an update ({@link UpdatePlan.InstallAt}): as late as the running app can do
 * the rest for the new one, so that the player, who must leave the app for its install, leaves it as late as possible.
 *
 * <p>The mod's apply reads the set and writes the mod folder in a way the new app reads again; the library is built
 * by the tools of the app that builds it, and carries their id: a library the running app builds is good for the new
 * app only when the new app's tools make the same id (the id inside its APK, {@link AppLibraryId}).
 */
public final class AppInstallRules {
    private AppInstallRules() {
    }

    /**
     * {@link UpdatePlan.InstallAt#LAST} when the new app's tools make the library the running app's tools make, else
     * {@link UpdatePlan.InstallAt#AFTER_MOD}: an id that is empty (an APK or an app built without it) or unreadable is
     * not the same one. Never FIRST: only a set the running app cannot read ends there, which the apply tells
     * ({@link #needsNewerApp}).
     */
    public static UpdatePlan.InstallAt placement(String runningLibraryId, String apkLibraryId) {
        boolean same = runningLibraryId != null && !runningLibraryId.isEmpty() && runningLibraryId.equals(apkLibraryId);
        return same ? UpdatePlan.InstallAt.LAST : UpdatePlan.InstallAt.AFTER_MOD;
    }

    /** Whether the apply stopped because the set is made for a newer app than the running one. */
    public static boolean needsNewerApp(UpdateException e) {
        return e != null && e.reason != null && UpdatePlan.Refusal.NEEDS_NEWER_APP.reasonKey().equals(e.reason.key);
    }
}
