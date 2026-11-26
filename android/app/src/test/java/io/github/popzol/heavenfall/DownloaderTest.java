package io.github.popzol.heavenfall;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DownloaderTest {
    private static final String REPO = "popzol/4u-heavenfall-mod-beta";
    private static final String NAME = "HeavenfallBeta-android-USA.hfpatch";
    private static final String URL_PATH = "/github/" + REPO + "/releases/download/v1.0/" + NAME;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeServer server;
    private File dir;
    private final Downloader.Space plenty = d -> Long.MAX_VALUE;

    @Before
    public void start() throws IOException {
        server = new FakeServer();
        dir = tmp.newFolder("downloads");
    }

    @After
    public void stop() {
        server.close();
    }

    static byte[] data(int size, int seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    static String sha(byte[] data) {
        try {
            return Downloader.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An asset as the rules would give it: GitHub's own address, the size and digest the release states. */
    static ReleaseRules.Asset asset(byte[] data) {
        return new ReleaseRules.Asset(NAME, data.length, sha(data), "https://github.com/" + REPO + "/releases/download/v1.0/" + NAME);
    }

    private Downloader downloader() {
        return new Downloader(server.map(), plenty);
    }

    private final Fraction ignore = f -> { };

    private UpdateException failure(Runnable0 r) {
        try {
            r.run();
        } catch (UpdateException e) {
            return e;
        }
        fail("the download should have failed");
        return null;
    }

    private interface Runnable0 {
        void run() throws UpdateException;
    }

    /** A good download arrives under its name, byte for byte, with no ".part" left. */
    @Test
    public void downloadsAndRenames() throws Exception {
        byte[] data = data(300_000, 1);
        server.file(URL_PATH, data);
        File f = downloader().download(asset(data), dir, ignore, new Control());
        assertEquals(NAME, f.getName());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertFalse(Downloader.partOf(dir, NAME).exists());
    }

    /** A cut connection keeps the part and the next call asks only for the bytes after it (a Range request). */
    @Test
    public void resumesAfterACut() throws Exception {
        byte[] data = data(400_000, 2);
        server.fileCutOnce(URL_PATH, data, 150_000);
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.NETWORK, e.failure);
        File part = Downloader.partOf(dir, NAME);
        assertTrue(part.isFile());
        long have = part.length();
        assertTrue("some bytes kept", have > 0 && have < data.length);
        File f = downloader().download(asset(data), dir, ignore, new Control());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertEquals("bytes=" + have + "-", server.ranges.get(server.ranges.size() - 1));
        assertFalse(part.exists());
    }

    /** A server that ignores the Range header sends the whole file: the part is overwritten, not appended to. */
    @Test
    public void aServerThatIgnoresRangeStartsOver() throws Exception {
        byte[] data = data(200_000, 3);
        server.fileIgnoringRange(URL_PATH, data);
        Files.write(Downloader.partOf(dir, NAME).toPath(), java.util.Arrays.copyOf(data, 50_000));
        File f = downloader().download(asset(data), dir, ignore, new Control());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    /** A part that the server refuses to continue (416) is dropped and the file comes whole. */
    @Test
    public void aRefusedRangeStartsOver() throws Exception {
        byte[] data = data(100_000, 4);
        server.route(URL_PATH, ex -> {
            if (ex.getRequestHeaders().getFirst("Range") != null) {
                ex.sendResponseHeaders(416, -1);
                return;
            }
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
        });
        Files.write(Downloader.partOf(dir, NAME).toPath(), new byte[1000]);
        File f = downloader().download(asset(data), dir, ignore, new Control());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    /** More bytes than the release states: refused, and nothing is left under the name or as a part. */
    @Test
    public void tooManyBytesIsAWrongSize() throws Exception {
        byte[] data = data(10_000, 5);
        byte[] more = java.util.Arrays.copyOf(data, 10_050);
        server.file(URL_PATH, more);
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.BAD_SIZE, e.failure);
        assertEquals(0, dir.list().length);
    }

    /** A file announced shorter than the release says is refused before it is written. */
    @Test
    public void tooFewBytesIsAWrongSize() throws Exception {
        byte[] data = data(10_000, 6);
        server.file(URL_PATH, java.util.Arrays.copyOf(data, 9_000));
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.BAD_SIZE, e.failure);
        assertEquals(0, dir.list().length);
    }

    /** The right size with other bytes: refused at the end, the part is thrown away, nothing is renamed. */
    @Test
    public void aWrongDigestIsRefused() throws Exception {
        byte[] data = data(50_000, 7);
        byte[] other = data.clone();
        other[1234] ^= 0x55;
        server.file(URL_PATH, other);
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.BAD_DIGEST, e.failure);
        assertEquals(0, dir.list().length);
    }

    /** A part whose first bytes are wrong is found out by the digest after it is completed. */
    @Test
    public void aCorruptPartIsFoundByTheDigest() throws Exception {
        byte[] data = data(80_000, 8);
        server.file(URL_PATH, data);
        byte[] bad = java.util.Arrays.copyOf(data, 30_000);
        bad[10] ^= 1;
        Files.write(Downloader.partOf(dir, NAME).toPath(), bad);
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.BAD_DIGEST, e.failure);
        assertEquals(0, dir.list().length);
        // the next try starts clean and works
        File f = downloader().download(asset(data), dir, ignore, new Control());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    /** A redirect to a host that is not the test server (and, in a release build, not on the list) is refused. */
    @Test
    public void aRedirectOffTheListIsRefused() throws Exception {
        byte[] data = data(1000, 9);
        server.redirect(URL_PATH, "https://evil.example/file");
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.UNTRUSTED_ADDRESS, e.failure);
        assertEquals(0, dir.list().length);
    }

    /** A redirect to plain http on another host, or to another port of the same host, is refused too. */
    @Test
    public void aRedirectToAnotherPortIsRefused() throws Exception {
        byte[] data = data(1000, 10);
        server.redirect(URL_PATH, "http://127.0.0.1:1/file");
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.UNTRUSTED_ADDRESS, e.failure);
    }

    /** A redirect inside the allowed place is followed (the release page sends the file to its storage). */
    @Test
    public void aGoodRedirectIsFollowed() throws Exception {
        byte[] data = data(5000, 11);
        server.redirect(URL_PATH, server.base() + "/github/storage/blob");
        server.file("/github/storage/blob", data);
        File f = downloader().download(asset(data), dir, ignore, new Control());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    /** A redirect loop ends instead of going on for ever. */
    @Test
    public void tooManyRedirectsStop() throws Exception {
        byte[] data = data(1000, 12);
        server.redirect(URL_PATH, server.base() + URL_PATH);
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.UNTRUSTED_ADDRESS, e.failure);
        assertTrue(server.count(URL_PATH) <= Http.MAX_REDIRECTS + 1);
    }

    /** Without the room for the file nothing is asked of the server. */
    @Test
    public void freeSpaceIsCheckedFirst() throws Exception {
        byte[] data = data(1000, 13);
        server.file(URL_PATH, data);
        Downloader small = new Downloader(server.map(), d -> data.length);   // the file fits, the reserve does not
        UpdateException e = failure(() -> small.download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.NO_SPACE, e.failure);
        assertEquals(0, server.count(URL_PATH));
    }

    /** The space needed counts the part already there: a resume needs only what is missing. */
    @Test
    public void aPartCountsAgainstTheSpaceNeeded() throws Exception {
        byte[] data = data(1_000_000, 14);
        server.file(URL_PATH, data);
        Files.write(Downloader.partOf(dir, NAME).toPath(), java.util.Arrays.copyOf(data, 900_000));
        Downloader d = new Downloader(server.map(), x -> 100_000 + Downloader.RESERVE_BYTES);
        File f = d.download(asset(data), dir, ignore, new Control());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    /** A cancel stops between two chunks, keeps the part and says so. */
    @Test
    public void cancelKeepsThePart() throws Exception {
        byte[] data = data(600_000, 15);
        server.file(URL_PATH, data);
        Control control = new Control();
        UpdateException e = failure(() -> downloader().download(asset(data), dir, f -> {
            if (f > 0.3) control.cancel();
        }, control));
        assertTrue(e.cancelled());
        assertTrue(Downloader.partOf(dir, NAME).isFile());
        assertFalse(new File(dir, NAME).exists());
    }

    /** A file already there and good is not downloaded again; a bad one is replaced. */
    @Test
    public void anExistingGoodFileIsKept() throws Exception {
        byte[] data = data(20_000, 16);
        server.file(URL_PATH, data);
        Files.write(new File(dir, NAME).toPath(), data);
        downloader().download(asset(data), dir, ignore, new Control());
        assertEquals(0, server.count(URL_PATH));
        Files.write(new File(dir, NAME).toPath(), new byte[20_000]);
        File f = downloader().download(asset(data), dir, ignore, new Control());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertEquals(1, server.count(URL_PATH));
    }

    /** An error status is a named failure with the status as its detail. */
    @Test
    public void anErrorStatusIsAServerFailure() throws Exception {
        byte[] data = data(1000, 17);
        server.status(URL_PATH, 503);
        UpdateException e = failure(() -> downloader().download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.SERVER, e.failure);
        assertEquals("503", e.detail);
        assertEquals(1, e.reason.args.size());
    }

    /** No server at all is the network failure, not a crash. */
    @Test
    public void aDeadServerIsANetworkFailure() throws Exception {
        byte[] data = data(1000, 18);
        UrlMap map = server.map();
        server.close();
        Downloader d = new Downloader(map, plenty);
        UpdateException e = failure(() -> d.download(asset(data), dir, ignore, new Control()));
        assertEquals(UpdateFailure.NETWORK, e.failure);
    }

    /** The hosts: https on GitHub's release hosts only (no other host, scheme, port or user, no look-alikes). */
    @Test
    public void onlyReleaseHostsAreAllowed() throws Exception {
        UrlMap none = UrlMap.NONE;
        String[] good = {"https://github.com/o/r/releases/download/v1/a", "https://objects.githubusercontent.com/x",
                "https://release-assets.githubusercontent.com/x", "https://GITHUB.com/x"};
        for (String u : good) assertTrue(u, Hosts.allowed(new URL(u), false, none));
        String[] bad = {"http://github.com/x", "https://github.com.evil.example/x", "https://evilgithub.com/x",
                "https://user@github.com/x", "https://github.com:8443/x", "https://api.github.com/x",
                "ftp://github.com/x", "https://githubusercontent.com/x"};
        for (String u : bad) assertFalse(u, Hosts.allowed(new URL(u), false, none));
        assertTrue(Hosts.allowed(new URL("https://api.github.com/repos/o/r/releases/latest"), true, none));
        assertFalse(Hosts.allowed(new URL("https://github.com/x"), true, none));
    }
}
