package io.github.popzol.heavenfall.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.mh4u.port.HeavenfallBridge;
import com.mh4u.port.HeavenfallScreens;

import java.io.File;

import io.github.popzol.heavenfall.DumpIdentifier;
import io.github.popzol.heavenfall.DumpScan;
import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.LauncherMod;
import io.github.popzol.heavenfall.ModCode;
import io.github.popzol.heavenfall.PhoneFacts;
import io.github.popzol.heavenfall.UpdateCheck;

/**
 * Reads the phone into {@link PhoneFacts}: the permission and the game folder as the entry flow decides them, the
 * three dumps in the folder, {@code installed.json}, the code library, free space, available memory, battery, the
 * job and the app's version. The files are read off the main thread ({@link #readAsync}); the check for the latest
 * versions is {@link UpdateChecker}'s.
 */
public final class PhoneReader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile DumpIdentifier identifier;

    private PhoneReader() {
    }

    /** The reader of the three dumps ({@link Wiring} sets the native one once per process). */
    public static void setIdentifier(DumpIdentifier replacement) {
        identifier = replacement;
    }

    /** Called on the main thread; {@code done} runs on the main thread with the facts. */
    public interface Done {
        void onRead(PhoneFacts facts);
    }

    public static void readAsync(Context context, Done done) {
        Context c = context.getApplicationContext();
        Thread t = new Thread(() -> {
            PhoneFacts facts = read(c);
            MAIN.post(() -> done.onRead(facts));
        }, "heavenfall-read");
        t.setDaemon(true);
        t.start();
    }

    /** Reads everything; reads files, so never on the main thread. */
    public static PhoneFacts read(Context c) {
        PhoneFacts f = new PhoneFacts();
        Wiring.install(c);
        HeavenfallScreens.ensureHome(c);
        Locations.recoverMod(c);   // before anything reads the mod: a swap cut short is put right
        f.appVersion = appVersion(c);
        f.permission = HeavenfallScreens.hasAccess(c);
        File saved = HeavenfallScreens.savedFolder(c);
        File folder = f.permission && saved != null && saved.isDirectory() ? saved : null;
        f.folder = folder;
        if (folder != null) f.found = DumpScan.scan(folder, identifier);
        Locations.base(c).mkdirs();
        f.installed = UpdateCheck.readInstalled(Locations.installedFile(c));
        // (about 25 MB hashed, a fraction of a second: the mod's code, against installed.json)
        if (f.installed != null) f.modCodeOnDisk = ModCode.digestOrNull(LauncherMod.modDir(Locations.home(c)));
        f.wantedId = HeavenfallBridge.wantedId(c);
        f.library = PhoneLibraryBuilder.stateOf(c);
        PhoneConditions conditions = new PhoneConditions(c);
        f.freeBytes = conditions.freeBytes();
        f.availableMemoryMb = conditions.availableMemoryMb();
        f.batteryPercent = conditions.batteryPercent();
        f.charging = conditions.charging();
        f.job = PhoneFacts.effectiveJob(UpdateService.running() ? HomeModel.Job.RUNNING : HomeModel.Job.NONE,
                UpdateService.jobState(c));
        return f;
    }

    private static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

}
