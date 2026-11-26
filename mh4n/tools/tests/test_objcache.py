"""The compiled objects' cache (recomp/objcache.py, through recomp/build.py's build): an object is never compiled twice
from the same C, flags and compiler, across builds and mod versions; a bad entry is not used; the oldest go first
when the disk needs the room (no game data; the compiler is a small Python program).
"""
from __future__ import annotations

import os
import stat
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import build_tuning  # noqa: E402
from recomp import build as rbuild  # noqa: E402
from recomp import objcache, units  # noqa: E402
from recomp.emit import LOCALS_PREAMBLE  # noqa: E402

FAKE_CC = r'''
import os, sys
args = sys.argv[1:]
if args == ["--version"]:
    print("fake cc 1.0")
    sys.exit(0)
out = args[args.index("-o") + 1]
if "-shared" in args:          # the link: the objects' contents, in order
    rsp = next(a for a in args if a.startswith("@"))[1:]
    names = [line.strip().strip('"') for line in open(rsp) if line.strip()]
    open(out, "w").write("".join(open(n).read() + "\n" for n in names))
    sys.exit(0)
src = args[args.index("-o") - 1]
text = open(src, encoding="latin-1").read()
with open(os.environ["FAKE_CC_LOG"], "a") as f:
    f.write(os.path.basename(src) + "\n")
if "DIES_WHOLE" in text and text.count("#define RC_FLUSH() ") > 1:
    sys.exit(137)
flags = " ".join(a for a in args if a.startswith("-D"))
open(out, "w").write(f"object of {os.path.basename(src)} {flags} {len(text)}")
'''


def unit_c(entries: list[int], mark: str = "") -> str:
    functions = [f"#define RC_FLUSH() rc_flush(ctx)\nvoid f_{a:08x}(RCtx *ctx) {{\n    /* {mark} */\n}}"
                 for a in entries]
    return units.text(functions, LOCALS_PREAMBLE)


class Builds:
    """A recompiled folder (three units), built with build.build and the fake compiler, every compile logged."""

    def __init__(self, tmp_path, monkeypatch):
        self.tmp = tmp_path
        fake = tmp_path / "fake_cc.py"
        fake.write_text(FAKE_CC)
        if sys.platform == "win32":
            self.cc = tmp_path / "cc.cmd"
            self.cc.write_text(f'@echo off\r\n"{sys.executable}" "{fake}" %*\r\nexit /b %errorlevel%\r\n')
        else:
            self.cc = tmp_path / "cc"
            self.cc.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "$@"\n')
            self.cc.chmod(self.cc.stat().st_mode | stat.S_IXUSR)
        self.log = tmp_path / "compiled.txt"
        monkeypatch.setenv("FAKE_CC_LOG", str(self.log))
        self.out = tmp_path / "recomp"
        (self.out / "src").mkdir(parents=True)
        self.cache = tmp_path / "object-cache"
        self.tuning = build_tuning.load()
        self.write("f00100000", unit_c([0x100000, 0x100400]))
        self.write("f00110000", unit_c([0x110000]))
        self.write("f00120000", unit_c([0x120000, 0x120400]))

    def write(self, name: str, text: str) -> None:
        (self.out / "src" / f"{name}.c").write_text(text, encoding="latin-1")

    def build(self, flags=None) -> list[str]:
        """Builds the library; returns what was compiled."""
        self.log.write_text("")
        rbuild.build(str(self.out), 2, "O1", [], "lib.so", [], cc=str(self.cc), extra_flags=flags,
                     tuning=self.tuning, cache_dir=str(self.cache))
        return sorted(self.log.read_text().split())

    def library(self) -> str:
        return (self.out / "lib.so").read_text()


def test_the_same_c_comes_from_the_cache_and_a_change_compiles_its_unit_only(tmp_path, monkeypatch, capsys):
    # the break: a new build (or a new version of a mod) compiling objects it already made
    b = Builds(tmp_path, monkeypatch)
    assert b.build() == ["f00100000.c", "f00110000.c", "f00120000.c"]
    first = b.library()
    assert b.build() == [] and b.library() == first
    assert "3 objects from it, 0 to compile" in capsys.readouterr().out
    b.write("f00110000", unit_c([0x110000], "changed"))
    assert b.build() == ["f00110000.c"]
    # the work folder gone (--clean): the objects come from the cache all the same
    for name in os.listdir(b.out / "obj"):
        if name.endswith(".o"):
            os.remove(b.out / "obj" / name)
    assert b.build() == []


def test_other_flags_miss(tmp_path, monkeypatch):
    # the break: an object made with other flags (another build mode) taken for this one
    b = Builds(tmp_path, monkeypatch)
    b.build()
    assert b.build(["-DRC_OTHER"]) == ["f00100000.c", "f00110000.c", "f00120000.c"]
    assert "-DRC_OTHER" in b.library()
    assert b.build() == []          # (both kept)


def test_a_bad_entry_is_not_used(tmp_path, monkeypatch, capsys):
    # the break: a cache object cut short or damaged on the disk linked into a library
    b = Builds(tmp_path, monkeypatch)
    b.build()
    objects = sorted(p for p in (b.cache / "objects").rglob("*.o"))
    objects[0].unlink()
    objects[0].write_text("damaged")                    # (a new file: the build's own link of it stays whole)
    (objects[1].with_suffix(".sum")).unlink()           # (cut short before its sum was written)
    assert len(b.build()) == 2
    assert "didn't match their sums: dropped" in capsys.readouterr().out
    assert "damaged" not in b.library()
    assert b.build() == []


def test_a_unit_compiled_in_parts_comes_back_in_parts(tmp_path, monkeypatch):
    # the break: a unit the system stopped twice compiled whole again (and stopped again) on every build
    b = Builds(tmp_path, monkeypatch)
    b.write("f00120000", unit_c([0x120000, 0x120400], "DIES_WHOLE"))
    compiled = b.build()
    assert compiled.count("f00120000.c") == 2 and {"f_00120000.c", "f_00120400.c"} <= set(compiled)
    lib = b.library()
    assert "object of f_00120000.c" in lib and "object of f_00120400.c" in lib and "object of f00120000.c" not in lib
    assert b.build() == [] and b.library() == lib


def put(cache: objcache.ObjectCache, tmp_path, name: str, size: int, when: float) -> str:
    src = tmp_path / f"{name}.o"
    src.write_bytes(name.encode() * (size // len(name)))
    k = objcache.key(name, name.encode(), [], [], "cc", "h")
    cache.put(k, str(src))
    os.utime(cache._path(k, ".o"), (when, when))
    return k


def test_the_oldest_objects_go_when_the_disk_needs_room(tmp_path):
    # the break: the cache filling the phone's storage, or dropping the objects just used instead of the oldest
    mb = 1 << 20
    cache = objcache.ObjectCache(str(tmp_path / "cache"), keep_free_mb=10, max_mb=100, floor_mb=2)
    keys = [put(cache, tmp_path, f"u{i}", mb, 1000.0 + i) for i in range(6)]
    assert cache.size() >= 6 * mb - 100
    # 5 MB free and 10 to be kept free: the cache may hold 6 + 5 - 10 = 1 MB, never under its floor of 2
    dropped, _ = cache.trim(free=5 * mb)
    assert dropped == 4
    left = [k for _, _, k in cache.entries()]
    assert left == keys[4:]
    # a use makes an object the newest
    assert cache.get(keys[4], str(tmp_path / "back.o")) and not cache.get(keys[0], str(tmp_path / "gone.o"))
    # plenty free: up to max_mb, nothing goes
    assert cache.trim(free=10_000 * mb) == (0, 0)
    # room for a build: the oldest go until it has it, down to the floor
    more = [put(cache, tmp_path, f"v{i}", mb, 2000.0 + i) for i in range(3)]
    dropped, _ = cache.shrink(2 * mb)
    assert dropped == 2 and [k for _, _, k in cache.entries()] == [more[1], more[2], keys[4]]
    assert cache.shrink(100 * mb)[0] == 1 and len(cache.entries()) == 2
