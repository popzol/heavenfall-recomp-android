package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The game's {@code load/mods/load_order.txt} (mh4n's mod list: the mods in load order, one per line; {@code # off: name}
 * keeps one off; a folder the file does not name is on). Every change is written whole ({@link Records#write}): a kill
 * or a full disk leaves the old file or the new one, never an empty one that would turn every mod on.
 */
public final class ModOrder {
    public static final String FILE = "load_order.txt";
    static final String HEADER = "# Written by the app: the mods in load order, one per line; \"# off: name\" keeps one that is off.";
    private static final String OFF = "# off: ";

    private ModOrder() {
    }

    /**
     * The lines with {@code name} off. {@code turnOff}: a line that has it on becomes {@code # off: name} in its place
     * (a code mod); without it, a name the file has already is left as it is. A name it does not have is added off.
     */
    public static List<String> withOff(List<String> lines, String name, boolean turnOff) {
        List<String> out = new ArrayList<>(lines);
        for (int i = 0; i < out.size(); i++) {
            String t = out.get(i).trim();
            if (t.equals(OFF + name)) return out;
            if (t.equals(name)) {
                if (turnOff) out.set(i, OFF + name);
                return out;
            }
        }
        if (out.isEmpty()) out.add(HEADER);
        out.add(OFF + name);
        return out;
    }

    /** {@link #withOff} on the file in {@code mods}, written whole; an unreadable file is an error, never "empty". */
    public static void keepOff(File mods, String name, boolean turnOff) throws IOException {
        File file = new File(mods, FILE);
        List<String> lines = file.isFile() ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
                : Collections.<String>emptyList();
        List<String> next = withOff(lines, name, turnOff);
        if (next.equals(lines)) return;
        Records.write(file, String.join("\n", next) + "\n");
    }
}
