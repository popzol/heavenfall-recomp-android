"""A code mod's code, recompiled (no game data, no compiler needed).

A mod built from the game's code puts its own code past the game's data (the extension, which recomp.json names)
and in packs: files its code loads at a fixed address of the heap, each recompiled at that address from the file's
bytes. Its patches leave things in the game's code the recompiler has to take as they are: a call where the
compare before a switch was (the called code sets the flags, so no bound is known), branches into the padding after
the code (a code cave, left to the interpreter), and, in Thumb code built by GCC, `bl` used as a far jump inside a
function.
"""
from __future__ import annotations

import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import recompile  # noqa: E402
from recomp.analysis import Image  # noqa: E402
from recomp.insn import B  # noqa: E402

BASE = 0x00100000
TEXT = 0x200                # the code the game has
CAVE = BASE + 0x400         # the padding after it, where a mod put code
EXT = BASE + 0x800          # the mod's own code, which its recomp.json names
PACK = 0x0C700000           # a pack: its file runs here


def b(op: int, at: int, target: int) -> int:
    return op << 24 | ((target - at - 8) >> 2) & 0xFFFFFF


def thumb_bl(at: int, target: int) -> tuple[int, int]:
    off = (target - at - 4) >> 1
    return 0xF000 | (off >> 11) & 0x7FF, 0xF800 | off & 0x7FF


def program() -> tuple[bytes, bytes, dict[int, str]]:
    words = {
        # f: calls the mod's code
        0x000: 0xE92D4010,                              # push {r4, lr}
        0x004: b(0xEB, BASE + 0x004, EXT),              # bl ext
        0x008: 0xE8BD8010,                              # pop {r4, pc}
        # g: a switch whose `cmp r0, #2` a mod made a call to its own compare
        0x010: 0xE92D4010,                              # push {r4, lr}
        0x014: b(0xEB, BASE + 0x014, EXT + 0x20),       # bl ext_compare
        0x018: 0x379FF100,                              # ldrlo pc, [pc, r0, lsl #2]
        0x01C: 0xE8BD8010,                              # pop {r4, pc} (index too big)
        0x020: BASE + 0x028,                            # case 0
        0x024: BASE + 0x030,                            # case 1
        0x028: 0xE3A00005, 0x02C: 0xE8BD8010,           # mov r0, #5; pop {r4, pc}
        0x030: 0xE3A00006, 0x034: 0xE8BD8010,           # mov r0, #6; pop {r4, pc}
        # h: a hook into a code cave
        0x040: b(0xEA, BASE + 0x040, CAVE),             # b cave
        # k: a function the pack calls
        0x080: 0xE12FFF1E,                              # bx lr
        CAVE - BASE: 0xE12FFF1E,                        # bx lr
        EXT - BASE: 0xE12FFF1E,                         # ext: bx lr
        EXT - BASE + 0x20: 0xE3500002,                  # ext_compare: cmp r0, #2
        EXT - BASE + 0x24: 0xE12FFF1E,                  # bx lr
    }
    halves = {
        # t (Thumb): `bl` to its own tail, as GCC jumps far in Thumb-1
        0x050: 0xB500,                                  # push {lr}
        0x052: 0x2800,                                  # cmp r0, #0
        0x054: 0xD003,                                  # beq tail
        0x05A: 0x2001, 0x05C: 0xBD00,                   # movs r0, #1; pop {pc}
        0x05E: 0x2002, 0x060: 0xBD00,                   # tail: movs r0, #2; pop {pc}
    }
    halves[0x056], halves[0x058] = thumb_bl(BASE + 0x056, BASE + 0x05E)
    code = bytearray(0x1000)
    for off, w in words.items():
        struct.pack_into("<I", code, off, w)
    for off, h in halves.items():
        struct.pack_into("<H", code, off, h)
    pack = bytearray(0x1000)
    for off, w in {0x00: 0xE92D4010,                    # push {r4, lr}
                   0x04: b(0xEB, PACK + 0x04, PACK + 0x10),  # bl veneer
                   0x08: 0xE8BD8010,                    # pop {r4, pc}
                   0x10: 0xE51FF004,                    # veneer: ldr pc, [pc, #-4]
                   0x14: BASE + 0x080}.items():         # (k)
        struct.pack_into("<I", pack, off, w)
    entries = {BASE: "arm", BASE + 0x10: "arm", BASE + 0x40: "arm", BASE + 0x50: "thumb", BASE + 0x80: "arm",
               EXT: "arm", EXT + 0x20: "arm", PACK: "arm"}
    return bytes(code), bytes(pack), entries


def translate(tmp_path, modded: bool):
    code, pack, entries = program()
    img = Image(code, BASE, TEXT, [(EXT, EXT + 0x100)], interpret_outside=modded, regions=[(PACK, pack)])
    res = recompile.recompile(img, entries, str(tmp_path), log=lambda *a: None)
    src = tmp_path / "src"
    return res, {p.name: p.read_text() for p in src.iterdir() if p.suffix == ".c"}


def test_a_pack_is_recompiled_at_its_address_with_what_its_veneers_call(tmp_path):
    res, c = translate(tmp_path, True)
    funcs = res["funcs"]
    assert {PACK, PACK + 0x10} <= set(funcs)         # the entry, and the veneer its `bl` reaches
    assert res["report"]["pack_functions"] == 2
    assert res["report"]["extra_code_functions"] == 4      # the extension's two and the pack's
    # the pack's own file of C: the `bl` a call of the veneer, the veneer a tail call of the word in the pack's
    # bytes (k, a game function)
    pack_c = c[f"f{PACK:08x}.c"]
    assert f"f_{PACK + 0x10:08x}(ctx);" in pack_c
    assert f"RC_FALL_EXPECT(); RC_FLUSH(); f_{BASE + 0x80:08x}(ctx); RC_RELOAD(); return;" in pack_c
    assert res["problems"] == [] and res["report"]["trap_instructions"] == 0


def test_a_switch_with_no_known_bound_runs_past_its_table_in_the_interpreter(tmp_path):
    res, c = translate(tmp_path, True)
    g = res["funcs"][BASE + 0x10]
    assert g.jtabs[BASE + 0x18] == [BASE + 0x28, BASE + 0x30]
    assert g.open_jtabs == {BASE + 0x18}
    assert res["report"]["open_jump_tables"] == 1
    text = c[f"f{BASE:08x}.c"]
    assert f"case 0u: goto L_{BASE + 0x28:08x};" in text and f"case 1u: goto L_{BASE + 0x30:08x};" in text
    # the jump itself, and what follows, in the interpreter: it returns where g would
    assert f"default: RC_FALL_EXPECT(); RC_FLUSH(); rc_call(ctx, 0x{BASE + 0x18:08x}u); return;" in text


def test_without_a_mod_changing_the_code_that_switch_stays_a_trap(tmp_path):
    res, _ = translate(tmp_path, False)
    assert f"f_{BASE + 0x10:08x}: unresolved jump table at {BASE + 0x18:#x}" in res["problems"]
    assert not res["funcs"][BASE + 0x10].open_jtabs


def test_a_branch_into_a_code_cave_is_a_tail_call_through_the_interpreter(tmp_path):
    res, c = translate(tmp_path, True)
    assert BASE + 0x40 in res["funcs"][BASE + 0x40].tails
    assert f"rc_call(ctx, 0x{CAVE:08x}u)" in c[f"f{BASE:08x}.c"]
    assert CAVE not in res["funcs"]
    assert res["report"]["interpreted_branches"] == 1


def test_a_thumb_bl_into_its_own_function_is_a_jump(tmp_path):
    res, c = translate(tmp_path, True)
    t = res["funcs"][BASE + 0x50]
    assert t.insns[BASE + 0x56].kind == B
    assert BASE + 0x5E in t.labels and (BASE + 0x5E, "thumb") not in t.calls
    assert BASE + 0x5E not in res["funcs"]
    assert f"f_{BASE + 0x5E:08x}(" not in c[f"f{BASE:08x}.c"]
