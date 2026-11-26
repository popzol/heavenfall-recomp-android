package io.github.popzol.heavenfall;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** F5: the mods' order file keeps a new code mod off, and is never left empty (an empty file turns every mod on). */
public class ModOrderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Catches a code mod left on (its line kept), the order changed, and a mod the file lacks left out (on). */
    @Test
    public void aNameIsTurnedOffInItsPlace() {
        List<String> lines = Arrays.asList("# x", "a", "b", "c");
        assertEquals(Arrays.asList("# x", "a", "# off: b", "c"), ModOrder.withOff(lines, "b", true));
        assertEquals("a data mod the player turned on stays on", lines, ModOrder.withOff(lines, "b", false));
        List<String> off = Arrays.asList("a", "# off: b");
        assertEquals(off, ModOrder.withOff(off, "b", true));
        assertEquals(Arrays.asList("a", "b", "c", "# off: d"), ModOrder.withOff(Arrays.asList("a", "b", "c"), "d", false));
        assertEquals(Arrays.asList(ModOrder.HEADER, "# off: d"), ModOrder.withOff(Collections.<String>emptyList(), "d", true));
    }

    /** Catches the file written in place, and one the app cannot read taken as empty and written over. */
    @Test
    public void writtenWholeAndNeverOverAnUnreadableFile() throws IOException {
        File mods = tmp.newFolder("mods");
        File file = new File(mods, ModOrder.FILE);
        ModOrder.keepOff(mods, "code mod", true);
        assertEquals(Arrays.asList(ModOrder.HEADER, "# off: code mod"), Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        Files.write(file.toPath(), "a\ncode mod\n".getBytes(StandardCharsets.UTF_8));
        ModOrder.keepOff(mods, "code mod", true);
        assertEquals(Arrays.asList("a", "# off: code mod"), Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        assertEquals(Arrays.asList(ModOrder.FILE), Arrays.asList(mods.list()));

        byte[] foreign = {'a', '\n', (byte) 0xFF, (byte) 0xFE, '\n'};
        Files.write(file.toPath(), foreign);
        try {
            ModOrder.keepOff(mods, "other", true);
            fail("a file that is not text is an error");
        } catch (IOException expected) {
            // named
        }
        assertArrayEquals("left as it was", foreign, Files.readAllBytes(file.toPath()));
    }
}
