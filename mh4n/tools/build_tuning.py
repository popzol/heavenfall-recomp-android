"""config/build_tuning.json: the numbers the code library's build goes by (each with its reason in the file's "why"):
the memory a compile job may take and what a unit's C costs (recomp/units.py cuts the C to fit it, recomp/jobs.py
runs as many jobs as the free memory holds), the translation's peak memory, the space a build needs, and the
objects' cache's bounds. Measured numbers, never written into the code."""
from __future__ import annotations

import json
import os

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


def space_needed_mb(tuning: dict) -> int:
    """The free space a whole build needs: the extracted executable, the C, the objects and Zig's copies of them,
    the library and its copy in the game's folder, and the least the objects' cache keeps."""
    s = tuning["space"]
    return int(s["dump_mb"] + s["sources_mb"] + s["objects_mb"] + s["zig_copies_mb"] + 2 * s["library_mb"]
               + s["cache_floor_mb"])
