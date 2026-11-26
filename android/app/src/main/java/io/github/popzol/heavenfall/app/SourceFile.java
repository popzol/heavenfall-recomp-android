package io.github.popzol.heavenfall.app;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import io.github.popzol.heavenfall.ModBuilder;

/**
 * A file of the player's or the release's, reached either as a plain path (the folder browser, with access to all
 * files) or through Android's document picker (a content address): either way the native layer gets a file descriptor
 * and reads it with pread, so nothing is copied.
 */
public final class SourceFile implements ModBuilder.Input {
    private final File file;
    private final ContentResolver resolver;
    private final Uri uri;

    private SourceFile(File file, ContentResolver resolver, Uri uri) {
        this.file = file;
        this.resolver = resolver;
        this.uri = uri;
    }

    public static SourceFile of(File file) {
        return new SourceFile(file, null, null);
    }

    public static SourceFile of(ContentResolver resolver, Uri uri) {
        return new SourceFile(null, resolver, uri);
    }

    @Override
    public ModBuilder.Handle open() throws IOException {
        ParcelFileDescriptor pfd;
        if (file != null) {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } else {
            try {
                pfd = resolver.openFileDescriptor(uri, "r");
            } catch (SecurityException | FileNotFoundException e) {
                throw new IOException("cannot open " + uri, e);
            }
            if (pfd == null) throw new IOException("no file behind " + uri);
        }
        final ParcelFileDescriptor opened = pfd;
        return new ModBuilder.Handle() {
            @Override
            public int fd() {
                return opened.getFd();
            }

            @Override
            public void close() throws IOException {
                opened.close();
            }
        };
    }

    @Override
    public String toString() {
        return file != null ? file.getPath() : String.valueOf(uri);
    }
}
