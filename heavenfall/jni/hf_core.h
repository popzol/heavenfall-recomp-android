/* The app's layer over the patch set's applier (heavenfall/c): what the Android side asks of it, in plain C with no JNI,
 * so the host's gcc builds and tests it (jni/test/hf_test.c) as the phone's compiler builds it into libheavenfall.so.
 *
 * Every file the player gives is a file descriptor, read with pread: the app opens it from a plain path or from the
 * document picker (ContentResolver.openFileDescriptor) and keeps it open for the call; nothing here closes or seeks it.
 * The mod's files are written under a folder path of the app's own storage.
 *
 * Results that Java reads are text ("key=value" lines): hf_identify_text, hf_set_info_text and hf_result_text. */
#ifndef HF_CORE_H
#define HF_CORE_H

#include <stddef.h>
#include <stdint.h>

#include "patchset.h"

/* Codes the applier does not have, beside TITLE_E_* (title_io.h). */
enum {
    HF_E_NEWER = -100,      /* the set's format is newer than this applier reads: the app must be updated first */
    HF_E_NOT_ANDROID = -101, /* a set of formats 1 to 3 (the 3DS app's): its files are not the recompiled game's mod */
    HF_E_INCOMPLETE = -102, /* a set without the mod's executable or exheader */
    HF_E_NO_XX = -103,      /* the set was made with MH XX and no MH XX was given */
    HF_E_NO_SHARED = -104,  /* the set reads the release's shared file and none was given */
    HF_E_READ = -105,       /* a file of the player's (or the set, or the shared file) could not be read */
    HF_E_WRITE = -106,      /* a file of the mod could not be written (errno in the result) */
    HF_E_SPACE = -107       /* the storage is full (a write failed with ENOSPC or EDQUOT) */
};

/* Where in the run a code came from: which file the player or the release is to blame for. */
enum { HF_STAGE_SET = 0, HF_STAGE_GAME, HF_STAGE_UPDATE, HF_STAGE_XX, HF_STAGE_SHARED, HF_STAGE_WRITE, HF_STAGE_COUNT };

enum {
    HF_KIND_NONE = 0,       /* not a game container, or not readable */
    HF_KIND_OTHER,          /* a 3DS container of another title (not MH4U, its update or MH XX) */
    HF_KIND_GAME,           /* MH4U's base game (title id in `title_id`, region in `region`) */
    HF_KIND_UPDATE,         /* MH4U's update */
    HF_KIND_XX              /* MH XX (JPN) */
};

typedef struct {
    int rc;                 /* the reader's own code: TITLE_OK, or the error that stopped it (TITLE_E_IO...) */
    int kind;               /* HF_KIND_* */
    int region;             /* REGION_EUR / REGION_USA / REGION_JPN; -1 when unknown */
    int encrypted;          /* 1: a game, update or MH XX that is not decrypted: nothing else can be read */
    uint64_t title_id;
    int has_revision;
    uint8_t revision[32];   /* a game's and MH XX's RomFS superblock hash; an update's ExeFS superblock hash */
} HfIdentity;

/* What a file is (a decrypted or encrypted MH4U, its update, MH XX, or something else). Never fails: `rc` and `kind`
 * say what it could not be read as. */
void hf_identify(int fd, HfIdentity *out);

typedef struct {
    int rc;                 /* TITLE_OK, or why the head could not be read (TITLE_E_FORMAT: not a set, cut or damaged) */
    int format;             /* the set's format number; 0 when it is not a set */
    int needs_newer_app;    /* format past what this applier reads */
    int region;             /* REGION_EUR / REGION_USA */
    char edition[17], version[17];
    int needs_xx;
    uint64_t xx_title;
    uint8_t xx_romfs_hash[32];
    int needs_shared;
    uint64_t shared_size;
    uint8_t shared_sha256[32];
    int needs_update;
    uint64_t update_title;
    uint8_t update_exefs_hash[32];
    uint32_t flags;
    unsigned files;
} HfSetInfo;

/* The set's head, read without applying it. */
void hf_set_info(int fd, HfSetInfo *out);

/* Bytes done of the total, over every pass of an apply; a nonzero return cancels. May be NULL. */
typedef int (*HfProgress)(void *ctx, uint64_t done, uint64_t total);

typedef struct {
    int set_fd, game_fd, update_fd;
    int xx_fd, shared_fd;       /* -1: none */
    const char *out_dir;        /* an existing, empty folder: the mod's files are made inside it */
    int verify_sources;         /* 1: every source file's recorded hash is checked before anything is written */
    HfProgress progress;
    void *progress_ctx;
} HfApplyArgs;

typedef struct {
    int rc;                     /* TITLE_OK (0) or a TITLE_E_* / HF_E_* code */
    int stage;                  /* HF_STAGE_*: where it stopped */
    int err;                    /* errno of a read or write that failed, 0 otherwise */
    int source_kind;            /* the source whose hash differed (PS_SRC_*), -1 otherwise */
    char path[256];             /* the file being made when it stopped */
    unsigned files;             /* files written whole */
    uint64_t bytes;             /* bytes written */
    uint8_t code_sha256[32];    /* of exefs/code.bin as written: what the code library is built from */
    int has_code_sha256;
} HfResult;

/* Makes the mod: the set's every file from the game, its update (and MH XX, and the release's shared file) into
 * `out_dir`, every recorded hash checked. Stops at the first error, or when `progress` returns nonzero (TITLE_E_CANCEL);
 * the files made stay where they are (the caller's staging folder is thrown away). */
void hf_apply(const HfApplyArgs *args, HfResult *res);

/* The text Java reads: "key=value" lines, each ending with "\n". Returns the length (truncated to `cap`, always
 * terminated; 0 when `cap` is 0). */
size_t hf_identify_text(const HfIdentity *id, char *buf, size_t cap);
size_t hf_set_info_text(const HfSetInfo *info, char *buf, size_t cap);
size_t hf_result_text(const HfResult *res, char *buf, size_t cap);

/* An English sentence for a code of any of the enums above, for logs. */
const char *hf_error_text(int rc);

#endif
