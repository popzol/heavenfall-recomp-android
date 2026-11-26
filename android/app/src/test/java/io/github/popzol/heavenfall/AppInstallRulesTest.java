package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppInstallRulesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Catches a library built by the running app being taken as the new app's when their tools differ (the new app
     * would then start with a library it cannot use): only the same id lets the build happen before the install.
     */
    @Test
    public void onlyTheSameLibraryIdBuildsBeforeTheInstall() {
        assertEquals(UpdatePlan.InstallAt.LAST, AppInstallRules.placement("33fc0ac05c73945c", "33fc0ac05c73945c"));
        assertEquals(UpdatePlan.InstallAt.AFTER_MOD, AppInstallRules.placement("33fc0ac05c73945c", "aaaaaaaaaaaaaaaa"));
    }

    /** Catches two unknown ids being "the same" (an APK or an app built without the id): they are not. */
    @Test
    public void anUnknownIdIsNeverTheSame() {
        assertEquals(UpdatePlan.InstallAt.AFTER_MOD, AppInstallRules.placement("", ""));
        assertEquals(UpdatePlan.InstallAt.AFTER_MOD, AppInstallRules.placement("x", ""));
        assertEquals(UpdatePlan.InstallAt.AFTER_MOD, AppInstallRules.placement("", "x"));
        assertEquals(UpdatePlan.InstallAt.AFTER_MOD, AppInstallRules.placement(null, null));
        assertEquals(UpdatePlan.InstallAt.AFTER_MOD, AppInstallRules.placement("x", null));
    }

    /** Catches the install-first fallback firing on any other failure of the apply. */
    @Test
    public void onlyTheNewerAppRefusalIsTheNewerApp() {
        assertTrue(AppInstallRules.needsNewerApp(new UpdateException(UpdatePlan.Refusal.NEEDS_NEWER_APP)));
        assertFalse(AppInstallRules.needsNewerApp(new UpdateException(UpdateFailure.APPLY_FAILED, "x")));
        assertFalse(AppInstallRules.needsNewerApp(new UpdateException(UpdatePlan.Refusal.NO_SET)));
        assertFalse(AppInstallRules.needsNewerApp(null));
    }

    private File apk(String name, String entry, byte[] content) throws IOException {
        File f = tmp.newFile(name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(content);
            z.closeEntry();
        }
        return f;
    }

    /** Catches an id read with its line ending, or from the wrong place in the APK. */
    @Test
    public void theIdIsReadFromTheApk() throws IOException {
        File f = apk("a.apk", AppLibraryId.ENTRY, "33fc0ac05c73945c\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("33fc0ac05c73945c", AppLibraryId.read(f));
        assertEquals("", AppLibraryId.read(apk("b.apk", "assets/other.txt", new byte[] {1})));
    }

    /** Catches a failure to read the id (a damaged file, a huge entry) stopping the update instead of meaning "unknown". */
    @Test
    public void anUnreadableApkHasNoId() throws IOException {
        assertEquals("", AppLibraryId.read(new File(tmp.getRoot(), "missing.apk")));
        File junk = tmp.newFile("junk.apk");
        java.nio.file.Files.write(junk.toPath(), new byte[] {1, 2, 3});
        assertEquals("", AppLibraryId.read(junk));
        assertEquals("", AppLibraryId.read(apk("big.apk", AppLibraryId.ENTRY, new byte[4096])));
    }
}
