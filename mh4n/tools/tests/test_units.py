"""The recompiled C's units (recomp/units.py): which functions share a file (no game data, no compiler needed).

A compile's memory follows its file's C, so a unit is kept under a size; the cuts fall on fixed addresses, so a
change to one function rewrites one file (what lets the objects' cache keep every other object), and the grouping
never changes a function's own C.
"""
from __future__ import annotations

import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import recompile  # noqa: E402
from recomp import units  # noqa: E402
from recomp.analysis import Image  # noqa: E402
from recomp.emit import LOCALS_PREAMBLE  # noqa: E402

BASE = 0x00100000
SIZE = 0x30000              # three 64 KiB buckets of code
STEP = 0x400                # a function every 1 KiB


def b(op: int, at: int, target: int) -> int:
    return op << 24 | ((target - at - 8) >> 2) & 0xFFFFFF


def program(value_of=lambda i: i & 0xFF) -> tuple[bytes, dict[int, str]]:
    """Functions every STEP bytes over the three buckets: `mov r0, #v` and a call of the next (the last returns)."""
    code = bytearray(SIZE)
    addrs = list(range(BASE, BASE + SIZE, STEP))
    for i, a in enumerate(addrs):
        words = [0xE92D4010, 0xE3A00000 | value_of(i)]               # push {r4, lr}; mov r0, #v
        if i + 1 < len(addrs):
            words.append(b(0xEB, a + 8, addrs[i + 1]))               # bl next
        words.append(0xE8BD8010)                                     # pop {r4, pc}
        for k, w in enumerate(words):
            struct.pack_into("<I", code, a - BASE + 4 * k, w)
    return bytes(code), {a: "arm" for a in addrs}


def translate(out, unit_bytes, keep_functions=True, value_of=lambda i: i & 0xFF):
    code, entries = program(value_of)
    res = recompile.recompile(Image(code, BASE, SIZE), entries, str(out), log=lambda *a: None,
                              unit_bytes=unit_bytes, keep_functions=keep_functions)
    src = out / "src"
    files = {p.name: p.read_text(encoding="latin-1") for p in src.iterdir()
             if p.suffix == ".c" and p.name not in ("table.c",)}
    return res, files


def per_function(files: dict[str, str]) -> dict[int, tuple[str, str]]:
    """Each function's C, and the file it is in; a function in two files fails here."""
    out = {}
    for name, text in files.items():
        for a, c in units.functions_of(text):
            assert a not in out, f"f_{a:08x} is in {out[a][1]} and in {name}"
            out[a] = (c, name)
    return out


def test_cut_units_hold_every_function_once_with_the_same_c_as_64_kib_files(tmp_path):
    # the break: a cut unit losing a function, holding one twice, or a function's C changing with its file
    _, whole = translate(tmp_path / "whole", None)
    res, cut = translate(tmp_path / "cut", 2048, keep_functions=False)
    assert sorted(whole) == [f"f{BASE + k:08x}.c" for k in range(0, SIZE, 0x10000)]
    assert len(cut) > len(whole)
    a, b_ = per_function(whole), per_function(cut)
    assert set(a) == set(b_) == set(range(BASE, BASE + SIZE, STEP))
    assert all(a[k][0] == b_[k][0] for k in a)
    assert "funcs" not in res and res["report"]["functions"] == len(a)
    for name, text in cut.items():
        # each unit fits (or is one function) and declares what it calls of the others
        fs = units.functions_of(text)
        assert len(fs) == 1 or sum(len(c) for _, c in fs) <= 2048, name
        for callee in units.NAMED.findall(text):
            assert f"void {callee}(RCtx *ctx);" in text, (name, callee)


def test_a_changed_function_rewrites_its_own_unit_only(tmp_path):
    # the break: a one-word change moving the cuts (every later object compiled again, the cache lost)
    _, before = translate(tmp_path / "a", 2048)
    changed = 37
    _, after = translate(tmp_path / "b", 2048, value_of=lambda i: (i + (i == changed)) & 0xFF)
    assert sorted(before) == sorted(after)
    differ = [n for n in before if before[n] != after[n]]
    assert differ == [per_function(before)[BASE + changed * STEP][1]]


def test_the_plan_cuts_on_fixed_addresses():
    # the break: cuts that follow the sizes (one grown function moving every cut after it)
    sizes = [(BASE + i * 0x100, 300) for i in range(0x300)]       # three buckets of 256 functions: 16 KiB units
    big = BASE + 0x14000
    grown = [(a, 15_000 if a == big else n) for a, n in sizes]
    plain, cut = units.plan(sizes, 30_000), units.plan(grown, 30_000)
    for plan, given in ((plain, sizes), (cut, grown)):
        size = dict(given)
        assert sorted(a for u in plan for a in u.addrs) == sorted(size)
        assert all(len(u.addrs) == 1 or sum(size[a] for a in u.addrs) <= 30_000 for u in plan)
    # the grown function's 16 KiB is cut again, within itself; every other unit stays as it was
    inside = range(big, big + 0x4000)
    assert [(u.name, u.addrs) for u in plain if u.addrs[0] not in inside] == \
        [(u.name, u.addrs) for u in cut if u.addrs[0] not in inside]
    assert len([u for u in cut if u.addrs[0] in inside]) > len([u for u in plain if u.addrs[0] in inside]) == 1
    # a function larger than a unit may be is alone, under its own name
    alone = units.plan([(BASE, 100), (BASE + 0x100, 50_000), (BASE + 0x200, 100)], 30_000)
    assert [u.name for u in alone] == [f"f{BASE:08x}_10000", f"f_{BASE + 0x100:08x}"]
    assert alone[0].addrs == [BASE, BASE + 0x200]
    # no limit, or one the bucket fits: one unit per 64 KiB, as the files always were
    assert [u.name for u in units.plan(sizes, None)] == [f"f{BASE + k:08x}" for k in range(0, 0x30000, 0x10000)]


def test_a_unit_in_parts_gives_each_function_alone_with_its_declarations(tmp_path):
    # the break: the lighter compile (recomp/jobs.py) losing a function or a declaration a part needs
    _, files = translate(tmp_path, None)
    name = f"f{BASE:08x}.c"
    pieces = units.parts(files[name], LOCALS_PREAMBLE)
    whole = units.functions_of(files[name])
    assert [n for n, _ in pieces] == [f"f_{a:08x}" for a, _ in whole]
    for (n, text), (a, c) in zip(pieces, whole):
        assert units.functions_of(text) == [(a, c)]
        for callee in units.NAMED.findall(c):
            assert f"void {callee}(RCtx *ctx);" in text, (n, callee)
