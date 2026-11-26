package io.github.popzol.heavenfall;

import java.io.File;
import java.util.Locale;

/**
 * Where the launcher's mod lives, and what keeps it the only code mod. The game and the code library's build both read
 * the mods of the app's user folder, {@code <home>/user/load/mods/<name>/} (home: the app's own folder,
 * {@code AppHome.home}): the folder here is one of them, named {@link #NAME}, with the mod's {@code romfs/},
 * {@code exefs/code.bin}, {@code exheader.bin}, {@code recomp.json} and {@code mod.json}. The staging folder and the
 * old one made while it is replaced start with a dot, which the mod list leaves out (mods/mod_list.h), and sit in the
 * same folder, so putting one in place of another is a rename.
 *
 * <p>The mods of the game folder's {@code mods/} are copied into the same folder (GameFolderActivity.syncMods): that
 * copy leaves this name alone ({@link #isReserved}: never replaced, never removed, never copied from there) and leaves
 * out any mod that carries code ({@link #carriesCode}), which would load beside or in place of this one.
 */
public final class LauncherMod {
    /** The mod's folder name under load/mods. */
    public static final String NAME = "Heavenfall";
    public static final String STAGING = ".heavenfall-staging";
    public static final String OLD = ".heavenfall-old";

    private LauncherMod() {
    }

    /** {@code <home>/user/load/mods}. */
    public static File modsFolder(File home) {
        return new File(new File(home, "user"), "load/mods");
    }

    public static File modDir(File home) {
        return new File(modsFolder(home), NAME);
    }

    public static File stagingDir(File home) {
        return new File(modsFolder(home), STAGING);
    }

    public static File oldDir(File home) {
        return new File(modsFolder(home), OLD);
    }

    /** The folder's swap: staging, installed and the old one, all in the mods folder. */
    public static ModSwap swap(File home) {
        return new ModSwap(modDir(home), stagingDir(home), oldDir(home));
    }

    /** A name in load/mods that is this mod's (the file systems of SD cards ignore the case, so the name does). */
    public static boolean isReserved(String modName) {
        if (modName == null) return false;
        String n = modName.trim().toLowerCase(Locale.ROOT);
        return n.equals(NAME.toLowerCase(Locale.ROOT)) || n.equals(STAGING) || n.equals(OLD);
    }

    private static final String[] CODE_FILES = {"exefs/code.ips", "exefs/code.bps", "exefs/code.bin", "code.ips",
            "code.bps", "code.bin", "exheader.bin"};

    /**
     * A mod folder that changes the game's code: a code replacement or patch, or an exheader, in it or in a title
     * folder (any 16 hex digits) inside it (Azahar's layout, {@code mods/<name>/<title id>/}).
     */
    public static boolean carriesCode(File mod) {
        if (hasCodeFile(mod)) return true;
        File[] kids = mod.listFiles(File::isDirectory);
        if (kids != null) {
            for (File kid : kids) {
                if (kid.getName().matches("(?i)[0-9a-f]{16}") && hasCodeFile(kid)) return true;
            }
        }
        return false;
    }

    private static boolean hasCodeFile(File folder) {
        for (String rel : CODE_FILES) {
            if (new File(folder, rel).isFile()) return true;
        }
        return false;
    }
}
