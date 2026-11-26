package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class PureJavaTest {
    /** The logic runs in a plain JVM only while it imports nothing from Android (org.json is the one allowed). */
    @Test
    public void noAndroidImports() throws Exception {
        File dir = new File("src/main/java/io/github/popzol/heavenfall");
        if (!dir.exists()) dir = new File("app/src/main/java/io/github/popzol/heavenfall");
        if (!dir.exists()) dir = new File("android/app/src/main/java/io/github/popzol/heavenfall");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        assertTrue(files != null && files.length > 0);
        List<String> found = new ArrayList<>();
        for (File f : files) {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("import ") && (s.startsWith("import android.") || s.startsWith("import androidx.")
                        || s.startsWith("import com.mh4u.") || s.startsWith("import dalvik."))) {
                    found.add(f.getName() + ": " + s);
                }
            }
        }
        assertEquals(new ArrayList<String>(), found);
    }
}
