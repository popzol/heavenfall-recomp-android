#!/usr/bin/env python3
"""Makes the synthetic fixtures of the native layer's tests (heavenfall/jni/test/fixtures).

    python make_fixtures.py <Heavenfall checkout> <fixtures folder> <scratch folder>

Everything is made up: no game data. The game, its update, MH XX and the mod are the synthetic ones of Heavenfall's own
tests (tests/install/title/test_patchset_android.py and its helpers, read from the checkout, never changed), the sets
are written by Heavenfall's own writer (core/install/title/patchset.py), and the expected tree of each set is made
from the mod folder for plain files and from Heavenfall's reference driver (tests/install/title/patchset_driver.c,
built here with the host gcc) for the ARCs, whose deflated bytes only a deflate gives (the entries are checked equal
to the mod's first). Run it again only when the writer's format changes; the fixtures are committed.

Files written to the fixtures folder: the dumps (game.cxi, update.cia, update.cxi, update_eur.cia, update_other.cia,
update_locked.cia, game_eur.cxi, xx.cxi, junk.bin), the sets (android.hfpatch with the shared file shared.hfpack,
android_xx.hfpatch, format3.hfpatch, set_newer.hfpatch, set_cut.hfpatch, bad_path.hfpatch) and expected.txt:

    tree <set> <path> <size> <sha256>     every file the set makes
    code <set> <sha256>                   the SHA-256 of its exefs/code.bin
    flip <dump> <offset>                  a byte of a source file of that dump (the test flips it in a copy)
    sha256 <name> <hex>                   the update's ExeFS hash as the applier reports it
"""

import hashlib
import shutil
import struct
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True

checkout, fixtures, scratch = (Path(arg).resolve() for arg in sys.argv[1:4])
for path in (checkout, checkout / "tests", checkout / "tests" / "install" / "title"):
    sys.path.insert(0, str(path))

from test_patchset import MOD_FILES, PLAIN, make_game, make_mod  # noqa: E402
from test_patchset_android import (  # noqa: E402
    SHARED, SONGS, STAMP, UPDATE_CODE, UPDATE_PREFIX, UPDATE_TAIL, assert_tree_is_the_mod, exefs_hash_of, make_update,
    make_update_mod, retabled)
from test_patchset_xx import make_xx, make_xx_mod  # noqa: E402
from support.blz import blz_compress  # noqa: E402

from core.game.executable import titles  # noqa: E402
from core.install.title import patchset as ps  # noqa: E402

c_dir = checkout / "core" / "install" / "title" / "c"
driver_source = checkout / "tests" / "install" / "title" / "patchset_driver.c"


def build_reference_driver() -> Path:
    """Heavenfall's own driver over its own C (the model of a caller), built with the host gcc."""
    scratch.mkdir(parents=True, exist_ok=True)
    objects = []
    for name in ("zlib_mini.c", "sha256.c", "romfs.c", "ncch.c", "dump.c", "blz.c", "patchset.c"):
        obj = scratch / (Path(name).stem + ".o")
        subprocess.run(["gcc", "-std=c99", "-O1", "-w", "-I", str(c_dir), "-c", str(c_dir / name), "-o", str(obj)],
                       check=True)
        objects.append(str(obj))
    exe = scratch / "reference_driver.exe"
    subprocess.run(["gcc", "-std=gnu99", "-O1", "-w", "-I", str(c_dir), str(driver_source), *objects, "-o", str(exe)],
                   check=True)
    return exe


def reference_tree(exe, set_path, game, out, shared=None, update=None, xx=None):
    out.mkdir(parents=True, exist_ok=True)
    args = [str(exe), str(set_path), str(game), str(out), "3", "-1", "1", "-1", str(xx) if xx else "-", "-",
            str(shared) if shared else "-", str(update), "tree"]
    text = subprocess.run(args, capture_output=True, text=True, check=True).stdout
    for line in text.splitlines():
        parts = line.split("|")
        rc = parts[3] if parts[0] == "file" else parts[1] if parts[0] in ("open", "bind", "bind2", "bind3", "bindu") \
            else parts[2] if parts[0] == "src" else "0"
        assert rc == "0", text


def manifest(name, tree, lines):
    for path in sorted(p for p in tree.rglob("*") if p.is_file()):
        data = path.read_bytes()
        lines.append(f"tree {name} {path.relative_to(tree).as_posix()} {len(data)} {hashlib.sha256(data).hexdigest()}")
        if path.relative_to(tree).as_posix() == "exefs/code.bin":
            lines.append(f"code {name} {hashlib.sha256(data).hexdigest()}")


def main() -> None:
    shutil.rmtree(fixtures, ignore_errors=True)
    shutil.rmtree(scratch / "work", ignore_errors=True)
    fixtures.mkdir(parents=True)
    work = scratch / "work"
    work.mkdir(parents=True)
    exe = build_reference_driver()
    lines = []

    game = make_game(fixtures / "game.cxi", titles.MH4U_USA)
    (work / "game.cxi").write_bytes(game.read_bytes())            # the mods' helper reads its exheader from here
    update = make_update(fixtures / "update.cia")
    make_update(fixtures / "update.cxi", form="ncch")
    make_update(fixtures / "update_eur.cia", titles.MH4U_EUR_UPDATE)
    make_update(fixtures / "update_locked.cia", encrypted=True)
    make_update(fixtures / "update_other.cia", code=blz_compress(UPDATE_PREFIX, UPDATE_TAIL[:-3] + b"abc"))
    make_game(fixtures / "game_eur.cxi", titles.MH4U_EUR)
    xx = make_xx(fixtures / "xx.cxi")
    (fixtures / "junk.bin").write_bytes(b"this is not a game container\n" * 200)

    # the plain set (the Beta's shape): the game, its update and the release's shared file
    mod = make_update_mod(work / "mod", update, {**MOD_FILES, **SONGS})
    ps.write_patchset(game, mod, fixtures / "android.hfpatch", "HeavenfallBeta", "usa", "0.3.0", shared=SHARED,
                      stamp=STAMP, update=update)
    (fixtures / "shared.hfpack").write_bytes(SHARED)
    tree = work / "tree_plain"
    reference_tree(exe, fixtures / "android.hfpatch", game, tree, shared=fixtures / "shared.hfpack", update=update)
    assert_tree_is_the_mod(tree, mod)
    manifest("android", tree, lines)

    # the set with MH XX (no shared file)
    xx_mod = make_xx_mod(work / "xx_mod")
    make_update_mod(work / "xx_mod", update, {})
    ps.write_patchset(game, xx_mod, fixtures / "android_xx.hfpatch", "Heavenfall", "usa", "0.3.0", level=1, xx_dump=xx,
                      update=update)
    tree = work / "tree_xx"
    reference_tree(exe, fixtures / "android_xx.hfpatch", game, tree, update=update, xx=xx)
    manifest("android_xx", tree, lines)

    # a console set (format 3: no update): not an Android set
    console = make_mod(work / "console_mod")
    ps.write_patchset(game, console, fixtures / "format3.hfpatch", "full", "usa", "0.1.0")

    blob = (fixtures / "android.hfpatch").read_bytes()
    newer = bytearray(blob)
    struct.pack_into("<I", newer, 4, ps.FORMAT_UPDATE + 1)           # a format this applier does not know
    (fixtures / "set_newer.hfpatch").write_bytes(bytes(newer))
    (fixtures / "set_cut.hfpatch").write_bytes(blob[:len(blob) * 6 // 10])
    (fixtures / "bad_path.hfpatch").write_bytes(retabled(blob, b"romfs/new/fresh.bin", b"../../new/fresh.bin"))

    # a byte inside a source file of each dump, for the test to flip in a copy
    data = (fixtures / "game.cxi").read_bytes()
    at = data.find(PLAIN[1000:1016])
    assert at > 0 and data.count(PLAIN[1000:1016]) == 1
    lines.append(f"flip game.cxi {at + 12}")      # past the 8 bytes the mod patches there: the output changes too
    lines.append("sha256 update_exefs " + exefs_hash_of(update).hex())
    lines.append("sha256 update_code_len " + str(len(UPDATE_CODE)))
    (fixtures / "expected.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    for path in sorted(fixtures.iterdir()):
        print(f"{path.name:22} {path.stat().st_size:>9}")


main()
