package io.github.popzol.heavenfall.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.popzol.heavenfall.Control;
import io.github.popzol.heavenfall.Downloader;
import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.JobProgress;
import io.github.popzol.heavenfall.JobState;
import io.github.popzol.heavenfall.ModApplier;
import io.github.popzol.heavenfall.ReleaseClient;
import io.github.popzol.heavenfall.Resume;
import io.github.popzol.heavenfall.UpdateException;
import io.github.popzol.heavenfall.UpdateFailure;
import io.github.popzol.heavenfall.UpdateRequest;
import io.github.popzol.heavenfall.UpdateRunner;
import io.github.popzol.heavenfall.UrlMap;

/**
 * Runs an update job (the APK, the mod, the library under one bar) as a foreground service of type dataSync with a
 * partial wake lock and a progress notification. The screens call {@link #start}, {@link #cancel} and
 * {@link #resume}, read {@link #progress()} and subscribe with {@link #observe}; every callback comes on the main
 * thread. The steps and what each failure leaves are {@link UpdateRunner}'s; a failed or cancelled job can always be
 * started again and goes on from where it stopped.
 */
public final class UpdateService extends Service {
    static final String TAG = "Heavenfall";
    static final String ACTION_CANCEL = "io.github.popzol.heavenfall.CANCEL_UPDATE";
    private static final long WAKE_LOCK_MS = 6L * 60 * 60 * 1000;
    private static final long NOTE_EVERY_MS = 1000;

    /** Told of every new snapshot, on the main thread. */
    public interface Observer {
        void onProgress(JobProgress progress);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();
    private static volatile JobProgress progress = JobProgress.IDLE;
    private static volatile Control control;
    private static volatile Thread worker;
    private static volatile ModApplier applier;

    private PowerManager.WakeLock wake;
    private volatile boolean timedOut;
    private long lastNote;

    // ---- what the screens use

    /** The last snapshot: IDLE before any job in this process. */
    public static JobProgress progress() {
        return progress;
    }

    /** A job is running (or starting): the home screen shows no button. */
    public static boolean running() {
        Thread w = worker;
        return progress.active() || (w != null && w.isAlive());
    }

    /** Subscribes to snapshots (the main thread); the current one is not replayed: read {@link #progress()}. */
    public static void observe(Observer o) {
        OBSERVERS.addIfAbsent(o);
    }

    public static void unobserve(Observer o) {
        OBSERVERS.remove(o);
    }

    /** The mod's applier ({@link Wiring} sets it once per process, before any job can start). */
    public static void setApplier(ModApplier a) {
        applier = a;
    }

    /**
     * Starts the job for {@code request}. The request is saved first so the job can be started again from the file
     * ({@link #resume}). Returns false when a job is running already or the request could not be saved (the progress
     * then says why). Call it with the app in front: Android lets only then a foreground service start.
     */
    public static boolean start(Context context, UpdateRequest request) {
        synchronized (UpdateService.class) {
            if (running()) return false;
            if (io.github.popzol.heavenfall.GameOpen.isOpen()) {
                // (Home blocks its buttons while the game is open; this stops any other way in, More's included)
                publish(failed(new UpdateException(UpdateFailure.GAME_OPEN, "start")));
                return false;
            }
            try {
                request.save();
            } catch (UpdateException e) {
                publish(failed(e));
                return false;
            }
            publish(JobProgress.starting(request.basePermille));
            // (no path goes in the intent: the service reads the saved request from the launcher's fixed place)
            Intent intent = new Intent(context, UpdateService.class);
            try {
                context.getApplicationContext().startForegroundService(intent);
            } catch (RuntimeException e) {
                // Android refused the foreground start (the app is in the background): nothing runs, the job is resumable
                Log.w(TAG, "the update service could not start: " + e);
                publish(failed(new UpdateException(UpdateFailure.UNEXPECTED, e.getClass().getSimpleName(), e)));
                return false;
            }
            return true;
        }
    }

    /** Cancels the running job; what is done stays and the job can be resumed. */
    public static void cancel(Context context) {
        Control c = control;
        if (c != null) c.cancel();
    }

    /**
     * The job saved by the last {@link #start}, or null (finished jobs delete theirs; a record that cannot be read, or
     * whose dumps are not files of the game folder, is no job). Its places are the launcher's own, never the record's.
     */
    public static UpdateRequest savedRequest(Context context) {
        return JobState.savedRequest(Locations.places(context), Locations.gameFolder(context));
    }

    /**
     * Goes on with the saved job in the running app: the bar from the resume note (or from the start), the plan made
     * again from what GitHub says now. False when there is nothing to resume or a job is running.
     */
    public static boolean resume(Context context) {
        UpdateRequest saved = savedRequest(context);
        if (saved == null) return false;
        String running = runningVersion(context);
        Resume note = JobState.note(saved.paths.places);
        if (note != null && note.verdict(running) != Resume.Verdict.GO) note = null;
        if (!start(context, saved.resumed(note, running))) return false;
        Notes.cancelResume(context);
        return true;
    }

    /**
     * For {@code HomeModel.Input.job}: RUNNING while a job runs, HALF_DONE when one was left half-done
     * ({@link io.github.popzol.heavenfall.UpdatePlan#jobLeft}: a resume note the app can go on with, a staging
     * folder, a build cut short), else NONE.
     */
    public static HomeModel.Job jobState(Context context) {
        if (running()) return HomeModel.Job.RUNNING;
        return JobState.left(Locations.places(context), Locations.gameFolder(context), runningVersion(context),
                com.mh4u.port.HeavenfallBridge.buildCutShort(context));
    }

    /**
     * Home's Discard, after its question: what the half-done job left is deleted (never while a job runs): its
     * request, note, downloads and staging folder, and the work folder of a build it left cut short (the builder's
     * objects' cache and tools stay). Off the main thread: a staging folder or a work folder holds thousands of files.
     */
    public static boolean discard(Context context) {
        synchronized (UpdateService.class) {
            if (running()) return false;
            boolean ok = JobState.discard(Locations.places(context));
            if (com.mh4u.port.HeavenfallBridge.buildCutShort(context)) {
                ok &= com.mh4u.port.HeavenfallBridge.discardBuild(context, Locations.libraryDir(context));
            }
            Notes.cancelResume(context);
            publish(JobProgress.IDLE);
            return ok;
        }
    }

    static String runningVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** After the app was replaced (the receiver): goes on at once when Android allows, else leaves a tap to resume. */
    static void afterReplace(Context context) {
        UpdateRequest saved = savedRequest(context);
        if (saved == null) return;
        Resume note = JobState.note(saved.paths.places);
        if (note == null) return;
        String running = runningVersion(context);
        if (note.verdict(running) != Resume.Verdict.GO) {
            // forgets a note the install never made good
            JobState.left(saved.paths.places, Locations.gameFolder(context), running, false);
            return;
        }
        boolean started = false;
        try {
            started = resume(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "the update could not resume by itself: " + e);
        }
        if (!started && !running()) Notes.resume(context);
    }

    // ---- the snapshot

    static void publish(JobProgress p) {
        progress = p;
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) o.onProgress(p);
        });
    }

    private static JobProgress failed(UpdateException e) {
        return JobProgress.failed(e, 0);
    }

    // ---- the service

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNote();   // first, whatever the intent: a service started as a foreground one must say so at once
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            Control c = control;
            if (c != null) c.cancel();
            if (!running()) stopSelf();
            return START_NOT_STICKY;
        }
        UpdateRequest request = intent == null ? null : savedRequest(this);
        Thread w = worker;
        if (request == null || (w != null && w.isAlive())) {
            if (w == null || !w.isAlive()) stopSelf();
            return START_NOT_STICKY;
        }
        timedOut = false;
        Control c = new Control();
        control = c;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heavenfall:update");
        wake.acquire(WAKE_LOCK_MS);
        Thread thread = new Thread(() -> work(request, c), "heavenfall-update");
        worker = thread;
        thread.start();
        return START_NOT_STICKY;
    }

    /** Android 15: the data-sync time limit of the day is over; the job stops (resumable) within a few seconds. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "update: the system's time limit for this service: stopping (the next run goes on from here)");
        timedOut = true;
        Control c = control;
        if (c != null) c.cancel();
    }

    private void work(UpdateRequest request, Control c) {
        Context app = getApplicationContext();
        JobProgress end;
        try {
            Wiring.install(app);
            Locations.recoverMod(app);   // a swap cut short is put right before this job touches the mod
            UrlMap map = Networks.urlMap(app);
            UpdateRunner.Parts parts = new UpdateRunner.Parts(new ReleaseClient(map), new Downloader(map),
                    new SelfUpdate(app, Config.tuning(app)), applier, new PhoneLibraryBuilder(app, Config.tuning(app)),
                    Config.editions(app), Config.tuning(app), line -> Log.i(TAG, line), new PhoneConditions(app));
            end = new UpdateRunner(parts, p -> {
                publish(p);
                note(p);
            }).run(request, c);
            if (timedOut && end.phase == JobProgress.Phase.CANCELLED) end = end.interrupted();
        } catch (RuntimeException e) {
            Log.e(TAG, "update", e);
            end = failed(new UpdateException(UpdateFailure.UNEXPECTED, e.getClass().getSimpleName(), e));
        }
        publish(end);
        JobProgress last = end;
        MAIN.post(() -> finish(last));
    }

    private void finish(JobProgress last) {
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Notes.ended(this, last);
        stopSelf();
    }

    private void note(JobProgress p) {
        long now = System.currentTimeMillis();
        if (now - lastNote < NOTE_EVERY_MS && p.active()) return;
        lastNote = now;
        if (p.active()) Notes.update(this, p);
    }

    private void startForegroundNote() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notes.RUNNING, Notes.running(this, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notes.RUNNING, Notes.running(this, progress));
        }
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
