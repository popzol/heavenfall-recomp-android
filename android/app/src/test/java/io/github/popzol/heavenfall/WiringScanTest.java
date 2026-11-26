package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void theLibraryBuildCompilesTheLaunchersModAloneIntoThePrivateStorage() throws Exception {
        // Catches (F5) a build that compiles every code mod switched on in the mods folder instead of the launcher's
        // mod alone, and (F1) a build into, or a library read from, anywhere but the app's private storage.
        assertTrue(read("mh4n/tools/android/phone_build.py").contains("add_argument(\"--mod\""));
        String builder = read(JAVA + "io/github/popzol/heavenfall/app/PhoneLibraryBuilder.java");
        assertTrue(builder.contains("MOD_FLAG = \"--mod\""));
        assertTrue(builder.contains("libraryDir, MOD_FLAG, paths.modDir)"));
        String locations = read(JAVA + "io/github/popzol/heavenfall/app/Locations.java");
        assertTrue(locations.contains("LauncherMod.modDir(home)") && locations.contains("LauncherMod.stagingDir(home)"));
        assertTrue(locations.contains("return c.getFilesDir();"));
        assertTrue("the records are in the private storage",
                locations.contains("return new File(c.getFilesDir(), \"heavenfall\");"));
        String entry = read(JAVA + "com/mh4u/port/GameFolderActivity.java");
        assertTrue("the entry takes no library from the game folder", entry.contains("if (n.endsWith(\".so\")) continue;"));
        assertTrue(entry.contains("File own = new File(getFilesDir(), LIBRARY);"));
        assertTrue("the entry checks the record before a start", entry.contains("StartGate.problem(this)"));
        assertFalse("launch.txt names no library", entry.contains("\"\\ncode=\""));
        String main = read("app_native/android_main.cpp");
        assertFalse("the native start copies no library from shared storage", main.contains("CopyIfChanged(code_src, code)"));
        assertTrue(main.contains("else if (!FileExists(code)) problem = \"No code library at \" + code;"));
    }

    @Test
    public void adbsStartIsForDebugBuildsOnly() throws Exception {
        // Catches (F4) a release build that lets any caller naming the shell as its referrer start the game without
        // the entry's checks.
        String game = read(JAVA + "com/mh4u/port/GameActivity.java");
        assertTrue(game.contains("EntryRule.mayStart(entered, by, debuggable)"));
        assertTrue(game.contains("ApplicationInfo.FLAG_DEBUGGABLE"));
        assertFalse(game.contains("if (!entered && !\"com.android.shell\".equals(by))"));
    }

    @Test
    public void onlyTheLauncherAndTheGameAreExportedInARelease() throws Exception {
        // Catches (F6) a screen of the app other apps can open in a release build (the entry flow, More, the
        // assistant, MH XX's page), and a debug manifest that stops exporting them for the tests through adb.
        String manifest = read("android/app/src/main/AndroidManifest.xml");
        Matcher m = Pattern.compile("<(activity|service|receiver|provider)\\s+android:name=\"([^\"]+)\"[^>]*?android:exported=\"true\"",
                Pattern.DOTALL).matcher(manifest);
        java.util.List<String> exported = new java.util.ArrayList<>();
        while (m.find()) exported.add(m.group(2));
        assertEquals(java.util.Arrays.asList(".GameActivity", "io.github.popzol.heavenfall.app.HomeActivity"), exported);
        String debug = read("android/app/src/debug/AndroidManifest.xml");
        for (String name : new String[] {"com.mh4u.port.GameFolderActivity", "io.github.popzol.heavenfall.app.AssistantActivity",
                "io.github.popzol.heavenfall.app.MoreActivity", "io.github.popzol.heavenfall.app.XxActivity"}) {
            assertTrue(name, debug.contains("android:name=\"" + name + "\" android:exported=\"true\""));
        }
        String home = read(JAVA + "io/github/popzol/heavenfall/app/HomeActivity.java");
        assertTrue("Home's auto-play needs the one-time token", home.contains("AutoPlay.consume(getFilesDir()"));
        assertFalse(home.contains("getBooleanExtra(HeavenfallBridge.EXTRA_AUTO_PLAY"));
        String phoenix = read(JAVA + "com/mh4u/port/PhoenixActivity.java");
        assertTrue(phoenix.contains("AutoPlay.issue(getFilesDir()"));
    }

    @Test
    public void codeModsAreOffBeforeTheyAreLiveAndTheMarkIsWrittenWhole() throws Exception {
        // Catches (F5) an imported code mod renamed into load/mods before it is off, an order file truncated before it
        // is written, a game-folder mark written in place, and an empty mark read as "everything came from there".
        String imp = read(JAVA + "com/mh4u/port/ImportActivity.java");
        int off = imp.indexOf("if (code) io.github.popzol.heavenfall.ModOrder.keepOff(modsDir(), name, true);");
        int rename = imp.indexOf("if (!root.renameTo(to)) throw new Refused(\"unwritable\", to.getPath());");
        assertTrue(off > 0 && rename > off);
        assertFalse(imp.contains("            if (code) keepOff(modsDir(), name);"));
        String entry = read(JAVA + "com/mh4u/port/GameFolderActivity.java");
        assertTrue(entry.contains("io.github.popzol.heavenfall.Records.write(mark, list.toString());"));
        assertFalse(entry.contains("File[] old = any ? null : to.listFiles(File::isDirectory);"));
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

    @Test
    public void theBuildFollowsTheMemoryAndKeepsItsCacheInThePrivateStorage() throws Exception {
        // Catches the app's own memory rule passed as --jobs (the build follows the memory available all along the
        // compile; --jobs is the ceiling: the cores), the cache's line never reaching the time left, and an objects'
        // cache "start over" deletes or that lives outside the app's private files.
        String service = read(JAVA + "com/mh4u/port/BuilderService.java");
        assertTrue(service.contains("int cores = Math.max(1, Runtime.getRuntime().availableProcessors());"));
        assertFalse(service.contains("(free - 300) / 300"));
        assertTrue(service.contains("else HeavenfallBridge.builderLine(line);"));
        int start = service.indexOf("static void startOver(");
        String startOver = service.substring(start, service.indexOf("\n    }\n", start));
        assertFalse("start over keeps the objects' cache", startOver.contains("object-cache"));
        assertTrue(startOver.contains("deleteAside(work(c));"));
        String tools = read(JAVA + "com/mh4u/port/BuilderTools.java");
        assertTrue(tools.contains("static File root(Context c) { return new File(c.getFilesDir(), \"builder\"); }"));
        String bridge = read(JAVA + "com/mh4u/port/HeavenfallBridge.java");
        assertTrue(bridge.contains("return new File(BuilderTools.root(c), \"object-cache\");"));
        String phoneBuild = read("mh4n/tools/android/phone_build.py");
        assertTrue("the build's default cache is beside its work folder",
                phoneBuild.contains("os.path.join(os.path.dirname(os.path.abspath(args.work)), CACHE)"));
        assertTrue(phoneBuild.contains("CACHE = \"object-cache\""));
    }

    @Test
    public void discardCleansABuildCutShortOffTheMainThread() throws Exception {
        // Catches (F3) a Discard that leaves a cut build's work folder behind (hundreds of MB, and Resume would come
        // back), that deletes the objects' cache or the tools, or that deletes thousands of files on the main thread.
        String service = read(JAVA + "io/github/popzol/heavenfall/app/UpdateService.java");
        assertTrue(service.contains("ok &= com.mh4u.port.HeavenfallBridge.discardBuild(context, Locations.libraryDir(context));"));
        String bridge = read(JAVA + "com/mh4u/port/HeavenfallBridge.java");
        assertTrue(bridge.contains("BuilderService.startOver(app, libraryDir.getAbsolutePath(), false);"));
        assertTrue(bridge.contains("BuilderService.clearing = true;"));
        String home = read(JAVA + "io/github/popzol/heavenfall/app/HomeActivity.java");
        int at = home.indexOf("case DISCARD_JOB:");
        String discard = home.substring(at, home.indexOf("break;", at));
        int thread = discard.indexOf("new Thread(");
        assertTrue(thread >= 0 && thread < discard.indexOf("UpdateService.discard(app)"));
    }
}
