#!/usr/bin/env python3
"""Compile recompiled C (from tools/recompile.py) into a shared library.

usage: python3 -m recomp.build build/recomp [--harness] [-j N] [--opt O1] [-o game.so]
       python3 -m recomp.build build/recomp --code-library [--cc CC] [-o mh4u_code.so]
       python3 -m recomp.build build/recomp --code-library --native [--deterministic]
       python3 -m recomp.build build/recomp --code-library [--native] --android [--cc CC] [-o lib.so]

--harness links the differential-test harness (gametest). --code-library builds the
library the PC port loads with `mh4u_runner --code` (paged memory, callbacks from the
host); --cc picks another compiler, e.g. llvm-mingw's x86_64-w64-mingw32-clang for a
Windows .dll. --native builds for the native runtime instead (RC_NATIVE: flat memory, and a
yield point that only looks at the host's preemption flag); with --deterministic the yield
points keep charging cycles (RC_NATIVE_DETERMINISTIC), for reproducible scheduling. Such a
library is named mh4u_code_native.so / mh4u_code_native_det.so unless -o says otherwise, and
only a native host loads it (the library says which it was built for in rc_build_mode).
--android builds the code library for Android's arm64 app instead, either mode (any clang with
lld, Zig's and llvm-mingw's included: no NDK, see runtime/android/README.md); its soname is
libmh4u_code.so, or libmh4u_code_native.so / libmh4u_code_native_det.so with --native.
Incremental: an object is reused while its source is older than it and
recomp.h has the contents it was compiled with (obj/recomp.h.sha1 remembers them, so a
copy of the tools with new dates, as unpacking a new package makes, recompiles nothing).
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if os.path.dirname(HERE) not in sys.path:
    sys.path.insert(0, os.path.dirname(HERE))

from recomp import jobs as compile_jobs  # noqa: E402
from recomp import objcache, units  # noqa: E402
from recomp.emit import LOCALS_PREAMBLE  # noqa: E402
RUNTIME = os.path.join(HERE, "runtime")
HARNESS = os.path.join(HERE, "harness", "harness.c")
DLL_GLUE = os.path.join(RUNTIME, "rc_dll.c")
BASE_FLAGS = ["-c", "-fPIC", "-ffp-contract=off", "-frounding-math", "-fno-strict-aliasing", "-fwrapv", "-w",
              f"-I{RUNTIME}"]

# ── Android (the arm64 app): the C library's headers are the few in runtime/android, and the
# functions come from the device's libc.so and libm.so, which the library names as needed
# through two empty stand-ins it is linked against. API 29 is the app's minSdk.
ANDROID_TARGET = "aarch64-linux-android29"
ANDROID_INCLUDE = os.path.join(RUNTIME, "android")
ANDROID_SONAME = "libmh4u_code.so"
ANDROID_PAGE = 16384  # Android 15's 16 KB pages; 4 KB devices load such a library too
# what the library may take from the device: Android's libc.so and libm.so have all of these
ANDROID_IMPORTS = frozenset({
    "memcpy", "memmove", "memset", "memcmp", "strlen", "__stack_chk_fail", "fegetround", "fesetround",
    *(f + suffix for f in ("sqrt", "fabs", "trunc", "floor", "ceil", "round", "rint", "nearbyint", "fma",
                           "fmin", "fmax", "copysign", "fmod", "ldexp") for suffix in ("", "f")),
})


def android_soname(native: bool = False, deterministic: bool = False) -> str:
    """The Android library's soname, and its file name for the native app: libmh4u_code.so for
    Azahar's app, libmh4u_code_native.so (or _native_det) for the native runtime's."""
    return f"libmh4u_code{native_suffix(native, deterministic)}.so"


def is_zig(cc: str) -> bool:
    return "zig" in os.path.basename(cc).lower()


def android_compiler(cc: str) -> str:
    """The compiler to build for Android with: llvm-mingw's target-named clang (a wrapper that
    insists on Windows) gives way to the plain clang next to it, also when it was found on the
    PATH by its name alone."""
    if "mingw32-clang" in os.path.basename(cc).lower():
        found = cc if os.path.dirname(cc) else (shutil.which(cc) or cc)
        plain = os.path.join(os.path.dirname(found), "clang" + (".exe" if found.lower().endswith(".exe") else ""))
        if os.path.isfile(plain):
            return plain
    return cc


def android_target_flags(cc: str) -> list[str]:
    return ["-target", "aarch64-linux-android"] if is_zig(cc) else [f"--target={ANDROID_TARGET}"]


def android_link_flags(cc: str, outdir: str, soname: str = ANDROID_SONAME) -> list[str]:
    """The link's flags, with the stand-ins for libc.so and libm.so made in outdir/android-stubs."""
    target = android_target_flags(cc)
    common = [*target, "-shared", "-nostdlib", *([] if is_zig(cc) else ["-fuse-ld=lld"])]
    stubs = os.path.join(outdir, "android-stubs")
    os.makedirs(stubs, exist_ok=True)
    empty = os.path.join(stubs, "empty.c")
    with open(empty, "w") as f:
        f.write("/* a stand-in: only its name counts (Android's own library is loaded) */\n")
    libs = []
    for name in ("libc.so", "libm.so"):
        lib = os.path.join(stubs, name)
        r = subprocess.run([cc, *common, f"-Wl,-soname,{name}", empty, "-o", lib], capture_output=True, text=True)
        if r.returncode:
            raise SystemExit(f"{cc} can't link for Android (it needs lld):\n{r.stderr[-2000:]}")
        libs.append(lib)
    return [*common, f"-Wl,-soname,{soname}", f"-Wl,-z,max-page-size={ANDROID_PAGE}",
            "-Wl,--build-id=sha1", "-Wl,-z,noexecstack", *libs]


def android_library_problems(path: str, soname: str = ANDROID_SONAME) -> list[str]:
    """What would keep Android's linker from loading the library at `path` in the app (an empty
    list when nothing): another machine, no libc.so/libm.so needed, another soname, text
    relocations, pages under 16 KB, or a function the device's C library doesn't have."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return ["not a 64-bit little-endian ELF file"]
    machine = struct.unpack_from("<H", data, 18)[0]
    problems = [] if machine == 183 else [f"built for machine {machine}, not AArch64 (183)"]
    e_phoff, e_shoff = struct.unpack_from("<QQ", data, 32)
    e_phentsize, e_phnum, e_shentsize, e_shnum = struct.unpack_from("<HHHH", data, 54)
    dynamic = None
    for i in range(e_phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz, _, p_align = struct.unpack_from("<IIQQQQQQ", data, e_phoff + i * e_phentsize)
        if p_type == 1 and p_align < ANDROID_PAGE:
            problems.append(f"a segment aligned to {p_align} bytes, not {ANDROID_PAGE}")
        if p_type == 2:
            dynamic = (p_offset, p_filesz)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, e_shoff + i * e_shentsize) for i in range(e_shnum)]
    dynsym = next((s for s in sections if s[1] == 11), None)  # SHT_DYNSYM
    if dynamic is None or dynsym is None:
        return problems + ["no dynamic section: not a shared library"]
    strtab = sections[dynsym[6]]
    def text(offset: int) -> str:
        start = strtab[4] + offset
        return data[start:data.index(b"\0", start)].decode("ascii", "replace")
    needed, found_soname, textrel = [], "", False
    for off in range(dynamic[0], dynamic[0] + dynamic[1], 16):
        tag, value = struct.unpack_from("<qQ", data, off)
        if tag == 0:
            break
        if tag == 1:
            needed.append(text(value))
        elif tag == 14:
            found_soname = text(value)
        elif tag == 22 or (tag == 30 and value & 4):  # DT_TEXTREL, DT_FLAGS & DF_TEXTREL
            textrel = True
    for lib in ("libc.so", "libm.so"):
        if lib not in needed:
            problems.append(f"doesn't name {lib} as needed")
    if found_soname != soname:
        problems.append(f"its soname is {found_soname or 'missing'}, not {soname}")
    if textrel:
        problems.append("it has text relocations")
    imports = set()
    for off in range(dynsym[4], dynsym[4] + dynsym[5], 24):
        name, info, _, shndx = struct.unpack_from("<IBBH", data, off)
        if name and shndx == 0 and (info >> 4) != 2:  # undefined, not weak
            imports.add(text(name))
    missing = sorted(imports - ANDROID_IMPORTS)
    if missing:
        problems.append("it calls functions from outside the few the port takes from Android's C library: " + ", ".join(missing))
    return problems


def compile_one(cc: str, src: str, obj: str, flags: list[str]) -> tuple[str, float, str]:
    t = time.time()
    r = subprocess.run([cc, *flags, src, "-o", obj], capture_output=True, text=True)
    return src, time.time() - t, (r.stderr if r.returncode else "")


def load_tuning(path: str | None = None) -> dict:
    """config/build_tuning.json (tools/build_tuning.py)."""
    sys.path.insert(0, os.path.dirname(HERE))
    import build_tuning
    try:
        return build_tuning.load(path)
    except build_tuning.TuningError as e:
        raise SystemExit(str(e)) from None


INCLUDE = re.compile(rb'^\s*#\s*include\s+"([^"]+)"', re.M)


def build(outdir: str, jobs: int, opt: str, defines: list[str], lib_name: str, extra_sources: list[str],
          cc: str = "clang", extra_flags: list[str] | None = None, runtime_header: str | None = None,
          android: bool = False, tuning: dict | None = None, cache_dir: str | None = None,
          jobs_by_memory: bool = False) -> str:
    """android: the arm64 library for Android, without the NDK; its soname follows the build
    mode in `defines` (android_soname), and it is checked as Android's linker would see it.
    `cache_dir`: the objects' cache (recomp/objcache.py): every object is taken from it when it has one made from the
    same C, flags and compiler, and every object compiled goes into it. `jobs_by_memory`: `jobs` is a ceiling, the
    number running follows the memory available (recomp/jobs.py; `tuning`, config/build_tuning.json)."""
    src_dir = os.path.join(outdir, "src")
    obj_dir = os.path.join(outdir, "obj")
    os.makedirs(obj_dir, exist_ok=True)
    tuning = tuning or load_tuning()
    # funcs.h only gains or loses declarations; a file whose calls change is rewritten itself.
    # recomp.h is in every object: when its contents differ from those the objects were
    # compiled with (obj/recomp.h.sha1), everything is recompiled; its date is not looked at,
    # since a copy of the tools (a new package unpacked) renews it without changing a thing
    with open(runtime_header or os.path.join(RUNTIME, "recomp.h"), "rb") as fh:
        header_hash = hashlib.sha1(fh.read()).hexdigest()
    stamp = os.path.join(obj_dir, "recomp.h.sha1")
    # no stamp yet (objects from before it was kept): they were built against the header
    # of their day, which the old date check kept current, so they stand and get the stamp
    header_changed = os.path.exists(stamp) and open(stamp).read().strip() != header_hash
    if android:
        cc = android_compiler(cc)
        extra_flags = [*android_target_flags(cc), "-nostdlibinc", "-isystem", ANDROID_INCLUDE, *(extra_flags or [])]
        soname = android_soname("RC_NATIVE" in defines, "RC_NATIVE_DETERMINISTIC" in defines)
    flags = [f"-{opt}",*BASE_FLAGS, f"-I{src_dir}", *[f"-D{d}" for d in defines], *(extra_flags or [])]
    sources = sorted(os.path.join(src_dir, f) for f in os.listdir(src_dir) if f.endswith(".c")) + extra_sources
    # objects are named after everything that changes the code, so builds with other
    # settings (harness, code library, another compiler) never reuse each other's objects
    variant = hashlib.sha1(" ".join([os.path.basename(cc), opt, *defines, *(extra_flags or [])]).encode()).hexdigest()[:8]

    def obj_of(s: str, folder: str = obj_dir) -> str:
        return os.path.join(folder, f"{os.path.basename(s)[:-2]}-{opt}-{variant}.o")

    t0 = time.time()
    todo, objs = [], []
    cache = keys = None
    if cache_dir:
        # the objects' cache: an object made from the same file, includes, flags and compiler is never made again
        c = tuning["cache"]
        cache = objcache.ObjectCache(cache_dir, c["keep_free_mb"], c["max_mb"], tuning["space"]["cache_floor_mb"])
        compiler = objcache.compiler_id(cc)
        headers = objcache.headers_id(RUNTIME)
        key_flags = [f.replace(src_dir, "<src>").replace(RUNTIME, "<runtime>") for f in flags]

        def key_of(path: str) -> str:
            with open(path, "rb") as fh:
                data = fh.read()
            includes = [open(os.path.join(src_dir, n.decode()), "rb").read() for n in INCLUDE.findall(data)
                        if os.path.isfile(os.path.join(src_dir, n.decode()))]
            return objcache.key(os.path.basename(path), data, includes, key_flags, compiler, headers)
        keys = {}
        for s in sources:
            o = obj_of(s)
            k = keys[s] = key_of(s)
            parts = cache.get_parts(k)
            if parts and all(cache.get(pk, obj_of(name + ".c", os.path.join(obj_dir, "parts"))) for name, pk in parts):
                objs += [obj_of(name + ".c", os.path.join(obj_dir, "parts")) for name, _ in parts]
                continue
            objs.append(o)
            if not cache.get(k, o):
                todo.append((s, o))
        bad = f" ({cache.dropped_bad} of its entries didn't match their sums: dropped)" if cache.dropped_bad else ""
        print(f"objects' cache {cache_dir}: {cache.hits} objects from it, {len(todo)} to compile{bad}")
    else:
        for s in sources:
            o = obj_of(s)
            objs.append(o)
            if header_changed or not os.path.exists(o) or os.path.getmtime(o) < os.path.getmtime(s):
                todo.append((s, o))
        if header_changed:
            # the other variants' objects were compiled against the old header too
            for name in os.listdir(obj_dir):
                if name.endswith(".o") and os.path.join(obj_dir, name) not in objs:
                    os.remove(os.path.join(obj_dir, name))
    report = _compile(todo, cc, flags, jobs, tuning, jobs_by_memory, outdir, obj_of, cache,
                      key_of if cache else None, is_zig(cc))
    split = {unit_obj: [p.obj for p in pieces] for unit_obj, pieces in report.parts.items()}
    objs = [p for o in objs for p in split.get(o, [o])]   # (a unit compiled one function per file: its parts)
    with open(stamp, "w") as fh:
        fh.write(header_hash + "\n")
    lib = os.path.join(outdir, lib_name)
    os.makedirs(os.path.dirname(lib), exist_ok=True)
    # the object list goes in a response file: Windows limits a command line to 32 KiB
    rsp = os.path.join(outdir, "objects.rsp")
    with open(rsp, "w") as f:
        f.write("\n".join('"' + o.replace("\\", "/") + '"' for o in objs) + "\n")
    if android:
        link = [cc, *android_link_flags(cc, outdir, soname), f"@{rsp}", "-o", lib]
    else:
        link = [cc, "-shared", f"@{rsp}", "-o", lib, *(["-lm"] if "mingw" not in cc else [])]
    r = subprocess.run(link, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stderr[-4000:])
    print(f"linked {lib} ({len(objs)} objects, {len(todo)} recompiled in {time.time() - t0:.0f}s)")
    if android:
        problems = android_library_problems(lib, soname)
        if problems:
            raise SystemExit(f"{lib} wouldn't load on Android: " + "; ".join(problems))
    if cache is not None:
        dropped, freed = cache.trim()
        gone = (f" ({dropped} of its oldest objects dropped, {freed >> 20} MB, for the disk's free space)"
                if dropped else "")
        print(f"objects' cache: {cache.size() >> 20} MB{gone}")
    return lib


def _compile(todo: list[tuple[str, str]], cc: str, flags: list[str], jobs: int, tuning: dict, jobs_by_memory: bool,
             outdir: str, obj_of, cache, key_of, zig: bool) -> "compile_jobs.Report":
    """The compiles of `todo` ((source, object) pairs): recomp/jobs.py's run_all, each object into the cache as it is
    made (a run cut short keeps what it made). With the cache and Zig, each compile gets a folder of its own for Zig's
    cache, emptied after it (Zig would keep a second copy of every object, which the cache already has)."""
    sys.path.insert(0, os.path.dirname(HERE))
    import build_tuning
    c = tuning["compile"]
    tasks = [compile_jobs.Task(os.path.basename(s)[:-2], s, o, os.path.getsize(s),
                               build_tuning.compile_mb(tuning, os.path.getsize(s))) for s, o in todo]
    rule = compile_jobs.MemoryRule(c["job_mb"], c["reserve_mb"], jobs) if jobs_by_memory else None
    if rule is None and tasks:
        print(f"compile jobs: {jobs}")
    zig_dir = os.path.join(outdir, "obj", "zig")
    parts_dir = os.path.join(outdir, "obj", "parts")
    pieces_left: dict[str, list] = {}

    def command(t):
        if os.path.lexists(t.obj):     # (never written into: it may be a hard link of the cache's)
            os.remove(t.obj)
        os.makedirs(os.path.dirname(t.obj), exist_ok=True)
        env = None
        if cache is not None and zig:
            d = os.path.join(zig_dir, t.name)
            shutil.rmtree(d, ignore_errors=True)
            os.makedirs(d)
            env = dict(os.environ, ZIG_GLOBAL_CACHE_DIR=d, ZIG_LOCAL_CACHE_DIR=d)
        return [cc, *flags, t.src, "-o", t.obj], env

    def split(t):
        with open(t.src, encoding="latin-1") as fh:
            pieces = units.parts(fh.read(), LOCALS_PREAMBLE)
        if len(pieces) < 2:
            return []
        folder = os.path.join(outdir, "parts", t.name)
        os.makedirs(folder, exist_ok=True)
        out = []
        for name, text in pieces:
            path = os.path.join(folder, name + ".c")
            with open(path, "w", encoding="latin-1", newline="\n") as fh:
                fh.write(text)
            out.append(compile_jobs.Task(name, path, obj_of(path, parts_dir), len(text),
                                         build_tuning.compile_mb(tuning, len(text)), parent=t.src))
        pieces_left[t.src] = [p.src for p in out]
        return out

    def on_done(t, r):
        if cache is not None and zig:
            shutil.rmtree(os.path.join(zig_dir, t.name), ignore_errors=True)
        if cache is None or r.rc != 0:
            return
        cache.put(key_of(t.src), t.obj)
        if t.parent:
            left = pieces_left[t.parent]
            left.remove(t.src)
            if not left:   # (the unit's every part made: the cache knows the unit by its parts)
                with open(t.parent, encoding="latin-1") as fh:
                    names = [n for n, _ in units.parts(fh.read(), LOCALS_PREAMBLE)]
                folder = os.path.dirname(t.src)
                cache.put_parts(key_of(t.parent), [(n, key_of(os.path.join(folder, n + ".c"))) for n in names])

    try:
        report = compile_jobs.run_all(tasks, command, jobs, rule, split, log=lambda m: print(m, flush=True),
                                      poll_s=c["poll_s"], on_done=on_done)
    except compile_jobs.CompileFailed as e:
        if e.memory:
            raise SystemExit(str(e)) from None
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from None
    if tasks:
        for line in compile_jobs.summary(report):
            print(line)
    return report


def native_defines(native: bool, deterministic: bool) -> list[str]:
    """The defines that select the native runtime (recomp.h): none for the paged build."""
    if deterministic and not native:
        raise SystemExit("--deterministic goes with --native")
    return ["RC_NATIVE", *(["RC_NATIVE_DETERMINISTIC"] if deterministic else [])] if native else []


def native_suffix(native: bool, deterministic: bool) -> str:
    return "" if not native else "_native_det" if deterministic else "_native"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("outdir")
    ap.add_argument("-j", "--jobs", type=int, default=os.cpu_count() or 2)
    ap.add_argument("--opt", default="O1")
    ap.add_argument("--harness", action="store_true", help="link the test harness (Linux/macOS)")
    ap.add_argument("--code-library", action="store_true", help="build the PC port's code library")
    ap.add_argument("--native", action="store_true",
                    help="build for the native runtime (RC_NATIVE: flat memory, preemption flag) "
                         "instead of the Azahar backend (RC_PAGED)")
    ap.add_argument("--deterministic", action="store_true",
                    help="with --native: keep charging cycles at every yield point "
                         "(RC_NATIVE_DETERMINISTIC)")
    ap.add_argument("--android", action="store_true", help="the code library for Android's arm64 app")
    ap.add_argument("--cc", default="clang")
    ap.add_argument("-D", dest="defines", action="append", default=[])
    ap.add_argument("-o", "--lib", default="")
    args = ap.parse_args(argv)
    native = native_defines(args.native, args.deterministic)
    suffix = native_suffix(args.native, args.deterministic)
    if args.code_library:
        lib = args.lib or f"mh4u_code{suffix}." + ("dll" if "mingw" in args.cc and not args.android else "so")
        build(args.outdir, args.jobs, args.opt, [*(native or ["RC_PAGED"]), *args.defines], lib, [DLL_GLUE],
              cc=args.cc, extra_flags=["-fvisibility=hidden"], android=args.android)
    elif args.android:
        raise SystemExit("--android goes with --code-library")
    else:
        build(args.outdir, args.jobs, args.opt, [*native, *args.defines], args.lib or "game.so",
              [HARNESS] if args.harness else [], cc=args.cc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
