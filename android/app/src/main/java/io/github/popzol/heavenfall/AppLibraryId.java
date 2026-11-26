package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The code library's id an APK carries ({@code assets/code_library_id.txt}, written by the app's build). */
public final class AppLibraryId {
    public static final String ENTRY = "assets/code_library_id.txt";
    private static final int MAX = 128;

    private AppLibraryId() {
    }

    /** The id inside {@code apk}, or "" when it has none or cannot be read. */
    public static String read(File apk) {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null || entry.getSize() > MAX) return "";
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] b = new byte[MAX];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
            }
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
