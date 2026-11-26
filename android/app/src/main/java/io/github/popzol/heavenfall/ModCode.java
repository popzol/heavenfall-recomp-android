package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The digest of what in the launcher's mod becomes code: every file of the mod's folder outside {@code romfs/}
 * ({@code exefs/code.bin}, {@code exheader.bin}, {@code recomp.json}, {@code mod.json}, anything else put there) and
 * the packs ({@code romfs/packs/}), which the code library's build compiles too. The game's other files in
 * {@code romfs/} are data and are left out (they are big, and no build reads them).
 *
 * <p>The job records it when the mod is applied ({@code installed.json}'s {@code mod_code_sha256}) and the library's
 * build records the one it was built from; before a build and before the game starts the folder's digest is taken
 * again, so a mod changed outside the app is never built nor started. About 20 MB is hashed (the executable), a
 * symbolic link counts by its target and is never followed.
 */
public final class ModCode {
    private ModCode() {
    }

    /** What the folder has now: its digest, or null when the folder or its executable is missing. */
    public static String digest(File modDir) throws IOException {
        if (modDir == null || !modDir.isDirectory() || !new File(modDir, "exefs/code.bin").isFile()) return null;
        List<String> lines = new ArrayList<>();
        walk(modDir, "", lines);
        Collections.sort(lines);
        MessageDigest all = sha();
        for (String line : lines) all.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        return hex(all.digest());
    }

    /** The folder's digest, or null when it is missing or cannot be read (taken as "changed" by the callers). */
    public static String digestOrNull(File modDir) {
        try {
            return digest(modDir);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void walk(File dir, String prefix, List<String> lines) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) throw new IOException("cannot list " + dir);
        for (File kid : kids) {
            String rel = prefix + kid.getName();
            String low = rel.toLowerCase(Locale.ROOT);
            boolean code = !(low.equals("romfs") || low.startsWith("romfs/"))
                    || low.equals("romfs/packs") || low.startsWith("romfs/packs/");
            if (Files.isSymbolicLink(kid.toPath())) {
                if (code || low.equals("romfs")) lines.add("link " + rel + " " + Files.readSymbolicLink(kid.toPath()));
            } else if (kid.isDirectory()) {
                if (low.equals("romfs")) {
                    // romfs/: only the packs are code; the rest is the game's data
                    File[] parts = kid.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).equals("packs"));
                    if (parts != null) for (File packs : parts) walkOne(packs, rel + "/" + packs.getName(), lines);
                } else if (code) {
                    walk(kid, rel + "/", lines);
                }
            } else if (code) {
                lines.add("file " + rel + " " + kid.length() + " " + fileSha(kid));
            }
        }
    }

    private static void walkOne(File packs, String rel, List<String> lines) throws IOException {
        if (Files.isSymbolicLink(packs.toPath())) lines.add("link " + rel + " " + Files.readSymbolicLink(packs.toPath()));
        else if (packs.isDirectory()) walk(packs, rel + "/", lines);
        else lines.add("file " + rel + " " + packs.length() + " " + fileSha(packs));
    }

    private static String fileSha(File f) throws IOException {
        return InstalledStore.sha256(f);
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
