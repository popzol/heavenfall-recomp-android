package io.github.popzol.heavenfall;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reads a game folder into the three {@link HomeModel.Source} values (the game, its 1.3.0 update, MH XX): every
 * file with a dump's extension in the folder is identified, and for each of the three the best candidate decides
 * (a usable one over an encrypted one over one of another version or region). Nothing is read below the folder's
 * top level, as the app's own game folder check does.
 */
public final class DumpScan {
    /** The title version of MH4U's 1.3.0 update (major 1, minor 3, micro 0 as the 3DS packs it: 1 &lt;&lt; 10 | 3 &lt;&lt; 4). */
    public static final int UPDATE_TITLE_VERSION = (1 << 10) | (3 << 4);

    private static final String[] EXTENSIONS = {".3ds", ".cci", ".cxi", ".cia"};

    private DumpScan() {
    }

    /** What the folder holds: the three sources and the file each came from (null when MISSING). */
    public static final class Found {
        public static final Found NONE = new Found(HomeModel.Source.MISSING, null, HomeModel.Source.MISSING, null,
                HomeModel.Source.MISSING, null);

        public final HomeModel.Source game;
        public final File gameFile;
        public final HomeModel.Source update;
        public final File updateFile;
        public final HomeModel.Source xx;
        public final File xxFile;

        public Found(HomeModel.Source game, File gameFile, HomeModel.Source update, File updateFile,
                     HomeModel.Source xx, File xxFile) {
            this.game = game;
            this.gameFile = gameFile;
            this.update = update;
            this.updateFile = updateFile;
            this.xx = xx;
            this.xxFile = xxFile;
        }
    }

    /** The source a file counts as; MISSING for a file that is none of the three kinds {@code kind}. */
    static HomeModel.Source sourceOf(DumpIdentifier.Identity id, DumpIdentifier.Identity.Kind kind) {
        if (id == null || id.kind != kind) return HomeModel.Source.MISSING;
        switch (kind) {
            case GAME:
                if (id.region == DumpIdentifier.Identity.Region.EUR) return HomeModel.Source.WRONG_REGION;
                if (id.region != DumpIdentifier.Identity.Region.USA) return HomeModel.Source.WRONG_VERSION;
                return id.encrypted ? HomeModel.Source.ENCRYPTED : HomeModel.Source.GOOD;
            case UPDATE:
                if (id.region != DumpIdentifier.Identity.Region.USA) return HomeModel.Source.WRONG_REGION;
                if (id.encrypted) return HomeModel.Source.ENCRYPTED;
                if (id.titleVersion >= 0 && id.titleVersion != UPDATE_TITLE_VERSION) return HomeModel.Source.WRONG_VERSION;
                return HomeModel.Source.GOOD;
            case XX:
                return id.encrypted ? HomeModel.Source.ENCRYPTED : HomeModel.Source.GOOD;
            default:
                return HomeModel.Source.MISSING;
        }
    }

    /** How near a problem is to a usable file: the best candidate is the one with the lowest rank. */
    private static int rank(HomeModel.Source s) {
        switch (s) {
            case GOOD: return 0;
            case ENCRYPTED: return 1;
            case WRONG_VERSION: return 2;
            case WRONG_REGION: return 3;
            default: return 4;
        }
    }

    /** The three sources of {@code folder} (a null or missing folder holds nothing). */
    public static Found scan(File folder, DumpIdentifier identifier) {
        File[] files = folder == null ? null : folder.listFiles(File::isFile);
        if (files == null) return Found.NONE;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        HomeModel.Source[] best = {HomeModel.Source.MISSING, HomeModel.Source.MISSING, HomeModel.Source.MISSING};
        File[] from = new File[3];
        DumpIdentifier.Identity.Kind[] kinds = {DumpIdentifier.Identity.Kind.GAME, DumpIdentifier.Identity.Kind.UPDATE,
                DumpIdentifier.Identity.Kind.XX};
        for (File f : files) {
            if (!hasDumpExtension(f.getName())) continue;
            DumpIdentifier.Identity id = identifier.identify(f);
            for (int i = 0; i < kinds.length; i++) {
                HomeModel.Source s = sourceOf(id, kinds[i]);
                if (s != HomeModel.Source.MISSING && rank(s) < rank(best[i])) {
                    best[i] = s;
                    from[i] = f;
                }
            }
        }
        return new Found(best[0], from[0], best[1], from[1], best[2], from[2]);
    }

    static boolean hasDumpExtension(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : EXTENSIONS) {
            if (n.endsWith(e)) return true;
        }
        return false;
    }
}
