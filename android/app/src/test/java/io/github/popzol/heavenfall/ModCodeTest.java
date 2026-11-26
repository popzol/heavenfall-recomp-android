package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** F2: the digest of what in the mod becomes code, checked before every build and start. */
public class ModCodeTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void put(File dir, String rel, String text) throws IOException {
        File f = new File(dir, rel);
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private File mod() throws IOException {
        File mod = tmp.newFolder("mod");
        put(mod, "exefs/code.bin", "code");
        put(mod, "exheader.bin", "header");
        put(mod, "romfs/packs/p1.bin", "pack");
        put(mod, "romfs/quest/q.mib", "data");
        return mod;
    }

    /**
     * Catches a change to the executable, the header, a pack or a new file beside them that the check misses (the
     * library would be built from code nobody recorded), and the game's data counted (a slow check for no reason).
     */
    @Test
    public void theCodeCountsAndTheDataDoesNot() throws IOException {
        File mod = mod();
        String before = ModCode.digest(mod);
        assertNotNull(before);
        put(mod, "romfs/quest/q.mib", "other data");
        put(mod, "romfs/new.arc", "more data");
        assertEquals("the game's data is not code", before, ModCode.digest(mod));
        String[][] changes = {{"exefs/code.bin", "code2"}, {"exheader.bin", "header2"}, {"romfs/packs/p1.bin", "pack2"},
                {"recomp.json", "{}"}, {"romfs/packs/sub/p2.bin", "x"}};
        String last = before;
        for (String[] c : changes) {
            put(mod, c[0], c[1]);
            String now = ModCode.digest(mod);
            assertNotEquals(c[0], last, now);
            last = now;
        }
        new File(mod, "recomp.json").renameTo(new File(mod, "recomp2.json"));
        assertNotEquals("a renamed file is a change", last, ModCode.digest(mod));
    }

    /** Catches a folder with no executable (or none at all) taken as a good mod. */
    @Test
    public void noExecutableIsNoDigest() throws IOException {
        File mod = mod();
        Files.delete(new File(mod, "exefs/code.bin").toPath());
        assertNull(ModCode.digest(mod));
        assertNull(ModCode.digestOrNull(new File(tmp.getRoot(), "none")));
        assertNull(ModCode.digestOrNull(null));
    }

    /** Catches a symbolic link followed (its target, anywhere, would be hashed or could change unseen). */
    @Test
    public void aLinkCountsByItsTarget() throws IOException {
        File mod = mod();
        File outside = tmp.newFile("outside.bin");
        try {
            Files.createSymbolicLink(new File(mod, "exefs/extra.bin").toPath(), outside.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assume.assumeNoException("this system makes no symbolic links", e);
        }
        String with = ModCode.digest(mod);
        Files.write(outside.toPath(), "changed".getBytes(StandardCharsets.UTF_8));
        assertEquals("the link's target is not read", with, ModCode.digest(mod));
    }
}
