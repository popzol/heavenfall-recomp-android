/* The native layer's tests (hf_core.c), built with the host's gcc by run_tests.sh: `hf_test <fixtures> <work>`.
 * The fixtures are synthetic (make_fixtures.py): a made-up game, update, MH XX and the sets made from them, with the
 * tree each set must make in expected.txt. Each test names the break it catches and prints PASS or FAIL; the exit
 * code is the number of failed tests. */
#ifdef __MINGW32__
#define __USE_MINGW_ANSI_STDIO 1
#endif
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

#ifdef _WIN32
#include <direct.h>
#include <io.h>
#define MKDIR(p) _mkdir(p)
#define OPEN_READ(p) _open((p), _O_RDONLY | _O_BINARY)
#define CLOSE(fd) _close(fd)
#else
#include <unistd.h>
#define MKDIR(p) mkdir((p), 0777)
#define OPEN_READ(p) open((p), O_RDONLY)
#define CLOSE(fd) close(fd)
#endif

#include "hf_core.h"
#include "sha256.h"

static const char *fixtures, *work;
static int failures, checks_failed;

#define CHECK(cond) do { if (!(cond)) { checks_failed++; printf("    check failed: %s (line %d)\n", #cond, __LINE__); } } while (0)

static void begin(void) { checks_failed = 0; }
static void end(const char *name) {
    printf("%s  %s\n", checks_failed ? "FAIL" : "PASS", name);
    if (checks_failed) failures++;
}

/* ---- files ------------------------------------------------------------------------------------------------------- */

static void path_of(char *out, size_t cap, const char *dir, const char *name) { snprintf(out, cap, "%s/%s", dir, name); }

static uint8_t *slurp(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    uint8_t *data;
    long n;
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    n = ftell(f);
    fseek(f, 0, SEEK_SET);
    data = (uint8_t *)malloc((size_t)n + 1);
    if (data && fread(data, 1, (size_t)n, f) != (size_t)n) { free(data); data = NULL; }
    fclose(f);
    if (len) *len = (size_t)n;
    return data;
}

static int fixture(const char *name) {
    char path[1024];
    int fd;
    path_of(path, sizeof path, fixtures, name);
    fd = OPEN_READ(path);
    if (fd < 0) printf("    cannot open %s\n", path);
    return fd;
}

/* A copy of a fixture in the work folder with one byte flipped (offset < 0: with the first `-offset` bytes kept only). */
static int copy_of(const char *name, long flip, long keep, const char *as) {
    char from[1024], to[1024];
    size_t len;
    uint8_t *data;
    FILE *f;
    path_of(from, sizeof from, fixtures, name);
    path_of(to, sizeof to, work, as);
    data = slurp(from, &len);
    if (!data) return -1;
    if (flip >= 0) data[flip] ^= 0x55;
    if (keep >= 0 && (size_t)keep < len) len = (size_t)keep;
    f = fopen(to, "wb");
    fwrite(data, 1, len, f);
    fclose(f);
    free(data);
    return OPEN_READ(to);
}

static void sha_hex(const uint8_t *data, size_t n, char hex[65]) {
    uint8_t digest[32];
    int i;
    sha256_buf(data, n, digest);
    for (i = 0; i < 32; i++) sprintf(hex + 2 * i, "%02x", digest[i]);
}

static void hex_of(const uint8_t *digest, char hex[65]) {
    int i;
    for (i = 0; i < 32; i++) sprintf(hex + 2 * i, "%02x", digest[i]);
}

static void rmtree(const char *path) {
    DIR *d = opendir(path);
    struct dirent *e;
    if (!d) { remove(path); return; }
    while ((e = readdir(d))) {
        char sub[1024];
        struct stat st;
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        path_of(sub, sizeof sub, path, e->d_name);
        if (!stat(sub, &st) && S_ISDIR(st.st_mode)) rmtree(sub); else remove(sub);
    }
    closedir(d);
#ifdef _WIN32
    _rmdir(path);
#else
    rmdir(path);
#endif
}

/* A new empty folder `work/<name>/out` (the output) inside `work/<name>` (which must hold nothing else afterwards). */
static void fresh(const char *name, char *case_dir, char *out_dir) {
    path_of(case_dir, 1024, work, name);
    rmtree(case_dir);
    MKDIR(case_dir);
    path_of(out_dir, 1024, case_dir, "out");
    MKDIR(out_dir);
}

static int count_entries(const char *dir) {
    DIR *d = opendir(dir);
    struct dirent *e;
    int n = 0;
    if (!d) return -1;
    while ((e = readdir(d))) if (strcmp(e->d_name, ".") && strcmp(e->d_name, "..")) n++;
    closedir(d);
    return n;
}

/* ---- expected.txt ------------------------------------------------------------------------------------------------ */

typedef struct { char set[32], path[256]; unsigned long long size; char sha[65]; } Tree;
static Tree trees[128];
static int ntrees;
static char code_of[8][2][80];
static int ncode;
static long flip_offset;
static char update_exefs[80];

static void load_expected(void) {
    char path[1024], line[1024];
    FILE *f;
    path_of(path, sizeof path, fixtures, "expected.txt");
    f = fopen(path, "r");
    if (!f) { printf("no %s\n", path); exit(100); }
    while (fgets(line, sizeof line, f)) {
        char kind[16] = "", a[256] = "", b[256] = "", c[80] = "", d[80] = "";
        int n = sscanf(line, "%15s %255s %255s %79s %79s", kind, a, b, c, d);
        if (n == 5 && !strcmp(kind, "tree")) {          /* tree <set> <path> <size> <sha256> */
            Tree *t = &trees[ntrees++];
            strcpy(t->set, a); strcpy(t->path, b); t->size = strtoull(c, NULL, 10); strcpy(t->sha, d);
        } else if (n == 3 && !strcmp(kind, "code")) {   /* code <set> <sha256> */
            strcpy(code_of[ncode][0], a); strcpy(code_of[ncode][1], b); ncode++;
        } else if (n == 3 && !strcmp(kind, "flip")) {   /* flip <dump> <offset> */
            flip_offset = strtol(b, NULL, 10);
        } else if (n == 3 && !strcmp(kind, "sha256") && !strcmp(a, "update_exefs")) {
            strcpy(update_exefs, b);
        }
    }
    fclose(f);
}

static const char *expected_code(const char *set) {
    int i;
    for (i = 0; i < ncode; i++) if (!strcmp(code_of[i][0], set)) return code_of[i][1];
    return "";
}

/* ---- walking a made tree ----------------------------------------------------------------------------------------- */

typedef struct { char path[256]; } Seen;
static Seen seen[256];
static int nseen;

static void walk(const char *root, const char *rel) {
    char dir[1024];
    DIR *d;
    struct dirent *e;
    if (*rel) path_of(dir, sizeof dir, root, rel); else snprintf(dir, sizeof dir, "%s", root);
    d = opendir(dir);
    if (!d) return;
    while ((e = readdir(d))) {
        char sub[1024], subrel[300];
        struct stat st;
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        path_of(sub, sizeof sub, dir, e->d_name);
        if (*rel) snprintf(subrel, sizeof subrel, "%s/%s", rel, e->d_name); else snprintf(subrel, sizeof subrel, "%s", e->d_name);
        if (!stat(sub, &st) && S_ISDIR(st.st_mode)) walk(root, subrel);
        else if (nseen < 256) strcpy(seen[nseen++].path, subrel);
    }
    closedir(d);
}

/* `out` holds exactly the files `set` is expected to make, each of its size and SHA-256. */
static void check_tree(const char *out, const char *set) {
    int i, want = 0, j;
    nseen = 0;
    walk(out, "");
    for (i = 0; i < ntrees; i++) {
        char file[1024], hex[65];
        size_t len;
        uint8_t *data;
        if (strcmp(trees[i].set, set)) continue;
        want++;
        path_of(file, sizeof file, out, trees[i].path);
        data = slurp(file, &len);
        CHECK(data != NULL);
        if (!data) continue;
        sha_hex(data, len, hex);
        if (len != trees[i].size || strcmp(hex, trees[i].sha)) printf("    differs: %s (%zu bytes)\n", trees[i].path, len);
        CHECK(len == trees[i].size && !strcmp(hex, trees[i].sha));
        free(data);
        for (j = 0; j < nseen; j++) if (!strcmp(seen[j].path, trees[i].path)) seen[j].path[0] = 0;
    }
    for (j = 0; j < nseen; j++) if (seen[j].path[0]) { printf("    unexpected file: %s\n", seen[j].path); checks_failed++; }
    CHECK(want > 0);
}

/* ---- a run ------------------------------------------------------------------------------------------------------- */

typedef struct { long calls, cancel_at; uint64_t last_done, total; int decreased, over; } Watch;

static int watch_progress(void *ctx, uint64_t done, uint64_t total) {
    Watch *w = (Watch *)ctx;
    w->calls++;
    if (done < w->last_done) w->decreased = 1;
    if (done > total) w->over = 1;
    w->last_done = done;
    w->total = total;
    return w->calls == w->cancel_at;
}

typedef struct { const char *set, *game, *update, *xx, *shared; int verify; long cancel_at; } Inputs;

/* Opens the named fixtures (NULL: none, -1) and applies. The descriptors are closed afterwards. */
static HfResult run_apply(const Inputs *in, const char *out, Watch *watch) {
    HfApplyArgs a;
    HfResult res;
    int fds[5], i;
    const char *names[5];
    names[0] = in->set; names[1] = in->game; names[2] = in->update; names[3] = in->xx; names[4] = in->shared;
    for (i = 0; i < 5; i++) fds[i] = names[i] ? fixture(names[i]) : -1;
    memset(&a, 0, sizeof a);
    a.set_fd = fds[0]; a.game_fd = fds[1]; a.update_fd = fds[2]; a.xx_fd = fds[3]; a.shared_fd = fds[4];
    a.out_dir = out;
    a.verify_sources = in->verify;
    a.progress = watch_progress;
    memset(watch, 0, sizeof *watch);
    watch->cancel_at = in->cancel_at;
    a.progress_ctx = watch;
    hf_apply(&a, &res);
    for (i = 0; i < 5; i++) if (fds[i] >= 0) CLOSE(fds[i]);
    return res;
}

static HfResult run_with_fds(int set, int game, int update, int xx, int shared, const char *out) {
    HfApplyArgs a;
    HfResult res;
    memset(&a, 0, sizeof a);
    a.set_fd = set; a.game_fd = game; a.update_fd = update; a.xx_fd = xx; a.shared_fd = shared;
    a.out_dir = out;
    a.verify_sources = 1;
    hf_apply(&a, &res);
    if (set >= 0) CLOSE(set);
    if (game >= 0) CLOSE(game);
    if (update >= 0) CLOSE(update);
    if (xx >= 0) CLOSE(xx);
    if (shared >= 0) CLOSE(shared);
    return res;
}

/* ---- the tests --------------------------------------------------------------------------------------------------- */

static HfIdentity identify(const char *name) {
    HfIdentity id;
    int fd = fixture(name);
    hf_identify(fd, &id);
    if (fd >= 0) CLOSE(fd);
    return id;
}

static void test_identify(void) {
    HfIdentity id;
    char hex[65];
    begin();
    id = identify("made_up_game.cxi.bin");
    CHECK(id.kind == HF_KIND_GAME && id.region == REGION_USA && !id.encrypted && id.has_revision);
    id = identify("made_up_game_eur.cxi.bin");
    CHECK(id.kind == HF_KIND_GAME && id.region == REGION_EUR);
    id = identify("made_up_xx.cxi.bin");
    CHECK(id.kind == HF_KIND_XX && id.region == REGION_JPN && !id.encrypted && id.has_revision);
    end("identify: the American game, the European game and MH XX are told apart (a wrong region could pass as the right one)");

    begin();
    id = identify("made_up_update.cia.bin");
    hex_of(id.revision, hex);
    CHECK(id.kind == HF_KIND_UPDATE && id.region == REGION_USA && !id.encrypted && id.has_revision);
    CHECK(!strcmp(hex, update_exefs));            /* the exact update's identity is the set's own hash */
    id = identify("made_up_update.cxi.bin");
    hex_of(id.revision, hex);
    CHECK(id.kind == HF_KIND_UPDATE && !strcmp(hex, update_exefs));
    id = identify("made_up_update_other.cia.bin");
    hex_of(id.revision, hex);
    CHECK(id.kind == HF_KIND_UPDATE && strcmp(hex, update_exefs));
    id = identify("made_up_update_eur.cia.bin");
    CHECK(id.kind == HF_KIND_UPDATE && id.region == REGION_EUR);
    end("identify: the update as a CIA and as a bare NCCH gives one identity, another revision another one (a wrong update could pass)");

    begin();
    id = identify("made_up_update_locked.cia.bin");
    CHECK(id.kind == HF_KIND_UPDATE && id.encrypted && !id.has_revision);
    id = identify("junk.bin");
    CHECK(id.kind == HF_KIND_NONE && id.rc == TITLE_E_FORMAT);
    id = identify("android.hfpatch");
    CHECK(id.kind == HF_KIND_NONE);
    hf_identify(-1, &id);
    CHECK(id.kind == HF_KIND_NONE && id.rc == TITLE_E_IO);
    end("identify: an encrypted update, a file that is no game and a bad descriptor are named, never taken for a game");

    begin();
    {
        char text[512];
        size_t n;
        id = identify("made_up_update.cia.bin");
        n = hf_identify_text(&id, text, sizeof text);
        CHECK(n == strlen(text) && strstr(text, "kind=update\n") && strstr(text, "region=usa\n") && strstr(text, update_exefs));
        n = hf_identify_text(&id, text, 10);
        CHECK(n < 10 && text[n] == 0);
        CHECK(hf_identify_text(&id, text, 0) == 0);
    }
    end("identify: the text Java reads is whole, or cut and still terminated");
}

static HfSetInfo set_info(const char *name) {
    HfSetInfo info;
    int fd = fixture(name);
    hf_set_info(fd, &info);
    if (fd >= 0) CLOSE(fd);
    return info;
}

static void test_set_info(void) {
    HfSetInfo s;
    char hex[65], file[1024];
    size_t len;
    uint8_t *shared;
    int i, files = 0;
    begin();
    s = set_info("android.hfpatch");
    path_of(file, sizeof file, fixtures, "shared.hfpack");
    shared = slurp(file, &len);
    sha_hex(shared, len, hex);
    free(shared);
    for (i = 0; i < ntrees; i++) files += !strcmp(trees[i].set, "android");
    CHECK(s.rc == 0 && s.format == PS_FORMAT_UPDATE && !s.needs_newer_app && s.region == REGION_USA);
    CHECK(!strcmp(s.edition, "HeavenfallBeta") && !strcmp(s.version, "0.3.0"));
    CHECK(!s.needs_xx && s.needs_shared && s.shared_size == len && s.needs_update && (int)s.files == files);
    hex_of(s.shared_sha256, file);
    CHECK(!strcmp(file, hex));
    hex_of(s.update_exefs_hash, file);
    CHECK(s.update_title == 0x0004000E00126300ull && !strcmp(file, update_exefs));
    end("set info: the head names the edition, version, region, shared file and the update the set was made for");

    begin();
    s = set_info("android_xx.hfpatch");
    CHECK(s.rc == 0 && s.needs_xx && s.xx_title == 0x0004000000197100ull && !s.needs_shared);
    end("set info: a set made with MH XX says so (the app must ask for the player's MH XX)");

    begin();
    s = set_info("set_newer.hfpatch");
    CHECK(s.needs_newer_app && s.format == PS_FORMAT_UPDATE + 1 && s.rc == 0);
    end("set info: a format newer than the applier reads is 'update the app first', not 'damaged'");

    begin();
    s = set_info("set_cut.hfpatch");
    CHECK(s.rc == TITLE_E_FORMAT && !s.needs_newer_app);
    s = set_info("junk.bin");
    CHECK(s.rc == TITLE_E_FORMAT && s.format == 0);
    s = set_info("format3.hfpatch");
    CHECK(s.rc == 0 && s.format >= 1 && s.format < PS_FORMAT_UPDATE && !s.needs_update);
    end("set info: a cut file and a file that is no set are refused, a console set is told by its format");

    begin();
    {
        char text[1024];
        s = set_info("android.hfpatch");
        CHECK(hf_set_info_text(&s, text, sizeof text) == strlen(text) && strstr(text, "format=4\n") &&
              strstr(text, "edition=HeavenfallBeta\n") && strstr(text, "shared=1\n") && strstr(text, "update=1\n"));
    }
    end("set info: the text Java reads has every field");
}

static void test_apply_makes_the_mod(void) {
    char cdir[1024], out[1024];
    Watch w;
    HfResult r;
    Inputs in = {"android.hfpatch", "made_up_game.cxi.bin", "made_up_update.cia.bin", NULL, "shared.hfpack", 1, -1};
    begin();
    fresh("plain", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == 0 && r.files > 10 && r.has_code_sha256);
    check_tree(out, "android");
    {
        char hex[65];
        hex_of(r.code_sha256, hex);
        CHECK(!strcmp(hex, expected_code("android")));     /* the code library is built from this hash */
    }
    CHECK(count_entries(cdir) == 1);                       /* nothing made beside the output folder */
    end("apply: the game, its update and the shared file make every file of the mod, bytes and code hash as expected");

    begin();
    CHECK(w.calls >= 3 && !w.decreased && !w.over && w.total > 0 && w.last_done == w.total);
    end("apply: the progress goes up, never past the total, and ends at the total");

    begin();
    in.update = "made_up_update.cxi.bin";
    fresh("bare", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == 0);
    check_tree(out, "android");
    end("apply: the update as a bare NCCH makes the same mod as the CIA");

    begin();
    in.update = "made_up_update.cia.bin";
    in.verify = 0;
    fresh("noverify", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == 0);
    check_tree(out, "android");
    end("apply: without the source check the same mod is made (the output's own hashes still hold)");
}

static void test_apply_with_xx(void) {
    char cdir[1024], out[1024];
    Watch w;
    HfResult r;
    Inputs in = {"android_xx.hfpatch", "made_up_game.cxi.bin", "made_up_update.cia.bin", "made_up_xx.cxi.bin", NULL, 1, -1};
    begin();
    fresh("xx", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == 0);
    check_tree(out, "android_xx");
    end("apply: a set made with MH XX makes the mod from the game, its update and MH XX");

    begin();
    in.xx = NULL;
    fresh("noxx", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == HF_E_NO_XX && r.stage == HF_STAGE_XX && count_entries(out) == 0);
    end("apply: a set that needs MH XX without it is refused before any file is made");

    begin();
    in.xx = "made_up_game.cxi.bin";           /* MH4U given as MH XX */
    fresh("wrongxx", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == TITLE_E_GAME && r.stage == HF_STAGE_XX && count_entries(out) == 0);
    end("apply: another game given as MH XX is refused at MH XX");
}

static void test_wrong_update(void) {
    static const struct { const char *update; int rc; } cases[] = {
        {NULL, TITLE_E_NO_UPDATE}, {"made_up_update_eur.cia.bin", TITLE_E_OTHER_UPDATE}, {"made_up_update_other.cia.bin", TITLE_E_OTHER_UPDATE},
        {"made_up_game.cxi.bin", TITLE_E_NOT_UPDATE}, {"made_up_update_locked.cia.bin", TITLE_E_ENCRYPTED}};
    size_t i;
    begin();
    for (i = 0; i < sizeof cases / sizeof *cases; i++) {
        char cdir[1024], out[1024];
        Watch w;
        HfResult r;
        Inputs in;
        in.set = "android.hfpatch"; in.game = "made_up_game.cxi.bin"; in.update = cases[i].update; in.xx = NULL;
        in.shared = "shared.hfpack"; in.verify = 1; in.cancel_at = -1;
        fresh("wrongupdate", cdir, out);
        r = run_apply(&in, out, &w);
        if (r.rc != cases[i].rc || r.stage != HF_STAGE_UPDATE || count_entries(out)) printf("    case %zu: rc %d stage %d\n", i, r.rc, r.stage);
        CHECK(r.rc == cases[i].rc && r.stage == HF_STAGE_UPDATE && count_entries(out) == 0);
    }
    end("apply: no update, the European one, another revision, the base game or an encrypted one are refused at the update, before any file is made");
}

static void test_bad_sets(void) {
    char cdir[1024], out[1024];
    Watch w;
    HfResult r;
    Inputs in = {"set_cut.hfpatch", "made_up_game.cxi.bin", "made_up_update.cia.bin", NULL, "shared.hfpack", 1, -1};
    begin();
    fresh("cut", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == TITLE_E_FORMAT && r.stage == HF_STAGE_SET && count_entries(out) == 0);
    end("apply: a set cut short (a download that stopped) is refused, nothing is made");

    begin();
    {
        int set = copy_of("android.hfpatch", 4000, -1, "set_flipped.hfpatch");
        fresh("flipped", cdir, out);
        r = run_with_fds(set, fixture("made_up_game.cxi.bin"), fixture("made_up_update.cia.bin"), -1, fixture("shared.hfpack"), out);
        CHECK(r.rc == TITLE_E_FORMAT && r.stage == HF_STAGE_SET && count_entries(out) == 0);
    }
    end("apply: a set with one byte changed is refused by its own hash, nothing is made");

    begin();
    in.set = "set_newer.hfpatch";
    fresh("newer", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == HF_E_NEWER && r.stage == HF_STAGE_SET && count_entries(out) == 0);
    end("apply: a set of a newer format is 'the app must be updated first'");

    begin();
    in.set = "format3.hfpatch";
    fresh("console", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == HF_E_NOT_ANDROID && count_entries(out) == 0);
    end("apply: a console set (format 3) is refused: its files are not the recompiled game's mod");

    begin();
    in.set = "bad_path.hfpatch";
    fresh("escape", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == TITLE_E_PATH && r.stage == HF_STAGE_SET && count_entries(out) == 0 && count_entries(cdir) == 1);
    end("apply: a set naming a file outside the mod's folder is refused, nothing is written anywhere");
}

static void test_bad_inputs(void) {
    char cdir[1024], out[1024];
    Watch w;
    HfResult r;
    Inputs in = {"android.hfpatch", "made_up_game.cxi.bin", "made_up_update.cia.bin", NULL, "shared.hfpack", 1, -1};
    begin();
    {
        int game = copy_of("made_up_game.cxi.bin", flip_offset, -1, "game_flipped.cxi");
        fresh("badgame", cdir, out);
        r = run_with_fds(fixture("android.hfpatch"), game, fixture("made_up_update.cia.bin"), -1, fixture("shared.hfpack"), out);
        CHECK(r.rc == TITLE_E_HASH && r.stage == HF_STAGE_GAME && r.source_kind == PS_SRC_FILE && count_entries(out) == 0);
    }
    end("apply: a game with one changed byte is refused by the source check, before any file is made");

    begin();
    {
        int game = copy_of("made_up_game.cxi.bin", flip_offset, -1, "game_flipped.cxi");
        HfApplyArgs a;
        memset(&a, 0, sizeof a);
        a.set_fd = fixture("android.hfpatch"); a.game_fd = game; a.update_fd = fixture("made_up_update.cia.bin");
        a.xx_fd = -1; a.shared_fd = fixture("shared.hfpack");
        a.verify_sources = 0;
        fresh("badgame2", cdir, out);
        a.out_dir = out;
        hf_apply(&a, &r);
        CHECK(r.rc == TITLE_E_HASH && r.stage == HF_STAGE_WRITE && !strcmp(r.path, "romfs/data/plain.bin"));
        CHECK(a.set_fd >= 0);
        CLOSE(a.set_fd); CLOSE(a.game_fd); CLOSE(a.update_fd); CLOSE(a.shared_fd);
    }
    end("apply: without the source check, the recorded hash of the output still stops a changed game");

    begin();
    {
        int shared = copy_of("shared.hfpack", 50000, -1, "shared_flipped.hfpack");
        fresh("badshared", cdir, out);
        r = run_with_fds(fixture("android.hfpatch"), fixture("made_up_game.cxi.bin"), fixture("made_up_update.cia.bin"), -1, shared, out);
        CHECK(r.rc == TITLE_E_HASH && r.stage == HF_STAGE_SHARED && count_entries(out) == 0);
    }
    end("apply: a shared file with one changed byte is refused before any file is made");

    begin();
    in.shared = NULL;
    fresh("noshared", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == HF_E_NO_SHARED && r.stage == HF_STAGE_SHARED && count_entries(out) == 0);
    end("apply: a set that reads the shared file without it is refused");

    begin();
    in.shared = "shared.hfpack";
    in.game = "made_up_game_eur.cxi.bin";
    fresh("eurgame", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == TITLE_E_GAME && r.stage == HF_STAGE_GAME && count_entries(out) == 0);
    in.game = "made_up_xx.cxi.bin";
    fresh("xxgame", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc != 0 && r.stage == HF_STAGE_GAME && count_entries(out) == 0);
    end("apply: the European game, or another game, given as the game is refused at the game");

    begin();
    fresh("badfd", cdir, out);
    r = run_with_fds(-1, -1, -1, -1, -1, out);
    CHECK(r.rc == TITLE_E_ARG);
    r = run_with_fds(9999, fixture("made_up_game.cxi.bin"), fixture("made_up_update.cia.bin"), -1, fixture("shared.hfpack"), out);
    CHECK(r.rc == HF_E_READ && r.stage == HF_STAGE_SET && r.err != 0);
    end("apply: a descriptor that cannot be read is 'could not read', with its errno, not a bad set");

    begin();
    in.game = "made_up_game.cxi.bin";
    path_of(cdir, sizeof cdir, work, "missing_folder");
    rmtree(cdir);
    path_of(out, sizeof out, cdir, "out");
    r = run_apply(&in, out, &w);
    CHECK(r.rc == HF_E_WRITE && r.stage == HF_STAGE_WRITE && r.err == ENOENT);
    end("apply: an output folder that cannot be made is 'could not write', with its errno");
}

static void test_cancel(void) {
    char cdir[1024], out[1024];
    Watch w;
    HfResult r;
    Inputs in = {"android.hfpatch", "made_up_game.cxi.bin", "made_up_update.cia.bin", NULL, "shared.hfpack", 1, -1};
    long total, k;
    begin();
    fresh("count", cdir, out);
    r = run_apply(&in, out, &w);
    total = w.calls;
    CHECK(r.rc == 0 && total >= 3);
    for (k = 1; k < total; k++) {
        in.cancel_at = k;
        fresh("cancel", cdir, out);
        r = run_apply(&in, out, &w);
        if (r.rc != TITLE_E_CANCEL) printf("    cancel at call %ld of %ld: rc %d\n", k, total, r.rc);
        CHECK(r.rc == TITLE_E_CANCEL);
        CHECK(count_entries(cdir) == 1);
    }
    end("apply: a cancel at any point of the run (the shared file, the checks, the files) stops it with 'cancelled'");

    begin();
    in.cancel_at = -1;
    fresh("again", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(r.rc == 0);
    check_tree(out, "android");
    end("apply: a run after cancelled ones makes the whole mod (no state is kept between runs)");
}

static void test_result_text(void) {
    char cdir[1024], out[1024], text[1024];
    Watch w;
    HfResult r;
    Inputs in = {"android.hfpatch", "made_up_game.cxi.bin", "made_up_update.cia.bin", NULL, "shared.hfpack", 1, -1};
    begin();
    fresh("text", cdir, out);
    r = run_apply(&in, out, &w);
    CHECK(hf_result_text(&r, text, sizeof text) == strlen(text) && strstr(text, "rc=0\n") && strstr(text, "code_sha256=") &&
          strstr(text, expected_code("android")));
    CHECK(hf_result_text(&r, text, 12) < 12);
    CHECK(strlen(hf_error_text(HF_E_NEWER)) > 5 && strlen(hf_error_text(HF_E_SPACE)) > 5 && strlen(hf_error_text(TITLE_E_HASH)) > 5);
    end("result text: the text Java reads has the code hash; every code has a sentence for the log");
}

int main(int argc, char **argv) {
    char text[1024];
    if (argc < 3) { printf("usage: hf_test <fixtures> <work>\n"); return 100; }
    fixtures = argv[1];
    work = argv[2];
    MKDIR(work);
    load_expected();
    (void)text;
    test_identify();
    test_set_info();
    test_apply_makes_the_mod();
    test_apply_with_xx();
    test_wrong_update();
    test_bad_sets();
    test_bad_inputs();
    test_cancel();
    test_result_text();
    printf("%d test(s) failed\n", failures);
    return failures;
}
