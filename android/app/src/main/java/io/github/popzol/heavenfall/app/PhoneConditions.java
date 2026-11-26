package io.github.popzol.heavenfall.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import io.github.popzol.heavenfall.UpdateRunner;

/**
 * The phone's free space, memory and battery, read when they are asked for: by the home screen (its blocks) and, the
 * battery, by the update job when its build step starts ({@link UpdateRunner.Conditions}). -1 (and not charging) when
 * Android does not say.
 */
final class PhoneConditions implements UpdateRunner.Conditions {
    private final Context context;

    PhoneConditions(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The free bytes where the launcher writes: its private folder and the home with the mod (the smaller). */
    public long freeBytes() {
        long a = freeAt(Locations.base(context));
        long b = freeAt(Locations.home(context));
        if (a < 0) return b;
        return b < 0 ? a : Math.min(a, b);
    }

    /**
     * The phone's memory in all with its swap, in MB, as the build counts it ({@code /proc/meminfo}'s MemTotal and
     * SwapTotal); Android's total memory when the file cannot be read.
     */
    public long memoryInAllMb() {
        long kib = -1;
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = -1, swap = 0;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = kib(line);
                else if (line.startsWith("SwapTotal:")) swap = Math.max(0, kib(line));
            }
            if (total > 0) kib = total + swap;
        } catch (IOException | RuntimeException e) {
            kib = -1;
        }
        if (kib > 0) return kib >> 10;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return -1;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.totalMem > 0 ? info.totalMem >> 20 : -1;
    }

    private static long kib(String line) {
        String[] w = line.trim().split("\\s+");
        return w.length >= 2 ? Long.parseLong(w[1]) : -1;
    }

    @Override
    public int batteryPercent() {
        Intent battery = battery();
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level >= 0 && scale > 0 ? (int) (level * 100L / scale) : -1;
    }

    @Override
    public boolean charging() {
        Intent battery = battery();
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private Intent battery() {
        return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private static long freeAt(File dir) {
        File d = dir;
        while (d != null && !d.isDirectory()) d = d.getParentFile();
        if (d == null) return -1;
        try {
            return new StatFs(d.getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
