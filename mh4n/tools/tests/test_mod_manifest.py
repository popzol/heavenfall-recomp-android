"""recomp.json, the file a code mod gives with its code (no game data).

It names the mod's own code past the game's data (its ranges and the functions' starts in them) and, from format 2
on, its packs: files of the mod's RomFS its code loads at a fixed address of the heap, each with its SHA-1 and its
functions' starts. code_mods.load_manifest reads it and refuses anything that does not fit the code it is for; a
build then leaves the file aside (build_game.py says why) and recompiles what it finds by itself. The library names
each pack (game_version.pack_record, version_source), and the runtime refuses it when a pack is not that file.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import zlib

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import code_mods  # noqa: E402
import game_version  # noqa: E402
from code_mods import ModError  # noqa: E402

TITLE = 0x0004000000126300
UPDATE = 0x0004000E00126300
EXT = (0x01200000, 0x01210000)       # the mod's code past the game's data, as its exheader maps it
PACK = 0x0C700000
CODE = bytes(range(256)) * 64


TOWN = ("romfs/packs/town.bin", PACK, b"\x1e\xff\x2f\xe1" * 0x800, [[f"0x{PACK:x}", "arm"]])


def write_mod(folder, fmt=2, pack_files=(TOWN,), **fields) -> str:
    """A mod folder with exefs/code.bin, a pack file per `pack_files` item (file, address, bytes, entries) and its
    recomp.json; `fields` replace the file's own."""
    os.makedirs(os.path.join(folder, "exefs"), exist_ok=True)
    with open(os.path.join(folder, "exefs", "code.bin"), "wb") as f:
        f.write(CODE)
    data = {"format": fmt, "title": f"{TITLE:016X}", "code_sha1": hashlib.sha1(CODE).hexdigest(),
            "code": [[f"0x{EXT[0]:x}", f"0x{EXT[0] + 0x400:x}"]],
            "entries": [[f"0x{EXT[0]:x}", "arm"], [f"0x{EXT[0] + 0x101:x}", "arm"], [f"0x{EXT[0] + 0x200:x}", "thumb"]]}
    if fmt >= 2:
        data["packs"] = []
        for name, address, blob, entries in pack_files:
            path = os.path.join(folder, *name.split("/"))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(blob)
            data["packs"].append({"file": name, "address": f"0x{address:x}", "sha1": hashlib.sha1(blob).hexdigest(),
                                  "entries": entries})
    data.update(fields)
    path = os.path.join(folder, "recomp.json")
    with open(path, "w") as f:
        json.dump(data, f)
    return path


def test_format_1_names_the_mods_code_and_its_entries(tmp_path):
    m = code_mods.load_manifest(write_mod(str(tmp_path), fmt=1), UPDATE, CODE, EXT)
    assert m.ranges == [(EXT[0], EXT[0] + 0x400)]
    # an odd address is a Thumb start whatever its mode says
    assert m.entries == {EXT[0]: "arm", EXT[0] + 0x100: "thumb", EXT[0] + 0x200: "thumb"}
    assert m.packs == [] and m.code_ranges() == m.ranges and m.all_entries() == m.entries


def test_format_2_adds_its_packs_recompiled_at_their_address(tmp_path):
    blob = b"\x1e\xff\x2f\xe1" * 0x800
    path = write_mod(str(tmp_path), pack_files=[
        ("romfs/packs/town.bin", PACK, blob, [[f"0x{PACK:x}", "arm"], [f"0x{PACK + 0x11:x}", "thumb"]]),
        ("romfs/packs/screen.bin", PACK + 0x80000, blob[:0x1000], [[f"0x{PACK + 0x80000:x}", "arm"]])])
    m = code_mods.load_manifest(path, UPDATE, CODE, EXT)
    assert [(p.file, p.address, p.data) for p in m.packs] == [("romfs/packs/town.bin", PACK, blob),
                                                              ("romfs/packs/screen.bin", PACK + 0x80000, blob[:0x1000])]
    assert m.packs[0].entries == {PACK: "arm", PACK + 0x10: "thumb"}
    assert m.code_ranges() == [(EXT[0], EXT[0] + 0x400), (PACK, PACK + 0x2000), (PACK + 0x80000, PACK + 0x81000)]
    assert m.all_entries() == {**m.entries, PACK: "arm", PACK + 0x10: "thumb", PACK + 0x80000: "arm"}


def test_a_mod_with_packs_only_names_no_range(tmp_path):
    m = code_mods.load_manifest(write_mod(str(tmp_path), code=[], entries=[]), UPDATE, CODE, None)
    assert m.ranges == [] and [p.address for p in m.packs] == [PACK]
    with pytest.raises(ModError, match="names no code"):
        code_mods.load_manifest(write_mod(str(tmp_path / "none"), pack_files=[], code=[], entries=[]),
                                UPDATE, CODE, None)


PAGE = b"\0" * 0x1000


@pytest.mark.parametrize("change, message", [
    ({"format": 3}, "format 3"),
    ({"title": "0004000000055D00"}, "another title"),
    ({"code_sha1": "0" * 40}, "another code"),
    ({"code": [["0x1210000", "0x1210100"]]}, "not inside the mod's code"),
    ({"entries": [["0x1200400", "arm"]]}, "not an instruction"),
    ({"entries": [["0x1200002", "arm"]]}, "not an instruction"),
    ({"entries": [["0x1200000", "x86"]]}, "not an instruction"),
    ({"packs": {}}, "not a list"),
    ({"packs": ["romfs/packs/town.bin"]}, "not a JSON object"),
    ({"code": "0x1200000"}, "recomp.json: "),
])
def test_a_file_that_does_not_fit_is_refused(tmp_path, change, message):
    with pytest.raises(ModError, match=message):
        code_mods.load_manifest(write_mod(str(tmp_path), **change), UPDATE, CODE, EXT)


@pytest.mark.parametrize("packs, message", [
    ([("romfs/packs/../../code.bin", PACK, PAGE, [])], "not a file of the mod's romfs/ folder"),
    ([("C:/packs/town.bin", PACK, PAGE, [])], "not a file of the mod's romfs/ folder"),
    ([("exefs/town.bin", PACK, PAGE, [])], "not a file of the mod's romfs/ folder"),
    ([("romfs/packs/town.bin", PACK, PAGE[:0x800], [])], "not whole pages"),
    ([("romfs/packs/town.bin", PACK + 0x800, PAGE, [])], "not whole pages"),
    ([("romfs/packs/town.bin", 0x07FFF000, PAGE, [])], "in the heap"),
    ([("romfs/packs/town.bin", 0x10000000, PAGE, [])], "in the heap"),
    ([("romfs/packs/town.bin", PACK, PAGE, [[f"0x{PACK + 0x1000:x}", "arm"]])], "not an instruction"),
    ([("romfs/packs/a.bin", PACK, PAGE * 2, []), ("romfs/packs/b.bin", PACK + 0x1000, PAGE, [])], "overlaps"),
])
def test_a_pack_that_does_not_fit_is_refused(tmp_path, packs, message):
    with pytest.raises(ModError, match=message):
        code_mods.load_manifest(write_mod(str(tmp_path), pack_files=packs), UPDATE, CODE, EXT)


def test_a_pack_changed_after_its_file_was_written_is_refused(tmp_path):
    path = write_mod(str(tmp_path))
    with open(os.path.join(str(tmp_path), "romfs", "packs", "town.bin"), "r+b") as f:
        f.write(b"\0\0\0\0")
    with pytest.raises(ModError, match="not the file it was made with"):
        code_mods.load_manifest(path, UPDATE, CODE, EXT)


def test_the_library_names_each_pack_for_the_runtimes_check(tmp_path):
    m = code_mods.load_manifest(write_mod(str(tmp_path)), UPDATE, CODE, EXT)
    pack = m.packs[0]
    record = game_version.pack_record(pack.file, pack.data)
    assert record == ("packs/town.bin", 0x2000, zlib.crc32(pack.data))
    source = game_version.version_source("usa_v130", "0" * 16, [], None, [record, ("packs/a\"b.bin", 0x1000, 1)])
    assert "rc_pack_count = 2u;" in source
    assert 'rc_pack_files[] = "packs/town.bin\\012packs/a\\042b.bin";' in source     # (plain ASCII C)
    assert "rc_pack_size[] = {0x2000u, 0x1000u};" in source
    assert f"rc_pack_crc32[] = {{0x{record[2]:08x}u, 0x00000001u}};" in source
    # a library without packs says nothing of them
    assert "rc_pack" not in game_version.version_source("usa_v130", "0" * 16, [], None, [])


def test_the_file_is_the_one_of_the_mod_whose_code_runs(tmp_path):
    first, last, bare, nameless = (str(tmp_path / n) for n in ("first", "last", "bare", "nameless"))
    write_mod(first, fmt=1)
    write_mod(last, fmt=1)
    os.makedirs(os.path.join(bare, "romfs"))
    os.makedirs(os.path.join(nameless, "exefs"))
    with open(os.path.join(nameless, "exefs", "code.bin"), "wb") as f:
        f.write(CODE)
    assert code_mods.manifest_path([first, last, bare]) == os.path.join(last, "recomp.json")
    # the code that runs is the last replacement's, which names nothing
    assert code_mods.manifest_path([first, last, nameless]) is None
