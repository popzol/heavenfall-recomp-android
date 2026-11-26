package io.github.popzol.heavenfall;

/**
 * The app's own relaunches (Save and restart in the game, coming back from Recents, a controller's start, the game
 * after an import) used to land on the entry, which started the game at once. They land on Home now, so Home does what
 * the entry did: when the relaunch asked for it and the installed game can start, it starts the game with no tap.
 */
public final class AutoPlay {
    private AutoPlay() {
    }

    /**
     * @param requested the relaunch carries the extra that asks for it
     * @param out       what Home shows for the phone as it is read now
     */
    public static boolean start(boolean requested, HomeModel.Output out) {
        return requested && out != null && out.canPlay;
    }
}
