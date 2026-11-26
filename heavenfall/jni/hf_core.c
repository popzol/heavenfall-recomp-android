/* The app's layer over the patch set's applier: see hf_core.h. Portable C99 over POSIX file descriptors (the host's
 * test build runs it on Windows through the C runtime's equivalents). */
#ifndef _WIN32
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64
#endif
#ifdef __MINGW32__
#define __USE_MINGW_ANSI_STDIO 1    /* (the host test build: printf with the 64-bit and size_t conversions) */
#endif

#include "hf_core.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "dump.h"
#include "sha256.h"

#ifdef _WIN32
#include <direct.h>
#include <io.h>
#define HF_MKDIR(p) _mkdir(p)
#define HF_OPEN_NEW(p) _open((p), _O_WRONLY | _O_CREAT | _O_TRUNC | _O_BINARY, 0666)
#define HF_WRITE(fd, b, n) _write((fd), (b), (unsigned)(n))
#define HF_CLOSE(fd) _close(fd)
#define HF_SYNC(fd) _commit(fd)
#else
#include <unistd.h>
#define HF_MKDIR(p) mkdir((p), 0777)
#define HF_OPEN_NEW(p) open((p), O_WRONLY | O_CREAT | O_TRUNC, 0666)
#define HF_WRITE(fd, b, n) write((fd), (b), (n))
#define HF_CLOSE(fd) close(fd)
#define HF_SYNC(fd) fsync(fd)
#endif

#ifndef EDQUOT
#define EDQUOT ENOSPC               /* (a platform without a quota error: the storage is full either way) */
#endif

#define WRITE_BUFFER (256u * 1024u)      /* the file's bytes go to the storage in blocks of this size */
#define REPORT_EVERY (256u * 1024u)       /* progress is told to the caller at least this many bytes apart */
#define CODE_PATH "exefs/code.bin"
#define EXHEADER_PATH "exheader.bin"

/* ---- reading: a descriptor as the applier's handle ------------------------------------------------------------- */

typedef struct {
    int fd;
    uint64_t size;
    int err;            /* errno of the first read that failed (EIO for a file shorter than asked) */
    int stage;
} HfFile;

static int file_open(HfFile *f, int fd, int stage) {
    struct stat st;
    f->fd = fd;
    f->size = 0;
    f->err = 0;
    f->stage = stage;
    if (fd < 0) return 0;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) {
        f->err = errno ? errno : EINVAL;
        return -1;
    }
    f->size = (uint64_t)st.st_size;
    return 0;
}

/* Reads exactly n bytes at `off`: pread (a descriptor shared with nobody else's offset). */
static int hf_read(void *ctx, void *handle, uint64_t off, void *buf, size_t n) {
    HfFile *f = (HfFile *)handle;
    uint8_t *p = (uint8_t *)buf;
    (void)ctx;
    while (n) {
#ifdef _WIN32
        int got = _lseeki64(f->fd, (__int64)off, SEEK_SET) < 0 ? -1 : _read(f->fd, p, n > 0x40000000u ? 0x40000000u : (unsigned)n);
#else
        ssize_t got = pread(f->fd, p, n, (off_t)off);
#endif
        if (got < 0) {
            if (errno == EINTR) continue;
            if (!f->err) f->err = errno ? errno : EIO;
            return 1;
        }
        if (got == 0) {
            if (!f->err) f->err = EIO;
            return 1;
        }
        p += got;
        off += (uint64_t)got;
        n -= (size_t)got;
    }
    return 0;
}

/* ---- text -------------------------------------------------------------------------------------------------------- */

typedef struct { char *buf; size_t cap, len; } Text;

static void text_add(Text *t, const char *fmt, ...) {
    va_list ap;
    int n;
    if (t->len + 1 >= t->cap) return;
    va_start(ap, fmt);
    n = vsnprintf(t->buf + t->len, t->cap - t->len, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    t->len += (size_t)n < t->cap - t->len ? (size_t)n : t->cap - t->len - 1;
}

static void text_hex(Text *t, const char *key, const uint8_t *bytes, int present) {
    static const char digits[] = "0123456789abcdef";
    char hex[65];
    int i;
    for (i = 0; present && i < 32; i++) {
        hex[2 * i] = digits[bytes[i] >> 4];
        hex[2 * i + 1] = digits[bytes[i] & 15];
    }
    hex[present ? 64 : 0] = 0;
    text_add(t, "%s=%s\n", key, hex);
}

/* A name from the set, as one line of plain characters (the key=value lines have no escapes). */
static void text_plain(Text *t, const char *key, const char *value) {
    char clean[260];
    size_t i;
    for (i = 0; value[i] && i + 1 < sizeof clean; i++)
        clean[i] = (unsigned char)value[i] >= 0x20 && (unsigned char)value[i] < 0x7F ? value[i] : '?';
    clean[i] = 0;
    text_add(t, "%s=%s\n", key, clean);
}

static const char *region_name(int region) {
    return region == REGION_EUR ? "eur" : region == REGION_USA ? "usa" : region == REGION_JPN ? "jpn" : "unknown";
}

size_t hf_identify_text(const HfIdentity *id, char *buf, size_t cap) {
    Text t;
    static const char *const kinds[] = {"none", "other", "game", "update", "xx"};
    t.buf = buf;
    t.cap = cap;
    t.len = 0;
    if (!cap) return 0;
    buf[0] = 0;
    text_add(&t, "rc=%d\nkind=%s\nregion=%s\nencrypted=%d\ntitle=%016llX\n", id->rc, kinds[id->kind],
             region_name(id->region), id->encrypted, (unsigned long long)id->title_id);
    text_hex(&t, "revision", id->revision, id->has_revision);
    return t.len;
}

size_t hf_set_info_text(const HfSetInfo *s, char *buf, size_t cap) {
    Text t;
    t.buf = buf;
    t.cap = cap;
    t.len = 0;
    if (!cap) return 0;
    buf[0] = 0;
    text_add(&t, "rc=%d\nformat=%d\nneeds_newer_app=%d\nregion=%s\n", s->rc, s->format, s->needs_newer_app,
             region_name(s->region));
    text_plain(&t, "edition", s->edition);
    text_plain(&t, "version", s->version);
    text_add(&t, "xx=%d\nxx_title=%016llX\n", s->needs_xx, (unsigned long long)s->xx_title);
    text_hex(&t, "xx_romfs_sha256", s->xx_romfs_hash, s->needs_xx);
    text_add(&t, "shared=%d\nshared_size=%llu\n", s->needs_shared, (unsigned long long)s->shared_size);
    text_hex(&t, "shared_sha256", s->shared_sha256, s->needs_shared);
    text_add(&t, "update=%d\nupdate_title=%016llX\n", s->needs_update, (unsigned long long)s->update_title);
    text_hex(&t, "update_exefs_sha256", s->update_exefs_hash, s->needs_update);
    text_add(&t, "cheats=%d\nfiles=%u\n", (s->flags & PS_FLAG_CHEATS) != 0, s->files);
    return t.len;
}

size_t hf_result_text(const HfResult *r, char *buf, size_t cap) {
    Text t;
    t.buf = buf;
    t.cap = cap;
    t.len = 0;
    if (!cap) return 0;
    buf[0] = 0;
    text_add(&t, "rc=%d\nstage=%d\nerrno=%d\nsource=%d\n", r->rc, r->stage, r->err, r->source_kind);
    text_plain(&t, "path", r->path);
    text_add(&t, "files=%u\nbytes=%llu\n", r->files, (unsigned long long)r->bytes);
    text_hex(&t, "code_sha256", r->code_sha256, r->has_code_sha256);
    return t.len;
}

const char *hf_error_text(int rc) {
    switch (rc) {
    case HF_E_NEWER: return "the set is of a newer format than this app reads";
    case HF_E_NOT_ANDROID: return "the set is not one for the recompiled game";
    case HF_E_INCOMPLETE: return "the set has no executable or no exheader";
    case HF_E_NO_XX: return "the set needs MH XX and none was given";
    case HF_E_NO_SHARED: return "the set needs the release's shared file and none was given";
    case HF_E_READ: return "a file could not be read";
    case HF_E_WRITE: return "a file of the mod could not be written";
    case HF_E_SPACE: return "the storage is full";
    default: return title_error_text(rc);
    }
}

/* ---- what a file is ---------------------------------------------------------------------------------------------- */

static void identity_of(HfIdentity *id, uint64_t title, int encrypted) {
    id->title_id = title;
    id->encrypted = encrypted;
    id->kind = title == DUMP_ID_XX ? HF_KIND_XX : HF_KIND_GAME;
    id->region = title == DUMP_ID_XX ? REGION_JPN : title == DUMP_ID_USA ? REGION_USA : REGION_EUR;
}

void hf_identify(int fd, HfIdentity *id) {
    HfFile f;
    Dump upd;
    uint64_t title = 0;
    int rc;
    memset(id, 0, sizeof *id);
    id->region = -1;
    if (file_open(&f, fd, HF_STAGE_GAME) || fd < 0) {
        id->rc = TITLE_E_IO;
        return;
    }
    rc = dump_identify(hf_read, NULL, &f, &title, NULL);
    if (rc == TITLE_OK || rc == TITLE_E_ENCRYPTED) {
        identity_of(id, title, rc == TITLE_E_ENCRYPTED);
        id->rc = TITLE_OK;
        if (rc == TITLE_OK && dump_romfs_hash(hf_read, NULL, &f, id->revision) == TITLE_OK) id->has_revision = 1;
        return;
    }
    id->rc = rc;
    id->kind = HF_KIND_NONE;
    if (rc != TITLE_E_GAME) return;                      /* not a container of a game at all, or unreadable */
    id->kind = HF_KIND_OTHER;
    rc = dump_open_update(&upd, hf_read, NULL, &f);      /* MH4U's update? (the reader above takes only games) */
    if (rc == TITLE_OK || rc == TITLE_E_ENCRYPTED) {
        id->kind = HF_KIND_UPDATE;
        id->rc = TITLE_OK;
        id->title_id = upd.title_id;
        id->region = upd.region;
        id->encrypted = rc == TITLE_E_ENCRYPTED;
        if (rc == TITLE_OK) {
            memcpy(id->revision, upd.header + DUMP_EXEFS_HASH, 32);
            id->has_revision = 1;
            dump_close(&upd);
        }
    } else if (rc != TITLE_E_NOT_UPDATE) {
        id->rc = rc;
    }
}

/* ---- a set's head ------------------------------------------------------------------------------------------------ */

void hf_set_info(int fd, HfSetInfo *info) {
    HfFile f;
    Patchset *ps;
    uint8_t head[8];
    int rc;
    memset(info, 0, sizeof *info);
    info->region = -1;
    if (file_open(&f, fd, HF_STAGE_SET) || fd < 0) {
        info->rc = TITLE_E_IO;
        return;
    }
    if (f.size >= sizeof head && !hf_read(NULL, &f, 0, head, sizeof head) && !memcmp(head, "HFPS", 4) &&
        get_le32(head + 4) > PS_FORMAT_UPDATE) {   /* a set of a format this applier cannot read: said, not opened */
        info->format = (int)get_le32(head + 4);
        info->needs_newer_app = 1;
        return;
    }
    rc = patchset_open(&ps, hf_read, NULL, &f, f.size, 0);
    if (rc) {
        info->rc = rc == TITLE_E_IO && f.err ? HF_E_READ : rc;
        return;
    }
    info->format = patchset_format(ps);
    info->region = patchset_region(ps);
    strncpy(info->edition, patchset_edition(ps), sizeof info->edition - 1);
    strncpy(info->version, patchset_version(ps), sizeof info->version - 1);
    info->needs_xx = patchset_second_game(ps, &info->xx_title, info->xx_romfs_hash);
    info->needs_shared = patchset_shared(ps, info->shared_sha256, &info->shared_size);
    info->needs_update = patchset_update(ps, &info->update_title, info->update_exefs_hash);
    info->flags = patchset_flags(ps);
    info->files = (unsigned)patchset_file_count(ps);
    patchset_close(ps);
}

/* ---- applying ---------------------------------------------------------------------------------------------------- */

typedef struct {
    const HfApplyArgs *args;
    HfResult *res;
    uint64_t total, done_base;       /* the bar: every pass's planned bytes, and what the passes before this one took */
    uint64_t last_report;
    int cancelled;
    /* the file being written */
    int fd;
    uint8_t *buf;
    size_t used;
    int werr;                        /* errno of the first write that failed */
    uint64_t file_planned;
    int hashing;
    Sha256 sha;
    uint64_t written;
} Run;

static int report(Run *r, uint64_t done, int force) {
    if (done > r->total) done = r->total;
    if (!force && done < r->last_report + REPORT_EVERY && done != r->total) return r->cancelled;
    r->last_report = done;
    if (r->args->progress && r->args->progress(r->args->progress_ctx, done, r->total)) r->cancelled = 1;
    return r->cancelled;
}

static int verify_progress(void *ctx, uint64_t at, uint64_t size) {
    Run *r = (Run *)ctx;
    (void)size;
    return report(r, r->done_base + at, 0);
}

static int flush_buffer(Run *r) {
    const uint8_t *p = r->buf;
    size_t left = r->used;
    while (left) {
        int n = (int)HF_WRITE(r->fd, p, left);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (!r->werr) r->werr = errno ? errno : EIO;
            return 1;
        }
        if (n == 0) {
            if (!r->werr) r->werr = EIO;
            return 1;
        }
        p += n;
        left -= (size_t)n;
    }
    r->used = 0;
    return 0;
}

static int sink_write(void *ctx, const void *data, size_t n) {
    Run *r = (Run *)ctx;
    const uint8_t *p = (const uint8_t *)data;
    if (r->hashing) sha256_update(&r->sha, p, n);
    r->written += n;
    while (n) {
        size_t room = WRITE_BUFFER - r->used, take = n < room ? n : room;
        memcpy(r->buf + r->used, p, take);
        r->used += take;
        p += take;
        n -= take;
        if (r->used == WRITE_BUFFER && flush_buffer(r)) return 1;
    }
    return 0;
}

static int sink_progress(void *ctx, uint64_t done, uint64_t total) {
    Run *r = (Run *)ctx;
    (void)total;
    if (done > r->file_planned) done = r->file_planned;
    return report(r, r->done_base + done, 0);
}

/* The file's path as the set names it: relative, "/" separated, no empty, "." or ".." component, no backslash, colon
 * or control byte. The applier checks it for a format 4 set; this is the same rule once more where the file is made. */
static int path_ok(const char *p) {
    size_t i, start = 0;
    if (!*p) return 0;
    for (i = 0;; i++) {
        unsigned char c = (unsigned char)p[i];
        if (c && c != '/') {
            if (c < 0x20 || c == 0x7F || c == '\\' || c == ':') return 0;
            continue;
        }
        if (i == start || (p[start] == '.' && (i - start == 1 || (i - start == 2 && p[start + 1] == '.')))) return 0;
        if (!c) return 1;
        start = i + 1;
    }
}

/* out_dir/path, its folders made. 0, or the errno. */
static int make_path(char *name, size_t cap, const char *out_dir, const char *path) {
    size_t n = strlen(out_dir), at;
    if (n + 1 + strlen(path) + 1 > cap) return ENAMETOOLONG;
    memcpy(name, out_dir, n);
    name[n] = '/';
    strcpy(name + n + 1, path);
    for (at = n + 1; name[at]; at++) {
        if (name[at] != '/') continue;
        name[at] = 0;
        if (HF_MKDIR(name) && errno != EEXIST) return errno;
        name[at] = '/';
    }
    return 0;
}

static void fail(HfResult *res, int rc, int stage) {
    res->rc = rc;
    res->stage = stage;
}

typedef struct {
    HfFile set, game, update, xx, shared;
    Patchset *ps;
    Dump game_dump, update_dump, xx_dump;
    int have_game, have_update, have_xx;
} Inputs;

static int stage_of_source(uint32_t kind) {
    return kind == PS_SRC_FILE ? HF_STAGE_GAME : kind == PS_SRC_SECOND ? HF_STAGE_XX
         : kind == PS_SRC_SHARED ? HF_STAGE_SHARED : HF_STAGE_UPDATE;
}

/* A read that failed is the file's, not the applier's: the code says so and the stage names whose file. */
static void settle_read_error(const Inputs *in, HfResult *res) {
    const HfFile *files[5];
    size_t i;
    files[0] = &in->set;
    files[1] = &in->game;
    files[2] = &in->update;
    files[3] = &in->xx;
    files[4] = &in->shared;
    if (res->rc != TITLE_E_IO) return;
    for (i = 0; i < 5; i++)
        if (files[i]->err) {
            res->rc = HF_E_READ;
            res->stage = files[i]->stage;
            res->err = files[i]->err;
            return;
        }
}

/* The set, the dumps, the shared file: opened and bound, nothing written. */
static int open_inputs(const HfApplyArgs *a, Inputs *in, HfResult *res) {
    uint8_t head[8];
    uint64_t shared_size;
    uint8_t shared_sha[32];
    int rc;
    if (file_open(&in->set, a->set_fd, HF_STAGE_SET) || a->set_fd < 0 || file_open(&in->game, a->game_fd, HF_STAGE_GAME) ||
        a->game_fd < 0 || file_open(&in->update, a->update_fd, HF_STAGE_UPDATE) ||
        file_open(&in->xx, a->xx_fd, HF_STAGE_XX) || file_open(&in->shared, a->shared_fd, HF_STAGE_SHARED)) {
        const HfFile *bad = in->set.err ? &in->set : in->game.err ? &in->game : in->update.err ? &in->update
                          : in->xx.err ? &in->xx : in->shared.err ? &in->shared : NULL;
        fail(res, bad ? HF_E_READ : TITLE_E_ARG, bad ? bad->stage : HF_STAGE_SET);
        res->err = bad ? bad->err : 0;
        return 1;
    }
    if (in->set.size >= sizeof head && !hf_read(NULL, &in->set, 0, head, sizeof head) && !memcmp(head, "HFPS", 4) &&
        get_le32(head + 4) > PS_FORMAT_UPDATE) {
        fail(res, HF_E_NEWER, HF_STAGE_SET);
        return 1;
    }
    rc = patchset_open(&in->ps, hf_read, NULL, &in->set, in->set.size, 1);
    if (rc) {
        fail(res, rc, HF_STAGE_SET);
        return 1;
    }
    if (patchset_format(in->ps) != PS_FORMAT_UPDATE) {
        fail(res, HF_E_NOT_ANDROID, HF_STAGE_SET);
        return 1;
    }
    if ((rc = dump_open(&in->game_dump, hf_read, NULL, &in->game))) {
        fail(res, rc, HF_STAGE_GAME);
        return 1;
    }
    in->have_game = 1;
    if ((rc = patchset_bind_dump(in->ps, &in->game_dump))) {
        fail(res, rc, HF_STAGE_GAME);
        return 1;
    }
    if (a->update_fd >= 0) {
        if ((rc = dump_open_update(&in->update_dump, hf_read, NULL, &in->update))) {
            fail(res, rc, HF_STAGE_UPDATE);
            return 1;
        }
        in->have_update = 1;
    }
    if ((rc = patchset_bind_update(in->ps, in->have_update ? &in->update_dump : NULL))) {
        fail(res, rc, HF_STAGE_UPDATE);
        return 1;
    }
    if (patchset_second_game(in->ps, NULL, NULL)) {
        if (a->xx_fd < 0) {
            fail(res, HF_E_NO_XX, HF_STAGE_XX);
            return 1;
        }
        if ((rc = dump_open(&in->xx_dump, hf_read, NULL, &in->xx))) {
            fail(res, rc, HF_STAGE_XX);
            return 1;
        }
        in->have_xx = 1;
        if ((rc = patchset_bind_second_dump(in->ps, &in->xx_dump))) {
            fail(res, rc, HF_STAGE_XX);
            return 1;
        }
    }
    if (patchset_shared(in->ps, shared_sha, &shared_size)) {
        if (a->shared_fd < 0) {
            fail(res, HF_E_NO_SHARED, HF_STAGE_SHARED);
            return 1;
        }
        if ((rc = patchset_bind_shared(in->ps, &in->shared, in->shared.size))) {
            fail(res, rc, HF_STAGE_SHARED);
            return 1;
        }
    }
    return 0;
}

/* The shared file as a whole against the hash the set names, then every source file of the dumps against its recorded
 * hash. `total`: the bar's length. */
static int verify_inputs(Run *r, Inputs *in) {
    HfResult *res = r->res;
    uint8_t want[32], digest[32];
    uint64_t size;
    size_t i, n = patchset_source_count(in->ps);
    int rc;
    if (patchset_shared(in->ps, want, &size)) {
        Sha256 sha;
        uint8_t *chunk = (uint8_t *)malloc(65536);
        uint64_t at;
        if (!chunk) {
            fail(res, TITLE_E_MEM, HF_STAGE_SHARED);
            return 1;
        }
        sha256_init(&sha);
        for (at = 0; at < in->shared.size;) {
            size_t take = in->shared.size - at < 65536 ? (size_t)(in->shared.size - at) : 65536;
            if (hf_read(NULL, &in->shared, at, chunk, take)) {
                free(chunk);
                fail(res, HF_E_READ, HF_STAGE_SHARED);
                res->err = in->shared.err;
                return 1;
            }
            sha256_update(&sha, chunk, take);
            at += take;
            if (report(r, r->done_base + at, 0)) {
                free(chunk);
                fail(res, TITLE_E_CANCEL, HF_STAGE_SHARED);
                return 1;
            }
        }
        free(chunk);
        sha256_final(&sha, digest);
        if (memcmp(digest, want, 32)) {
            fail(res, TITLE_E_HASH, HF_STAGE_SHARED);
            res->source_kind = PS_SRC_SHARED;
            return 1;
        }
        r->done_base += in->shared.size;
    }
    for (i = 0; r->args->verify_sources && i < n; i++) {
        PatchsetSourceInfo info;
        patchset_source_info(in->ps, i, &info);
        if (info.kind == PS_SRC_SHARED) continue;          /* covered by the whole file's hash */
        rc = patchset_verify_source(in->ps, i, verify_progress, r);
        if (rc) {
            fail(res, rc, stage_of_source(info.kind));
            if (rc == TITLE_E_HASH) res->source_kind = (int)info.kind;
            return 1;
        }
        r->done_base += info.size;
    }
    return 0;
}

static uint64_t verify_bytes(const HfApplyArgs *a, const Inputs *in) {
    uint64_t total = 0, size;
    size_t i;
    if (patchset_shared(in->ps, NULL, &size)) total += in->shared.size;
    for (i = 0; a->verify_sources && i < patchset_source_count(in->ps); i++) {
        PatchsetSourceInfo info;
        patchset_source_info(in->ps, i, &info);
        if (info.kind != PS_SRC_SHARED) total += info.size;
    }
    return total;
}

static int write_files(Run *r, Inputs *in) {
    HfResult *res = r->res;
    size_t i, n = patchset_file_count(in->ps);
    char name[1024];
    for (i = 0; i < n; i++) {
        PatchsetFileInfo info;
        TitleOut out;
        int rc, err;
        patchset_file_info(in->ps, i, &info);
        strncpy(res->path, info.path, sizeof res->path - 1);
        if (!path_ok(info.path)) {
            fail(res, TITLE_E_PATH, HF_STAGE_WRITE);
            return 1;
        }
        if ((err = make_path(name, sizeof name, r->args->out_dir, info.path))) {
            fail(res, err == ENOSPC || err == EDQUOT ? HF_E_SPACE : HF_E_WRITE, HF_STAGE_WRITE);
            res->err = err;
            return 1;
        }
        r->fd = HF_OPEN_NEW(name);
        if (r->fd < 0) {
            fail(res, errno == ENOSPC || errno == EDQUOT ? HF_E_SPACE : HF_E_WRITE, HF_STAGE_WRITE);
            res->err = errno;
            return 1;
        }
        r->used = 0;
        r->werr = 0;
        r->written = 0;
        r->file_planned = info.size;
        r->hashing = !strcmp(info.path, CODE_PATH);
        if (r->hashing) sha256_init(&r->sha);
        out.write = sink_write;
        out.ctx = r;
        out.progress = sink_progress;
        out.progress_ctx = r;
        rc = patchset_apply_file(in->ps, i, &out, PS_VERIFY_OUTPUT);
        if (!rc && flush_buffer(r)) rc = TITLE_E_IO;
        if (!rc && HF_SYNC(r->fd)) {
            r->werr = errno ? errno : EIO;
            rc = TITLE_E_IO;
        }
        if (HF_CLOSE(r->fd) && !rc) {
            r->werr = errno ? errno : EIO;
            rc = TITLE_E_IO;
        }
        r->fd = -1;
        if (rc) {
            if (rc == TITLE_E_IO && r->werr) {
                fail(res, r->werr == ENOSPC || r->werr == EDQUOT ? HF_E_SPACE : HF_E_WRITE, HF_STAGE_WRITE);
                res->err = r->werr;
            } else {
                fail(res, rc, HF_STAGE_WRITE);
            }
            return 1;
        }
        if (r->hashing) {
            sha256_final(&r->sha, res->code_sha256);
            res->has_code_sha256 = 1;
        }
        res->files++;
        res->bytes += r->written;
        r->done_base += info.size;
        report(r, r->done_base, 1);
        if (r->cancelled && i + 1 < n) {
            fail(res, TITLE_E_CANCEL, HF_STAGE_WRITE);
            return 1;
        }
    }
    return 0;
}

static int has_file(const Patchset *ps, const char *path) {
    size_t i;
    for (i = 0; i < patchset_file_count(ps); i++) {
        PatchsetFileInfo info;
        patchset_file_info(ps, i, &info);
        if (!strcmp(info.path, path)) return 1;
    }
    return 0;
}

void hf_apply(const HfApplyArgs *args, HfResult *res) {
    Inputs *in = (Inputs *)calloc(1, sizeof *in);
    Run run;
    memset(res, 0, sizeof *res);
    res->source_kind = -1;
    if (!in || !args || !args->out_dir) {
        fail(res, in ? TITLE_E_ARG : TITLE_E_MEM, HF_STAGE_SET);
        free(in);
        return;
    }
    memset(&run, 0, sizeof run);
    run.args = args;
    run.res = res;
    run.fd = -1;
    run.buf = (uint8_t *)malloc(WRITE_BUFFER);
    if (!run.buf) {
        fail(res, TITLE_E_MEM, HF_STAGE_WRITE);
        free(in);
        return;
    }
    if (!open_inputs(args, in, res)) {
        if (!has_file(in->ps, CODE_PATH) || !has_file(in->ps, EXHEADER_PATH)) {
            fail(res, HF_E_INCOMPLETE, HF_STAGE_SET);
        } else {
            size_t i;
            uint64_t planned = verify_bytes(args, in);
            for (i = 0; i < patchset_file_count(in->ps); i++) {
                PatchsetFileInfo info;
                patchset_file_info(in->ps, i, &info);
                planned += info.size;
            }
            run.total = planned ? planned : 1;
            if (report(&run, 0, 1)) fail(res, TITLE_E_CANCEL, HF_STAGE_SET);
            else if (!verify_inputs(&run, in)) write_files(&run, in);
        }
    }
    settle_read_error(in, res);
    if (run.fd >= 0) HF_CLOSE(run.fd);
    free(run.buf);
    if (in->ps) patchset_close(in->ps);
    if (in->have_game) dump_close(&in->game_dump);
    if (in->have_update) dump_close(&in->update_dump);
    if (in->have_xx) dump_close(&in->xx_dump);
    free(in);
}
