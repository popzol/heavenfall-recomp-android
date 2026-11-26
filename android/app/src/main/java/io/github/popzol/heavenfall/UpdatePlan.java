package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The steps of one "Update to X", in the order they run, with each step's weight on the single progress bar.
 *
 * <p>Order: the app's APK when a newer one is in the release, downloaded first ({@link StepKind#APP}); the mod's set;
 * the shared file when the set names one; applying (the mod folder made in a staging folder, checked, then put in place
 * together with {@code installed.json}); building the library when it is missing or not made for this mod's code or
 * these tools (when only the mod is new, the step is kept as {@link Step#conditional}: after applying,
 * {@link #buildStillNeeded} says whether the new code needs it, and {@link #withoutBuild} drops it). Installing the
 * APK ({@link StepKind#APP_INSTALL}) replaces the running app: Android stops it, and an app in the background cannot
 * bring itself back, so the install never happens while the player is in the app (the player must leave it first).
 * To keep the player in the app for as long as possible, the install goes as late as the running app can do the rest
 * ({@link InstallAt}): decided once the APK is downloaded ({@link #withInstall}); a {@link Resume} note carries the
 * update over to what runs after it.
 *
 * <p>An interrupted step leaves what {@link Step#leftover} says; the home screen sees it again as a half-done job
 * ({@link #jobLeft}) or, after applying, as a library that is not good.
 */
public final class UpdatePlan {
    public enum StepKind {
        APP("hf_step_app"), APP_INSTALL("hf_step_app_install"), SET("hf_step_set"), SHARED("hf_step_shared"), APPLY("hf_step_apply"), BUILD("hf_step_build");

        private final String labelKey;

        StepKind(String labelKey) {
            this.labelKey = labelKey;
        }

        /** The string resource that names the step beside the bar. */
        public String labelKey() {
            return labelKey;
        }
    }

    /** Where the APK's install goes among the steps (see {@link UpdatePlan#withInstall}). */
    public enum InstallAt {
        /** Right after the download: every other step runs in the new app (the set needs it, or the new app must apply it). */
        FIRST,
        /** After the mod is made; the library is the new app's to build (its tools are not these). */
        AFTER_MOD,
        /** At the very end: the running app does the mod and the library (the new app's tools make the same library). */
        LAST
    }

    /** What an interrupted step leaves behind. */
    public enum Leftover {
        /** Nothing is touched: the system installs the APK whole or not at all; resume.json stays. */
        NOTHING,
        /** The bytes already downloaded stay in a ".part" file and the next try continues from them. */
        PARTIAL_DOWNLOAD,
        /** The staging folder is useless and is deleted; the installed mod and installed.json are the old ones. */
        STAGING_DISCARDED,
        /** The compiled objects stay, so the next build compiles only what is left. */
        BUILD_CACHE
    }

    public enum Status { UP_TO_DATE, STEPS, REFUSED }

    /** Why no plan can be made. */
    public enum Refusal {
        BAD_TAG("hf_refusal_bad_tag"),
        NO_RELEASE("hf_refusal_no_release"),
        NO_SET("hf_refusal_no_set"),
        NO_SHARED("hf_refusal_no_shared"),
        NEEDS_NEWER_APP("hf_refusal_needs_newer_app");

        private final String reasonKey;

        Refusal(String reasonKey) {
            this.reasonKey = reasonKey;
        }

        public String reasonKey() {
            return reasonKey;
        }

        public String adviceKey() {
            return this == NEEDS_NEWER_APP ? "hf_advice_update_app" : "hf_advice_try_later";
        }
    }

    public static final class Step {
        public final StepKind kind;
        /** The release file the step fetches (APP, SET, SHARED), else null. */
        public final ReleaseRules.Asset asset;
        /** The step's expected time in milliseconds: its share of the bar. */
        public final long weightMs;
        /** The step runs after the app was replaced and restarted. */
        public final boolean afterRestart;
        /**
         * The step may turn out not to be needed (see {@link UpdatePlan#buildStillNeeded}) and is not known to run:
         * it is left out of the time left and the step count until {@link UpdatePlan#withBuildConfirmed} (the bar
         * keeps it: {@link UpdatePlan#permille}).
         */
        public final boolean conditional;
        public final Leftover leftover;

        Step(StepKind kind, ReleaseRules.Asset asset, long weightMs, boolean afterRestart, boolean conditional,
             Leftover leftover) {
            this.kind = kind;
            this.asset = asset;
            this.weightMs = Math.max(1, weightMs);
            this.afterRestart = afterRestart;
            this.conditional = conditional;
            this.leftover = leftover;
        }

        Step withoutRestart() {
            return afterRestart(false);
        }

        Step afterRestart(boolean after) {
            return new Step(kind, asset, weightMs, after, conditional, leftover);
        }

        /** The step is now known to run: it counts in the time left and the step count. */
        Step confirmed() {
            return new Step(kind, asset, weightMs, afterRestart, false, leftover);
        }

        /** Whether the step counts in the time left and the step count (an undecided one does not). */
        boolean counts() {
            return !conditional;
        }
    }

    /** What the plan is made from. Set the fields that apply; the defaults are "none". */
    public static final class Input {
        public String appVersion = "";
        /** The app release's tag, and its APK when the release carries it. */
        public String latestApp;
        public ReleaseRules.Asset appAsset;
        /** What is installed, or null. */
        public Installed installed;
        /** The mod release's tag and the set chosen for this phone (with MH XX or not), its shared file. */
        public String latestMod;
        public ReleaseRules.Asset setAsset;
        public boolean xx;
        /** The set names a shared file and the release carries it. */
        public boolean setNamesShared;
        public ReleaseRules.Asset sharedAsset;
        /** The set's format is newer than this app applies. */
        public boolean setNeedsNewerApp;
        public HomeModel.Library library = HomeModel.Library.GOOD;
        /** More > Reinstall and More > Rebuild. */
        public boolean forceMod;
        public boolean forceBuild;
        /** Where the bar starts, after a resume (0..999). */
        public int basePermille;
    }

    public final Status status;
    public final Refusal refusal;
    /** The mod version the plan goes to (null for a plan that changes only the app or only the library). */
    public final String modTarget;
    public final boolean withXx;
    public final int basePermille;
    private final List<Step> steps;
    private final String editionId;
    private final String appTarget;

    private UpdatePlan(Status status, Refusal refusal, List<Step> steps, String editionId, String appTarget,
                       String modTarget, boolean withXx, int basePermille) {
        this.status = status;
        this.refusal = refusal;
        this.steps = Collections.unmodifiableList(steps);
        this.editionId = editionId;
        this.appTarget = appTarget;
        this.modTarget = modTarget;
        this.withXx = withXx;
        this.basePermille = basePermille;
    }

    private static UpdatePlan refused(Refusal refusal) {
        return new UpdatePlan(Status.REFUSED, refusal, new ArrayList<Step>(), null, null, null, false, 0);
    }

    /** The plan for the edition {@code editionId} from what the app found. */
    public static UpdatePlan make(String editionId, Input in, Tuning tuning) {
        if ((in.latestApp != null && !Version.isValid(in.latestApp))
                || (in.latestMod != null && !Version.isValid(in.latestMod))) {
            return refused(Refusal.BAD_TAG);
        }
        boolean appNewer = in.latestApp != null && in.appAsset != null
                && Version.newer(in.latestApp, in.appVersion) == 1;
        boolean modNeeded = in.forceMod || in.installed == null
                || (in.latestMod != null && Version.updateTo(in.latestMod, in.installed.modVersion))
                || in.installed.withXx != in.xx;
        boolean libraryNeeded = in.forceBuild || in.library != HomeModel.Library.GOOD;
        if (modNeeded && in.latestMod == null) return refused(Refusal.NO_RELEASE);
        if (modNeeded && in.setAsset == null) return refused(Refusal.NO_SET);
        if (modNeeded && in.setNamesShared && in.sharedAsset == null) return refused(Refusal.NO_SHARED);
        if (modNeeded && in.setNeedsNewerApp && !appNewer) return refused(Refusal.NEEDS_NEWER_APP);

        List<Step> steps = new ArrayList<>();
        if (appNewer) {
            steps.add(new Step(StepKind.APP, in.appAsset, downloadMs(in.appAsset.size, tuning), false, false,
                    Leftover.PARTIAL_DOWNLOAD));
            steps.add(new Step(StepKind.APP_INSTALL, null, tuning.appInstallSeconds * 1000L, false, false,
                    Leftover.NOTHING));
        }
        boolean after = appNewer;
        if (modNeeded) {
            steps.add(new Step(StepKind.SET, in.setAsset, downloadMs(in.setAsset.size, tuning), after, false,
                    Leftover.PARTIAL_DOWNLOAD));
            long applied = in.setAsset.size;
            if (in.setNamesShared) {
                steps.add(new Step(StepKind.SHARED, in.sharedAsset, downloadMs(in.sharedAsset.size, tuning), after,
                        false, Leftover.PARTIAL_DOWNLOAD));
                applied += in.sharedAsset.size;
            }
            steps.add(new Step(StepKind.APPLY, null, applied * 1000L / (tuning.applyKibPerSecond * 1024L), after,
                    false, Leftover.STAGING_DISCARDED));
        }
        if (modNeeded || libraryNeeded) {
            // a new mod may keep the same code, and then its library stays good: the step is decided after APPLY
            steps.add(new Step(StepKind.BUILD, null, tuning.buildSeconds * 1000L, after, !libraryNeeded && in.installed != null,
                    Leftover.BUILD_CACHE));
        }
        if (steps.isEmpty()) {
            return new UpdatePlan(Status.UP_TO_DATE, null, steps, editionId, null, null, false, in.basePermille);
        }
        return new UpdatePlan(Status.STEPS, null, steps, editionId, appNewer ? in.latestApp : null,
                modNeeded ? in.latestMod : null, in.xx, in.basePermille);
    }

    private static long downloadMs(long bytes, Tuning t) {
        return bytes * 1000L / (t.downloadKibPerSecond * 1024L);
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean has(StepKind kind) {
        for (Step s : steps) {
            if (s.kind == kind) return true;
        }
        return false;
    }

    /** The steps that run in the running app: the APK alone when the plan replaces the app, else every step. */
    public List<Step> stepsBeforeRestart() {
        List<Step> list = new ArrayList<>();
        for (Step s : steps) {
            if (!s.afterRestart) list.add(s);
        }
        return list;
    }

    public List<Step> stepsAfterRestart() {
        List<Step> list = new ArrayList<>();
        for (Step s : steps) {
            if (s.afterRestart) list.add(s);
        }
        return list;
    }

    /**
     * The note to write just before the APK is installed; null when the plan does not replace the app. The new app
     * reads it even when nothing is left to do, to tell that the update is done.
     */
    public Resume resumeNote() {
        int at = indexOf(StepKind.APP_INSTALL);
        if (appTarget == null || at < 0) return null;
        return new Resume(editionId, appTarget, modTarget, withXx, Math.min(999, permille(at, 0.0)));
    }

    /** The place of the first step of this kind, or -1. */
    public int indexOf(StepKind kind) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind == kind) return i;
        }
        return -1;
    }

    /** Whether steps are left to run after the APK's install (they run in the new app). */
    public boolean stepsAfterInstall() {
        int at = indexOf(StepKind.APP_INSTALL);
        return at >= 0 && at < steps.size() - 1;
    }

    /**
     * The same steps with the APK's install placed at {@code where}. The steps that run after it are flagged
     * {@link Step#afterRestart}; {@link InstallAt#AFTER_MOD} leaves out the build (the new app plans it again from
     * what it finds). Nothing changes for a plan with no install.
     */
    public UpdatePlan withInstall(InstallAt where) {
        int at = indexOf(StepKind.APP_INSTALL);
        if (at < 0) return this;
        Step install = steps.get(at);
        List<Step> head = new ArrayList<>(), mod = new ArrayList<>();
        for (Step s : steps) {
            if (s.kind == StepKind.APP) {
                head.add(s);
            } else if (s.kind == StepKind.BUILD) {
                if (where != InstallAt.AFTER_MOD) mod.add(s);
            } else if (s.kind != StepKind.APP_INSTALL) {
                mod.add(s);
            }
        }
        List<Step> list = new ArrayList<>(head);
        if (where == InstallAt.FIRST) {
            list.add(install);
            for (Step s : mod) list.add(s.afterRestart(true));
        } else {
            for (Step s : mod) list.add(s.afterRestart(false));
            list.add(install);
        }
        return new UpdatePlan(status, refusal, list, editionId, appTarget, modTarget, withXx, basePermille);
    }

    /**
     * The plan the new app makes after the restart: the same steps without the APK, the bar going on from the note's
     * place (the steps are chosen again from what the app finds; this is only for the bar's share of a rebuilt plan).
     */
    public UpdatePlan afterRestart(Resume note) {
        List<Step> rest = new ArrayList<>();
        for (Step s : steps) {
            if (s.kind != StepKind.APP && s.kind != StepKind.APP_INSTALL) rest.add(s.withoutRestart());
        }
        return new UpdatePlan(rest.isEmpty() ? Status.UP_TO_DATE : Status.STEPS, null, rest, editionId, null, modTarget,
                withXx, note.basePermille);
    }

    /** The plan without its BUILD step (the applied code did not need a build). */
    public UpdatePlan withoutBuild() {
        List<Step> rest = new ArrayList<>();
        for (Step s : steps) {
            if (s.kind != StepKind.BUILD) rest.add(s);
        }
        return new UpdatePlan(rest.isEmpty() ? Status.UP_TO_DATE : status, refusal, rest, editionId, appTarget,
                modTarget, withXx, basePermille);
    }

    /**
     * The same plan with the BUILD step known to run (the applied code needs it): it now counts in the time left and
     * the step count, which grow by it at that moment (the bar had its share all along: {@link #permille}).
     */
    public UpdatePlan withBuildConfirmed() {
        List<Step> list = new ArrayList<>();
        for (Step s : steps) list.add(s.kind == StepKind.BUILD ? s.confirmed() : s);
        return new UpdatePlan(status, refusal, list, editionId, appTarget, modTarget, withXx, basePermille);
    }

    /**
     * After APPLY: whether the library still has to be built. {@code applied} is the record just written,
     * {@code libraryPresent} whether the library file is on disk, {@code onDiskId} the id found beside it and
     * {@code wantedId} the id of the app's tools.
     */
    public static boolean buildStillNeeded(Installed applied, LibraryBuilder.State library, String wantedId) {
        return applied.libraryState(library.present, library.id, wantedId, library.matches(applied))
                != HomeModel.Library.GOOD;
    }

    /**
     * The sum of the weights of the steps known to run: the time the whole job is thought to take. A build that may
     * turn out not to be needed ({@link Step#conditional}) is not in it: the build is counted only when it will run,
     * known after applying the mod (the code of the new mod is in the set, and no release file says it before).
     */
    public long totalMs() {
        long sum = 0;
        for (Step s : steps) {
            if (s.counts()) sum += s.weightMs;
        }
        return sum;
    }

    /** The number of steps the player is told ("Step 4 of 5"): the steps known to run ({@link #totalMs}). */
    public int stepCount() {
        int n = 0;
        for (Step s : steps) {
            if (s.counts()) n++;
        }
        return n;
    }

    /**
     * The bar's place, 0..1000, with {@code stepIndex} steps done and the running step {@code fraction} (0..1)
     * through. It starts at the plan's base and reaches 1000 only when every step is done. Unlike the time left, the
     * bar keeps a build that may not run: the bar can never go back, so a build left out until it is known to run
     * would find the bar already at the end and leave it there for the whole build; a build that turns out not to be
     * needed only moves the bar forward ({@link #withoutBuild}).
     */
    public int permille(int stepIndex, double fraction) {
        if (stepIndex >= steps.size()) return 1000;
        double f = Math.max(0.0, Math.min(1.0, fraction));
        long done = 0;
        long all = 0;
        for (int i = 0; i < steps.size(); i++) {
            all += steps.get(i).weightMs;
            if (i < stepIndex) done += steps.get(i).weightMs;
        }
        double position = (done + steps.get(stepIndex).weightMs * f) / (double) all;
        int value = basePermille + (int) Math.floor((1000 - basePermille) * position);
        return Math.min(999, Math.max(basePermille, value));
    }

    /** The time left, in milliseconds, with {@code stepIndex} steps done and the running step {@code fraction} through. */
    public long remainingMs(int stepIndex, double fraction) {
        return remainingMs(stepIndex, fraction, -1);
    }

    /**
     * The same, with the running step's own measure of its time left ({@code stepMsLeft}, a build's pace:
     * {@link BuildEta}) in place of the plan's estimate for that step; a negative one leaves the estimate.
     */
    public long remainingMs(int stepIndex, double fraction, long stepMsLeft) {
        if (stepIndex >= steps.size()) return 0;
        double f = Math.max(0.0, Math.min(1.0, fraction));
        Step running = steps.get(stepIndex);
        long left = !running.counts() ? 0 : stepMsLeft >= 0 ? stepMsLeft : Math.round(running.weightMs * (1.0 - f));
        for (int i = stepIndex + 1; i < steps.size(); i++) {
            if (steps.get(i).counts()) left += steps.get(i).weightMs;
        }
        return left;
    }

    /**
     * Whether a job was left half-done: past its downloads, a resume note the app can go on with (the app replaced
     * itself), a staging folder (the mod was being made) or a build cut short (cancelled, failed or stopped: the
     * builder's work folder, which Resume goes on from, at the unit it reached, without translating again). A ".part"
     * download, a failed download or a declined APK install is not half-done: nothing installed was touched, and the
     * next Update goes on from the bytes there.
     */
    public static HomeModel.Job jobLeft(Resume note, String runningApp, boolean stagingFolder, boolean buildCutShort) {
        boolean go = note != null && note.verdict(runningApp) == Resume.Verdict.GO;
        return go || stagingFolder || buildCutShort ? HomeModel.Job.HALF_DONE : HomeModel.Job.NONE;
    }
}
