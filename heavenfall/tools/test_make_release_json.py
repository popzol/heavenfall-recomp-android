"""Tests of make_release_json.py: the file has the shape the app's release rules accept (GitHub's `releases/latest`).

Run: python -m unittest discover -s heavenfall/tools
"""
import hashlib
import json
import os
import re
import tempfile
import unittest

import make_release_json as tool


class ReleaseJsonShape(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def file(self, name, data):
        path = os.path.join(self.dir.name, name)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def test_every_field_the_apps_rules_read_is_there_and_exact(self):
        """A missing digest, a size that is not the file's or an address off the release's own downloads would make the
        app refuse its own release file (ReleaseRules.parse)."""
        apk = self.file("Heavenfall-android.apk", b"apk bytes" * 1000)
        checksum = self.file("Heavenfall-android.apk.sha256", b"abc  Heavenfall-android.apk\n")
        out = os.path.join(self.dir.name, "Heavenfall-android-release.json")
        self.assertEqual(0, tool.main(["--repository", "popzol/heavenfall-recomp-android", "--tag", "v0.1.0", "--out", out,
                                       apk, checksum]))
        with open(out, encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual({"tag_name", "assets"}, set(data))
        self.assertEqual("v0.1.0", data["tag_name"])
        by_name = {a["name"]: a for a in data["assets"]}
        self.assertEqual({"Heavenfall-android.apk", "Heavenfall-android.apk.sha256"}, set(by_name))
        for name, asset in by_name.items():
            self.assertEqual({"name", "size", "digest", "browser_download_url"}, set(asset))
            with open(os.path.join(self.dir.name, name), "rb") as f:
                content = f.read()
            self.assertEqual(len(content), asset["size"])
            self.assertIsInstance(asset["size"], int)
            self.assertEqual("sha256:" + hashlib.sha256(content).hexdigest(), asset["digest"])
            self.assertTrue(re.fullmatch(r"sha256:[0-9a-f]{64}", asset["digest"]))
            self.assertEqual("https://github.com/popzol/heavenfall-recomp-android/releases/download/v0.1.0/" + name,
                             asset["browser_download_url"])

    def test_what_is_not_a_release_is_refused(self):
        """A tag without a version, a repository that is not owner/name, a name with a slash or a missing file must stop
        the CI step, not publish a file the app cannot trust."""
        apk = self.file("a.apk", b"x")
        for repository, tag in (("noslash", "v0.1.0"), ("a/b", "0.1.0"), ("a/b", "v1.2"), ("a/b/c", "v0.1.0")):
            with self.assertRaises(ValueError, msg=(repository, tag)):
                tool.release(repository, tag, [apk])
        with self.assertRaises(ValueError):
            tool.release("a/b", "v0.1.0", [])
        with self.assertRaises(ValueError):
            tool.release("a/b", "v0.1.0", [apk, apk])
        out = os.path.join(self.dir.name, "x.json")
        self.assertEqual(1, tool.main(["--repository", "a/b", "--tag", "v0.1.0", "--out", out,
                                       os.path.join(self.dir.name, "missing.apk")]))
        self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
