package io.github.popzol.heavenfall;

/**
 * Whether the player has left the app, for the one thing that must not happen in front of them: the app's own update
 * ends the running app (Android stops every app whose APK is replaced), and an app in the background cannot open its
 * screen again (Android 10 and later), so the player would find themselves outside the app with the update going on
 * somewhere they cannot see. The phone says, every moment, whether the app is on the screen; the player has left when
 * it has stayed off it for {@code quietMs} without a break (a quick look at another app and back does not count).
 */
public final class LeaveGate {
    private final long quietMs;
    private long awaySince = -1;

    public LeaveGate(long quietMs) {
        this.quietMs = Math.max(0, quietMs);
    }

    /** Tells the gate what the phone says at {@code nowMs}; true once the app has been off the screen for the quiet time. */
    public boolean left(boolean appOnScreen, long nowMs) {
        if (appOnScreen) {
            awaySince = -1;
            return false;
        }
        if (awaySince < 0) awaySince = nowMs;
        return nowMs - awaySince >= quietMs;
    }
}
