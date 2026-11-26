package io.github.popzol.heavenfall;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The launcher's small records on disk ({@code installed.json}, {@code job.json}, {@code resume.json}, the auto-play
 * token, the mods' order file and mark): each written to a temporary file, synced, renamed over the old one and the
 * folder synced, so a cut at any moment leaves the old record or the new one; read back with a size cap, so a file
 * that is not one of the app's is read as none.
 */
public final class Records {
    /** A record bigger than this is not one the app wrote: it is read as none. */
    public static final int MAX_BYTES = 256 * 1024;

    /** Makes a folder's new entries reach the disk; the Android side sets one through the system's own calls. */
    public interface FolderSync {
        void sync(File folder) throws IOException;
    }

    private static volatile FolderSync folderSync = Records::channelSync;

    private Records() {
    }

    /** The folder sync to use ({@code Wiring} sets Android's); null puts back the one through a file channel. */
    public static void setFolderSync(FolderSync sync) {
        folderSync = sync == null ? Records::channelSync : sync;
    }

    /** Writes {@code text} to {@code file} whole or not at all; the folder is made when it is missing. */
    public static void write(File file, String text) throws IOException {
        File target = file.getAbsoluteFile();
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("no folder " + dir);
        File temp = new File(dir, target.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        syncFolder(dir);
    }

    /** The folder synced where the platform allows (a folder cannot be opened for it on every system): false when not. */
    public static boolean syncFolder(File dir) {
        if (dir == null) return false;
        try {
            folderSync.sync(dir);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void channelSync(File dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /** The record's text, or null when the file is missing, cannot be read or is bigger than {@link #MAX_BYTES}. */
    public static String read(File file) {
        try {
            if (file == null || !file.isFile() || file.length() > MAX_BYTES) return null;
            byte[] bytes = Files.readAllBytes(file.toPath());
            return bytes.length > MAX_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Deletes a record (a missing one is fine); false when it is still there. */
    public static boolean delete(File file) {
        return !file.exists() || file.delete() || !file.exists();
    }
}
