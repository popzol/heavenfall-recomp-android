package io.github.popzol.heavenfall.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import io.github.popzol.heavenfall.JobProgress;
import io.github.popzol.heavenfall.UpdateTexts;

/** The update job's notifications: the progress one, how it ended, the player's confirmation and the tap to resume. */
final class Notes {
    private static final String CHANNEL = "heavenfall_update";
    static final int RUNNING = 60;
    static final int ENDED = 61;
    static final int CONFIRM = 62;
    static final int RESUME = 63;

    private Notes() {
    }

    private static NotificationManager manager(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Texts.resolve(c, UpdateTexts.CHANNEL),
                    NotificationManager.IMPORTANCE_LOW));
        }
        return nm;
    }

    private static Notification.Builder builder(Context c) {
        manager(c);
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(com.mh4u.port.R.mipmap.ic_launcher)
                .setOnlyAlertOnce(true);
        if (open != null) {
            b.setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE
                    | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return b;
    }

    /** The foreground notification while the job runs: the bar, the state line, a Cancel button. */
    static Notification running(Context c, JobProgress p) {
        Intent stop = new Intent(c, UpdateService.class).setAction(UpdateService.ACTION_CANCEL);
        return builder(c).setContentTitle(Texts.resolve(c, UpdateTexts.RUNNING_TITLE))
                .setContentText(Texts.resolve(c, p.state)).setOngoing(true)
                .setProgress(1000, p.permille, p.permille <= 0)
                .addAction(new Notification.Action.Builder(null, Texts.resolve(c, UpdateTexts.CANCEL),
                        PendingIntent.getForegroundService(c, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    static void update(Context c, JobProgress p) {
        NotificationManager nm = manager(c);
        if (nm != null) nm.notify(RUNNING, running(c, p));
    }

    /** How the job ended (not shown for a cancel by the player). */
    static void ended(Context c, JobProgress p) {
        NotificationManager nm = manager(c);
        if (nm == null) return;
        nm.cancel(RUNNING);
        String title;
        switch (p.phase) {
            case DONE:
                title = UpdateTexts.DONE_TITLE;
                break;
            case FAILED:
                title = UpdateTexts.FAILED_TITLE;
                break;
            default:
                return;
        }
        Notification.Builder b = builder(c).setContentTitle(Texts.resolve(c, title)).setAutoCancel(true);
        b.setContentText(p.phase == JobProgress.Phase.FAILED ? Texts.resolve(c, p.failureReason) + " "
                + Texts.resolve(c, p.failureAdvice) : Texts.resolve(c, p.state));
        nm.notify(ENDED, b.build());
    }

    /** The player must confirm the app's install: a tap opens the system's own confirmation. */
    static void confirm(Context c, Intent confirm, int sessionId) {
        NotificationManager nm = manager(c);
        if (nm == null) return;
        nm.notify(CONFIRM, builder(c).setContentTitle(Texts.resolve(c, UpdateTexts.CONFIRM_TITLE))
                .setContentText(Texts.resolve(c, UpdateTexts.CONFIRM_TEXT)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, sessionId, confirm, PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_UPDATE_CURRENT)).build());
    }

    static void cancelConfirm(Context c) {
        NotificationManager nm = manager(c);
        if (nm != null) nm.cancel(CONFIRM);
    }

    /** The app was replaced and the job could not start by itself: a tap opens the app, which offers to resume. */
    static void resume(Context c) {
        NotificationManager nm = manager(c);
        if (nm == null) return;
        nm.notify(RESUME, builder(c).setContentTitle(Texts.resolve(c, UpdateTexts.RESUME_TITLE))
                .setContentText(Texts.resolve(c, UpdateTexts.RESUME_TEXT)).setAutoCancel(true).build());
    }

    static void cancelResume(Context c) {
        NotificationManager nm = manager(c);
        if (nm != null) nm.cancel(RESUME);
    }
}
