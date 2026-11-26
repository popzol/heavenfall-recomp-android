package io.github.popzol.heavenfall.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.mh4u.port.HeavenfallBridge;

import io.github.popzol.heavenfall.Installed;
import io.github.popzol.heavenfall.LauncherMod;
import io.github.popzol.heavenfall.ModCode;
import io.github.popzol.heavenfall.StartCheck;
import io.github.popzol.heavenfall.UpdateCheck;

/**
 * The entry flow's check before the game starts ({@link StartCheck}), read from the phone: {@code installed.json} in
 * the private storage, the mod's folder and the library there. Files only: off the main thread.
 */
public final class StartGate {
    private StartGate() {
    }

    /** What stops the start, or null when the game may start. */
    public static StartCheck.Problem problem(Context context) {
        Context c = context.getApplicationContext();
        Wiring.install(c);
        Installed installed = UpdateCheck.readInstalled(Locations.installedFile(c));
        String code = installed == null ? null : ModCode.digestOrNull(LauncherMod.modDir(Locations.home(c)));
        StartCheck.Problem p = StartCheck.check(installed, code, PhoneLibraryBuilder.stateOf(c), HeavenfallBridge.wantedId(c));
        if (p != null) android.util.Log.w(UpdateService.TAG, "start refused: " + p);
        return p;
    }

    /** The sentence for a refused start, in the app's language. */
    public static String text(Context c, StartCheck.Problem problem) {
        return Texts.resolve(c, problem.text);
    }

    /** A refused start: the player is told why and Home opens (it offers what fixes it); {@code from} closes. */
    public static void refuse(Activity from, StartCheck.Problem problem) {
        Toast.makeText(from, Texts.resolve(from, problem.text), Toast.LENGTH_LONG).show();
        Intent home = new Intent(from, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        from.startActivity(home);
        from.finish();
    }
}
