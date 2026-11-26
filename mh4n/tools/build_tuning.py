"""config/build_tuning.json: the numbers the code library's build goes by (each with its reason in the file's "why"):
the memory a compile job may take and what a unit's C costs (recomp/units.py cuts the C to fit it, recomp/jobs.py
runs as many jobs as the free memory holds), the translation's peak memory, the space a build needs, and the
objects' cache's bounds. Measured numbers, never written into the code."""
from __future__ import annotations

import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(os.path.dirname(HERE), "config", "build_tuning.json")
FORMAT = 1


class TuningError(ValueError):
    pass


def load(path: str | None = None) -> dict:
    """The file's numbers (format 1), every one of them present and positive."""
    path = path or PATH
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise TuningError(f"{path} can't be read: {e}") from None
    if not isinstance(data, dict) or data.get("format") != FORMAT:
        raise TuningError(f"{path} is not format {FORMAT}")
    wanted = {"compile": ("job_mb", "base_mb", "mb_per_mib_of_c", "reserve_mb", "poll_s"),
              "translation": ("peak_mb",),
              "space": ("dump_mb", "sources_mb", "objects_mb", "zig_copies_mb", "library_mb", "cache_floor_mb"),
              "cache": ("keep_free_mb", "max_mb")}
    for part, keys in wanted.items():
        for k in keys:
            v = data.get(part, {}).get(k) if isinstance(data.get(part), dict) else None
            if isinstance(v, bool) or not isinstance(v, (int, float)) or v <= 0:
                raise TuningError(f"{path}: {part}.{k} must be a positive number")
    if data["compile"]["job_mb"] <= data["compile"]["base_mb"]:
        raise TuningError(f"{path}: compile.job_mb must be more than compile.base_mb")
    return data


def unit_bytes(tuning: dict) -> int:
    """The most C a unit gets: what fits a compile job's memory once its base is taken."""
    c = tuning["compile"]
    return int((c["job_mb"] - c["base_mb"]) / c["mb_per_mib_of_c"] * (1 << 20))


def compile_mb(tuning: dict, c_bytes: int) -> float:
    """A compile's expected peak memory for a unit of c_bytes of C."""
    c = tuning["compile"]
    return c["base_mb"] + c["mb_per_mib_of_c"] * c_bytes / (1 << 20)


COMPILED = re.compile(r"^\s*compiled (\S+) \((\d+) KiB of C\): ([\d.]+) s, (\d+) MB", re.M)
TRANSLATION_PEAK = re.compile(r"^translation: peak memory (\d+) MB", re.M)


def fit_log(text: str) -> dict:
    """A build's log (the phone's builder_log.txt, or build_game.py's output) against this file's numbers: each
    compile's peak against its unit's C (least squares, peak = base + slope x MiB), how far the compiles went over
    that line (the 99th percentile and the worst), and the translation's peak. What to copy back: compile.base_mb
    (the fitted base plus the 99th percentile's excess: 99 % of the compiles measured fit their expected peak),
    compile.mb_per_mib_of_c, translation.peak_mb (the peak, rounded up)."""
    rows = [(int(kib) / 1024, int(mb)) for _, kib, _, mb in COMPILED.findall(text)]
    out: dict = {"compiles": len(rows)}
    if len(rows) >= 2 and len({x for x, _ in rows}) >= 2:
        n = len(rows)
        mx, my = sum(x for x, _ in rows) / n, sum(y for _, y in rows) / n
        slope = sum((x - mx) * (y - my) for x, y in rows) / sum((x - mx) ** 2 for x, _ in rows)
        base = my - slope * mx
        over = sorted(y - (base + slope * x) for x, y in rows)
        p99 = over[min(n - 1, int(n * 0.99))]
        out.update(base_mb=round(base, 1), mb_per_mib_of_c=round(slope, 1), over_p99_mb=round(p99, 1),
                   over_max_mb=round(over[-1], 1), suggested_base_mb=math.ceil(base + max(0.0, p99)),
                   peak_mb=max(y for _, y in rows))
    peaks = [int(m) for m in TRANSLATION_PEAK.findall(text)]
    if peaks:
        out["translation_peak_mb"] = max(peaks)
    return out


def space_needed_mb(tuning: dict) -> int:
    """The free space a whole build needs: the extracted executable, the C, the objects and Zig's copies of them,
    the library and its copy in the game's folder, and the least the objects' cache keeps."""
    s = tuning["space"]
    return int(s["dump_mb"] + s["sources_mb"] + s["objects_mb"] + s["zig_copies_mb"] + 2 * s["library_mb"]
               + s["cache_floor_mb"])


if __name__ == "__main__":
    # python build_tuning.py fit LOG: a build's measured numbers (fit_log), beside this file's
    if len(sys.argv) != 3 or sys.argv[1] != "fit":
        raise SystemExit("usage: build_tuning.py fit LOG")
    with open(sys.argv[2], encoding="utf-8", errors="replace") as f:
        found = fit_log(f.read())
    t = load()
    c = t["compile"]
    found["now"] = {"compile.base_mb": c["base_mb"], "compile.mb_per_mib_of_c": c["mb_per_mib_of_c"],
                    "compile.job_mb": c["job_mb"], "translation.peak_mb": t["translation"]["peak_mb"]}
    print(json.dumps(found, indent=1))
