#!/usr/bin/env python3
"""Builds the game's code library ON THE PHONE, for the native Android app's builder (Settings, Game folder, "Build on
this phone"): the same tools/build_game.py the PC scripts beside this file run, with the same arguments
(`--native --android`, the base game's shaders with --romfs, the code mod staged as the PC scripts stage it), so that
whatever the pipeline accepts and checks on a PC it accepts and checks here too.

    python3 phone_build.py --update UPDATE.cia [--game GAME.cci] [--game-mods FOLDER | --user-mods USER | --mod FOLDER]
                           --work DIR --dest FOLDER --cc COMPILER [--jobs N] [--expect-id ID] [--clean]
                           [--cache DIR] [--tuning FILE]

The code mods (they have to be the ones the game will run with): --game-mods, the game folder's mods/ as the app copies
it to the phone's user folder (the first build: nothing is copied yet); --user-mods, the app's user folder, whose
load/mods the game runs (every mod that is on, in load_order.txt's order); --mod, one mod's folder. Tools that know
build_game.py's --game-mods take the first two as they are (every code mod, a mod's exheader.bin...); older ones
build in the first code mod found, as the PC scripts did.

The app runs it with the CPython for Android it downloaded (python.org) and Zig's C compiler (PyPI's ziglang 0.13.0,
the one build_game.py fetches on Linux), through its two small launchers (app_native/builder/toolrun.c): --cc is the
launcher libzigcc.so in the app's native library folder.

Besides build_game.py's own output it prints lines for the app, each starting with "@@":
    @@id ID                     the code library id these tools give (the app compares it with its own first)
    @@stage NAME                extract, version, mods, recompile, shaders, compile, link, install
    @@progress NAME DONE TOTAL  how far a stage is
    @@error NAME MESSAGE        the stage that failed and why (the last line of a failed run)
    @@done PATH                 the library is in place, with its .id beside it

Memory and space (config/build_tuning.json, tools/build_tuning.py): before anything, the space a build needs is checked
(the objects' cache gives up its oldest objects when that makes room); before the translation, its memory; while
compiling, as many compiles run as the memory available holds (--jobs the most), and one the system stops for its
memory is compiled again alone, then one function per file (tools/recomp/jobs.py).

Resuming: the work folder stays between runs. The recompiled C is written only where it changed and every object goes
into the objects' cache (--cache, beside the work folder: --clean leaves it) as it is made, keyed by its C, flags and
compiler (tools/recomp/objcache.py), so a run cut short (the app closed, the phone too hot, no battery) goes on from
the objects already made, and a new version of a mod compiles only the C it changed; an object the cut left half
written is found here and made again.
The library reaches --dest only whole (written beside it, then renamed), with its .id.
The stages before the compile (the extraction, the code mods, the analysis and the translation to C with its function
list, the geometry shaders: minutes of every core on a phone) are done again only when what they are made from changed:
once they are through, the work folder's inputs.txt keeps a hash of their inputs (the update and the game, the code
mods, these tools and the Python that runs them) and a list of the C they wrote; a run whose inputs give the same hash,
with that C still there, goes straight to compiling what is missing (build_game.py --compile-only). The C is the same as
a run from an empty work folder writes, and so is every object, save one: Zig 0.13's clang compiles one very large
function of the game code (f_00ce7920 with the single-screen mod) into one of a few objects from one run to the next,
cut or not (the app's launcher, toolrun.c, makes that less likely).

Everything this writes is made from the player's own game and stays on the phone.
"""
from __future__ import annotations

import argparse
import contextlib
import errno
import hashlib
import io
import os
import re
import shutil
import signal
import struct
import subprocess
import sys
import threading
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
sys.path.insert(0, TOOLS)

import build_game  # noqa: E402

import build_tuning  # noqa: E402
from recomp import jobs as compile_jobs  # noqa: E402
from recomp import objcache  # noqa: E402

TITLE = "0004000000126300"
LIBRARY = "libmh4u_code_native.so"
CACHE = "object-cache"   # the objects' cache, beside the work folder (which --clean deletes)


def emit(line: str) -> None:
    sys.__stdout__.write(line + "\n")
    sys.__stdout__.flush()


# ---- the stages before the compile, kept between runs (the module's docstring; the MHGU port's phone_build.py the same)
STAMP = "inputs.txt"
WHOLE = 128 << 20      # a file up to this size is hashed whole (the update: 48 MB, under a second on a phone)...
END = 1 << 20          # ...a larger one (the game's .3ds: GBs, a minute to read) by its size, its date and its two ends
SMALL = 64 << 10       # in a mods folder: the files the code mods are made of, and small ones (an order, a switch),
# whole; the rest (romfs) by their sizes (a mod's packs too: its recomp.json holds their SHA-1, code_mods.MANIFEST)
CODE_NAMES = {"code.bin", "code.ips", "code.bps", "exheader.bin", "recomp.json"}
# the tools as the app packs them (its scripts/pack_builder_tools.py: tools/ and config/ without the PC's research)
TOOL_TOPS = ("tools", "config")
TOOL_SKIP = {"__pycache__", "ghidra", "native", "harness", "tests"}


def file_digest(path: str) -> str:
    st = os.stat(path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        if st.st_size <= WHOLE:
            for block in iter(lambda: f.read(1 << 20), b""):
                h.update(block)
            return f"{st.st_size} {h.hexdigest()}"
        h.update(f.read(END))
        f.seek(-END, os.SEEK_END)
        h.update(f.read(END))
    return f"{st.st_size} {st.st_mtime_ns} {h.hexdigest()}"


def folder_digest(top: str | None, whole=lambda name, size: True, skip_dirs=(), skip_ext=()) -> str:
    """Every folder and file under top (relative names, in order): a file's bytes when whole(name, size), else its size."""
    if not top or not os.path.isdir(top):
        return "none"
    h = hashlib.sha256()
    for root, dirs, names in os.walk(top):
        dirs[:] = sorted(d for d in dirs if d not in skip_dirs)
        rel = os.path.relpath(root, top).replace(os.sep, "/")
        h.update(f"d {rel}\n".encode("utf-8", "surrogateescape"))
        for n in sorted(names):
            if skip_ext and n.endswith(skip_ext):
                continue
            path = os.path.join(root, n)
            size = os.path.getsize(path)
            h.update(f"f {rel}/{n} {file_digest(path) if whole(n, size) else size}\n".encode("utf-8", "surrogateescape"))
    return h.hexdigest()


def mods_digest(top: str | None) -> str:
    return folder_digest(top, lambda name, size: name.lower() in CODE_NAMES or size <= SMALL)


def tools_digest() -> str:
    h = hashlib.sha256()
    for top in TOOL_TOPS:
        d = folder_digest(os.path.join(build_game.ROOT, top), skip_dirs=TOOL_SKIP, skip_ext=(".pyc", ".exe", ".dll", ".so"))
        h.update(f"{top} {d}\n".encode())
    return h.hexdigest()


def any_digest(path: str | None) -> str:
    return file_digest(path) if path and os.path.isfile(path) else "none"


def stamp_hash(lines: list[str]) -> str:
    return hashlib.sha256("\n".join(lines).encode("utf-8", "surrogateescape")).hexdigest()


def written(out: str) -> str:
    """What the stages before the compile left for it: the C (out/recomp/src, names and sizes) and the dump's header,
    which names the library."""
    h = hashlib.sha256()
    src = os.path.join(out, "recomp", "src")
    names = sorted(os.listdir(src)) if os.path.isdir(src) else []
    if "funcs.h" not in names or "table.c" not in names:
        return "none"
    for n in names:
        h.update(f"{n} {os.path.getsize(os.path.join(src, n))}\n".encode("utf-8", "surrogateescape"))
    exheader = os.path.join(out, "dump", "exheader.bin")
    h.update(file_digest(exheader).encode() if os.path.isfile(exheader) else b"no exheader")
    return h.hexdigest()


def read_stamp(work: str) -> tuple[str, str]:
    try:
        with open(os.path.join(work, STAMP), encoding="utf-8", errors="replace") as f:
            first = f.readline().split()
        return (first[0], first[1]) if len(first) >= 2 else ("", "")
    except OSError:
        return "", ""


def write_stamp(work: str, inputs: str, made: str, lines: list[str]) -> None:
    path = os.path.join(work, STAMP)
    with open(path + ".part", "w", encoding="utf-8", errors="replace") as f:
        f.write(f"{inputs} {made}\n" + "\n".join(lines) + "\n")
    os.replace(path + ".part", path)


def drop_stamp(work: str) -> None:
    try:
        os.remove(os.path.join(work, STAMP))
    except OSError:
        pass


# The translation's steps (recompile(), then the geometry shaders) and the share of its time each takes, between what a
# PC (98 s: 35 the first analysis, 2 where its code is, 3 the data pointers, 12 the second analysis, 46 the writing of
# the C) and a Moto g06 of 4 GB, which swaps, measured (571 s: the code's place and the data pointers 149 of them): the
# progress of the stage "recompile" goes through them (Stages.translated, translation_progress)
TRANSLATION = (("first", 0.32), ("cover", 0.06), ("pointers", 0.06), ("second", 0.12), ("writing", 0.42),
               ("shaders", 0.02))


class Stages(io.TextIOBase):
    """build_game.py's output, passed on as it is, with the app's @@ lines for the stages it goes through."""

    PATTERNS = [
        (re.compile(r"^extracting "), "extract"),
        (re.compile(r"^version: "), "version"),
        (re.compile(r"^code mods: "), "mods"),
        (re.compile(r"^recompiling"), "recompile"),
        (re.compile(r"^geometry shaders"), "shaders"),
        (re.compile(r"^compiling with "), "compile"),
        (re.compile(r"^linked "), "link"),
    ]
    ANALYSED = re.compile(r"analysed (\d+)/(\d+)")

    def __init__(self):
        super().__init__()
        self.stage = "extract"
        self.buffer_ = ""
        self.on_stage = None
        self.steps = {}  # (the translation's steps, TRANSLATION: how far each is, 0 to 1)
        self.functions = 0
        self.said = None

    def writable(self):
        return True

    def write(self, s):
        sys.__stdout__.write(s)
        self.buffer_ += s
        while "\n" in self.buffer_:
            line, self.buffer_ = self.buffer_.split("\n", 1)
            self.line(line.strip())
        return len(s)

    def flush(self):
        sys.__stdout__.flush()

    def line(self, text):
        for pattern, stage in self.PATTERNS:
            if pattern.search(text) and stage != self.stage:
                self.set(stage)
        m = self.ANALYSED.search(text)
        if m:  # (recompile()'s own lines, of the second analysis: translation_progress says more, and sooner)
            self.translated("second", int(m.group(1)) / max(1, int(m.group(2))), int(m.group(2)))

    def set(self, stage):
        self.stage = stage
        emit(f"@@stage {stage}")
        if self.on_stage:
            self.on_stage(stage)

    def translated(self, step: str, done: float, functions: int = 0) -> None:
        """A step of the translation is this far (0 to 1, never back; the steps before it are through): @@progress
        recompile, in functions (the app shows "N / M functions"), the share of the translation done (TRANSLATION)."""
        names = [name for name, _ in TRANSLATION]
        if step not in names:
            return
        for name in names[:names.index(step)]:
            self.steps[name] = 1.0
        self.steps[step] = max(self.steps.get(step, 0.0), min(1.0, done))
        self.functions = max(self.functions, functions)
        part = sum(share * self.steps.get(name, 0.0) for name, share in TRANSLATION)
        total = self.functions or 1
        now = (min(total, round(part * total)), total)
        if now != self.said:
            self.said = now
            emit(f"@@progress recompile {now[0]} {now[1]}")


class Tail(io.TextIOBase):
    """stderr passed on as it is, its last 64 KB kept (a compiler's error, read after a failed compile)."""

    def __init__(self):
        super().__init__()
        self.text = ""

    def writable(self):
        return True

    def write(self, s):
        sys.__stderr__.write(s)
        self.text = (self.text + s)[-(64 << 10):]
        return len(s)

    def flush(self):
        sys.__stderr__.flush()


class Failures:
    """The commands the build ran through subprocess.run that ended badly (their words, their exit, their stderr's
    tail), the last few: a step that stops the build with no message of its own (recomp/build.py raises SystemExit with
    its command's stderr, empty when a signal killed it) still leaves what is known of it here, for the log."""

    KEEP = 8

    def __init__(self):
        self.items: list[tuple[str, int, str]] = []

    def add(self, argv, rc: int, stderr) -> None:
        words = " ".join(str(a) for a in argv) if isinstance(argv, (list, tuple)) else str(argv)
        self.items = (self.items + [(words[:300], rc, (stderr or "")[-1500:])])[-self.KEEP:]

    @contextlib.contextmanager
    def watching(self):
        real = subprocess.run

        def run(*popenargs, **kwargs):
            r = real(*popenargs, **kwargs)
            if r.returncode:
                self.add(popenargs[0] if popenargs else kwargs.get("args", ""), r.returncode, r.stderr)
            return r
        subprocess.run = run
        try:
            yield
        finally:
            subprocess.run = real


def exit_words(rc: int) -> str:
    """A return code as it reads in the log: a negative one is the signal that killed the process."""
    if rc < 0:
        try:
            name = signal.Signals(-rc).name
        except ValueError:
            name = "an unknown signal"
        return f"killed by {name} (signal {-rc})"
    if 128 < rc < 160:
        return f"exit {rc} (128 + {rc - 128}: what a kill by signal {rc - 128} shows as in a shell)"
    return f"exit {rc}"


def stop_report(stage: str, message: str, tail: str, failures: Failures) -> list[str]:
    """What is known when a build stops, as log lines: the stage, the message (or that there was none), the commands
    that ended badly and the build's stderr."""
    message = " ".join(str(message).split())
    lines = [f"the build stopped in the {stage} stage" + (f": {message}" if message else "")]
    if not message:
        lines.append("  the step that stopped it gave no message")
    for words, rc, err in failures.items:
        err = err.strip()
        lines.append(f"  a command that ended badly: {words} -> {exit_words(rc)}"
                     + (f"; its stderr ended: {err[-300:]}" if err else "; nothing on its stderr"))
    tail = tail.strip()
    lines.append(f"  the build's stderr ended: {tail[-1500:]}" if tail
                 else "  nothing was written to the build's stderr")
    return lines


AGAIN_ENV = "HEAVENFALL_BUILD_AGAIN"   # (set in the fresh process of a retry: it never retries again)


def again_argv(argv: list[str]) -> list[str]:
    """The command of this builder run again from the start, in a fresh process, with the same arguments (unbuffered,
    as the app starts it: BuilderService)."""
    return [sys.executable, "-u", os.path.abspath(__file__), *argv]


def run_again_fresh(argv: list[str]):
    """The retry of a compile-stage stop, as the app's Resume does it: this process is replaced by a fresh run of the
    builder with the same arguments (the same pid and output, so the app reads on), which keeps the translation and
    goes straight to the compile. Run in the process that made the translation, the last link was stopped by SIGABRT
    with nothing on its stderr three times out of three (B168); in a fresh process it went through every time, with
    less memory available."""
    def again(_cmd):
        sys.stdout, sys.stderr = sys.__stdout__, sys.__stderr__
        sys.stdout.flush()
        sys.stderr.flush()
        os.environ[AGAIN_ENV] = "1"
        os.execv(sys.executable, again_argv(argv))
    return again


def build_with_retry(run, cmd: list[str], tap: Stages, errors: Tail, failures: Failures, zig_cache: str,
                     again=None):
    """run(cmd) (build_game.main); a stop in the compile stage is tried once more, with --compile-only (the translation
    is kept and the objects' cache gives back what was made), after saying what is known of it: by again(cmd) when
    given (run_again_fresh: a fresh process), else by run. A stop that repeats goes on as it is, and so does a stop in
    the fresh process of a retry. Zig's cache with an object gone that its record still names (a Galaxy S25 Ultra,
    after some seventy cuts of one build by the heat rule: a compile of the same C again fails with "cannot open
    .../zig-cache/o/.../X.o") is emptied first: the cache only saves compiling the same C twice."""
    try:
        return run(cmd)
    except SystemExit as e:
        if e.code in (0, None) or tap.stage != "compile" or os.environ.get(AGAIN_ENV):
            raise
        message = e.code if isinstance(e.code, str) else f"stopped with code {e.code}"
        for line in stop_report(tap.stage, message, errors.text, failures):
            emit(line)
        if "zig-cache" in errors.text and os.path.isdir(zig_cache):
            print("Zig's cache names a file it no longer has: emptied")
            shutil.rmtree(zig_cache, ignore_errors=True)
        print("the compile goes on once more (the translation is kept, the objects made are in the cache)"
              + (", in a fresh process" if again else ""))
        errors.text = ""
        return (again or run)(cmd if "--compile-only" in cmd else cmd + ["--compile-only"])


def whole_object(path: str) -> bool:
    """An ELF object whose section headers are all inside the file: one cut short while it was written isn't."""
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            head = f.read(64)
        if len(head) < 64 or head[:4] != b"\x7fELF" or head[4] != 2:
            return False
        shoff = struct.unpack_from("<Q", head, 40)[0]
        shentsize, shnum = struct.unpack_from("<HH", head, 58)
        return shoff > 0 and shoff + shentsize * shnum <= size
    except OSError:
        return False


def drop_broken_objects(obj_dir: str) -> int:
    dropped = 0
    if os.path.isdir(obj_dir):
        for name in os.listdir(obj_dir):
            path = os.path.join(obj_dir, name)
            if name.endswith(".o") and not whole_object(path):
                os.remove(path)
                dropped += 1
    return dropped


class CompileProgress(threading.Thread):
    """While compiling: how many of the C files have their object (build.py prints only every 20 files)."""

    def __init__(self, recomp_dir: str):
        super().__init__(daemon=True)
        self.src = os.path.join(recomp_dir, "src")
        self.obj = os.path.join(recomp_dir, "obj")
        self.stop = threading.Event()

    def run(self):
        last = None
        while not self.stop.wait(3):
            try:
                sources = [f[:-2] for f in os.listdir(self.src) if f.endswith(".c")]
                done = 0
                objects = os.listdir(self.obj) if os.path.isdir(self.obj) else []
                for s in sources:
                    src_time = os.path.getmtime(os.path.join(self.src, s + ".c"))
                    if any(o.startswith(s + "-") and o.endswith(".o") and os.path.getmtime(os.path.join(self.obj, o)) >= src_time
                           for o in objects):
                        done += 1
                now = (done, len(sources) + 1)  # (+1: rc_dll.c, the glue)
                if now != last:
                    emit(f"@@progress compile {now[0]} {now[1]}")
                    last = now
            except OSError:
                pass


def stage_mod(mod: str, user_dir: str) -> str:
    """The code mod as build_game.py reads it (load/mods/<title>, Azahar's layout), from the folder the player chose: a
    mods folder with 0004000000126300 in it (or a mod's folder with it inside), or the mod's own folder (with exefs):
    what the PC scripts beside this file take."""
    title = None
    for depth_dir in [mod] + sorted(os.path.join(mod, d) for d in os.listdir(mod) if os.path.isdir(os.path.join(mod, d))):
        for name in sorted(os.listdir(depth_dir)) if os.path.isdir(depth_dir) else []:
            if re.fullmatch(r"0004000[0eE]00126[13]00", name) and os.path.isdir(os.path.join(depth_dir, name)):
                title = os.path.join(depth_dir, name)
                break
        if title:
            break
    if title is None and os.path.isdir(os.path.join(mod, "exefs")):
        title = mod
    if title is None:
        raise SystemExit(f"there is no {TITLE} or exefs folder in {mod}")
    shutil.rmtree(user_dir, ignore_errors=True)
    dest = os.path.join(user_dir, "load", "mods", TITLE)
    os.makedirs(os.path.dirname(dest))
    shutil.copytree(title, dest)
    return title


def first_code_mod(mods_dir: str) -> str | None:
    """Older tools: the first folder of mods_dir (a mods/ folder or load/mods) with a title folder or exefs in it."""
    if not os.path.isdir(mods_dir):
        return None
    for name in sorted(os.listdir(mods_dir)):
        d = os.path.join(mods_dir, name)
        if not os.path.isdir(d) or name.startswith("."):
            continue
        if re.fullmatch(r"0004000[0eE]00126[13]00", name) or os.path.isdir(os.path.join(d, "exefs")):
            return mods_dir if re.fullmatch(r"0004000[0eE]00126[13]00", name) else d
        try:
            stage_probe = [n for n in os.listdir(d) if re.fullmatch(r"0004000[0eE]00126[13]00", n)]
        except OSError:
            stage_probe = []
        if stage_probe:
            return d
    return None


def install(lib: str, dest_dir: str) -> str:
    """The library and its .id into the player's folder, each written beside its place first and then renamed: a cut
    leaves the old pair or the new one, never half of one."""
    pairs = [(lib, os.path.join(dest_dir, LIBRARY)), (build_game.id_path(lib), os.path.join(dest_dir, build_game.id_path(LIBRARY)))]
    for src, dst in pairs:
        part = dst + ".part"
        shutil.copyfile(src, part)
        with open(part, "rb+") as f:
            os.fsync(f.fileno())
        os.replace(part, dst)
    return pairs[0][1]


def folder_bytes(top: str) -> int:
    total = 0
    for root, _, files in os.walk(top):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def space_needed(work: str, cache_dir: str, tuning: dict) -> tuple[int, str]:
    """The MB of free space the build still lacks (0: it has enough) and the numbers it went by. The build needs
    config/build_tuning.json's space (build_tuning.space_needed_mb); the work folder's files count as room (a run
    writes over them or uses them again), and when that isn't enough the objects' cache gives up its oldest objects
    down to its floor."""
    need = build_tuning.space_needed_mb(tuning)
    work_mb = folder_bytes(work) >> 20
    free_mb = shutil.disk_usage(work).free >> 20
    dropped = 0
    if free_mb + work_mb < need and os.path.isdir(cache_dir):
        c = tuning["cache"]
        cache = objcache.ObjectCache(cache_dir, c["keep_free_mb"], c["max_mb"], tuning["space"]["cache_floor_mb"])
        dropped, _ = cache.shrink((need - free_mb - work_mb) << 20)
        free_mb = shutil.disk_usage(work).free >> 20
    why = (f"space: the build needs {need} MB; {free_mb} MB free, {work_mb} MB in the work folder"
           + (f", {dropped} of the objects' cache's oldest objects dropped" if dropped else ""))
    return max(0, need - free_mb - work_mb), why


def memory_check(tuning: dict, meminfo: str = "/proc/meminfo") -> tuple[str | None, str]:
    """The translation's memory (config/build_tuning.json translation.peak_mb: one process holds the whole code's
    analysis) against the device's: (the reason it can never fit, or None; a line for the log). A device that has it
    in all but not free now goes on: Android stops apps in the background to give memory to the one in front, and
    swap takes the rest (slower). Where /proc/meminfo can't be read (a PC) nothing is checked."""
    peak = tuning["translation"]["peak_mb"]
    try:
        info = compile_jobs.read_meminfo(meminfo)
    except OSError:
        return None, f"memory: the translation needs about {peak} MB (not checked here)"
    total = (info.get("MemTotal", 0) + info.get("SwapTotal", 0)) >> 10
    free = (info.get("MemAvailable", 0) + info.get("SwapFree", 0)) >> 10
    line = f"memory: the translation needs about {peak} MB; {free} MB available now, {total} MB in all with swap"
    if total and total < peak:
        return (f"the translation of the game's code needs about {peak} MB of memory and this device has {total} MB "
                f"in all (with its swap)"), line
    if free < peak:
        line += " (apps in the background will be stopped to make room, or swap used: slower)"
    return None, line


def lean_recompile() -> None:
    """The translator's memory on a phone's few GB (the same C comes out). The translator itself lets go of what it no
    longer reads (recompile.py: the first analysis's functions and its cover once the data pointers are found, the
    decoder's cache after the second analysis, each function once its C is written; one copy of each C statement); here,
    Python's collector of reference cycles is kept off while it runs."""
    import gc
    rc = build_game.recompile

    # the collector walks every object the analysis keeps (a phone of 4 GB swaps much of the translator's memory out,
    # and a collection reads it all back); the translation makes no cycles worth collecting (the same peak with it
    # off, measured): off until the C is written, then one collection
    def collected(run):
        def wrapper(*a, **k):
            was = gc.isenabled()
            gc.disable()
            try:
                return run(*a, **k)
            finally:
                if was:
                    gc.enable()
                gc.collect()
        return wrapper
    rc.recompile = collected(rc.recompile)


def translation_progress(tap: Stages) -> None:
    """The translation's progress as it goes (TRANSLATION), from inside recompile() (the same C comes out). Its first
    analysis, the data pointers and its writing of the C said nothing, only the second analysis did ("analysed N/M"):
    the screen sat at "Translate the game's code (10 %)" for minutes on a phone (reported as stuck at 10 %), then at
    30 % while the C was written. Now each step says how far it is about every 2 s (@@progress recompile), and the
    build's log gets a line at every tenth of a step."""
    from recomp import analysis
    from recomp import emit as emitter
    rc = build_game.recompile
    analyse_all, analyse = analysis.Analyzer.analyze_all, analysis.Analyzer.analyze
    pointers, function, recompile = rc.data_pointer_entries, emitter.FunctionEmitter.function, rc.recompile
    cover = rc.code_cover
    now = {"step": "", "done": 0, "of": 1, "functions": 0, "at": 0.0, "tenth": -1}
    words = {"first": "analysing the code", "cover": "where the code is", "pointers": "looking for code in the data",
             "second": "analysing it again", "writing": "writing the C", "shaders": "the geometry shaders"}

    def tell(force=False):
        t = time.monotonic()
        if not force and t - now["at"] < 2:
            return
        now["at"] = t
        part = min(1.0, now["done"] / max(1, now["of"]))
        tap.translated(now["step"], part, now["functions"] or (now["of"] if now["step"] in ("first", "second") else 0))
        tenth = int(part * 10)
        if tenth != now["tenth"]:
            now["tenth"] = tenth
            print(f"  translation: {words[now['step']]}, {now['done']} of {now['of']}", flush=True)

    def begin(step, of):
        now.update(step=step, done=0, of=max(1, of), tenth=-1)
        tell(True)

    def analyze_all(self, entries, progress=None):
        begin("second" if progress else "first", len(entries))
        funcs = analyse_all(self, entries, progress)
        now["done"] = now["of"] = len(funcs)
        if progress:
            now["functions"] = len(funcs)
        tell(True)
        return funcs

    def analyze(self, *a, **k):
        if now["step"] in ("first", "second"):
            now["done"] += 1
            tell()
        return analyse(self, *a, **k)

    class Counted:
        """The first analysis's functions as code_cover() walks them (values()), counted; the rest goes to the dict."""

        def __init__(self, funcs):
            self.funcs = funcs

        def values(self):
            for f in self.funcs.values():
                now["done"] += 1
                if now["done"] & 255 == 0:
                    tell()
                yield f

        def __getattr__(self, name):
            return getattr(self.funcs, name)

    def code_cover(funcs):  # (every halfword the first analysis's code covers: on a phone of 4 GB, a minute of swapping)
        begin("cover", len(funcs))
        return cover(Counted(funcs))

    def data_pointer_entries(img, an, segments, *rest, **k):
        # (it reads every word of the data segments, recompile.py's values(): those reads are counted, the analysis's
        # own reads of the code are not)
        ranges = [(start, start + size) for start, size in segments]
        begin("pointers", sum(size // 4 for _, size in segments))
        read = img.read32

        def counted(a):
            for lo, hi in ranges:
                if lo <= a < hi:
                    now["done"] += 1
                    if now["done"] & 4095 == 0:
                        tell()
                    break
            return read(a)
        try:
            img.read32 = counted
        except AttributeError:  # (an Image that takes no attribute of its own: not counted)
            pass
        try:
            found = pointers(img, an, segments, *rest, **k)
        finally:
            if img.__dict__.get("read32") is counted:
                del img.read32
        now["done"] = now["of"]
        tell(True)
        return found

    def written(self, f):
        if now["step"] != "writing":
            begin("writing", now["functions"])
        now["done"] += 1
        tell()
        return function(self, f)

    def recompiled(*a, **k):
        res = recompile(*a, **k)
        begin("shaders", 1)  # (build_game.py's next: the version's table, then the geometry shaders, read from the game)
        return res
    analysis.Analyzer.analyze_all, analysis.Analyzer.analyze = analyze_all, analyze
    emitter.FunctionEmitter.function = written
    rc.code_cover, rc.data_pointer_entries, rc.recompile = code_cover, data_pointer_entries, recompiled


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--update", required=True, help="the decrypted update .cia (or the game itself for a version without one)")
    ap.add_argument("--game", help="the base game's decrypted .3ds/.cci: its geometry shaders go into the library")
    ap.add_argument("--mod", help="a code mod to build in (the mods folder or the mod's own folder)")
    ap.add_argument("--game-mods", help="the game folder's mods/: its code mods, as the app copies them")
    ap.add_argument("--user-mods", help="the app's user folder: the mods on in its load/mods, as the game runs them")
    ap.add_argument("--work", required=True, help="the work folder (kept between runs to go on where one stopped)")
    ap.add_argument("--dest", required=True, help="the folder the library and its .id go to (the game's folder)")
    ap.add_argument("--cc", required=True, help="the C compiler (the app's libzigcc.so)")
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 2,
                    help="the most compiles at once (default: the cores); as many run as the memory available holds")
    ap.add_argument("--expect-id", default="", help="the id the app wants: anything else stops before the long part")
    ap.add_argument("--clean", action="store_true", help="once the library is in place, delete the work folder (its C, "
                    "objects and Zig's cache: about 600 MB; a run after that starts from the beginning)")
    ap.add_argument("--cache", help="the compiled objects' cache, kept across builds and mod versions (default: "
                    f"{CACHE} beside the work folder; never inside it: --clean deletes that)")
    ap.add_argument("--tuning", default=build_tuning.PATH, help="the build's numbers (config/build_tuning.json)")
    args = ap.parse_args(argv)

    t0 = time.time()
    tap = Stages()
    errors, failures = Tail(), Failures()   # (what the build wrote on stderr; the commands that ended badly)
    emit(f"@@pid {os.getpid()}")
    lib_id = build_game.code_library_id(True, False)
    emit(f"@@id {lib_id}")
    if args.expect_id and args.expect_id != lib_id:
        emit(f"@@error tools these tools make libraries with id {lib_id}, the app wants {args.expect_id}")
        return 1
    try:
        try:
            tuning = build_tuning.load(args.tuning)
        except build_tuning.TuningError as e:
            emit(f"@@error tools {' '.join(str(e).split())}")
            return 1
        os.makedirs(args.work, exist_ok=True)
        cache_dir = args.cache or os.path.join(os.path.dirname(os.path.abspath(args.work)), CACHE)
        if os.path.abspath(cache_dir).startswith(os.path.abspath(args.work) + os.sep) and args.clean:
            emit(f"@@error cache the objects' cache {cache_dir} is inside the work folder, which --clean deletes")
            return 1
        out = os.path.join(args.work, "game")
        recomp = os.path.join(out, "recomp")
        need, why = space_needed(args.work, cache_dir, tuning)
        print(why)
        if need > 0:
            emit(f"@@error space {need} MB more free space needed ({why})")
            return 1
        dropped = drop_broken_objects(os.path.join(recomp, "obj"))
        if dropped:
            print(f"{dropped} objects cut short by an earlier run: made again")
        if not os.access(args.dest, os.W_OK):
            emit(f"@@error dest can't write in {args.dest}")
            return 1

        # the stages before the compile: kept from the last run if they were made from the same files and tools
        if args.game_mods:
            mods = f"game-mods {args.game_mods} {mods_digest(args.game_mods)}"
        elif args.user_mods:
            mods = f"user-mods {args.user_mods} {mods_digest(os.path.join(args.user_mods, 'load', 'mods'))}"
        else:
            mods = f"mod {args.mod} {mods_digest(args.mod)}" if args.mod else "mods none"
        lines = [f"tools {lib_id} {tools_digest()}", "python " + " ".join(sys.version.split()),
                 f"update {args.update} {any_digest(args.update)}", f"game {args.game} {any_digest(args.game)}", mods]
        inputs = stamp_hash(lines)
        kept_inputs, kept_made = read_stamp(args.work)
        reuse = kept_inputs == inputs and kept_made != "none" and kept_made == written(out)
        if reuse:
            print(f"inputs as the last run's ({time.time() - t0:.1f} s to check): its translation is kept, on to the compile")
        else:
            drop_stamp(args.work)
            if kept_inputs:
                print("the last run's translation isn't for these inputs (or its C is gone): translating again")

        if not reuse:
            never, line = memory_check(tuning)
            print(line)
            if never:
                emit(f"@@error memory {never}")
                return 1

        # (--jobs is the ceiling: the compiles running follow the memory available, recomp/jobs.py)
        cmd = ["--dump", args.update, "--native", "--android", "--cc", args.cc, "--out", out, "-j", str(args.jobs),
               "--tuning", args.tuning, "--jobs-by-memory", "--object-cache", cache_dir]
        if args.game:
            cmd += ["--romfs", args.game]
        # (build_game.py with --game-mods also reads every mod of a user folder that is on: mh4n feat-4ucode)
        whole = hasattr(build_game, "mod_folders")
        single = args.mod
        if args.game_mods and whole:
            cmd += ["--game-mods", args.game_mods]
            print(f"code mods of {args.game_mods}")
        elif args.user_mods and whole:
            cmd += ["--mods", args.user_mods]
            print(f"code mods that are on in {os.path.join(args.user_mods, 'load', 'mods')}")
        elif args.game_mods:
            single = first_code_mod(args.game_mods)
        elif args.user_mods:
            single = first_code_mod(os.path.join(args.user_mods, "load", "mods"))
        if single and not (whole and (args.game_mods or args.user_mods)):
            if not reuse:  # (the compile alone doesn't read it)
                tap.set("mods")
                title = stage_mod(single, os.path.join(args.work, "mods"))
                print(f"code mod from {title}")
            cmd += ["--mods", os.path.join(args.work, "mods")]
        elif "--game-mods" not in cmd and "--mods" not in cmd:
            cmd += ["--no-mods"]
        tap.set("extract")

        if reuse:
            cmd += ["--compile-only"]
        else:
            lean_recompile()
            translation_progress(tap)
        progress = CompileProgress(recomp)

        def on_stage(stage):
            if stage != "compile" or progress.is_alive():
                return
            if not reuse:  # (the stages before it are through: what they were made from, and the C they wrote)
                write_stamp(args.work, inputs, written(out), lines)
            print(f"compiling from {time.time() - t0:.1f} s after the start")
            progress.start()
        tap.on_stage = on_stage
        sys.stdout = tap
        try:
            sys.stderr = errors
            with failures.watching():
                build_with_retry(build_game.main, cmd, tap, errors, failures, os.path.join(args.work, "zig-cache"),
                                 again=run_again_fresh(sys.argv[1:] if argv is None else list(argv)))
        finally:
            progress.stop.set()
            sys.stdout, sys.stderr = sys.__stdout__, sys.__stderr__
        lib = os.path.join(recomp, "android", LIBRARY)
        tap.set("install")
        placed = install(lib, args.dest)
        if args.clean:
            shutil.rmtree(args.work, ignore_errors=True)
        emit(f"@@done {placed}")
        return 0
    except SystemExit as e:
        if e.code in (0, None):
            return 0
        message = str(e.code) if not isinstance(e.code, int) else f"stopped with code {e.code}"
        for line in stop_report(tap.stage, message, errors.text, failures):
            emit(line)
        emit(f"@@error {tap.stage} {' '.join(message.split())}")
        return 1
    except OSError as e:
        traceback.print_exc()
        for line in stop_report(tap.stage, str(e), errors.text, failures):
            emit(line)
        kind = "space" if e.errno == errno.ENOSPC else tap.stage
        emit(f"@@error {kind} {e}")
        return 1
    except MemoryError:
        for line in stop_report(tap.stage, "out of memory", errors.text, failures):
            emit(line)
        emit(f"@@error memory {tap.stage}")
        return 1
    except Exception as e:  # noqa: BLE001 (any other failure: the app shows it, the log has the traceback)
        traceback.print_exc()
        for line in stop_report(tap.stage, f"{type(e).__name__}: {e}", errors.text, failures):
            emit(line)
        emit(f"@@error {tap.stage} {type(e).__name__}: {' '.join(str(e).split())}")
        return 1


if __name__ == "__main__":
    t0 = time.time()
    code = main()
    print(f"({time.time() - t0:.0f} s)")
    sys.exit(code)
