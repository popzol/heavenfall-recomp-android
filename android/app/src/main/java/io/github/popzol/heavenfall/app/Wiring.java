package io.github.popzol.heavenfall.app;

import android.content.Context;

/**
 * Where the parts that need the native library are put in place, once per process (the application class calls it, so
 * a job started by the update receiver or the service has them as well as the screens). Two seams are open:
 *
 * <ul>
 * <li>the dump reading: {@link PhoneReader#setIdentifier} takes a {@link io.github.popzol.heavenfall.DumpIdentifier};
 * until one is set the checks of the game folder's own reader are used (the game and its update; MH XX reads as
 * missing);</li>
 * <li>the mod's applier: {@link UpdateService#setApplier} takes a {@link io.github.popzol.heavenfall.ModApplier};
 * until one is set a stand-in that says so is used.</li>
 * </ul>
 */
final class Wiring {
    private static boolean done;

    private Wiring() {
    }

    static synchronized void install(Context context) {
        if (done) return;
        done = true;
        // The native reader of the three dumps and the native applier are set here:
        //     PhoneReader.setIdentifier(new <the native identifier>());
        //     UpdateService.setApplier(new <the native applier>(context));
    }
}
