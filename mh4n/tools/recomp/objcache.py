"""The compiled objects' cache, kept across builds and across mod versions: an object is found by what it is compiled
from (its file's name and C, the headers it includes, the flags and the compiler), so the same unit is never compiled
twice, whatever build or mod it comes from. Bounded by the disk's free space, the least recently used dropped first.

    <root>/objects/<k[:2]>/<k>.o      the object (hard-linked with the build's copy where the disk allows it)
    <root>/objects/<k[:2]>/<k>.sum    its SHA-256 and size: an object that doesn't match is not used, and goes
    <root>/objects/<k[:2]>/<k>.parts  a unit compiled one function per file (recomp/jobs.py): its parts' names and keys
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess

BIG = 128 << 20   # a file up to this size is hashed whole; a larger one (a compiler) by its size, date and two ends
END = 1 << 20


def file_digest(path: str) -> str:
    st = os.stat(path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        if st.st_size <= BIG:
            for block in iter(lambda: f.read(1 << 20), b""):
                h.update(block)
            return f"{st.st_size} {h.hexdigest()}"
        h.update(f.read(END))
        f.seek(-END, os.SEEK_END)
        h.update(f.read(END))
    return f"{st.st_size} {st.st_mtime_ns} {h.hexdigest()}"


def compiler_id(cc: str) -> str:
    """What the compiler is: its `--version`, its file and, for the app's launcher of Zig (BUILDER_ZIG), Zig's."""
    found = cc if os.path.isfile(cc) else shutil.which(cc)
    parts = [f"cc {file_digest(found) if found else cc}"]
    zig = os.environ.get("BUILDER_ZIG")
    if zig and os.path.isfile(zig):
        parts.append(f"zig {file_digest(zig)}")
    try:
        r = subprocess.run([cc, "--version"], capture_output=True, text=True, timeout=120)
        parts.append(r.stdout.strip().splitlines()[0] if r.stdout.strip() else f"exit {r.returncode}")
    except (OSError, subprocess.SubprocessError) as e:
        parts.append(f"no version ({e})")
    return "\n".join(parts)


def headers_id(folder: str) -> str:
    """Every header under `folder` (the runtime's), by name and content."""
    h = hashlib.sha256()
    for root, dirs, names in os.walk(folder):
        dirs.sort()
        for n in sorted(names):
            if n.endswith(".h"):
                path = os.path.join(root, n)
                h.update(os.path.relpath(path, folder).replace(os.sep, "/").encode() + b"\0")
                with open(path, "rb") as f:
                    h.update(hashlib.sha256(f.read().replace(b"\r", b"")).digest())
    return h.hexdigest()


def key(name: str, source: bytes, includes: list[bytes], flags: list[str], compiler: str, headers: str) -> str:
    """An object's key: its file's name (the object records it) and C, the contents of the files of the build's own
    folder it includes, the flags (with the folders' paths taken out: `includes` and `headers` hold what they give),
    the compiler and the runtime's headers."""
    h = hashlib.sha256()
    h.update(b"name\0" + name.encode() + b"\0")
    h.update(hashlib.sha256(source).digest())
    for data in includes:
        h.update(b"include\0" + hashlib.sha256(data).digest())
    h.update(b"flags\0" + "\0".join(flags).encode() + b"\0")
    h.update(b"compiler\0" + compiler.encode() + b"\0")
    h.update(b"headers\0" + headers.encode())
    return h.hexdigest()


def _place(src: str, dest: str) -> None:
    """dest becomes a copy of src: a hard link where the disk allows it (no second copy), else a copy written beside
    it and renamed. (A file already at dest is removed first: a compile writes a new file, never into a linked one.)"""
    if os.path.lexists(dest):
        os.remove(dest)
    try:
        os.link(src, dest)
        return
    except OSError:
        pass
    part = dest + ".part"
    shutil.copyfile(src, part)
    os.replace(part, dest)


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class ObjectCache:
    def __init__(self, root: str, keep_free_mb: float, max_mb: float, floor_mb: float):
        self.root = root
        self.objects = os.path.join(root, "objects")
        os.makedirs(self.objects, exist_ok=True)
        self.keep_free = int(keep_free_mb * (1 << 20))
        self.max = int(max_mb * (1 << 20))
        self.floor = int(floor_mb * (1 << 20))
        self.hits = self.misses = self.dropped_bad = 0

    def _path(self, k: str, ext: str) -> str:
        return os.path.join(self.objects, k[:2], k + ext)

    def get(self, k: str, dest: str) -> bool:
        """The object of key k placed at dest, when the cache has it whole; an entry that doesn't match its sum goes."""
        obj, total = self._path(k, ".o"), self._path(k, ".sum")
        try:
            with open(total, encoding="ascii") as f:
                want_sum, want_size = f.read().split()
            ok = os.path.getsize(obj) == int(want_size) and _sha256(obj) == want_sum
        except (OSError, ValueError):
            ok = False
        if not ok:
            if os.path.exists(obj) or os.path.exists(total):
                self.dropped_bad += 1
                self._drop(k)
            self.misses += 1
            return False
        _place(obj, dest)
        os.utime(obj)   # (its last use: the oldest go first)
        self.hits += 1
        return True

    def put(self, k: str, src: str) -> None:
        """The object at src kept under key k (its sum written last: an entry cut short has none and is not used)."""
        os.makedirs(os.path.join(self.objects, k[:2]), exist_ok=True)
        obj, total = self._path(k, ".o"), self._path(k, ".sum")
        if os.path.exists(total):
            os.remove(total)
        _place(src, obj)
        with open(total + ".part", "w", encoding="ascii") as f:
            f.write(f"{_sha256(obj)} {os.path.getsize(obj)}\n")
        os.replace(total + ".part", total)

    def get_parts(self, k: str) -> list[tuple[str, str]] | None:
        try:
            with open(self._path(k, ".parts"), encoding="utf-8") as f:
                return [(n, pk) for n, pk in json.load(f)]
        except (OSError, ValueError, TypeError):
            return None

    def put_parts(self, k: str, parts: list[tuple[str, str]]) -> None:
        os.makedirs(os.path.join(self.objects, k[:2]), exist_ok=True)
        path = self._path(k, ".parts")
        with open(path + ".part", "w", encoding="utf-8") as f:
            json.dump(parts, f)
        os.replace(path + ".part", path)

    def _drop(self, k: str) -> None:
        for ext in (".sum", ".o", ".parts"):
            try:
                os.remove(self._path(k, ext))
            except OSError:
                pass

    def entries(self) -> list[tuple[float, int, str]]:
        """(last use, bytes, key) of every object, oldest first."""
        out = []
        for d in os.listdir(self.objects):
            folder = os.path.join(self.objects, d)
            if not os.path.isdir(folder):
                continue
            for n in os.listdir(folder):
                if n.endswith(".o"):
                    st = os.stat(os.path.join(folder, n))
                    out.append((st.st_mtime, st.st_size, n[:-2]))
        return sorted(out)

    def size(self) -> int:
        return sum(n for _, n, _ in self.entries())

    def limit(self, free: int, size: int) -> int:
        """How much the cache may hold, with `free` bytes free on its disk now and `size` bytes in it: at most max,
        and no more than leaves keep_free of the disk free, but never under its floor."""
        return max(self.floor, min(self.max, size + free - self.keep_free))

    def shrink(self, wanted: int) -> tuple[int, int]:
        """The oldest objects dropped until `wanted` bytes are freed or the cache is down to its floor (room for a
        build); returns (objects dropped, bytes)."""
        entries = self.entries()
        size = sum(n for _, n, _ in entries)
        return self._drop_oldest(entries, size, max(self.floor, size - wanted))

    def trim(self, free: int | None = None) -> tuple[int, int]:
        """The oldest objects dropped until the cache is within its limit; returns (objects dropped, bytes)."""
        if free is None:
            free = shutil.disk_usage(self.root).free
        entries = self.entries()
        size = sum(n for _, n, _ in entries)
        return self._drop_oldest(entries, size, self.limit(free, size))

    def _drop_oldest(self, entries: list[tuple[float, int, str]], size: int, limit: int) -> tuple[int, int]:
        dropped = freed = 0
        for _, n, k in entries:
            if size - freed <= limit:
                break
            self._drop(k)
            dropped += 1
            freed += n
        return dropped, freed
