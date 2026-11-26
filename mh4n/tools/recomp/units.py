"""The recompiled C's files, the units the compiler is given one at a time: which functions go together.

A compile's peak memory follows the size of its file's C (the whole file's syntax tree and code are held until its
end), so a unit is kept under a size, `max_bytes` of C (build_game.py takes it from config/build_tuning.json: the
memory a compile job may take, through the cost of a byte of C measured there). Functions are grouped by address, so
that a change to one function's code rewrites its own unit only:

* every 64 KiB of code (by the functions' entry addresses) is one unit, `f<address>.c`, as long as its C fits;
* in one that doesn't, a function too large to share a unit is alone (`f_<address>.c`, the function's own name), and
  the rest of the 64 KiB is halved by address, and halved again, until each part fits or holds one function
  (`f<start>_<size>.c`).

The cuts fall on fixed addresses (the 64 KiB, their halves...), never where the sizes happen to add up, so they move
only when a part's size crosses `max_bytes`: a changed function rewrites its own unit, and only a part that grew past
the size (or shrank under it) is cut again, within itself.

Each unit declares the functions it names (not funcs.h, the declarations of every function of the game, which every
compile would read again), so its file holds all the C it is compiled from besides the runtime's headers.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

BUCKET_SHIFT = 16
NAMED = re.compile(r"\bf_[0-9a-f]{8}\b")


@dataclass
class Unit:
    name: str               # the file's name without .c
    addrs: list[int]        # its functions' entries, in address order


def plan(sizes: list[tuple[int, int]], max_bytes: int | None) -> list[Unit]:
    """The units for functions given as (entry, bytes of C), in any order; max_bytes None: one unit per 64 KiB."""
    buckets: dict[int, list[tuple[int, int]]] = {}
    for a, n in sorted(sizes):
        buckets.setdefault(a >> BUCKET_SHIFT, []).append((a, n))
    units = []
    for bucket, funcs in sorted(buckets.items()):
        start = bucket << BUCKET_SHIFT
        if max_bytes is None or sum(n for _, n in funcs) <= max_bytes:
            units.append(Unit(f"f{start:08x}", [a for a, _ in funcs]))
            continue
        alone = [a for a, n in funcs if n > max_bytes]
        rest = [(a, n) for a, n in funcs if n <= max_bytes]
        parts = _halves(rest, start, 1 << BUCKET_SHIFT, max_bytes)
        units += sorted([Unit(f"f_{a:08x}", [a]) for a in alone] + parts, key=lambda u: u.addrs[0])
    return units


def _halves(funcs: list[tuple[int, int]], start: int, size: int, max_bytes: int) -> list[Unit]:
    if not funcs:
        return []
    if len(funcs) == 1 or sum(n for _, n in funcs) <= max_bytes:
        return [Unit(f"f{start:08x}_{size:x}", [a for a, _ in funcs])]
    half = size >> 1
    middle = start + half
    return (_halves([f for f in funcs if f[0] < middle], start, half, max_bytes) +
            _halves([f for f in funcs if f[0] >= middle], middle, half, max_bytes))


FUNCTION_START = re.compile(r"^#define RC_FLUSH\(\) ", re.M)     # (each function's C begins with its RC_FLUSH)
FUNCTION_NAME = re.compile(r"^(?:static )?void (?:f|fb)_([0-9a-f]{8})\(RCtx \*ctx\) \{", re.M)


def functions_of(unit_text: str) -> list[tuple[int, str]]:
    """A unit's functions, (entry, its C as the emitter wrote it), from the unit's file."""
    starts = [m.start() for m in FUNCTION_START.finditer(unit_text)]
    out = []
    for a, b in zip(starts, starts[1:] + [len(unit_text)]):
        block = unit_text[a:b]
        out.append((int(FUNCTION_NAME.search(block).group(1), 16), block[:-1] if block.endswith("\n") else block))
    return out


def parts(unit_text: str, preamble: str) -> list[tuple[str, str]]:
    """The unit as one file per function, (name, C), each declaring what the unit declared that it names: what a
    unit the system stopped for its memory is compiled as (recomp/jobs.py)."""
    head = unit_text[:FUNCTION_START.search(unit_text).start()] if FUNCTION_START.search(unit_text) else ""
    declared = set(NAMED.findall(head))
    return [(f"f_{a:08x}", text([c], preamble, declared)) for a, c in functions_of(unit_text)]


def text(functions: list[str], preamble: str, known: set[str] | None = None) -> str:
    """A unit's C: the runtime's header, the declarations of the functions its C names (those of `known`, every
    function there is, when given), the preamble, then each function's C as the emitter wrote it."""
    body = "\n".join([preamble, *functions])
    names = sorted(n for n in set(NAMED.findall(body)) if known is None or n in known)
    return '#include "recomp.h"\n' + "".join(f"void {n}(RCtx *ctx);\n" for n in names) + body + "\n"
