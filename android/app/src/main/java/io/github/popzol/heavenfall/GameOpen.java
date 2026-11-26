package io.github.popzol.heavenfall;

/**
 * Whether the game is open in this process: its activity exists, on the screen or not (the player may have gone to
 * the phone's home screen with the game still running). The app's own update never replaces the app while this is
 * true: Android stops the app when its APK is replaced, and the hunt in progress would be lost ({@link LeaveGate}).
 * The game's activity tells it (opened in its start, closed in its end); a process that ends takes the flag with it.
 */
public final class GameOpen {
    private static volatile boolean open;

    private GameOpen() {
    }

    /** The game's activity was made and runs the game. */
    public static void opened() {
        open = true;
    }

    /** The game's activity ended. */
    public static void closed() {
        open = false;
    }

    public static boolean isOpen() {
        return open;
    }
}
