"""Running the compiles (recomp/jobs.py): how many at once by the memory available, and what happens to a compile the
system stops (no game data; the compiler is a small Python program that dies the way Android's low-memory killer
makes a compiler die).
"""
from __future__ import annotations

import os
import sys
import threading

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))

from recomp import jobs  # noqa: E402
from recomp import units  # noqa: E402
from recomp.emit import LOCALS_PREAMBLE  # noqa: E402

# the compiler: writes the object, or dies as a stopped compile does; what it does is read from the unit's C
FAKE_CC = r'''
import os, sys
src, obj = sys.argv[1], sys.argv[sys.argv.index("-o") + 1]
text = open(src, encoding="latin-1").read()
with open(os.environ["FAKE_CC_LOG"], "a") as f:
    f.write(os.path.basename(src) + "\n")
functions = text.count("#define RC_FLUSH() ")
if "DIES_ALWAYS" in text:
    sys.exit(137)
if "DIES_WITH_OTHERS" in text and os.environ.get("FAKE_CC_ALONE") != "1":
    sys.stderr.write("clang: error: clang frontend command failed\nclang terminated unexpectedly\n")
    sys.exit(1)
if "DIES_WHOLE" in text and functions > 1:
    sys.exit(137)
if "BAD_C" in text:
    sys.stderr.write(src + ":1:1: error: expected expression\n")
    sys.exit(1)
open(obj, "w").write("object of " + os.path.basename(src))
'''


def unit_c(entries: list[int], marks: dict[int, str] | None = None) -> str:
    """A unit's C as the emitter writes it (RC_FLUSH, then the function), a mark in a function's body."""
    marks = marks or {}
    functions = [f"#define RC_FLUSH() rc_flush(ctx)\nvoid f_{a:08x}(RCtx *ctx) {{\n    /* {marks.get(a, '')} */\n}}"
                 for a in entries]
    return units.text(functions, LOCALS_PREAMBLE)


class Rig:
    """Units written to a folder, compiled by the fake compiler through run_all, every compile logged."""

    def __init__(self, tmp_path, alone_flag=False):
        self.dir = tmp_path
        self.cc = tmp_path / "fake_cc.py"
        self.cc.write_text(FAKE_CC)
        self.log = tmp_path / "compiled.txt"
        self.log.write_text("")
        self.alone_flag = alone_flag
        self.lines: list[str] = []

    def task(self, name: str, text: str, est_mb: float = 100.0) -> jobs.Task:
        src = self.dir / f"{name}.c"
        src.write_text(text, encoding="latin-1")
        return jobs.Task(name, str(src), str(self.dir / f"{name}.o"), len(text), est_mb)

    def command(self, t: jobs.Task):
        env = dict(os.environ, FAKE_CC_LOG=str(self.log))
        if self.alone_flag and t.level > 0:
            env["FAKE_CC_ALONE"] = "1"
        return [sys.executable, str(self.cc), t.src, "-o", t.obj], env

    def split(self, t: jobs.Task) -> list[jobs.Task]:
        with open(t.src, encoding="latin-1") as f:
            pieces = units.parts(f.read(), LOCALS_PREAMBLE)
        if len(pieces) < 2:
            return []
        folder = self.dir / "parts" / t.name
        folder.mkdir(parents=True)
        out = []
        for name, text in pieces:
            path = folder / f"{name}.c"
            path.write_text(text, encoding="latin-1")
            out.append(jobs.Task(name, str(path), str(folder / f"{name}.o"), len(text), 50.0, parent=t.src))
        return out

    def run(self, tasks, ceiling=3):
        return jobs.run_all(tasks, self.command, ceiling, None, self.split, log=self.lines.append, poll_s=0.2)

    def compiled(self) -> list[str]:
        return self.log.read_text().split()


def test_a_unit_stopped_with_others_is_compiled_again_alone(tmp_path):
    # the break: a compile Android's low-memory killer stopped ("clang terminated unexpectedly") failing the build
    rig = Rig(tmp_path, alone_flag=True)
    tasks = [rig.task("fa", unit_c([0x100000], {0x100000: "DIES_WITH_OTHERS"}), 300),
             rig.task("fb", unit_c([0x110000]), 200), rig.task("fc", unit_c([0x120000]), 100)]
    report = rig.run(tasks)
    assert sorted(report.done) == ["fa", "fb", "fc"]
    assert rig.compiled().count("fa.c") == 2
    assert len(report.retries) == 1 and report.retries[0].startswith("fa: stopped (exit 1) with ")
    assert report.retries[0].endswith("others compiling; again alone")
    assert any(line.startswith("retry: fa ") and "compiled again alone" in line for line in rig.lines)
    assert all(os.path.exists(t.obj) for t in tasks) and not report.parts


def test_a_unit_stopped_alone_is_compiled_one_function_per_file(tmp_path):
    # the break: a unit too heavy for the phone even alone failing the build, or losing a function when cut
    rig = Rig(tmp_path)
    entries = [0x100000, 0x100400, 0x100800]
    big = rig.task("f00100000", unit_c(entries, {0x100400: "DIES_WHOLE"}), 500)
    report = rig.run([big, rig.task("f00110000", unit_c([0x110000]))])
    assert rig.compiled().count("f00100000.c") == 2                     # with the other, then alone
    parts = report.parts[big.obj]
    assert [p.name for p in parts] == [f"f_{a:08x}" for a in entries]
    assert all(os.path.exists(p.obj) for p in parts) and not os.path.exists(big.obj)
    assert set(report.done) == {"f00110000", *(p.name for p in parts)}
    assert report.retries[-1] == "f00100000: stopped alone (exit 137); in 3 parts"


def test_a_function_stopped_alone_fails_the_build_with_the_players_text(tmp_path):
    # the break: an endless retry, or a failure that names nothing the player can act on
    rig = Rig(tmp_path)
    with pytest.raises(jobs.CompileFailed) as e:
        rig.run([rig.task("f_00ce7920", unit_c([0xCE7920], {0xCE7920: "DIES_ALWAYS"}), 205),
                 rig.task("f00110000", unit_c([0x110000]))])
    assert e.value.memory
    assert "ran out of memory" in str(e.value) and "f_00ce7920" in str(e.value) and "close other apps" in str(e.value)
    assert rig.compiled().count("f_00ce7920.c") == 2


def test_an_error_in_the_c_is_not_retried(tmp_path):
    # the break: the compiler's own error taken for a stopped compile (retried, cut, the real error hidden)
    rig = Rig(tmp_path)
    with pytest.raises(jobs.CompileFailed) as e:
        rig.run([rig.task("fbad", unit_c([0x100000, 0x100400], {0x100000: "BAD_C"}))])
    assert not e.value.memory and "expected expression" in str(e.value)
    assert rig.compiled() == ["fbad.c"]


def test_stopped_is_a_signal_137_or_clangs_crash_text():
    assert jobs.stopped(-9, "") and jobs.stopped(137, "") and jobs.stopped(1, "x\nclang terminated unexpectedly\n")
    assert not jobs.stopped(1, "f.c:1:1: error: expected expression")


def meminfo(tmp_path, available_kb: int, name="meminfo") -> str:
    path = tmp_path / name
    path.write_text(f"MemTotal:        5600000 kB\nMemFree:          200000 kB\nMemAvailable:   {available_kb} kB\n"
                    "SwapTotal:       4000000 kB\nSwapFree:        3000000 kB\n")
    return str(path)


def test_jobs_follow_the_memory_available(tmp_path):
    # the break: more compiles than the memory holds (the phone's killer stops them), or none at all
    readings = iter([2100, 1300, 300, None, 2100, 5000])
    rule = jobs.MemoryRule(200, 400, 8, available=lambda: next(readings))
    assert rule.jobs() == 8                     # (2100 - 400) // 200 = 8, the ceiling
    assert rule.jobs() == 4                     # (1300 - 400) // 200
    assert rule.jobs() == 1                     # never fewer than one
    assert rule.jobs() == 8                     # unknown: the start's number
    assert rule.jobs(held_mb=600) == 8          # what the build's own compiles hold counts as its own
    assert rule.jobs() == 8                     # never more than at the start
    first = jobs.MemoryRule(200, 400, 8, available=lambda: 1100)
    assert first.jobs() == 3 and first.jobs(held_mb=1000) == 3
    assert first.slots(jobs.Task("big", "", "", 0, 450)) == 3 and first.slots(jobs.Task("s", "", "", 0, 90)) == 1
    assert jobs.available_mb(meminfo(tmp_path, 1048576)) == 1024
    assert jobs.read_meminfo(meminfo(tmp_path, 1048576))["SwapFree"] == 3000000


class Quick(threading.Thread):
    """A compile that takes `seconds` and makes its object (for the jobs' rule over a build, without processes)."""

    def __init__(self, task, done, seconds):
        super().__init__(daemon=True)
        self.task, self.done, self.seconds = task, done, seconds
        self.result = None

    def run(self):
        threading.Event().wait(self.seconds)
        self.result = jobs.Result(0, self.seconds, 80.0)
        self.done.put(self)

    def held_mb(self):
        return 80.0


def test_the_jobs_come_down_while_the_build_runs():
    # the break: the jobs read once at the start, while another app takes the phone's memory mid-build
    readings = [2400] * 3 + [700] * 1000
    rule = jobs.MemoryRule(200, 400, 6, available=lambda: readings.pop(0))
    tasks = [jobs.Task(f"u{i:02d}", "", "", 1000, 100) for i in range(16)]
    lines: list[str] = []
    report = jobs.run_all(tasks, lambda t: ([], None), 6, rule, log=lines.append, poll_s=0.03,
                          starter=lambda t, argv, env, q: Quick(t, q, 0.06))
    assert len(report.done) == 16
    # 6 at the start; then (700 MB available + 80 MB a compile holds - 400) / 200 MB a job: 3 at most
    assert report.jobs_seen[0][1] == 6 and 1 <= report.jobs_seen[-1][1] <= 3
    assert any(line.startswith("compile jobs: ") and " at " in line for line in lines)
    assert any(line.startswith("compile jobs: ") for line in jobs.summary(report))
