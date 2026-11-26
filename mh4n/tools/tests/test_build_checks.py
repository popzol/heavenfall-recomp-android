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
