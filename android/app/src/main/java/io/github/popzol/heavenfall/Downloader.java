package io.github.popzol.heavenfall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads one release file into a folder. The bytes go to {@code <name>.part}; a cut connection or a cancel leaves
 * the part and the next call goes on from it (an HTTP range). The size and the SHA-256 the release states are
 * checked while the bytes stream, and the part is renamed to its name only when both match: a file that is there
 * under its name is a good one. Free space is checked before anything is asked.
 */
public final class Downloader {
    /** The free space left over after the file: the system and the other steps need some. */
    static final long RESERVE_BYTES = 8L * 1024 * 1024;
    private static final int BUFFER = 64 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,96}");

    /** Free bytes for a folder. */
    public interface Space {
        long usable(File dir);
    }

    private final UrlMap map;
    private final Space space;

    public Downloader(UrlMap map) {
        this(map, new Space() {
            @Override
            public long usable(File dir) {
                return dir.getUsableSpace();
            }
        });
    }

    public Downloader(UrlMap map, Space space) {
        this.map = map;
        this.space = space;
    }

    public static File partOf(File dir, String name) {
        return new File(dir, name + ".part");
    }

    /**
     * The file of {@code asset} in {@code dir}, downloaded (or went on from its part, or found already there and
     * good). {@code progress} gets 0..1 as bytes arrive.
     */
    public File download(ReleaseRules.Asset asset, File dir, Fraction progress, Control control) throws UpdateException {
        if (!FILE_NAME.matcher(asset.name).matches()) throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, asset.name);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new UpdateException(UpdateFailure.CANNOT_WRITE, dir.getPath());
        }
        File target = new File(dir, asset.name);
        File part = partOf(dir, asset.name);
        if (target.isFile()) {
            if (target.length() == asset.size && asset.sha256.equals(sha256(target, control))) {
                progress.fraction(1.0);
                return target;
            }
            deleteQuietly(target);
        }
        long have = part.isFile() ? part.length() : 0;
        if (have > asset.size) {
            deleteQuietly(part);
            have = 0;
        }
        if (have == asset.size) {
            if (asset.sha256.equals(sha256(part, control))) return finish(part, target, progress);
            deleteQuietly(part);
            have = 0;
        }
        if (space.usable(dir) < asset.size - have + RESERVE_BYTES) {
            throw new UpdateException(UpdateFailure.NO_SPACE, asset.name);
        }
        for (int attempt = 0; ; attempt++) {
            control.check();
            Outcome outcome = fetch(asset, part, have, progress, control);
            if (outcome == Outcome.DONE) return finish(part, target, progress);
            // the server did not honour the range, or the part cannot be continued: once again from zero
            if (attempt >= 1 || have == 0) throw new UpdateException(UpdateFailure.SERVER, "range");
            deleteQuietly(part);
            have = 0;
        }
    }

    private enum Outcome { DONE, RESTART }

    private Outcome fetch(ReleaseRules.Asset asset, File part, long have, Fraction progress, Control control)
            throws UpdateException {
        try (Http.Response r = Http.get(asset.url, false, map, have, "application/octet-stream")) {
            boolean append = false;
            if (have > 0 && r.status == 416) return Outcome.RESTART;
            r.requireOk();
            if (r.status == 206) {
                Matcher m = CONTENT_RANGE.matcher(String.valueOf(r.header("Content-Range")));
                if (!m.matches() || Long.parseLong(m.group(1)) != have) return Outcome.RESTART;
                if (Long.parseLong(m.group(3)) != asset.size) {
                    deleteQuietly(part);
                    throw new UpdateException(UpdateFailure.BAD_SIZE, asset.name);
                }
                append = true;
            } else {
                long length = r.contentLength();
                if (length >= 0 && length != asset.size) {
                    deleteQuietly(part);
                    throw new UpdateException(UpdateFailure.BAD_SIZE, asset.name);
                }
                have = 0;
            }
            MessageDigest digest = newDigest();
            if (append) hashInto(part, digest, have, control);
            long total = have;
            try (FileOutputStream out = new FileOutputStream(part, append); InputStream in = r.body()) {
                byte[] buffer = new byte[BUFFER];
                while (true) {
                    control.check();
                    int n;
                    try {
                        n = in.read(buffer);
                    } catch (IOException e) {
                        out.flush();
                        throw new UpdateException(UpdateFailure.NETWORK, asset.name, e);
                    }
                    if (n < 0) break;
                    total += n;
                    if (total > asset.size) {
                        out.close();
                        deleteQuietly(part);
                        throw new UpdateException(UpdateFailure.BAD_SIZE, asset.name);
                    }
                    try {
                        out.write(buffer, 0, n);
                    } catch (IOException e) {
                        String text = String.valueOf(e.getMessage());
                        boolean full = text.contains("ENOSPC") || text.toLowerCase(Locale.ROOT).contains("no space");
                        throw new UpdateException(full ? UpdateFailure.NO_SPACE : UpdateFailure.CANNOT_WRITE, asset.name, e);
                    }
                    digest.update(buffer, 0, n);
                    progress.fraction((double) total / asset.size);
                }
                out.getFD().sync();
            }
            if (total < asset.size) throw new UpdateException(UpdateFailure.NETWORK, "ended early: " + asset.name);
            if (!asset.sha256.equals(hex(digest.digest()))) {
                deleteQuietly(part);
                throw new UpdateException(UpdateFailure.BAD_DIGEST, asset.name);
            }
            return Outcome.DONE;
        } catch (IOException e) {
            throw new UpdateException(UpdateFailure.CANNOT_WRITE, asset.name, e);
        }
    }

    private static File finish(File part, File target, Fraction progress) throws UpdateException {
        try {
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UpdateException(UpdateFailure.CANNOT_WRITE, target.getName(), e);
        }
        progress.fraction(1.0);
        return target;
    }

    // ---- hashing

    /** The SHA-256 of a whole file, as 64 lower-case hex digits. */
    public static String sha256(File file, Control control) throws UpdateException {
        MessageDigest digest = newDigest();
        hashInto(file, digest, file.length(), control);
        return hex(digest.digest());
    }

    private static void hashInto(File file, MessageDigest digest, long bytes, Control control) throws UpdateException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            long left = bytes;
            while (left > 0) {
                control.check();
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) break;
                digest.update(buffer, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            throw new UpdateException(UpdateFailure.CANNOT_WRITE, file.getName(), e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }
}
