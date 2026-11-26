package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** F7: what builds the APK never holds the signing key, and what signs it runs alone, approved, from main. */
public class ReleaseWorkflowTest {
    private static String workflow() {
        return Support.readRepo(".github/workflows/build.yml");
    }

    private static String job(String all, String name) {
        int at = all.indexOf("\n  " + name + ":\n");
        assertTrue("no job " + name, at >= 0);
        Matcher next = Pattern.compile("\n  [a-z][a-z0-9_-]*:\n").matcher(all);
        int end = all.length();
        if (next.find(at + 1)) end = next.start();
        return all.substring(at, end);
    }

    /**
     * Catches the key in the job that runs the build's code (any script of the repository, any dependency, could read
     * it), a release job that builds, one without its environment (no reviewers), and a tag released from a commit
     * that is not on main.
     */
    @Test
    public void theKeyIsOnlyInTheApprovedReleaseJob() {
        String all = workflow();
        String build = job(all, "build");
        String release = job(all, "release");
        assertFalse("the build job sees no secret", build.contains("secrets."));
        assertTrue(build.contains("contents: read"));
        assertFalse("the build job writes no key", build.contains("base64 -d") || build.contains("> android/keystore.properties"));
        assertTrue(release.contains("environment: release"));
        assertTrue(release.contains("needs: build"));
        assertTrue(release.contains("git merge-base --is-ancestor \"$GITHUB_SHA\" origin/main"));
        for (String building : new String[] {"gradlew", "build_apk", "build_sdl3", "pip install", "run_tests"}) {
            assertFalse("the release job builds nothing: " + building, release.contains(building));
        }
        assertTrue(release.contains("zipalign\" -f -P 16 4"));
        assertTrue(release.contains("apksigner\" verify --print-certs"));
        assertTrue(release.contains("Android Debug"));
    }

    /** Catches an action, a Python package or the Gradle wrapper that can change under the same name. */
    @Test
    public void everythingTheBuildFetchesIsPinned() {
        String all = workflow();
        Matcher uses = Pattern.compile("uses: ([^\\s]+)").matcher(all);
        List<String> loose = new ArrayList<>();
        int n = 0;
        while (uses.find()) {
            n++;
            if (!uses.group(1).matches("[\\w.-]+/[\\w./-]+@[0-9a-f]{40}")) loose.add(uses.group(1));
        }
        assertTrue(n > 5);
        assertTrue("actions not pinned to a commit: " + loose, loose.isEmpty());
        assertTrue(all.contains("gradle/actions/wrapper-validation@"));
        assertTrue(all.contains("pip install --require-hashes --only-binary :all: -r .github/ci-requirements.txt"));
        assertFalse(all.contains("pip install cmake"));
        String reqs = Support.readRepo(".github/ci-requirements.txt");
        for (String line : reqs.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("--hash=")) continue;
            assertTrue("pinned to one version: " + t, t.matches("[a-z0-9_-]+==[0-9][0-9a-z.]* \\\\"));
        }
        assertTrue(reqs.contains("--hash=sha256:"));
        String wrapper = Support.readRepo("android/gradle/wrapper/gradle-wrapper.properties");
        assertTrue(wrapper.matches("(?s).*distributionSha256Sum=[0-9a-f]{64}.*"));
    }
}
