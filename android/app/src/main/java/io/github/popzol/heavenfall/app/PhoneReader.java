package io.github.popzol.heavenfall.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import com.mh4u.port.HeavenfallBridge;
import com.mh4u.port.HeavenfallScreens;

import java.io.File;

import io.github.popzol.heavenfall.DumpIdentifier;
import io.github.popzol.heavenfall.DumpScan;
import io.github.popzol.heavenfall.LibraryBuilder;
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
    private static volatile DumpIdentifier identifier = new UpstreamDumpIdentifier();

    private PhoneReader() {
    }

    /** The reader of the three dumps (the native one replaces the checks of the game folder's own reader). */
    public static void setIdentifier(DumpIdentifier replacement) {
        identifier = replacement == null ? new UpstreamDumpIdentifier() : replacement;
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
        HeavenfallScreens.ensureHome(c);
        f.appVersion = appVersion(c);
        f.permission = HeavenfallScreens.hasAccess(c);
        File saved = HeavenfallScreens.savedFolder(c);
        File folder = f.permission && saved != null && saved.isDirectory() ? saved : null;
        f.folder = folder;
        if (folder != null) f.found = DumpScan.scan(folder, identifier);
        File base = Locations.base(c);
        base.mkdirs();
        f.installed = UpdateCheck.readInstalled(new File(base, "installed.json"));
        f.wantedId = HeavenfallBridge.wantedId(c);
        if (folder != null) {
            LibraryBuilder.State library = new PhoneLibraryBuilder(c, folder).state();
            f.libraryPresent = library.present;
            f.libraryIdOnDisk = library.id;
        }
        f.freeBytes = free(base, folder);
        f.availableMemoryMb = availableMemoryMb(c);
        battery(c, f);
        f.job = PhoneFacts.effectiveJob(UpdateService.jobState(c), UpdateService.progress(),
                UpdateService.savedRequest(c) != null);
        return f;
    }

    private static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The free bytes where the downloads go and where the library goes: the smaller of the two. */
    private static long free(File base, File folder) {
        long a = freeAt(base);
        long b = folder == null ? -1 : freeAt(folder);
        if (a < 0) return b;
        return b < 0 ? a : Math.min(a, b);
    }

    private static long freeAt(File dir) {
        try {
            return new StatFs(dir.getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long availableMemoryMb(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return -1;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem >> 20;
    }

    private static void battery(Context c, PhoneFacts f) {
        Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) f.batteryPercent = (int) (level * 100L / scale);
        f.charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }
}
