#!/usr/bin/env python3
"""The game's version as an input of the tools: which version a code.bin is, what a code mod's exheader.bin says
about the code it brings, the port's own addresses in that version, and what the code library is told about it.

Every version has its own addresses: the project's tables (config/functions.tsv, hooks.txt, tunables.txt) and the
port's native code (config/port_addresses.txt: port/src/hooks.cpp, swkbd_bridge.cpp, the runtime's frame counter)
hold USA v1.3.0's, and config/shifts/<version>.txt moves them to another version (tools/version_map.py).
config/versions.json lists the versions; each has a short key (usa_v130, eur_v110, usa_v010, ...).

A library made for another version than USA v1.3.0 carries its version and the port's addresses in it
(game_version.c, written beside the recompiled code): rc_game_version, rc_port_addr_usa[] and rc_port_addr_here[],
which the native runtime reads (mh4n/game_version.h) to translate the port's addresses. A USA v1.3.0 library has no
table (the port's addresses are its own), so that library, and its id, stay what they were. Every library also
carries the id of the tools that made it (rc_library_id, the .id file's) and, when a code mod put code past the
game's data (Heavenfall's "extension"), that code's range and CRC-32 (rc_ext_*), which the runtime checks.

    python3 tools/game_version.py list                      # the versions and whether the port runs them
    python3 tools/game_version.py translate usa_v010 0x01057194 ...
"""
from __future__ import annotations

import argparse
import bisect
import collections
import hashlib
import json
import os
import sys
import zlib
from dataclasses import dataclass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import exheader as exh  # noqa: E402
import version_map  # noqa: E402

VERSIONS = os.path.join(ROOT, "config", "versions.json")
PORT_ADDRESSES = os.path.join(ROOT, "config", "port_addresses.txt")
PAGE = 0x1000
# The source file build_game.py writes beside the recompiled code (not one of build_game.ID_FILES: what it holds
# is the game's version, not the tools').
SOURCE_NAME = "game_version.c"


class VersionError(ValueError):
    """A code or a mod's exheader the tools can't make a library of; the text says what to do."""


def load_versions(path: str | None = None) -> list[dict]:
    with open(path or VERSIONS, encoding="utf-8") as f:
        return json.load(f)["versions"]


def find(key: str, versions: list[dict]) -> dict:
    for v in versions:
        if v.get("key") == key:
            return v
    raise VersionError(f"no version {key!r} in config/versions.json (known: "
                       + ", ".join(v["key"] for v in versions if v.get("key")) + ")")


def by_code(code: bytes, versions: list[dict]) -> dict | None:
    """The version whose decompressed code.bin this is, by its SHA-1."""
    sha1 = hashlib.sha1(code).hexdigest()
    return next((v for v in versions if v.get("code_sha1") == sha1), None)


def mod_id(program_id: int) -> int:
    if program_id & 0x000000FF_00000000 == 0x0000000E_00000000:
        program_id &= ~0x0000000E_00000000
    return program_id


def buildable(version: dict) -> bool:
    """The tables are for the target version; another one needs its shifts (tools/version_map.py)."""
    return bool(version.get("target") or version.get("shifts"))


def segments(version: dict) -> dict[str, tuple[int, int]]:
    return {k: (int(a, 16), int(s, 16)) for k, (a, s) in version.get("segments", {}).items()}


def unknown_code_message(program_id: int | None, versions: list[dict]) -> str:
    """What to tell a player whose code.bin is no version the project lists."""
    same_title = [v for v in versions if program_id is not None and v.get("program_id")
                  and mod_id(int(v["program_id"], 16)) == mod_id(program_id)]
    runs = [v["name"] for v in versions if buildable(v) and not v.get("mod_base_only")]
    if same_title and not any(v.get("code_sha1") for v in same_title):
        v = same_title[0]
        return (f"this is {v['name']}, a version the port has no addresses for yet: its library needs its own "
                f"tables (config/shifts), which someone with both that dump and the USA v1.3.0 one makes with "
                f"tools/version_map.py. The port runs: {', '.join(runs)}")
    if same_title:
        return ("this code.bin is a version of the game the project doesn't list (config/versions.json): another "
                "update, a modified code.bin or a damaged dump. The port runs: " + ", ".join(runs))
    return ("this code.bin isn't a version the project knows (config/versions.json); the port runs: " + ", ".join(runs))


# ── a code mod's exheader ──────────────────────────────────────────────────

@dataclass(frozen=True)
class ModCode:
    version: dict                       # the version whose code the mod's code is made from
    header: exh.ExHeader                # the mod's exheader: the segments the code is loaded with
    extension: tuple[int, int] | None   # [start, end) of the mod's code past the version's data and .bss


def _align(x: int) -> int:
    return (x + PAGE - 1) & ~(PAGE - 1)


def check_mod_exheader(game: exh.ExHeader, mod: exh.ExHeader, code_size: int, versions: list[dict],
                       where: str = "exheader.bin") -> ModCode:
    """A code mod's exheader.bin, checked as the native loader checks it (code_patch::CheckExheaderOverride) and
    matched to a version: the same program, its .text and .rodata where and as big as one known version's, its
    .data where that version's is, either as big (and then the same .bss) or bigger and covering that version's
    whole .bss (Heavenfall: the old .bss in .data, zeros in the file, and new pages after it), and code.bin as big
    as its segments. Returns the version and the range past the version's data (the mod's "extension"), or raises
    VersionError with what is wrong."""
    if game.program_id is not None and mod.program_id is not None and mod_id(mod.program_id) != mod_id(game.program_id):
        raise VersionError(f"{where} is for program {mod.program_id:016X}, not this game's {game.program_id:016X}")
    expected = (mod.text.num_pages + mod.ro.num_pages + mod.data.num_pages) * PAGE
    if code_size != expected:
        raise VersionError(f"{where}: the mod's code.bin is 0x{code_size:X} bytes but its segments take 0x{expected:X}: "
                           "the code.bin and exheader.bin are not from the same build of the mod")
    for name in ("text", "ro", "data"):
        s = getattr(mod, name)
        if s.size > s.num_pages * PAGE:
            raise VersionError(f"{where}: its .{name} segment is larger than its pages")
    candidates = []
    for v in versions:
        seg = segments(v)
        if not seg or not v.get("program_id") or mod_id(int(v["program_id"], 16)) != mod_id(mod.program_id or 0):
            continue
        if (mod.text.address, mod.text.size) != seg["text"] or (mod.ro.address, mod.ro.size) != seg["rodata"]:
            continue
        data_addr, data_size = seg["data"]
        bss_addr, bss_size = seg["bss"]
        if mod.data.address != data_addr or mod.data.size < data_size:
            continue
        mod_end = mod.data.address + mod.data.size
        if mod.data.size == data_size:
            if mod.bss_size != bss_size:
                continue
            candidates.append((v, None))
        elif mod_end >= bss_addr + bss_size:
            start = _align(bss_addr + bss_size)
            candidates.append((v, (start, mod_end) if mod_end > start else None))
    if not candidates:
        raise VersionError(f"{where}: the code it describes (.text 0x{mod.text.size:X} bytes, .rodata 0x{mod.ro.size:X}, "
                           f".data 0x{mod.data.size:X}) is no version of the game the tools know: a mod made for "
                           "another version or region, or a layout the port can't run")
    v, ext = candidates[0]
    return ModCode(v, mod, ext)


# ── the port's own addresses ───────────────────────────────────────────────

def load_port_addresses(path: str | None = None) -> list[tuple[int, str]]:
    """config/port_addresses.txt: `<address> <name>  # where` per line (USA v1.3.0)."""
    out = []
    with open(path or PORT_ADDRESSES, encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                parts = line.split()
                out.append((int(parts[0], 16), parts[1] if len(parts) > 1 else ""))
    return out


def port_table(version: dict, addresses: list[tuple[int, str]], root: str = ROOT) -> list[tuple[int, int]]:
    """(USA v1.3.0 address, this version's) for each port address; [] for the target version (the addresses are
    its own). An address without a translation is a VersionError: the library would point the hooks elsewhere."""
    if version.get("target"):
        return []
    if not version.get("shifts"):
        raise VersionError(f"{version.get('name', 'this version')} has no address map (config/shifts)")
    shifts = version_map.Shifts.load(os.path.join(root, version["shifts"]))
    out, missing = [], []
    for usa, name in addresses:
        here = shifts.translate(usa)
        if here is None:
            missing.append(f"0x{usa:08x} {name}")
        else:
            out.append((usa, here))
    if missing:
        raise VersionError(f"{version['shifts']} has no address for the port's " + ", ".join(missing)
                           + ": rebuild it with tools/version_map.py")
    return sorted(set(out))


def pack_record(file: str, data: bytes) -> tuple[str, int, int]:
    """A code mod's pack (code_mods.Pack: `file` in the mod's folder, under romfs/) as the library names it for the
    runtime's check: its path in the game's RomFS, its size and CRC-32."""
    path = file.replace("\\", "/")
    if not path.startswith("romfs/"):
        raise VersionError(f"the pack {file} is not a file of the mod's RomFS (romfs/)")
    return path[len("romfs/"):], len(data), zlib.crc32(data)


def _c_string(text: str) -> str:
    """`text` as a C string literal of plain ASCII (other bytes as octal escapes)."""
    out = []
    for b in text.encode("utf-8"):
        out.append(chr(b) if 0x20 <= b < 0x7F and chr(b) not in '"\\?' else f"\\{b:03o}")
    return '"' + "".join(out) + '"'


def version_source(key: str, library_id: str, table: list[tuple[int, int]],
                   extension: tuple[int, int, int] | None = None,
                   packs: list[tuple[str, int, int]] | None = None) -> str:
    """game_version.c: what the runtime is told about the game version the library was made for. `packs` (a code
    mod's, pack_record's): the RomFS files the library recompiled, which the runtime checks before the game runs
    (native/src/services/fs/pack_check.cpp); a library without them says nothing of packs."""
    base, size, crc = extension or (0, 0, 0)
    usa = ", ".join(f"0x{a:08x}u" for a, _ in table) or "0"
    here = ", ".join(f"0x{b:08x}u" for _, b in table) or "0"
    pack_lines = ""
    if packs:
        files = _c_string("\n".join(p for p, _, _ in packs))
        sizes = ", ".join(f"0x{s:x}u" for _, s, _ in packs)
        crcs = ", ".join(f"0x{c:08x}u" for _, _, c in packs)
        pack_lines = (
            "/* a code mod's packs: RomFS files its code loads at run time, recompiled from these bytes"
            " (a path a line) */\n"
            f"GV_EXPORT const unsigned rc_pack_count = {len(packs)}u;\n"
            f"GV_EXPORT const char rc_pack_files[] = {files};\n"
            f"GV_EXPORT const unsigned rc_pack_size[] = {{{sizes}}};\n"
            f"GV_EXPORT const unsigned rc_pack_crc32[] = {{{crcs}}};\n")
    return f"""/* Written by tools/build_game.py (tools/game_version.py): the game version this library was made for. */
#if defined(_WIN32)
#define GV_EXPORT __declspec(dllexport)
#else
#define GV_EXPORT __attribute__((visibility("default")))
#endif
/* config/versions.json's key */
GV_EXPORT const char rc_game_version[] = "{key}";
/* the id of the tools that made it (build_game.py's code_library_id, the .id file beside the library) */
GV_EXPORT const char rc_library_id[] = "{library_id}";
/* the port's addresses (config/port_addresses.txt): USA v1.3.0's and this version's; none for USA v1.3.0 */
GV_EXPORT const unsigned rc_port_addr_count = {len(table)}u;
GV_EXPORT const unsigned rc_port_addr_usa[] = {{{usa}}};
GV_EXPORT const unsigned rc_port_addr_here[] = {{{here}}};
/* a code mod's code past the game's data (its "extension"): where, how big and its CRC-32 when the library was made */
GV_EXPORT const unsigned rc_ext_base = 0x{base:08x}u;
GV_EXPORT const unsigned rc_ext_size = 0x{size:x}u;
GV_EXPORT const unsigned rc_ext_crc32 = 0x{crc:08x}u;
{pack_lines}"""


# The port's own copy of the maps (port/src/game_shifts.h): a library carries the port's addresses that
# config/port_addresses.txt had when it was made, and its id doesn't change with that list, so a library of another
# version made by older tools lacks the addresses added since (1.0's EUR v1.1.0 libraries and 1.0.1's gather.cpp). The
# port takes those from this copy, as port_table would have (port/src/game_addr.h's SetGameAddresses).
PORT_SHIFTS = os.path.join(ROOT, "port", "src", "game_shifts.h")


def port_shifts_header(versions: list[dict] | None = None, root: str = ROOT) -> str:
    """port/src/game_shifts.h: every mapped version's config/shifts, as C++ tables (LF line ends)."""
    def name(key: str, part: str) -> str:
        return "kShifts" + "".join(w.capitalize() for w in key.split("_")) + part

    def rows(intervals: list[tuple[int, int, int]]) -> str:
        return "".join(f"    {{0x{s:08x}u, 0x{e:08x}u, {'-' if d < 0 else ''}0x{abs(d):x}}},\n" for s, e, d in intervals)

    tables, entries = [], []
    for v in versions if versions is not None else load_versions():
        if v.get("target") or not v.get("shifts"):
            continue
        key = v["key"]
        shifts = version_map.Shifts.load(os.path.join(root, v["shifts"]))
        text_start, text_end = shifts.text or (0, 0)
        tables.append(f"// {v['shifts']}: {v['name']}\n"
                      f"inline constexpr GameShift {name(key, 'Text')}[] = {{\n{rows(shifts.intervals)}}};\n")
        data = "nullptr, 0"
        data_start, data_end = shifts.data or (0, 0)
        if shifts.data:
            tables.append(f"inline constexpr GameShift {name(key, 'Data')}[] = {{\n{rows(shifts.data_intervals)}}};\n")
            data = f"{name(key, 'Data')}, {len(shifts.data_intervals)}"
        entries.append(f"    {{\"{key}\", 0x{text_start:08x}u, 0x{text_end:08x}u, {name(key, 'Text')}, "
                       f"{len(shifts.intervals)}, 0x{data_start:08x}u, 0x{data_end:08x}u, {data}}},\n")
    return ("// Written by tools/game_version.py port-shifts from config/shifts (don't edit it: tests/test_port_addresses.py\n"
            "// checks that it is current). Each mapped version's map of USA v1.3.0's addresses, as tools/version_map.py's\n"
            "// Shifts: an address of USA's .text in an interval [start, end) is at address + shift in that version, one of\n"
            "// .text in none has no address there; outside .text, and outside the data when a version's data moved, an\n"
            "// address is the same. game_addr.h takes from it the port's addresses that a code library of that version\n"
            "// doesn't carry (made by tools from before they were in config/port_addresses.txt).\n"
            "#pragma once\n\n#include <cstdint>\n\nnamespace Port {\n\n"
            "struct GameShift {\n    std::uint32_t start, end;  // USA v1.3.0's [start, end)\n"
            "    std::int32_t shift;        // where they are in that version: address + shift\n};\n\n"
            "struct GameShifts {\n"
            "    const char* key;                     // config/versions.json's key (a library's rc_game_version)\n"
            "    std::uint32_t text_start, text_end;  // USA's .text\n"
            "    const GameShift* text;\n    std::uint32_t text_count;\n"
            "    std::uint32_t data_start, data_end;  // USA's .rodata to the end of .bss, when the data moved (else 0, 0)\n"
            "    const GameShift* data;\n    std::uint32_t data_count;\n};\n\n"
            + "\n".join(tables)
            + "\ninline constexpr GameShifts kGameShifts[] = {\n" + "".join(entries) + "};\n\n}  // namespace Port\n")


def extension_crc(code: bytes, text_base: int, extension: tuple[int, int] | None) -> tuple[int, int, int] | None:
    """(base, size, CRC-32) of the extension's bytes in the code (code.bin starts at text_base)."""
    if not extension:
        return None
    start, end = extension
    data = code[start - text_base:end - text_base]
    return start, len(data), zlib.crc32(data)


def port_code_ranges(version: dict, functions: dict[int, int], hooks: dict[int, str],
                     tunables: dict[int, tuple[int, str]], addresses: list[tuple[int, str]],
                     root: str = ROOT) -> list[tuple[int, int, str]]:
    """The code the port hooks, tunes or calls, at `version`'s addresses: (start, end, what). `functions`: USA v1.3.0
    function start -> size (config/functions.tsv); `hooks` and `tunables` are already at the version's addresses."""
    shifts = None if version.get("target") or not version.get("shifts") else \
        version_map.Shifts.load(os.path.join(root, version["shifts"]))
    here = (lambda a: a) if shifts is None else shifts.translate
    sizes = {}
    for usa, size in functions.items():
        t = here(usa)
        if t is not None:
            sizes[t] = size
    out = [(a, a + sizes.get(a, 4), f"hook {name}") for a, name in hooks.items()]
    out += [(a, a + 4, f"tunable {name} 0x{a:08x}") for a, (_, name) in tunables.items()]
    for usa, name in addresses:
        t = here(usa)
        if t is not None and t in sizes and not name.startswith("hook:"):
            out.append((t, t + sizes[t], f"function {name}"))
    return sorted(set(out))


def app_addresses(version: dict, addresses: list[tuple[int, str]], hooks: dict[int, str],
                  tunables: dict[int, tuple[int, str]], root: str = ROOT) -> list[tuple[int, str]]:
    """Every address of the game the port uses, at `version`'s addresses: its own (config/port_addresses.txt, moved
    as port_table moves them), its hook points and its tunables (`hooks` and `tunables` already at the version's)."""
    moved = dict(port_table(version, addresses, root))
    out = [(moved.get(usa, usa), name) for usa, name in addresses]
    out += [(a, f"hook {name}") for a, name in hooks.items()]
    out += [(a, f"tunable {name}") for a, (_, name) in tunables.items()]
    return sorted(set(out))


def functions_here(version: dict, rows: list[dict], root: str = ROOT) -> list[tuple[int, int, bool]]:
    """config/functions.tsv's rows (build_function_table.read_tsv) as (start, size, Thumb) at `version`'s addresses;
    a function with no address there is left out."""
    shifts = None if version.get("target") or not version.get("shifts") else \
        version_map.Shifts.load(os.path.join(root, version["shifts"]))
    out = []
    for r in rows:
        usa = int(r["address"], 16)
        here = usa if shifts is None else shifts.translate(usa)
        if here is not None:
            out.append((here, int(r["size"], 16), r["mode"] == "thumb"))
    return out


# A data address of the game is in place in a mod's code when the words of the code that hold it (literal pools)
# still hold it there: a clear majority (version_map's), or, with no such word, its nearest neighbours' within this.
NEIGHBOURS = 0x2000


def misplaced(reference: bytes, modded: bytes, header: exh.ExHeader, functions: list[tuple[int, int, bool]],
              wanted: list[tuple[int, str]]) -> list[str]:
    """place_check's list of what moved, with nothing carried."""
    return place_check(reference, modded, header, functions, wanted)[0]


def own_memory(game: exh.ExHeader, mod: exh.ExHeader) -> tuple[int, int] | None:
    """[start, end) of the memory a mod's exheader maps past the game's (from the page after the game's .bss to the
    end of the mod's .bss), or None when it maps none."""
    start = _align(game.data.address + game.data.size + game.bss_size)
    end = mod.data.address + mod.data.size + mod.bss_size
    return (start, end) if end > start else None


def place_check(reference: bytes, modded: bytes, header: exh.ExHeader, functions: list[tuple[int, int, bool]],
                wanted: list[tuple[int, str]], own: tuple[int, int] | None = None,
                carriable: set[int] | frozenset = frozenset()) -> tuple[list[str], list[tuple[int, int, str]]]:
    """Which of `wanted` (address, name) a code mod made from the same version as `reference` (its unmodified code,
    laid out by `header`) moved: ("name 0x... (why)" for each that the library can't follow, [] when every one is
    where that version has it; and (address, the mod's address, name) for each the mod moved whole, which the library
    carries).

    In .text, the function holding the address (`functions`: start, size, Thumb) must be the reference's at the same
    address once the branches and pointers that leave it are set aside (version_map.compare), or, when the mod
    patched it, lie between two functions that are. In the data (.rodata to the end of .bss), the words of the
    reference that hold the address (its code's literal pools, its data's pointers) must hold it in the mod's code
    too (a clear majority of them), or, when no word holds it, those of its nearest addresses on both sides that some
    word holds. An address of `carriable` (the port's own, which the library can carry for its version) whose every
    holder now holds one same other address inside `own` (the mod's own memory, own_memory) is moved by the mod:
    its new address is the one the port uses. Some holders moved and some not, two new addresses, a new address
    outside the mod's memory, a hook or a tunable moved: named, as anything else out of place."""
    text_lo, text_hi = header.text.address, header.text.address + header.text.size
    data_lo, data_hi = header.ro.address, header.data.address + header.data.size + header.bss_size
    src, dst = version_map.image_of(header, reference), version_map.image_of(header, modded)
    funcs = sorted(f for f in functions if text_lo <= f[0] < text_hi)
    starts = [f[0] for f in funcs]

    def same(f: tuple[int, int, bool]) -> bool:
        return version_map.compare(src, dst, f[0], f[0], f[1], f[2]) is not None

    def code_place(a: int) -> str | None:
        a &= ~1
        i = bisect.bisect_right(starts, a) - 1
        if i < 0 or a >= funcs[i][0] + funcs[i][1]:
            return None if reference[a - text_lo:a - text_lo + 4] == modded[a - text_lo:a - text_lo + 4] else \
                "in no listed function, and its bytes differ"
        if same(funcs[i]):
            return None
        before = next((f for f in reversed(funcs[max(0, i - 8):i]) if same(f)), None)
        after = next((f for f in funcs[i + 1:i + 9] if same(f)), None)
        return None if before and after else "its function and the code around it are not where they were"

    held: dict[int, list] = {}      # data address -> [words of the reference holding it, of them still there, others]
    for start, size in ((text_lo, header.text.size), (header.ro.address, header.ro.size),
                        (header.data.address, header.data.size)):
        lo, hi = start - text_lo, start - text_lo + (size // 4) * 4
        for v, w in zip(memoryview(reference[lo:hi]).cast("I"), memoryview(modded[lo:hi]).cast("I")):
            if data_lo <= v < data_hi:
                c = held.setdefault(v, [0, 0, collections.Counter()])
                c[0] += 1
                if v == w:
                    c[1] += 1
                else:
                    c[2][w] += 1
    evidence = sorted(held)

    def kept(v: int) -> bool:
        total, there, _ = held[v]
        return there * 3 >= total * 2

    def moved_whole(a: int) -> int | None:
        """The one address every word that held `a` holds now, inside the mod's own memory; else None."""
        total, there, others = held[a]
        if there or len(others) != 1 or own is None:
            return None
        (new, count), = others.items()
        return new if count == total and own[0] <= new < own[1] else None

    def data_place(a: int) -> str | None:
        if a in held:
            total, there, others = held[a]
            now = f", {others.most_common(1)[0][1]} of them 0x{others.most_common(1)[0][0]:08x}" if others else ""
            return None if kept(a) else f"{there} of the {total} words that hold it still do{now}"
        i = bisect.bisect_left(evidence, a)
        lo = evidence[i - 1] if i > 0 and a - evidence[i - 1] <= NEIGHBOURS else None
        hi = evidence[i] if i < len(evidence) and evidence[i] - a <= NEIGHBOURS else None
        if lo is None or hi is None:
            return "no word of the code holds it or an address near it"
        return None if kept(lo) and kept(hi) else "the addresses near it moved"

    out, carried = [], []
    for a, name in wanted:
        is_data = data_lo <= a < data_hi
        why = code_place(a) if text_lo <= (a & ~1) < text_hi else data_place(a) if is_data else None
        if not why:
            continue
        new = moved_whole(a) if is_data and a in held else None
        if new is not None and a in carriable:
            carried.append((a, new, name))
        else:
            unfollowed = ": the port can't follow a hook or tunable there" if new is not None else ""
            out.append(f"{name} 0x{a:08x} ({why}{unfollowed})")
    return out, carried


def touched(reference: bytes, modded: bytes, text_base: int, ranges: list[tuple[int, int, str]]) -> list[str]:
    """What of `ranges` a code mod changed: the bytes differ between the reference code and the modded one."""
    out = []
    for start, end, what in ranges:
        a, b = start - text_base, end - text_base
        if 0 <= a < b <= min(len(reference), len(modded)) and reference[a:b] != modded[a:b]:
            out.append(what)
    return out


def reference_code(version: dict, sources: list[str], work_dir: str) -> bytes | None:
    """The unmodified code of `version` from one of the player's dumps (a .3ds/.cci/.cia given with --dump or --romfs),
    if one of them is that version (its SHA-1); None otherwise."""
    import codebin2elf
    import extract_ncch
    want = version.get("code_sha1")
    if not want:
        return None
    for i, path in enumerate(sources):
        if os.path.splitext(path)[1].lower() not in (".3ds", ".cci", ".cia", ".cxi"):
            continue
        out = os.path.join(work_dir, f"reference{i}")
        try:
            extract_ncch.extract(path, out)
            with open(os.path.join(out, "exheader.bin"), "rb") as f:
                header = exh.parse(f.read())
            with open(os.path.join(out, "code.bin"), "rb") as f:
                code = codebin2elf.load_code(header, f.read())
        except (OSError, ValueError):
            continue
        if hashlib.sha1(code).hexdigest() == want:
            return code
    return None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="the versions and whether the port runs them")
    t = sub.add_parser("translate", help="a version's addresses for USA v1.3.0 ones")
    t.add_argument("key")
    t.add_argument("addresses", nargs="+")
    p = sub.add_parser("port-shifts", help="write port/src/game_shifts.h, the port's copy of config/shifts")
    p.add_argument("-o", "--output", default=PORT_SHIFTS)
    args = ap.parse_args(argv)
    versions = load_versions()
    if args.cmd == "port-shifts":
        with open(args.output, "wb") as f:
            f.write(port_shifts_header(versions).encode())
        print(f"wrote {args.output}")
        return 0
    if args.cmd == "list":
        for v in versions:
            state = ("the tables' own" if v.get("target") else
                     f"through {v['shifts']}" if v.get("shifts") else "no addresses yet")
            print(f"{v.get('key', '?'):10} {v['name']}: {state}")
        return 0
    v = find(args.key, versions)
    shifts = version_map.Shifts.load(os.path.join(ROOT, v["shifts"])) if v.get("shifts") else None
    for a in args.addresses:
        usa = int(a, 16)
        here = usa if v.get("target") else shifts.translate(usa) if shifts else None
        print(f"0x{usa:08x} -> " + ("unmapped" if here is None else f"0x{here:08x}"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
