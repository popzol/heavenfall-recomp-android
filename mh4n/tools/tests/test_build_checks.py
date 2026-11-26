"""The checks before a build on a phone (android/phone_build.py, config/build_tuning.json): the space it needs and the
translation's memory, each failing by name before the long part starts (no game data).
"""
from __future__ import annotations

import json
import os
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, os.path.join(ROOT, "tools", "android"))

import build_tuning  # noqa: E402
import phone_build  # noqa: E402


def meminfo(tmp_path, total_kb: int, available_kb: int, swap_kb: int = 0) -> str:
    path = tmp_path / "meminfo"
    path.write_text(f"MemTotal: {total_kb} kB\nMemAvailable: {available_kb} kB\nSwapTotal: {swap_kb} kB\n"
                    f"SwapFree: {swap_kb} kB\n")
    return str(path)


def test_the_translations_memory_is_checked_against_the_device(tmp_path):
    # the break: a phone that can never hold the translation starting it anyway (and dying minutes later)
    tuning = build_tuning.load()
    peak = tuning["translation"]["peak_mb"]
    never, line = phone_build.memory_check(tuning, meminfo(tmp_path, (peak // 2) << 10, (peak // 4) << 10))
    assert never and f"{peak} MB" in never
    # enough in all but not free now: it goes on, saying so
    never, line = phone_build.memory_check(tuning, meminfo(tmp_path, (peak * 2) << 10, (peak // 2) << 10))
    assert never is None and "stopped to make room" in line
    never, line = phone_build.memory_check(tuning, meminfo(tmp_path, (peak * 3) << 10, (peak * 2) << 10))
    assert never is None and "stopped" not in line
    assert phone_build.memory_check(tuning, str(tmp_path / "none"))[0] is None


def test_the_space_counts_the_work_folder_and_the_caches_oldest(tmp_path):
    # the break: a run of a build already half done refused for the space its own files take
    tuning = build_tuning.load()
    work = tmp_path / "work"
    work.mkdir()
    lack, why = phone_build.space_needed(str(work), str(tmp_path / "object-cache"), tuning)
    assert lack == 0 and f"needs {build_tuning.space_needed_mb(tuning)} MB" in why
    # a disk with less free than a build needs: what is missing, by name
    small = json.loads(json.dumps(tuning))
    small["space"]["sources_mb"] = 1 << 30
    lack, why = phone_build.space_needed(str(work), str(tmp_path / "object-cache"), small)
    assert lack > 0 and "MB free" in why


def test_a_builds_log_gives_the_numbers_to_copy_back():
    # the break: the phone's measured build read wrong (the units then cut to a memory the phone doesn't have)
    lines = [f"  compiled f{i:08x} ({kib} KiB of C): 3.5 s, {round(60 + 180 * kib / 1024) + (9 if i == 3 else 0)} MB"
             + (", with 2 others" if i % 2 else "") for i, kib in enumerate([64, 128, 256, 384, 512, 640])]
    log = "\n".join(["compile jobs: 3", *lines, "  compiled f_00ce7920 (271 KiB of C): 9.0 s, ? MB",
                     "translation: peak memory 1488 MB (translation.peak_mb 1600)", "compiles: 6 in 20 s"])
    found = build_tuning.fit_log(log)
    assert found["compiles"] == 6 and found["translation_peak_mb"] == 1488
    assert 55 <= found["base_mb"] <= 70 and 170 <= found["mb_per_mib_of_c"] <= 190
    assert 0 < found["over_max_mb"] <= 9 and found["over_p99_mb"] == found["over_max_mb"]   # (six: p99 is the worst)
    assert found["suggested_base_mb"] >= found["base_mb"] + found["over_p99_mb"]


def test_the_tuning_file_is_checked(tmp_path):
    # the break: a damaged or partial file of numbers making units of no size, or zero jobs
    bad = json.loads(open(build_tuning.PATH, encoding="utf-8").read())
    bad["compile"]["job_mb"] = bad["compile"]["base_mb"]
    path = tmp_path / "tuning.json"
    path.write_text(json.dumps(bad))
    with pytest.raises(build_tuning.TuningError):
        build_tuning.load(str(path))
    del bad["compile"]
    path.write_text(json.dumps(bad))
    with pytest.raises(build_tuning.TuningError):
        build_tuning.load(str(path))
    t = build_tuning.load()
    assert 0 < build_tuning.unit_bytes(t) and build_tuning.compile_mb(t, build_tuning.unit_bytes(t)) <= \
        t["compile"]["job_mb"] + 1
