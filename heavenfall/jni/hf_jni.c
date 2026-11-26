/* libheavenfall.so's JNI: the Java class io.github.popzol.heavenfall.app.HeavenfallNative over hf_core.c. Every function
 * returns the text hf_core.h describes (or a code), and nothing here keeps state between calls. */
#include <jni.h>

#include <stdlib.h>
#include <string.h>

#include "hf_core.h"

#define OUT_DIR_MAX 3072
#define TEXT_MAX 2048

/* The Java side's progress callback: `boolean update(long done, long total)`, true to cancel. */
typedef struct {
    JNIEnv *env;
    jobject sink;
    jmethodID update;
} Sink;

static int progress_to_java(void *ctx, uint64_t done, uint64_t total) {
    Sink *s = (Sink *)ctx;
    jboolean cancel;
    if (!s->sink) return 0;
    cancel = (*s->env)->CallBooleanMethod(s->env, s->sink, s->update, (jlong)done, (jlong)total);
    if ((*s->env)->ExceptionCheck(s->env)) {     /* a callback that threw stops the run (no JNI call is allowed with one pending) */
        (*s->env)->ExceptionClear(s->env);
        return 1;
    }
    return cancel == JNI_TRUE;
}

static jstring text_to_java(JNIEnv *env, const char *text) {
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL Java_io_github_popzol_heavenfall_app_HeavenfallNative_nativeIdentify(JNIEnv *env, jclass cls,
                                                                                             jint fd) {
    HfIdentity id;
    char text[TEXT_MAX];
    (void)cls;
    hf_identify((int)fd, &id);
    hf_identify_text(&id, text, sizeof text);
    return text_to_java(env, text);
}

JNIEXPORT jstring JNICALL Java_io_github_popzol_heavenfall_app_HeavenfallNative_nativeSetInfo(JNIEnv *env, jclass cls,
                                                                                            jint fd) {
    HfSetInfo info;
    char text[TEXT_MAX];
    (void)cls;
    hf_set_info((int)fd, &info);
    hf_set_info_text(&info, text, sizeof text);
    return text_to_java(env, text);
}

JNIEXPORT jstring JNICALL Java_io_github_popzol_heavenfall_app_HeavenfallNative_nativeApply(
    JNIEnv *env, jclass cls, jint set_fd, jint game_fd, jint update_fd, jint xx_fd, jint shared_fd, jbyteArray out_dir,
    jboolean verify_sources, jobject sink) {
    HfApplyArgs args;
    HfResult res;
    Sink s;
    char dir[OUT_DIR_MAX], text[TEXT_MAX];
    jsize n;
    (void)cls;
    memset(&args, 0, sizeof args);
    n = out_dir ? (*env)->GetArrayLength(env, out_dir) : 0;
    if (n <= 0 || n >= OUT_DIR_MAX) {            /* no folder, or one too long to be one of the app's */
        memset(&res, 0, sizeof res);
        res.rc = TITLE_E_ARG;
        res.source_kind = -1;
        hf_result_text(&res, text, sizeof text);
        return text_to_java(env, text);
    }
    (*env)->GetByteArrayRegion(env, out_dir, 0, n, (jbyte *)dir);
    dir[n] = 0;
    s.env = env;
    s.sink = sink;
    s.update = NULL;
    if (sink) {
        jclass k = (*env)->GetObjectClass(env, sink);
        s.update = (*env)->GetMethodID(env, k, "update", "(JJ)Z");
        if (!s.update) s.sink = NULL;
        (*env)->DeleteLocalRef(env, k);
        (*env)->ExceptionClear(env);
    }
    args.set_fd = (int)set_fd;
    args.game_fd = (int)game_fd;
    args.update_fd = (int)update_fd;
    args.xx_fd = (int)xx_fd;
    args.shared_fd = (int)shared_fd;
    args.out_dir = dir;
    args.verify_sources = verify_sources == JNI_TRUE;
    args.progress = progress_to_java;
    args.progress_ctx = &s;
    hf_apply(&args, &res);
    hf_result_text(&res, text, sizeof text);
    return text_to_java(env, text);
}
