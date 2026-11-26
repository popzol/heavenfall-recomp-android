package io.github.popzol.heavenfall.app;

import java.io.File;
import java.util.function.BooleanSupplier;

import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.Identity;
import io.github.popzol.heavenfall.LauncherMod;
import io.github.popzol.heavenfall.ModBuilder;
import io.github.popzol.heavenfall.ModSwap;
import io.github.popzol.heavenfall.SetInfo;
import io.github.popzol.heavenfall.SourceCheck;

/**
 * The mod made on the phone: the one class the screens and the update job call. Everything is blocking (the files are
 * big): call it off the main thread. The pieces without Android are in {@link ModBuilder}, {@link ModSwap},
 * {@link SourceCheck} and the error texts ({@code ApplyError}).
 */
public final class ModMaker {
    private static final HeavenfallNative NATIVE = new HeavenfallNative();

    private ModMaker() {
    }

    /** What a file the player gave is. */
    public static Identity identify(SourceFile file) {
        return ModBuilder.identify(NATIVE, file);
    }

    /** A downloaded set's head, without applying it ({@code setNeedsNewerApp} for the update plan, the update and MH XX it names). */
    public static SetInfo setInfo(SourceFile set) {
        return ModBuilder.setInfo(NATIVE, set);
    }

    /** The checklist's line for the game; {@code file} null: not there. {@code region} is the edition's ("usa"). */
    public static HomeModel.Source checkGame(SourceFile file, String region) {
        return SourceCheck.game(file == null ? null : identify(file), region);
    }

    /** The checklist's line for the update; {@code set} (may be null) names the exact update it was made for. */
    public static HomeModel.Source checkUpdate(SourceFile file, String region, SetInfo set) {
        return SourceCheck.update(file == null ? null : identify(file), region,
                set != null && set.needsUpdate ? set.updateExefsSha256 : "");
    }

    /** The checklist's line for MH XX; {@code set} (may be null) names the exact revision it was made with. */
    public static HomeModel.Source checkXx(SourceFile file, SetInfo set) {
        return SourceCheck.xx(file == null ? null : identify(file), set != null && set.needsXx ? set.xxRomfsSha256 : "");
    }

    /**
     * Makes the mod into {@code swap}'s staging folder from the set, the game, its update (and MH XX, and the
     * release's shared file: null when there are none), checks every recorded hash and, only when all of it worked,
     * puts the folder in place of the installed mod. {@code progress} gets the bytes done of the total;
     * {@code cancelled} is looked at at each call of it. {@link ModBuilder.Failed} says why it stopped in the player's
     * words ({@code error.reason()} and {@code error.advice()}); the installed mod is as it was.
     */
    public static ModBuilder.Made apply(ModSwap swap, SourceFile set, SourceFile game, SourceFile update, SourceFile xx,
                                        SourceFile shared, ModBuilder.Progress progress, BooleanSupplier cancelled)
            throws ModBuilder.Failed {
        return ModBuilder.apply(NATIVE, new ModBuilder.Request(set, game, update, xx, shared, swap, true), progress,
                cancelled);
    }

    /** {@link #apply} over plain paths, the staging and installed folders as the update job names them. */
    public static ModBuilder.Made apply(File modDir, File staging, File set, File game, File update, File xx, File shared,
                                        ModBuilder.Progress progress, BooleanSupplier cancelled) throws ModBuilder.Failed {
        return apply(new ModSwap(modDir, staging, new File(modDir.getParentFile(), LauncherMod.OLD)), SourceFile.of(set),
                SourceFile.of(game), SourceFile.of(update), xx == null ? null : SourceFile.of(xx),
                shared == null ? null : SourceFile.of(shared), progress, cancelled);
    }
}
