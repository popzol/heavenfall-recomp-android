package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * The app's own relaunches (Save and restart in the game, coming back from Recents, a controller's start, the game
 * after an import) used to land on the entry, which started the game at once. They land on Home now, so Home does what
 * the entry did: when the relaunch asked for it and the installed game can start, it starts the game with no tap.
 *
 * <p>Home is the launcher's entry, which any app can open with any extra; so the ask counts only with a one-time token:
 * the relaunching page writes a random value into a file of the app's private storage ({@link #issue}) and puts the
 * same value in the intent; Home starts the game only when the file holds that value and was written less than
 * {@link #MAX_AGE_MS} ago, and deletes the file whatever it held ({@link #consume}).
 */
public final class AutoPlay {
    public static final String TOKEN_FILE = "auto_play_token.txt";
    /** A relaunch takes a few seconds (the old process ends first); a token older than this is not honoured. */
    public static final long MAX_AGE_MS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AutoPlay() {
    }

    /**
     * @param requested the relaunch carries the extra that asks for it, with a token {@link #consume} accepted
     * @param out       what Home shows for the phone as it is read now
     */
    public static boolean start(boolean requested, HomeModel.Output out) {
        return requested && out != null && out.canPlay;
    }

    /** Writes a new token into {@code dir} and returns it, for the relaunch's intent. */
    public static String issue(File dir, long nowMs) throws IOException {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        String token = sb.toString();
        Records.write(new File(dir, TOKEN_FILE), token + "\n" + nowMs + "\n");
        return token;
    }

    /**
     * Whether {@code token} (the intent's, null when it has none) is the one {@code dir} holds, written at most
     * {@link #MAX_AGE_MS} before {@code nowMs}. The file is deleted whatever the answer: a token is used once.
     */
    public static boolean consume(File dir, String token, long nowMs) {
        File file = new File(dir, TOKEN_FILE);
        String text = Records.read(file);
        if (text != null || file.exists()) Records.delete(file);
        if (token == null || token.isEmpty() || text == null) return false;
        String[] lines = text.split("\n");
        if (lines.length < 2 || !lines[0].trim().equals(token)) return false;
        long written;
        try {
            written = Long.parseLong(lines[1].trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return written <= nowMs && nowMs - written <= MAX_AGE_MS;
    }
}
