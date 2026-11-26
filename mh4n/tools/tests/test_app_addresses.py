"""The port's addresses in a code mod built from the dump's own version (no game data).

The port reads, calls and hooks the game at fixed addresses of the version it is built for. A mod built from that
version may patch the code around them, but must leave each one where the version has it: a function the port uses
is the same code there (or a patched one between two that are), and a table it reads is still the one the game's
code points at. A table of the port's that the mod moved whole into its own memory (every word that pointed at it
points at the mod's copy) is followed: the library carries the mod's address. Anything else out of place stops the
build, named (game_version.place_check).
"""
from __future__ import annotations

import os
import struct
import sys
from types import SimpleNamespace as NS

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import build_game  # noqa: E402
import game_version  # noqa: E402

TEXT, RO, DATA = 0x00100000, 0x00101000, 0x00102000
HEADER = NS(text=NS(address=TEXT, size=0x1000), ro=NS(address=RO, size=0x1000), data=NS(address=DATA, size=0x1000),
            bss_size=0x1000)
TABLE, OTHER, ABOVE = RO + 0x100, RO + 0x200, RO + 0x300     # data the game's code points at
MOVED = DATA + 0x1800                                        # where a mod put its own copy of TABLE
MOV_R0, BX_LR = 0xE3A00000, 0xE12FFF1E
FUNCTIONS = [(TEXT + 0x10 * i, 0x10, False) for i in range(6)]
C = TEXT + 0x20                                              # a function the port uses


def reference() -> bytearray:
    code = bytearray(0x3000)
    for i, (start, _, _) in enumerate(FUNCTIONS):
        # mov r0, #i; bx lr; and a literal pool that points at the game's data
        struct.pack_into("<4I", code, start - TEXT, MOV_R0 | i, BX_LR, TABLE, OTHER if i == 1 else ABOVE)
    struct.pack_into("<I", code, DATA - TEXT, TABLE)        # a pointer of the data to the table
    return code


def patched(code: bytearray, *starts: int) -> bytearray:
    for s in starts:
        struct.pack_into("<I", code, s - TEXT, 0xEA000000 | 0x3FFF)   # b somewhere: the mod's hook
    return code


WANTED = [(C + 4, "C"), (TABLE, "TABLE"), (OTHER + 0x10, "NEAR_OTHER")]


def test_the_unmodified_code_has_every_address_in_place():
    ref = bytes(reference())
    assert game_version.misplaced(ref, ref, HEADER, FUNCTIONS, WANTED) == []


def test_a_patched_function_between_two_in_place_is_in_place():
    ref = bytes(reference())
    assert game_version.misplaced(ref, bytes(patched(reference(), C)), HEADER, FUNCTIONS, WANTED) == []


def test_a_function_whose_neighbourhood_changed_too_is_named():
    ref = bytes(reference())
    mod = bytes(patched(reference(), *(s for s, _, _ in FUNCTIONS)))
    assert game_version.misplaced(ref, mod, HEADER, FUNCTIONS, WANTED) == [
        f"C 0x{C + 4:08x} (its function and the code around it are not where they were)"]


def test_a_table_the_code_no_longer_points_at_is_named():
    ref = bytes(reference())
    mod = bytes(reference()).replace(struct.pack("<I", TABLE), struct.pack("<I", MOVED))
    assert game_version.misplaced(ref, mod, HEADER, FUNCTIONS, [(TABLE, "TABLE")]) == [
        f"TABLE 0x{TABLE:08x} (0 of the 7 words that hold it still do, 7 of them 0x{MOVED:08x})"]


def test_data_no_word_points_near_is_named_as_unknown():
    ref = bytes(reference())
    far = DATA + 0x2000 - 4         # the end of .bss
    assert game_version.misplaced(ref, ref, HEADER, FUNCTIONS, [(far, "FAR")]) == [
        f"FAR 0x{far:08x} (no word of the code holds it or an address near it)"]


MOD_HEADER = NS(text=HEADER.text, ro=HEADER.ro, data=NS(address=DATA, size=0x3000), bss_size=0x1000)
OWN = game_version.own_memory(HEADER, MOD_HEADER)             # what the mod's exheader maps past the game's
COPY = OWN[0] + 0x100                                         # the mod's copy of TABLE, in its own memory


def moved(holders: int, to: int = COPY, second: int | None = None) -> bytes:
    """The game's code with the first `holders` words that held TABLE holding `to` (the rest `second`, if given)."""
    code, word, n = bytearray(reference()), struct.pack("<I", TABLE), 0
    at = code.find(word)
    while at >= 0:
        if n < holders or second is not None:
            code[at:at + 4] = struct.pack("<I", to if n < holders else second)
        n, at = n + 1, code.find(word, at + 4)
    return bytes(code)


def check(mod: bytes, wanted=((TABLE, "TABLE"),), carriable=frozenset({TABLE})):
    return game_version.place_check(bytes(reference()), mod, HEADER, FUNCTIONS, list(wanted), OWN, carriable)


def test_the_mods_own_memory_is_what_its_exheader_maps_past_the_game():
    assert OWN == (DATA + 0x2000, DATA + 0x4000)
    assert game_version.own_memory(HEADER, HEADER) is None


def test_a_table_the_mod_moved_whole_into_its_memory_is_carried():
    assert check(moved(7)) == ([], [(TABLE, COPY, "TABLE")])


def test_a_table_moved_in_part_stops_the_build():
    assert check(moved(3)) == ([f"TABLE 0x{TABLE:08x} (4 of the 7 words that hold it still do, 3 of them "
                                f"0x{COPY:08x})"], [])
    names, carried = check(moved(3, second=COPY + 0x10))          # two new addresses
    assert carried == [] and len(names) == 1
    names, carried = check(moved(7, to=MOVED))                    # outside the mod's memory
    assert carried == [] and len(names) == 1


def test_a_hook_or_tunable_moved_whole_stops_the_build():
    names, carried = check(moved(7), wanted=[(TABLE, "tunable T")], carriable=frozenset())
    assert carried == [] and names == [f"tunable T 0x{TABLE:08x} (0 of the 7 words that hold it still do, 7 of them "
                                       f"0x{COPY:08x}: the port can't follow a hook or tunable there)"]


def test_the_library_carries_the_move_with_every_other_port_address():
    port = [(TABLE, "TABLE"), (C + 4, "C")]
    # USA v1.3.0's library has no table: with a move it gets every address, the others as they are
    assert build_game.carried_table([], port, {TABLE: COPY}) == [(C + 4, C + 4), (TABLE, COPY)]
    assert build_game.carried_table([], port, {}) == []
    # another version's: its own pairs, the move by that version's address
    assert build_game.carried_table([(0x1000, TABLE), (0x2000, C)], port, {TABLE: COPY}) == [(0x1000, COPY),
                                                                                             (0x2000, C)]


def test_the_addresses_are_the_ports_own_its_hooks_and_its_tunables():
    got = game_version.app_addresses({"target": True}, [(C + 4, "C")], {TEXT: "A"}, {TEXT + 0x10: (1, "B")})
    assert got == [(TEXT, "hook A"), (TEXT + 0x10, "tunable B"), (C + 4, "C")]


def test_a_version_read_twice_is_the_same_version():
    a = {"key": "usa_v130", "name": "USA v1.3.0"}
    assert build_game.same_version(a, dict(a))
    assert not build_game.same_version(a, {"key": "eur_v130", "name": "EUR v1.3.0"})
    assert not build_game.same_version(a, None) and build_game.same_version(None, None)
