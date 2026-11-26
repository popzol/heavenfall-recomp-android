package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Runs one update job: asks for the latest releases, makes the {@link UpdatePlan} and does its steps one after the
 * other, telling {@link JobProgress} to a listener. APP downloads the APK and hands it to the {@link AppInstaller}
 * (after writing the resume note: installing ends this process); SET and SHARED download; APPLY makes the mod through
 * the {@link ModApplier} and then writes {@code installed.json}; BUILD runs the {@link LibraryBuilder} and records
 * the library it made. A step that fails or is cancelled leaves what the plan says it leaves, so the same request
 * can be run again and goes on from there. Pure Java: the phone's parts come in through the interfaces.
 */
public final class UpdateRunner {
    /** Where progress goes (called on the job's own thread). */
    public interface Listener {
        void progress(JobProgress progress);
    }

    /** A line for the app's log. */
    public interface Log {
        void line(String line);
    }

    /** The phone's state a build needs, read when the build step starts: -1 (and not charging) when not known. */
    public interface Conditions {
        /** Free bytes where the build works. */
        long freeBytes();

        long availableMemoryMb();

        int batteryPercent();

        boolean charging();
    }

    /** Nothing known: no build is stopped by the phone's state. */
    public static final Conditions UNKNOWN_CONDITIONS = new Conditions() {
        @Override
        public long freeBytes() {
            return -1;
        }

        @Override
        public long availableMemoryMb() {
            return -1;
        }

        @Override
        public int batteryPercent() {
            return -1;
        }

        @Override
        public boolean charging() {
            return false;
        }
    };

    /** What the runner works with. */
    public static final class Parts {
        public final ReleaseClient client;
        public final Downloader downloader;
        public final AppInstaller appInstaller;
        public final ModApplier applier;
        public final LibraryBuilder builder;
        public final Editions editions;
        public final Tuning tuning;
        public final Log log;
        public final Conditions conditions;

        public Parts(ReleaseClient client, Downloader downloader, AppInstaller appInstaller, ModApplier applier,
                     LibraryBuilder builder, Editions editions, Tuning tuning, Log log) {
            this(client, downloader, appInstaller, applier, builder, editions, tuning, log, null);
        }

        public Parts(ReleaseClient client, Downloader downloader, AppInstaller appInstaller, ModApplier applier,
                     LibraryBuilder builder, Editions editions, Tuning tuning, Log log, Conditions conditions) {
            this.client = client;
            this.downloader = downloader;
            this.appInstaller = appInstaller;
            this.applier = applier;
            this.builder = builder;
            this.editions = editions;
            this.tuning = tuning;
            this.conditions = conditions == null ? UNKNOWN_CONDITIONS : conditions;
            this.log = log == null ? new Log() {
                @Override
                public void line(String line) {
                }
            } : log;
        }
    }

    private static final long PUBLISH_EVERY_MS = 200;

    private final Parts parts;
    private final Listener listener;

    public UpdateRunner(Parts parts, Listener listener) {
        this.parts = parts;
        this.listener = listener;
    }

    /** Runs the job to its end and returns the last progress (DONE, RESTARTING, FAILED or CANCELLED). */
    public JobProgress run(UpdateRequest request, Control control) {
        Run run = new Run(request, control);
        try {
            run.execute();
        } catch (UpdateException e) {
            run.stopped(e);
        } catch (RuntimeException e) {
            parts.log.line("update job: unexpected " + e);
            run.stopped(new UpdateException(UpdateFailure.UNEXPECTED, e.getClass().getSimpleName(), e));
        }
        return run.last;
    }

    private final class Run {
        final UpdateRequest request;
        final JobPaths paths;
        final Control control;
        UpdatePlan plan;
        int index;
        JobProgress last = JobProgress.IDLE;
        Text lastState;
        int lastPermille = -1;
        long lastTime;
        Installed installed;
        /** The running step's own measure of its time left ({@link Fraction#msLeft}); -1 when it has none. */
        volatile long stepMsLeft = -1;

        Run(UpdateRequest request, Control control) {
            this.request = request;
            this.paths = request.paths;
            this.control = control;
        }

        void execute() throws UpdateException {
            publish(JobProgress.Phase.RUNNING, request.basePermille, new Text("hf_state_job_checking"), 0, null, 0, 0, true);
            parts.log.line("update job: " + request.editionId + ", app " + request.appVersion + ", base " + request.basePermille);
            Editions.Edition edition = parts.editions.byId(request.editionId);
            if (edition == null) throw new UpdateException(UpdateFailure.UNEXPECTED, "edition " + request.editionId);
            writeText(paths.jobFile, request.toJson());
            UpdateCheck.Latest latest = UpdateCheck.fetch(parts.client, parts.editions, edition);
            control.check();
            installed = UpdateCheck.readInstalled(paths.installedFile);
            if (installed != null && !installed.edition.equals(edition.id)) installed = null;
            LibraryBuilder.State library = parts.builder.state();
            HomeModel.Library state = installed == null ? Installed.libraryStateWithoutRecord(library.present)
                    : installed.libraryState(library.present, library.id, parts.builder.wantedId(),
                            library.matches(installed));
            UpdatePlan.Input input = UpdateCheck.planInput(latest, parts.editions, request, installed, state);
            plan = UpdatePlan.make(edition.id, input, parts.tuning);
            if (plan.status == UpdatePlan.Status.REFUSED) {
                if (plan.refusal == UpdatePlan.Refusal.NO_RELEASE && latest.mod.failure != null) throw latest.mod.failure;
                throw new UpdateException(plan.refusal);
            }
            if (!plan.has(UpdatePlan.StepKind.APP)) {
                delete(new File(paths.downloads, parts.editions.app().apk));
                delete(Downloader.partOf(paths.downloads, parts.editions.app().apk));
            }
            if (plan.status == UpdatePlan.Status.UP_TO_DATE) {
                done();
                return;
            }
            File set = null;
            File shared = null;
            ReleaseRules.Release release = latest.mod.ok() ? latest.mod.release : null;
            List<UpdatePlan.Step> steps = plan.steps();
            for (index = 0; index < steps.size(); index++) {
                UpdatePlan.Step step = steps.get(index);
                control.check();
                Text label = new Text(step.kind.labelKey());
                switch (step.kind) {
                    case APP:
                        if (appStep(step, label)) return;
                        break;
                    case SET:
                        set = download(step, label, 0, 1);
                        break;
                    case SHARED:
                        shared = download(step, label, 0, 1);
                        break;
                    case APPLY:
                        applyStep(edition, release, input, set, shared, label);
                        set = null;
                        shared = null;
                        break;
                    case BUILD:
                        if (step.conditional && !buildNeeded()) {
                            plan = plan.withoutBuild();
                            index = steps.size();
                        } else {
                            buildStep(label);
                        }
                        break;
                    default:
                        throw new UpdateException(UpdateFailure.UNEXPECTED, String.valueOf(step.kind));
                }
            }
            done();
        }

        /** True when the app was replaced and this job ends here (it goes on in the new app). */
        boolean appStep(UpdatePlan.Step step, Text label) throws UpdateException {
            double share = Math.min(1.0, downloadMs(step.asset.size) / (double) step.weightMs);
            File apk = download(step, label, 0, share);
            publishStep(share, new Text("hf_state_job_installing_app"));
            Resume note = plan.resumeNote();
            if (note != null) writeText(paths.resumeFile, note.toJson());
            try {
                parts.appInstaller.install(apk, control, new AppInstaller.Waiting() {
                    @Override
                    public void needsConfirmation() {
                        publishStep(share, new Text("hf_state_job_confirm_install"));
                    }
                });
            } catch (UpdateException e) {
                delete(paths.resumeFile);
                throw e;
            }
            publish(JobProgress.Phase.RESTARTING, lastPermille, new Text("hf_state_job_restarting"), 0, step.kind,
                    index + 1, plan.steps().size(), true);
            return true;
        }

        File download(UpdatePlan.Step step, Text label, double from, double span) throws UpdateException {
            return parts.downloader.download(step.asset, paths.downloads, new Fraction() {
                @Override
                public void fraction(double done) {
                    publishStep(from + span * done, label);
                }
            }, control);
        }

        void applyStep(Editions.Edition edition, ReleaseRules.Release release, UpdatePlan.Input input, File set,
                       File shared, Text label) throws UpdateException {
            if (set == null || release == null) throw new UpdateException(UpdateFailure.UNEXPECTED, "apply without a set");
            ModApplier.Applied applied;
            try {
                applied = parts.applier.apply(paths, set, shared, plan.withXx, control, new Fraction() {
                    @Override
                    public void fraction(double done) {
                        publishStep(done, label);
                    }
                });
                if (applied == null) throw new UpdateException(UpdateFailure.APPLY_FAILED, "no result");
            } catch (UpdateException e) {
                deleteTree(paths.staging);
                throw e;
            } catch (RuntimeException e) {
                deleteTree(paths.staging);
                throw new UpdateException(UpdateFailure.APPLY_FAILED, e.getClass().getSimpleName(), e);
            }
            String setName = edition.setName(UpdateCheck.REGION, plan.withXx);
            ReleaseRules.Asset setAsset = release.asset(setName);
            installed = installed != null
                    ? installed.applied(release.tag, setName, setAsset.sha256, plan.withXx, applied.codeSha256,
                            applied.modCodeSha256)
                    : Installed.fresh(edition.id, release.tag, setName, setAsset.sha256, plan.withXx, applied.codeSha256,
                            applied.modCodeSha256);
            writeText(paths.installedFile, installed.toJson());
            parts.log.line("update job: applied " + release.tag + (plan.withXx ? " with MH XX" : ""));
            deleteTree(paths.staging);
            delete(set);
            if (shared != null) delete(shared);
        }

        boolean buildNeeded() {
            return installed == null
                    || UpdatePlan.buildStillNeeded(installed, parts.builder.state(), parts.builder.wantedId());
        }

        void buildStep(Text label) throws UpdateException {
            if (installed == null) throw new UpdateException(UpdateFailure.BUILD_FAILED, "nothing installed");
            String code = modCodeChecked(paths.modDir, installed);
            conditionsChecked(parts.conditions, parts.tuning);
            LibraryBuilder.Built built = parts.builder.build(paths, control, new Fraction() {
                @Override
                public void fraction(double done) {
                    publishStep(done, label);
                }

                @Override
                public void msLeft(long ms) {
                    stepMsLeft = ms;
                }
            });
            stepMsLeft = -1;
            if (built == null) throw new UpdateException(UpdateFailure.BUILD_FAILED, "no library");
            installed = installed.withLibrary(built.toolsId, code, built.file);
            writeText(paths.installedFile, installed.toJson());
            parts.log.line("update job: library built by " + built.toolsId);
        }

        void done() {
            delete(paths.resumeFile);
            delete(paths.jobFile);
            publish(JobProgress.Phase.DONE, 1000, new Text("hf_state_job_done"), 0, null, 0, 0, true);
        }

        void stopped(UpdateException e) {
            parts.log.line("update job: stopped, " + e.getMessage());
            boolean cancelled = e.cancelled();
            UpdatePlan.StepKind kind = plan != null && index < plan.steps().size() ? plan.steps().get(index).kind : null;
            JobProgress.Phase phase = cancelled ? JobProgress.Phase.CANCELLED : JobProgress.Phase.FAILED;
            Text state = new Text(cancelled ? "hf_state_job_cancelled" : "hf_state_job_failed");
            JobProgress p = new JobProgress(phase, Math.max(lastPermille, 0), state, 0, kind,
                    plan == null ? 0 : index + 1, plan == null ? 0 : plan.steps().size(), e.reason, e.advice);
            last = p;
            listener.progress(p);
        }

        // ---- progress

        long downloadMs(long bytes) {
            return bytes * 1000L / (parts.tuning.downloadKibPerSecond * 1024L);
        }

        void publishStep(double stepFraction, Text state) {
            int permille = plan.permille(index, stepFraction);
            boolean same = state.equals(lastState);
            if (same && permille == lastPermille) return;
            publish(JobProgress.Phase.RUNNING, permille, state, plan.remainingMs(index, stepFraction, stepMsLeft),
                    plan.steps().get(index).kind, index + 1, plan.steps().size(), !same || stepFraction >= 1.0);
        }

        void publish(JobProgress.Phase phase, int permille, Text state, long msLeft, UpdatePlan.StepKind kind,
                     int number, int count, boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastTime < PUBLISH_EVERY_MS) return;
            lastTime = now;
            lastPermille = permille;
            lastState = state;
            JobProgress p = new JobProgress(phase, permille, state, msLeft, kind, number, count, null, null);
            last = p;
            listener.progress(p);
        }
    }

    // ---- what a build needs

    /**
     * The installed mod's code digest, checked against the one {@code installed.json} recorded when the mod was made:
     * a library is only ever built from that code. A mod changed since, or gone, is {@link UpdateFailure#MOD_CHANGED}
     * (the home screen then offers Reinstall).
     */
    static String modCodeChecked(File modDir, Installed installed) throws UpdateException {
        String code;
        try {
            code = ModCode.digest(modDir);
        } catch (IOException e) {
            throw new UpdateException(UpdateFailure.MOD_CHANGED, e.getClass().getSimpleName(), e);
        }
        if (code == null || !code.equals(installed.modCodeSha256)) {
            throw new UpdateException(UpdateFailure.MOD_CHANGED, code == null ? "no mod code" : "mod code changed");
        }
        return code;
    }

    /** Space, battery (unless charging) and memory against the tuning, when the build step starts. */
    static void conditionsChecked(Conditions c, Tuning tuning) throws UpdateException {
        long free = c.freeBytes();
        if (free >= 0 && free < tuning.minFreeBuildBytes()) {
            throw new UpdateException(UpdateFailure.BUILD_SPACE, "free " + free);
        }
        int battery = c.batteryPercent();
        if (battery >= 0 && !c.charging() && battery < tuning.minBatteryPercent) {
            throw new UpdateException(UpdateFailure.BUILD_BATTERY, "battery " + battery);
        }
        long memory = c.availableMemoryMb();
        if (memory >= 0 && memory < tuning.minAvailableMemoryMb) {
            throw new UpdateException(UpdateFailure.BUILD_MEMORY, "memory " + memory);
        }
    }

    // ---- files

    /** Writes {@code text} to {@code file} whole or not at all ({@link Records#write}). */
    static void writeText(File file, String text) throws UpdateException {
        try {
            Records.write(file, text);
        } catch (IOException e) {
            throw new UpdateException(UpdateFailure.CANNOT_WRITE, file.getName(), e);
        }
    }

    static void delete(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        delete(file);
    }
}
