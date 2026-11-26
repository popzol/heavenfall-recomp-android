package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The places where the launcher's Java meets files it cannot call: the native settings pages (C++), the build script
 * and upstream's activities. Each check reads the source of the other side, so a rename on one side shows here.
 */
public class WiringScanTest {
    private static File root() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            if (new File(dir, "app_native/content.h").isFile()) return dir;
        }
        throw new IllegalStateException("the repository's root was not found");
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(new File(root(), relative).toPath()), StandardCharsets.UTF_8);
    }

    private static final String JAVA = "android/app/src/main/java/";

    @Test
    public void theModsPageGuardsTheLaunchersModByItsName() throws Exception {
        // Catches the native Mods page guarding another name than the folder the launcher puts its mod in (the page
        // would then let it be switched off or removed), or the save, the switch and the removal losing a rule.
        Matcher m = Pattern.compile("kLauncherMod = \"([^\"]+)\"").matcher(read("app_native/content.h"));
        assertTrue(m.find());
        assertEquals(LauncherMod.NAME, m.group(1));
        String content = read("app_native/content.cpp");
        assertTrue("the save writes the launcher's mod on", content.contains("if (content_mod_is_launcher(m.name)) m.on = true;"));
        assertTrue("the save writes a mod with code off", content.contains("else if (m.code) m.on = false;"));
        assertTrue("the removal refuses the launcher's mod", content.contains("if (content_mod_is_launcher(name)) return false;"));
        String panel = read("app_native/settings_panel.cpp");
        assertTrue("the page asks before it turns a mod on or off", panel.contains("content_mod_switch(*mod, v)"));
        assertTrue("the page offers no Remove for the launcher's mod", panel.contains("!content_mod_is_launcher(name)) names.push_back(tr(\"Remove\"))"));
    }

    @Test
    public void theLibraryBuildGetsTheUserFolderTheModLivesIn() throws Exception {
        // Catches a build that reads another folder's mods than the one the launcher's mod is made in (the library
        // would then leave the mod's code out and the game would refuse it).
        assertTrue(read("mh4n/tools/android/phone_build.py").contains("--user-mods"));
        String builder = read(JAVA + "io/github/popzol/heavenfall/app/PhoneLibraryBuilder.java");
        assertTrue(builder.contains("MOD_FLAG = \"--user-mods\""));
        assertTrue(builder.contains("LauncherMod.userFolder(Locations.home(context))"));
        String locations = read(JAVA + "io/github/popzol/heavenfall/app/Locations.java");
        assertTrue(locations.contains("LauncherMod.modDir(home)") && locations.contains("LauncherMod.stagingDir(home)"));
    }

    @Test
    public void upstreamsWaysAroundTheLauncherLandOnIt() throws Exception {
        // Catches a way back into the app (the relaunch from Recents, a USB controller's start) or into upstream's own
        // builder that does not go through the launcher's Home.
        String phoenix = read(JAVA + "com/mh4u/port/PhoenixActivity.java");
        assertTrue(phoenix.contains("EXTRA_AUTO_PLAY"));
        String builder = read(JAVA + "com/mh4u/port/BuilderActivity.java");
        assertTrue(builder.contains("HeavenfallBridge.toLauncher(this)"));
        String game = read(JAVA + "com/mh4u/port/GameActivity.java");
        assertTrue(game.contains("HeavenfallBridge.buildInLauncher()"));
    }
}
