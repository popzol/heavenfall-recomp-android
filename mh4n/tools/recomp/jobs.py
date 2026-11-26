"""Running the compiles: as many at once as the memory holds, a compile the system stops retried, each one measured.

* **Jobs by memory** (`MemoryRule`, when the build asks for it; recomp/build.py): at the start, as many jobs as the
  memory available (`MemAvailable`, /proc/meminfo) holds once a reserve is left, at `job_mb` each (the units are cut to
  fit it: recomp/units.py), never fewer than one nor more than the ceiling (the cores). While the build runs the
  memory is read again every `poll_s`: what the build may use is what is available plus what its own compiles hold
  now, and the jobs come down when that does (and back up to the start's number when it comes back); a unit expected
  to need more than a job's memory takes as many jobs' room as it needs. Without it, `ceiling` jobs (upstream's).
* **A stopped compile** (killed by a signal, exit 137, or Zig's "clang terminated unexpectedly": what Android's
  low-memory killer does to a compiler) is not a failed build: the unit is compiled again alone (nothing else
  compiling); stopped again, it is compiled lighter, one function per file, each alone (the same code for every
  function: only the calls between that unit's functions are no longer inlined); a function stopped alone, or a unit
  of one function stopped alone, fails the build with what the player can do. Any other failure is the compiler's
  error, as before.
* **Measured**: each compile's seconds and peak memory (the high-water mark of its processes: `wait4`'s on Linux and
  Android, the job object's processes' peak working set on Windows), every retry and every change of the jobs, in the
  build's output, and the totals at the end.
"""
from __future__ import annotations

import math
import os
import queue
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field

STOPPED_TEXTS = ("clang terminated unexpectedly", "LLVM ERROR: out of memory", "std::bad_alloc")


@dataclass
class Task:
    name: str               # the unit (its file's name without .c)
    src: str
    obj: str
    c_bytes: int
    est_mb: float           # its expected peak memory
    level: int = 0          # 0 with the others, 1 alone, 2 a part of a unit compiled one function per file
    tries: int = 0
    parent: str = ""        # a part's unit (its source)


@dataclass
class Result:
    rc: int
    seconds: float
    peak_mb: float | None
    stderr: str = ""

    @property
    def stopped(self) -> bool:
        return stopped(self.rc, self.stderr)


def stopped(rc: int, stderr: str) -> bool:
    """A compile the system stopped (out of memory, most likely), not one that failed on its C."""
    return rc < 0 or rc == 128 + 9 or any(t in stderr for t in STOPPED_TEXTS)


# ---- the memory available

def read_meminfo(path: str = "/proc/meminfo") -> dict[str, int]:
    """/proc/meminfo's fields in kB."""
    out = {}
    with open(path, encoding="ascii", errors="replace") as f:
        for line in f:
            name, _, rest = line.partition(":")
            words = rest.split()
            if words and words[0].isdigit():
                out[name.strip()] = int(words[0])
    return out


def available_mb(path: str = "/proc/meminfo") -> float | None:
    """MemAvailable in MB, or None where it can't be read (Windows: GlobalMemoryStatusEx's available memory)."""
    if sys.platform == "win32" and path == "/proc/meminfo":
        return _windows_available_mb()
    try:
        info = read_meminfo(path)
    except OSError:
        return None
    return info["MemAvailable"] / 1024 if "MemAvailable" in info else None


class MemoryRule:
    """How many jobs: (available + what the build's compiles hold now - reserve) / job_mb, between 1 and the ceiling;
    while the build runs, never more than at the start."""

    def __init__(self, job_mb: float, reserve_mb: float, ceiling: int, available=available_mb):
        self.job_mb, self.reserve_mb, self.ceiling = job_mb, reserve_mb, max(1, ceiling)
        self.available = available
        self.start: int | None = None
        self.last_mb: float | None = None

    def jobs(self, held_mb: float = 0.0) -> int:
        mb = self.available()
        self.last_mb = mb
        if mb is None:
            return self.start or self.ceiling
        n = max(1, min(self.ceiling, int((mb + held_mb - self.reserve_mb) // self.job_mb)))
        if self.start is None:
            self.start = n
        return min(n, self.start)

    def slots(self, task: Task) -> int:
        """The jobs' room a unit takes: one, or more for a unit expected to need more than a job's memory."""
        return max(1, math.ceil(task.est_mb / self.job_mb))


# ---- one compile, measured

class Compile(threading.Thread):
    """One compile in its own thread: `result` once done; `held_mb()` what its processes hold now."""

    def __init__(self, task: Task, cmd: list[str], env: dict | None, done: queue.Queue):
        super().__init__(daemon=True)
        self.task, self.cmd, self.env, self.done = task, cmd, env, done
        self.result: Result | None = None
        self.pid = 0
        self._win = None

    def run(self):
        t0 = time.monotonic()
        try:
            if sys.platform == "win32":
                rc, peak, err = self._run_windows()
            elif hasattr(os, "wait4"):
                rc, peak, err = self._run_posix()
            else:
                r = subprocess.run(self.cmd, env=self.env, capture_output=True, text=True)
                rc, peak, err = r.returncode, None, r.stderr
        except OSError as e:
            rc, peak, err = 127, None, f"can't run {self.cmd[0]}: {e}"
        self.result = Result(rc, time.monotonic() - t0, peak, err)
        self.done.put(self)

    def _run_posix(self):
        p = subprocess.Popen(self.cmd, env=self.env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.pid = p.pid
        err = p.stderr.read().decode("utf-8", "replace")
        p.stderr.close()
        _, status, usage = os.wait4(p.pid, 0)
        p.returncode = os.waitstatus_to_exitcode(status)
        # ru_maxrss: the largest of the process and its children (Zig's driver and its clang), kB on Linux
        peak = usage.ru_maxrss / (1 << 20) if sys.platform == "darwin" else usage.ru_maxrss / 1024
        return p.returncode, peak, err

    def _run_windows(self):
        self._win = _WindowsJob()
        return self._win.run(self.cmd, self.env)

    def held_mb(self) -> float:
        if self._win is not None:
            return self._win.held_mb()
        if self.pid and sys.platform.startswith("linux"):
            return _linux_tree_mb(self.pid)
        return 0.0


def _linux_tree_mb(root: int) -> float:
    """The resident memory of a process and its descendants now (from /proc: the build sees its own processes)."""
    children, rss = {}, {}
    page = os.sysconf("SC_PAGE_SIZE")
    try:
        names = os.listdir("/proc")
    except OSError:
        return 0.0
    for d in names:
        if not d.isdigit():
            continue
        try:
            with open(f"/proc/{d}/stat", encoding="ascii", errors="replace") as f:
                fields = f.read().rsplit(")", 1)[1].split()
        except (OSError, IndexError):
            continue
        pid = int(d)
        children.setdefault(int(fields[1]), []).append(pid)
        rss[pid] = int(fields[21]) * page
    total, todo = 0, [root]
    while todo:
        pid = todo.pop()
        total += rss.get(pid, 0)
        todo += children.get(pid, [])
    return total / (1 << 20)


# ---- Windows: the job object's processes (a compile's driver starts the compiler as a process of its own)

if sys.platform == "win32":
    import ctypes
    import ctypes.wintypes as wt

    _k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _psapi = ctypes.WinDLL("psapi", use_last_error=True)
    _ntdll = ctypes.WinDLL("ntdll")
    _k32.CreateJobObjectW.restype = wt.HANDLE
    _k32.OpenProcess.restype = wt.HANDLE
    _k32.AssignProcessToJobObject.argtypes = [wt.HANDLE, wt.HANDLE]
    _k32.QueryInformationJobObject.argtypes = [wt.HANDLE, ctypes.c_int, ctypes.c_void_p, wt.DWORD, ctypes.c_void_p]
    _k32.CloseHandle.argtypes = [wt.HANDLE]
    _ntdll.NtResumeProcess.argtypes = [wt.HANDLE]

    class _Counters(ctypes.Structure):
        _fields_ = [("cb", wt.DWORD), ("PageFaultCount", wt.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                    ("PeakPagefileUsage", ctypes.c_size_t)]

    class _Pids(ctypes.Structure):
        _fields_ = [("assigned", wt.DWORD), ("listed", wt.DWORD), ("pids", ctypes.c_size_t * 64)]

    class _MemoryStatus(ctypes.Structure):
        _fields_ = [("dwLength", wt.DWORD), ("dwMemoryLoad", wt.DWORD), ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong), ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong), ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong), ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

    _psapi.GetProcessMemoryInfo.argtypes = [wt.HANDLE, ctypes.POINTER(_Counters), wt.DWORD]

    def _counters(handle) -> _Counters | None:
        c = _Counters()
        c.cb = ctypes.sizeof(c)
        return c if _psapi.GetProcessMemoryInfo(handle, ctypes.byref(c), c.cb) else None

    def _windows_available_mb() -> float | None:
        s = _MemoryStatus()
        s.dwLength = ctypes.sizeof(s)
        return s.ullAvailPhys / (1 << 20) if _k32.GlobalMemoryStatusEx(ctypes.byref(s)) else None

    class _WindowsJob:
        QUERY = 0x1000 | 0x0400 | 0x0010 | 0x00100000

        def __init__(self):
            self.handles: dict[int, int] = {}
            self.lock = threading.Lock()

        def run(self, cmd, env):
            job = _k32.CreateJobObjectW(None, None)
            p = subprocess.Popen(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                 creationflags=0x4)  # (suspended until it is in the job: its children are too)
            main = _k32.OpenProcess(0x1F0FFF, False, p.pid)
            _k32.AssignProcessToJobObject(job, main)
            with self.lock:
                self.handles[p.pid] = main
            _ntdll.NtResumeProcess(main)
            err = []
            reader = threading.Thread(target=lambda: err.append(p.stderr.read()), daemon=True)
            reader.start()
            pids = _Pids()
            while p.poll() is None:
                if _k32.QueryInformationJobObject(job, 3, ctypes.byref(pids), ctypes.sizeof(pids), None):
                    for i in range(min(pids.listed, 64)):
                        pid = pids.pids[i]
                        if pid not in self.handles:
                            h = _k32.OpenProcess(self.QUERY, False, pid)
                            if h:
                                with self.lock:
                                    self.handles[pid] = h
                time.sleep(0.02)
            reader.join()
            with self.lock:
                peaks = [c.PeakWorkingSetSize for c in map(_counters, self.handles.values()) if c]
                for h in self.handles.values():
                    _k32.CloseHandle(h)
                self.handles = {}
            _k32.CloseHandle(job)
            text = b"".join(e for e in err if e).decode("utf-8", "replace")
            return p.returncode, (max(peaks) / (1 << 20) if peaks else None), text

        def held_mb(self) -> float:
            with self.lock:
                return sum(c.WorkingSetSize for c in map(_counters, self.handles.values()) if c) / (1 << 20)
else:
    def _windows_available_mb() -> float | None:
        return None


def own_peak_mb() -> float | None:
    """This process's peak resident memory, its children's not counted (Linux and Android: getrusage's ru_maxrss;
    Windows: its peak working set)."""
    if sys.platform == "win32":
        _k32.GetCurrentProcess.restype = wt.HANDLE
        c = _counters(_k32.GetCurrentProcess())
        return c.PeakWorkingSetSize / (1 << 20) if c else None
    try:
        import resource
    except ImportError:
        return None
    m = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return m / (1 << 20) if sys.platform == "darwin" else m / 1024


# ---- the compiles

class CompileFailed(Exception):
    """The compiler failed on a unit's C (errors), or the system stopped a unit that has nothing lighter left
    (`memory`): the build stops with this text."""

    def __init__(self, text: str, memory: bool = False):
        super().__init__(text)
        self.memory = memory


@dataclass
class Report:
    done: dict[str, Result] = field(default_factory=dict)          # (every compile that made its object)
    parts: dict[str, list[Task]] = field(default_factory=dict)     # a unit's object -> its parts (compiled lighter)
    retries: list[str] = field(default_factory=list)
    jobs_seen: list[tuple[float, int, float | None]] = field(default_factory=list)  # (seconds, jobs, available MB)
    errors: list[tuple[str, str]] = field(default_factory=list)
    seconds: float = 0.0


def run_all(tasks: list[Task], command, ceiling: int, rule: MemoryRule | None = None, split=None,
            log=print, poll_s: float = 2.0, starter=None, on_done=None) -> Report:
    """Compile every task, biggest first. `command(task)` -> (argv, env); `split(task)` -> its parts (Tasks, one
    function each) or [] for a unit of one function; `on_done(task, result)` after each compile; `starter(task, argv,
    env, queue)` -> a Compile not yet started (tests give their own). Raises CompileFailed once the compiles still
    running end: the first five errors, or the unit the system stopped with nothing lighter left."""
    starter = starter or (lambda t, argv, env, q: Compile(t, argv, env, q))
    report = Report()
    t0 = time.monotonic()
    pending = sorted(tasks, key=lambda t: (-t.est_mb, t.name))
    running: dict[str, Compile] = {}
    done_q: queue.Queue = queue.Queue()
    failure: CompileFailed | None = None
    jobs = rule.jobs() if rule else max(1, ceiling)
    report.jobs_seen.append((0.0, jobs, rule.last_mb if rule else None))
    if rule:
        log(f"compile jobs: {jobs} (memory available {_mb(rule.last_mb)}, {rule.reserve_mb:.0f} MB left to the "
            f"system, up to {rule.job_mb:.0f} MB a job, at most {rule.ceiling})")
    last_poll = time.monotonic()

    def slots(t: Task) -> int:
        return rule.slots(t) if rule else 1

    while pending or running:
        # what may start: one alone needs nothing else running (and nothing starts beside it)
        while pending and failure is None:
            t = pending[0]
            if running and (t.level > 0 or any(c.task.level > 0 for c in running.values())):
                break
            used = sum(slots(c.task) for c in running.values())
            if running and used + slots(t) > jobs:
                break
            pending.pop(0)
            t.tries += 1
            argv, env = command(t)
            c = starter(t, argv, env, done_q)
            running[t.name] = c
            c.start()
        if not running:
            break
        try:
            finished = done_q.get(timeout=max(0.05, poll_s - (time.monotonic() - last_poll)))
        except queue.Empty:
            finished = None
        if rule and time.monotonic() - last_poll >= poll_s:
            last_poll = time.monotonic()
            held = sum(c.held_mb() for c in running.values())
            now = rule.jobs(held)
            if now != jobs:
                at = time.monotonic() - t0
                log(f"compile jobs: {now} at {at:.0f} s (memory available {_mb(rule.last_mb)}, "
                    f"the compiles hold {held:.0f} MB)")
                report.jobs_seen.append((at, now, rule.last_mb))
                jobs = now
        if finished is None:
            continue
        running.pop(finished.task.name)
        t, r = finished.task, finished.result
        if on_done:
            on_done(t, r)
        if r.rc == 0:
            report.done[t.name] = r
            log(f"  compiled {t.name} ({t.c_bytes >> 10} KiB of C): {r.seconds:.1f} s, {_mb(r.peak_mb)}"
                + (f", with {len(running)} others" if running else ", alone" if t.level else ""))
            continue
        if not r.stopped:
            report.errors.append((t.src, r.stderr))
            failure = failure or CompileFailed("")
            continue
        what = f"{t.name} ({t.c_bytes >> 10} KiB of C, expected {t.est_mb:.0f} MB)"
        how = f"exit {r.rc}" if r.rc >= 0 else f"signal {-r.rc}"
        if t.level == 0:
            t.level = 1
            report.retries.append(f"{t.name}: stopped ({how}) with {len(running)} others compiling; again alone")
            log(f"retry: {what} was stopped by the system ({how}, peak {_mb(r.peak_mb)}) with {len(running)} other "
                "compiles: compiled again alone")
            pending.insert(0, t)
            continue
        pieces = split(t) if split and t.level == 1 else []
        if pieces:
            for p in pieces:
                p.level = 2
            report.parts[t.obj] = pieces
            report.retries.append(f"{t.name}: stopped alone ({how}); in {len(pieces)} parts")
            log(f"retry: {what} was stopped again alone ({how}): compiled lighter, one function per file "
                f"({len(pieces)} files, each alone; the calls between them are not inlined)")
            pending[0:0] = sorted(pieces, key=lambda p: (-p.est_mb, p.name))
            continue
        report.retries.append(f"{t.name}: stopped alone ({how}); nothing lighter")
        log(f"retry: {what} was stopped alone ({how}) and is one function: nothing lighter to compile")
        failure = failure or CompileFailed(
            f"the phone ran out of memory compiling part of the game's code ({t.name}, about {t.est_mb:.0f} MB, "
            f"stopped by the system {t.tries} times, the last with nothing else compiling): close other apps or "
            "restart the phone, then build again (it goes on from where it stopped)", memory=True)
    report.seconds = time.monotonic() - t0
    if failure and failure.memory:
        raise failure
    if report.errors:
        raise CompileFailed("\n".join(f"error in {src}:\n{err[-2000:]}" for src, err in report.errors[:5]))
    return report


def _mb(v: float | None) -> str:
    return "? MB" if v is None else f"{v:.0f} MB"


def summary(report: Report) -> list[str]:
    """The totals: seconds and peak memory per compile, the jobs, the retries."""
    rows = list(report.done.values())
    if not rows:
        return ["compiled nothing (every object from the cache or already made)"]
    secs = sorted(r.seconds for r in rows)
    peaks = sorted(r.peak_mb for r in rows if r.peak_mb is not None)

    def pct(xs, p):
        return xs[min(len(xs) - 1, int(len(xs) * p))]
    lines = [f"compiles: {len(rows)} in {report.seconds:.0f} s; seconds each p50 {pct(secs, .5):.1f}, p90 "
             f"{pct(secs, .9):.1f}, max {secs[-1]:.1f}, sum {sum(secs):.0f}"]
    if peaks:
        top = max(report.done.items(), key=lambda kv: kv[1].peak_mb or 0)
        lines.append(f"compile memory (each compile's peak): p50 {pct(peaks, .5):.0f} MB, p90 {pct(peaks, .9):.0f} MB, "
                     f"max {peaks[-1]:.0f} MB ({top[0]})")
    lines.append("compile jobs: " + ", ".join(f"{n} at {at:.0f} s" + (f" ({mb:.0f} MB available)" if mb else "")
                                              for at, n, mb in report.jobs_seen))
    lines.append(f"retries: {len(report.retries)}" + (": " + "; ".join(report.retries) if report.retries else ""))
    return lines
