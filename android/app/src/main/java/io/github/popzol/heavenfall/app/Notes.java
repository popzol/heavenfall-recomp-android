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
    private static final String END_CHANNEL = "heavenfall_update_end";
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

    /** The end's channel: seen in the status bar, with the phone's usual sound (the app may be closed by then). */
    private static NotificationManager endManager(Context c) {
        NotificationManager nm = manager(c);
        if (nm != null && nm.getNotificationChannel(END_CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(END_CHANNEL, Texts.resolve(c, UpdateTexts.END_CHANNEL),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        return nm;
    }

    /** A tap opens Home (the launcher's own screen, in a task of its own when the app was closed). */
    private static PendingIntent home(Context c) {
        Intent open = new Intent(c, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * How the job ended, always told ({@link UpdateTexts#ended}): up to date (a tap opens Home, where Play is), stopped
     * with its reason and advice, or cancelled. A tap opens Home. Not for the end of a job whose app is replacing
     * itself: the new app goes on with it and tells its own end.
     */
    static void ended(Context c, JobProgress p) {
        NotificationManager nm = endManager(c);
        if (nm == null) return;
        nm.cancel(RUNNING);
        String[] texts = UpdateTexts.ended(p);
        if (texts == null) return;
        String text = texts[1] != null ? Texts.resolve(c, texts[1])
                : Texts.resolve(c, p.failureReason != null ? p.failureReason : p.state) + " "
                + Texts.resolve(c, p.failureAdvice != null ? p.failureAdvice : new io.github.popzol.heavenfall.Text("hf_advice_resume"));
        Notification n = new Notification.Builder(c, END_CHANNEL).setSmallIcon(com.mh4u.port.R.mipmap.ic_launcher)
                .setContentTitle(Texts.resolve(c, texts[0])).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
                .setContentIntent(home(c)).build();
        nm.notify(ENDED, n);
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
        NotificationManager end = endManager(c);
        if (end == null) return;
        end.notify(RESUME, new Notification.Builder(c, END_CHANNEL).setSmallIcon(com.mh4u.port.R.mipmap.ic_launcher)
                .setContentTitle(Texts.resolve(c, UpdateTexts.RESUME_TITLE))
                .setContentText(Texts.resolve(c, UpdateTexts.RESUME_TEXT)).setAutoCancel(true)
                .setContentIntent(home(c)).build());
    }

    static void cancelResume(Context c) {
        NotificationManager nm = manager(c);
        if (nm != null) nm.cancel(RESUME);
    }
}
