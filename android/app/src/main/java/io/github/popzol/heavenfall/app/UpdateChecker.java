package io.github.popzol.heavenfall.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mh4u.port.HeavenfallBridge;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.popzol.heavenfall.Editions;
import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.ReleaseClient;
import io.github.popzol.heavenfall.UpdateCheck;

/**
 * The check the home screen makes when the app opens. One call from the main thread: it sets
 * {@code in.checking}, asks GitHub on a thread of its own, and back on the main thread fills
 * {@code latestApp}, {@code latestMod} and {@code releaseHasXxSet}, clears {@code checking} and calls {@code done}.
 * Settings > Check for updates off: nothing is asked and the fields stay as they were.
 */
public final class UpdateChecker {
    /** Called on the main thread when the check ended (with or without an answer). */
    public interface Done {
        void onChecked();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile UpdateCheck.Latest last;

    private UpdateChecker() {
    }

    /** Whether the player has Check for updates on. */
    public static boolean enabled(Context c) {
        return HeavenfallBridge.updatesOn(c);
    }

    /** What the last check found (null before one, or when it was off). */
    public static UpdateCheck.Latest last() {
        return last;
    }

    /** Starts the check; false when it did not start (the switch is off, or one is running already). */
    public static boolean check(Context context, HomeModel.Input in, Done done) {
        if (!enabled(context) || !RUNNING.compareAndSet(false, true)) return false;
        in.checking = true;
        Context c = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            UpdateCheck.Latest latest = null;
            try {
                Editions editions = Config.editions(c);
                latest = UpdateCheck.fetch(new ReleaseClient(Networks.urlMap(c)), editions, editions.first());
            } catch (RuntimeException e) {
                // the table could not be read: nothing is known
            }
            UpdateCheck.Latest found = latest;
            MAIN.post(() -> {
                RUNNING.set(false);
                if (found != null) {
                    last = found;
                    UpdateCheck.fill(found, Config.editions(c), in);
                }
                in.checking = false;
                if (done != null) done.onChecked();
            });
        }, "heavenfall-check");
        thread.setDaemon(true);
        thread.start();
        return true;
    }
}
