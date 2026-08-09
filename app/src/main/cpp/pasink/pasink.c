// pasink — a tiny PulseAudio client that suspends/resumes a sink by name.
//
// Why this exists: our guest audio runs through a bundled PulseAudio 13.0 daemon. When the app is
// backgrounded (or the HDMI/output route changes) the sink's AAudio output stream dies and does not
// re-open itself, leaving the game silent. GameNative's proven fix is `pactl suspend-sink AAudioSink`
// (suspend then resume), which reopens the sink's output stream and re-grabs the current route.
//
// We can't load a control MODULE into the daemon (the bundled module-cli is a 17.0 build, ABI-
// incompatible with the 13.0 daemon), and bundling a stock `pactl` drags a large codec-lib chain.
// Instead this helper dlopen()s the 13.0 libpulse CLIENT that already ships in files/pulseaudio and
// calls pa_context_suspend_sink_by_name() directly over the native socket — the same protocol path a
// 17.0 pactl used successfully against the 13.0 daemon in on-device testing (protocol 35<->33).
//
// It links nothing pulse at build time (pure dlopen/dlsym), so it needs no pulse headers and adds no
// runtime NEEDED deps beyond liblog/libdl. Runs on pause/resume only, never at startup, so it cannot
// affect winepulse's initial device init.

#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>

#define TAG "pasink"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// PulseAudio opaque handles are just pointers to us.
typedef void pa_threaded_mainloop;
typedef void pa_mainloop_api;
typedef void pa_context;
typedef void pa_operation;

// Enum values from PulseAudio's public API (stable ABI).
enum { PA_CONTEXT_READY = 4, PA_CONTEXT_FAILED = 5, PA_CONTEXT_TERMINATED = 6 };
enum { PA_OPERATION_RUNNING = 0, PA_OPERATION_DONE = 1, PA_OPERATION_CANCELLED = 2 };

typedef void (*pa_context_notify_cb_t)(pa_context *c, void *userdata);
typedef void (*pa_context_success_cb_t)(pa_context *c, int success, void *userdata);

typedef struct {
    pa_threaded_mainloop *(*mainloop_new)(void);
    int   (*mainloop_start)(pa_threaded_mainloop *m);
    void  (*mainloop_stop)(pa_threaded_mainloop *m);
    void  (*mainloop_free)(pa_threaded_mainloop *m);
    void  (*mainloop_lock)(pa_threaded_mainloop *m);
    void  (*mainloop_unlock)(pa_threaded_mainloop *m);
    void  (*mainloop_wait)(pa_threaded_mainloop *m);
    void  (*mainloop_signal)(pa_threaded_mainloop *m, int wait_for_accept);
    pa_mainloop_api *(*mainloop_get_api)(pa_threaded_mainloop *m);
    pa_context *(*context_new)(pa_mainloop_api *mainloop, const char *name);
    int   (*context_connect)(pa_context *c, const char *server, int flags, const void *api);
    void  (*context_disconnect)(pa_context *c);
    void  (*context_unref)(pa_context *c);
    void  (*context_set_state_callback)(pa_context *c, pa_context_notify_cb_t cb, void *userdata);
    int   (*context_get_state)(pa_context *c);
    pa_operation *(*context_suspend_sink_by_name)(pa_context *c, const char *name, int suspend,
                                                  pa_context_success_cb_t cb, void *userdata);
    int   (*operation_get_state)(pa_operation *o);
    void  (*operation_unref)(pa_operation *o);
} pa_api;

typedef struct {
    pa_api *api;
    pa_threaded_mainloop *m;
    int op_success;
} cb_ctx;

static void state_cb(pa_context *c, void *userdata) {
    (void) c;
    cb_ctx *x = (cb_ctx *) userdata;
    x->api->mainloop_signal(x->m, 0);
}

static void success_cb(pa_context *c, int success, void *userdata) {
    (void) c;
    cb_ctx *x = (cb_ctx *) userdata;
    x->op_success = success;
    x->api->mainloop_signal(x->m, 0);
}

// dlopen a lib in a dir with RTLD_GLOBAL so later libs resolve its symbols. Returns handle or NULL.
static void *open_lib(const char *dir, const char *name) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!h) LOGW("dlopen %s failed: %s", path, dlerror());
    return h;
}

#define SYM(field, symname) \
    do { *(void **)(&api.field) = dlsym(libpulse, symname); \
         if (!api.field) { LOGW("missing symbol %s", symname); dlclose(libpulse); return -3; } } while (0)

// Returns 0 on success, negative on error.
JNIEXPORT jint JNICALL
Java_com_winlator_star_xenvironment_components_PulseAudioComponent_nativeSuspendSink(
        JNIEnv *env, jclass clazz, jstring jdir, jstring jserver, jstring jsink, jboolean suspend) {
    (void) clazz;
    const char *dir    = (*env)->GetStringUTFChars(env, jdir, NULL);
    const char *server = (*env)->GetStringUTFChars(env, jserver, NULL);
    const char *sink   = (*env)->GetStringUTFChars(env, jsink, NULL);
    jint rc = -1;

    // Resolve the 13.0 client stack that already sits in files/pulseaudio, in dependency order.
    open_lib(dir, "libsndfile.so");                 // libpulsecommon needs it; ignore failure here
    open_lib(dir, "libpulsecommon-13.0.so");        // libpulse needs it
    void *libpulse = open_lib(dir, "libpulse.so");
    if (!libpulse) { rc = -2; goto done; }

    pa_api api;
    memset(&api, 0, sizeof(api));
    SYM(mainloop_new,   "pa_threaded_mainloop_new");
    SYM(mainloop_start, "pa_threaded_mainloop_start");
    SYM(mainloop_stop,  "pa_threaded_mainloop_stop");
    SYM(mainloop_free,  "pa_threaded_mainloop_free");
    SYM(mainloop_lock,  "pa_threaded_mainloop_lock");
    SYM(mainloop_unlock,"pa_threaded_mainloop_unlock");
    SYM(mainloop_wait,  "pa_threaded_mainloop_wait");
    SYM(mainloop_signal,"pa_threaded_mainloop_signal");
    SYM(mainloop_get_api,"pa_threaded_mainloop_get_api");
    SYM(context_new,    "pa_context_new");
    SYM(context_connect,"pa_context_connect");
    SYM(context_disconnect,"pa_context_disconnect");
    SYM(context_unref,  "pa_context_unref");
    SYM(context_set_state_callback,"pa_context_set_state_callback");
    SYM(context_get_state,"pa_context_get_state");
    SYM(context_suspend_sink_by_name,"pa_context_suspend_sink_by_name");
    SYM(operation_get_state,"pa_operation_get_state");
    SYM(operation_unref,"pa_operation_unref");

    pa_threaded_mainloop *m = api.mainloop_new();
    if (!m) { rc = -4; goto done; }
    cb_ctx x = { &api, m, 0 };

    if (api.mainloop_start(m) < 0) { api.mainloop_free(m); rc = -5; goto done; }
    api.mainloop_lock(m);

    pa_context *ctx = api.context_new(api.mainloop_get_api(m), "bannerlator-pasink");
    if (!ctx) { api.mainloop_unlock(m); api.mainloop_stop(m); api.mainloop_free(m); rc = -6; goto done; }

    api.context_set_state_callback(ctx, state_cb, &x);
    if (api.context_connect(ctx, server, 0, NULL) < 0) { rc = -7; goto teardown; }

    // Wait until the context is READY (or fails). state_cb signals us on every transition.
    for (;;) {
        int st = api.context_get_state(ctx);
        if (st == PA_CONTEXT_READY) break;
        if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) { rc = -8; goto teardown; }
        api.mainloop_wait(m);
    }

    // Fire the suspend/resume and wait for the operation to finish.
    pa_operation *op = api.context_suspend_sink_by_name(ctx, sink, suspend ? 1 : 0, success_cb, &x);
    if (!op) { rc = -9; goto teardown; }
    while (api.operation_get_state(op) == PA_OPERATION_RUNNING) api.mainloop_wait(m);
    api.operation_unref(op);
    rc = x.op_success ? 0 : -10;

teardown:
    api.context_disconnect(ctx);
    api.context_unref(ctx);
    api.mainloop_unlock(m);
    api.mainloop_stop(m);
    api.mainloop_free(m);

done:
    if (rc == 0) LOGI("suspend-sink %s %d ok", sink, (int) suspend);
    else         LOGW("suspend-sink %s %d failed rc=%d", sink, (int) suspend, (int) rc);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    (*env)->ReleaseStringUTFChars(env, jserver, server);
    (*env)->ReleaseStringUTFChars(env, jsink, sink);
    return rc;
}
