package com.mh4u.port;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * What the launcher's screens (io.github.popzol.heavenfall.app) use of this package's: the game's look (colours, plates,
 * buttons, the notice), the storage permission and the game folder as the entry flow decides them, the dump checks,
 * and the intents of the pages that stay upstream's. Upstream's classes are package-private and this one file opens
 * only what the screens need; nothing here changes how they work.
 */
public final class HeavenfallScreens {
    private HeavenfallScreens() {}

    public static final int WINE = Look.WINE, PANEL = Look.PANEL, CREAM = Look.CREAM, GOLD = Look.GOLD,
            BRONZE = Look.BRONZE, EDGE = Look.EDGE, ALERT = Look.ALERT;
    /** The request code of {@link #requestAccess} on Android 10 and before. */
    public static final int ACCESS_REQUEST = 2;

    // ---- the look

    public static int dp(Context c, int v) {
        return Look.dp(c, v);
    }

    public static TextView text(Context c, CharSequence s, int sp, int color, boolean bold) {
        return Look.text(c, s, sp, color, bold);
    }

    public static GradientDrawable plate(Context c, int fill, int radius) {
        return Look.plate(c, fill, radius);
    }

    public static Button button(Context c, String s, boolean main) {
        return Look.button(c, s, main);
    }

    /** One button of a notice. */
    public static final class Choice {
        public final String label;
        public final boolean main;
        public final Runnable action;

        public Choice(String label, boolean main, Runnable action) {
            this.label = label;
            this.main = main;
            this.action = action;
        }
    }

    /** The game-styled notice: a gold title, the text, the choices (the last one is Back's). */
    public static Dialog notice(Activity a, String title, CharSequence body, Choice... choices) {
        Look.Choice[] list = new Look.Choice[choices.length];
        for (int i = 0; i < choices.length; i++) list[i] = new Look.Choice(choices[i].label, choices[i].main, choices[i].action);
        return Look.notice(a, title, body, list);
    }

    // ---- storage

    /** Whether the app may read any folder (all-files access on Android 11 and later). */
    public static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for it as the game folder's screen does: Android's all-files page for this app (Android 11 and later), the
     * permission dialog before. The caller looks again when it comes back to the front.
     */
    public static void requestAccess(Activity a) {
        if (Build.VERSION.SDK_INT < 30) {
            a.requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, ACCESS_REQUEST);
            return;
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** The app's own folder is made usable (a folder another app owns is given back), before anything is written. */
    public static void ensureHome(Context c) {
        AppHome.ensure(c);
    }

    private static File savedFile(Context c) {
        return new File(AppHome.home(c), "game_folder.txt");
    }

    /** The game folder the player chose (game_folder.txt), or null when none. */
    public static File savedFolder(Context c) {
        String path = AppHome.read(savedFile(c));
        return path.isEmpty() ? null : new File(path);
    }

    /** Keeps {@code dir} as the game folder, as the entry screen does when it accepts one; false when it cannot be written. */
    public static boolean saveFolder(Context c, File dir) {
        try {
            AppHome.write(savedFile(c), dir.getAbsolutePath());
        } catch (Exception e) {
            AppHome.ensure(c);
            try {
                AppHome.write(savedFile(c), dir.getAbsolutePath());
            } catch (Exception e2) {
                return false;
            }
        }
        new File(AppHome.home(c), "game_folder_bad.txt").delete();
        return true;
    }

    public interface FolderUse {
        void folder(File dir);
    }

    /** The folder browser of the game folder's screen, over the phone's storage and each SD card. */
    public static Dialog chooseFolder(Activity a, String title, String hereText, String lookText, FolderUse use) {
        return FolderBrowser.open(a, null, title, HeavenfallScreens::holdsDump,
                (dir, holds) -> holds ? hereText : lookText, use::folder, null, false);
    }

    /** A folder with a file of a dump's kind in it (by name: what the file is, is read later). */
    static boolean holdsDump(File d) {
        File[] files = d.listFiles(File::isFile);
        if (files == null) return false;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".3ds") || n.endsWith(".cci") || n.endsWith(".cxi") || n.endsWith(".cia")) return true;
        }
        return false;
    }

    // ---- the dumps

    /** What a dump file says of itself: the title it is, whether it is encrypted, a CIA's title version (-1 for none). */
    public static final class DumpFacts {
        public final long titleId;
        public final boolean encrypted;
        public final int version;

        DumpFacts(long titleId, boolean encrypted, int version) {
            this.titleId = titleId;
            this.encrypted = encrypted;
            this.version = version;
        }
    }

    /**
     * Reads {@code file} with the game folder's own reader (Dump): a card image, a bare NCCH or a CIA. A CIA whose game
     * is encrypted cannot be opened by it, so its title and version are read from the TMD alone. Null when the file is
     * none of those.
     */
    public static DumpFacts readDump(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            try {
                Dump.Ncch n = new Dump(false).find(f);
                return new DumpFacts(n.programId, !n.noCrypto, n.cia ? n.ciaVersion : -1);
            } catch (Dump.Problem p) {
                return encryptedCia(f);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static long be32(byte[] b, int o) {
        return (b[o] & 0xFFL) << 24 | (b[o + 1] & 0xFFL) << 16 | (b[o + 2] & 0xFFL) << 8 | (b[o + 3] & 0xFFL);
    }

    private static int be16(byte[] b, int o) {
        return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF);
    }

    private static long align(long x) {
        return (x + 63) / 64 * 64;
    }

    /**
     * A CIA whose first content is encrypted: Dump refuses it, but its TMD still says the title and its version. Null
     * for a file that is no CIA, whose TMD cannot be read, or whose first content is not marked encrypted (a CIA that
     * Dump refused for another reason is not taken for a good one).
     */
    private static DumpFacts encryptedCia(RandomAccessFile f) throws IOException {
        byte[] head = new byte[24];
        f.seek(0);
        f.readFully(head);
        if (le32(head, 0) != 0x2020) return null;
        long tmdOff = align(align(align(le32(head, 0)) + le32(head, 8)) + le32(head, 12));
        long tmdSize = le32(head, 16);
        if (tmdSize < 0x400 || tmdSize > 0x20000 || tmdOff + tmdSize > f.length()) return null;
        byte[] tmd = new byte[(int) tmdSize];
        f.seek(tmdOff);
        f.readFully(tmd);
        int sigLen;
        switch ((int) be32(tmd, 0)) {
            case 0x10000: case 0x10003: sigLen = 0x200 + 0x3C; break;
            case 0x10001: case 0x10004: sigLen = 0x100 + 0x3C; break;
            case 0x10002: case 0x10005: sigLen = 0x3C + 0x40; break;
            default: return null;
        }
        int th = 4 + sigLen, chunks = th + 0xC4 + 0x24 * 64;
        if (chunks > tmd.length) return null;
        long titleId = be32(tmd, th + 0x4C) << 32 | be32(tmd, th + 0x50);
        int version = be16(tmd, th + 0x9C), count = be16(tmd, th + 0x9E);
        for (int i = 0; i < count && chunks + i * 0x30 + 0x10 <= tmd.length; i++) {
            int c = chunks + i * 0x30;
            if (be16(tmd, c + 4) == 0) return (be16(tmd, c + 6) & 1) != 0 ? new DumpFacts(titleId, true, version) : null;
        }
        return null;
    }

    /**
     * Makes the small files the game starts from (code.bin, exheader.bin, source.txt) out of the two dumps, as the entry
     * flow's check does; nothing is done when they are current. Null when they are right, else the message that says
     * what is wrong with the dumps. Off the main thread.
     */
    public static String prepareGame(Context c, File game, File update) {
        File folder = new File(c.getFilesDir(), "game");
        String want = "base=" + game.getAbsolutePath() + "\nupdate=" + update.getAbsolutePath() + "\n";
        try {
            File source = new File(folder, "source.txt");
            if (source.isFile() && new File(folder, "code.bin").isFile()
                    && want.equals(new String(java.nio.file.Files.readAllBytes(source.toPath()), java.nio.charset.StandardCharsets.UTF_8))) {
                return null;
            }
        } catch (IOException e) {
            // read again below
        }
        try {
            new Dump(Look.es()).prepare(game, update, folder);
            return null;
        } catch (Dump.Problem p) {
            return p.getMessage();
        }
    }

    // ---- the pages that stay upstream's

    /** The game as the entry flow starts it after its own checks (it shows its screen when something is not right). */
    public static Intent playIntent(Context c) {
        return new Intent(c, GameFolderActivity.class);
    }

    public static Intent licensesIntent(Context c) {
        return new Intent(c, LicensesActivity.class);
    }

    public static Intent logShareIntent(Context c) {
        return new Intent(c, LogShareActivity.class);
    }
}
