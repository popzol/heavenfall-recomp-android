package io.github.popzol.heavenfall.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import android.app.PendingIntent;
import android.os.Build;
import android.provider.Settings;
import android.net.Uri;
import android.content.pm.Signature;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.popzol.heavenfall.AppInstaller;
import io.github.popzol.heavenfall.Control;
import io.github.popzol.heavenfall.SelfUpdateRules;
import io.github.popzol.heavenfall.UpdateException;
import io.github.popzol.heavenfall.UpdateFailure;

/**
 * Replaces the running app with a downloaded APK. Before anything is installed the APK must be this app (the same
 * package name), have a higher version code and carry the same signing certificates as the installed app (the
 * SHA-256 of each certificate compared: {@link SelfUpdateRules}). The install is a PackageInstaller session. Where
 * Android lets an app update itself without asking (API 31 and later, with the manifest's
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION) it does so; otherwise the system's confirmation is shown (opened at once and
 * kept as a notification) and the job waits for the player's answer.
 */
public final class SelfUpdate implements AppInstaller {
    static final String ACTION = "io.github.popzol.heavenfall.INSTALL_RESULT";
    /** How long a session may stay without any answer from the system, and how long the player has to confirm. */
    static final long FIRST_ANSWER_MS = 120_000;
    static final long CONFIRM_MS = 10 * 60_000;

    /** One message of the installer to the waiting job. */
    static final class Result {
        final int status;
        final String message;
        final Intent confirm;

        Result(int status, String message, Intent confirm) {
            this.status = status;
            this.message = message;
            this.confirm = confirm;
        }
    }

    private static final ConcurrentHashMap<Integer, LinkedBlockingQueue<Result>> WAITING = new ConcurrentHashMap<>();

    private final Context context;

    public SelfUpdate(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Whether the player allowed this app to install apps (a store-less app asks for it once in Android's settings). */
    public static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Android's screen where the player allows this app to install apps. */
    public static Intent allowInstallsIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Called by the receiver of the installer's results. */
    static void deliver(Intent intent) {
        int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        LinkedBlockingQueue<Result> queue = WAITING.get(session);
        if (queue == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, SelfUpdateRules.STATUS_FAILURE);
        Intent confirm = null;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirm = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
        }
        queue.offer(new Result(status, String.valueOf(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                confirm));
    }

    // ---- the checks

    /** Throws the failure when {@code apk} may not replace this app. */
    public static void verify(Context c, File apk) throws UpdateException {
        PackageManager pm = c.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) throw new UpdateException(UpdateFailure.SELF_UNREADABLE, apk.getName());
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            throw new UpdateException(UpdateFailure.SELF_FAILED, "own package", e);
        }
        UpdateFailure failure = SelfUpdateRules.check(c.getPackageName(), installed.getLongVersionCode(),
                signers(installed), archive.packageName, archive.getLongVersionCode(), signers(archive));
        if (failure != null) throw new UpdateException(failure, apk.getName());
    }

    /** The SHA-256 of each certificate the package is signed with now. */
    static Set<String> signers(PackageInfo info) {
        Set<String> set = new HashSet<>();
        SigningInfo signing = info.signingInfo;
        if (signing == null) return set;
        Signature[] signatures = signing.getApkContentsSigners();
        if (signatures == null) return set;
        for (Signature s : signatures) set.add(SelfUpdateRules.certificateSha256(s.toByteArray()));
        return set;
    }

    // ---- the install

    @Override
    public void install(File apk, Control control, Waiting waiting) throws UpdateException {
        verify(context, apk);
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id;
        try {
            id = installer.createSession(params);
        } catch (IOException | RuntimeException e) {
            throw new UpdateException(UpdateFailure.SELF_FAILED, "session", e);
        }
        LinkedBlockingQueue<Result> queue = new LinkedBlockingQueue<>();
        WAITING.put(id, queue);
        boolean finished = false;
        try {
            commit(installer, id, apk);
            awaitResult(queue, id, control, waiting);
            finished = true;
        } finally {
            WAITING.remove(id);
            Notes.cancelConfirm(context);
            if (!finished) abandon(installer, id);
        }
    }

    private void commit(PackageInstaller installer, int id, File apk) throws UpdateException {
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(context, InstallResultReceiver.class).setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(context, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            IntentSender sender = pi.getIntentSender();
            session.commit(sender);
        } catch (IOException e) {
            String text = String.valueOf(e.getMessage());
            throw new UpdateException(text.contains("ENOSPC") ? UpdateFailure.SELF_STORAGE : UpdateFailure.SELF_FAILED,
                    "write", e);
        } catch (RuntimeException e) {
            throw new UpdateException(UpdateFailure.SELF_FAILED, "commit", e);
        }
    }

    private void awaitResult(LinkedBlockingQueue<Result> queue, int id, Control control, Waiting waiting)
                              throws UpdateException {
        long deadline = System.currentTimeMillis() + FIRST_ANSWER_MS;
        boolean asked = false;
        while (true) {
            control.check();
            Result r;
            try {
                r = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpdateException(UpdateFailure.INTERRUPTED, "install", e);
            }
            if (r == null) {
                if (System.currentTimeMillis() > deadline) throw new UpdateException(UpdateFailure.SELF_NO_ANSWER, "install");
                continue;
            }
            if (r.status == SelfUpdateRules.STATUS_SUCCESS) return;
            if (r.status == SelfUpdateRules.STATUS_PENDING_USER_ACTION) {
                if (!asked) {
                    asked = true;
                    deadline = System.currentTimeMillis() + CONFIRM_MS;
                    waiting.needsConfirmation();
                }
                if (r.confirm != null) {
                    Notes.confirm(context, new Intent(r.confirm), id);
                    try {
                        context.startActivity(new Intent(r.confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        // the app is in the background: the notification above is the way to confirm
                    }
                }
                continue;
            }
            throw new UpdateException(SelfUpdateRules.forStatus(r.status), r.message);
        }
    }

    private static void abandon(PackageInstaller installer, int id) {
        try {
            installer.abandonSession(id);
        } catch (RuntimeException ignored) {
            // the session is gone already
        }
    }
}
