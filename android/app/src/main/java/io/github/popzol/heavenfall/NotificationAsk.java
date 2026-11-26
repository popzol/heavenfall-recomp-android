package io.github.popzol.heavenfall;

/**
 * When the app asks to show notifications. Android 13 and later turn them off until the player allows them; the
 * update job runs with its notification either way (a foreground service always runs), so the answer only decides
 * whether the bar can be seen outside the app. The question is asked once, when the first job starts, with the reason.
 */
public final class NotificationAsk {
    /** The first Android version that asks the player (Tiramisu). */
    public static final int FIRST_SDK = 33;

    private NotificationAsk() {
    }

    /**
     * @param granted the permission is given already
     * @param asked   the question was put to the player before (whatever the answer)
     */
    public static boolean shouldAsk(int sdk, boolean granted, boolean asked) {
        return sdk >= FIRST_SDK && !granted && !asked;
    }
}
