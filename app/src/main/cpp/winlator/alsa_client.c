#include <aaudio/AAudio.h>
#include <jni.h>
#include <stdlib.h>
#include <android/log.h>

// ALSA-path AAudio player (guest winealsa -> aserver -> here). Blocking-write stream.
//
// Bannerlator adaptive rework (mirrors the PulseAudio module): the stream used to open at a FIXED
// LOW_LATENCY buffer and do nothing on underruns or route changes, so it crackled under box64/FEX+DXVK
// load and went silent when headphones/BT (dis)connected. Now the stream is wrapped in AlsaStream and
// write() (a) GROWS the buffer one burst at a time on underruns (xrun-driven, up to capacity), and
// (b) REOPENS the stream on a route disconnect (AAUDIO_ERROR_DISCONNECTED) so audio follows to the new
// device. streamPtr stays an opaque handle to Java (ALSAClient), so no Java changes are needed.

#define TAG "alsa_client"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define WAIT_COMPLETION_TIMEOUT 100 * 1000000L

enum Format {U8, S16LE, S16BE, FLOATLE, FLOATBE};

typedef struct {
    AAudioStream *stream;
    int32_t format;
    int8_t  channelCount;
    int32_t sampleRate;
    int32_t curBufferSize;   // frames; grows on underrun
    int32_t framesPerBurst;  // device burst granularity
    int32_t capacity;        // device max buffer (frames)
    int32_t lastXrun;        // last-seen underrun count
    int      started;        // was requestStart called (so a reopen can re-start)
} AlsaStream;

static aaudio_format_t toAAudioFormat(int format) {
    switch (format) {
        case FLOATLE:
        case FLOATBE:
            return AAUDIO_FORMAT_PCM_FLOAT;
        case U8:
            return AAUDIO_FORMAT_UNSPECIFIED;
        case S16LE:
        case S16BE:
        default:
            return AAUDIO_FORMAT_PCM_I16;
    }
}

// Open (or reopen) the underlying AAudio stream on the CURRENT default route, applying curBufferSize.
static int openStream(AlsaStream *s) {
    AAudioStreamBuilder *builder;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return -1;

    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, toAAudioFormat(s->format));
    AAudioStreamBuilder_setChannelCount(builder, s->channelCount);
    AAudioStreamBuilder_setSampleRate(builder, s->sampleRate);

    if (AAudioStreamBuilder_openStream(builder, &s->stream) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        s->stream = NULL;
        return -1;
    }
    AAudioStreamBuilder_delete(builder);

    if (s->curBufferSize > 0) AAudioStream_setBufferSizeInFrames(s->stream, s->curBufferSize);
    s->framesPerBurst = AAudioStream_getFramesPerBurst(s->stream);
    s->capacity       = AAudioStream_getBufferCapacityInFrames(s->stream);
    s->lastXrun       = 0;
    return 0;
}

// Grow the buffer by one burst on a fresh underrun (monotonic, capped at capacity). Cheap; safe here.
static void adaptBuffer(AlsaStream *s) {
    if (s->framesPerBurst <= 0 || s->curBufferSize <= 0 || s->capacity <= 0) return;
    int32_t x = AAudioStream_getXRunCount(s->stream);
    if (x <= s->lastXrun) return;
    s->lastXrun = x;
    if (s->curBufferSize >= s->capacity) return;
    int32_t want = s->curBufferSize + s->framesPerBurst;
    if (want > s->capacity) want = s->capacity;
    int32_t got = AAudioStream_setBufferSizeInFrames(s->stream, want);
    if (got > 0) s->curBufferSize = got;
}

// Reopen on the current route after a disconnect (headset/BT/HDMI change), preserving state.
static int reopen(AlsaStream *s) {
    if (s->stream) { AAudioStream_close(s->stream); s->stream = NULL; }
    if (openStream(s) < 0) return -1;
    if (s->started) {
        AAudioStream_requestStart(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
    LOGW("reopened AAudio stream on route change (buf=%d frames)", (int) s->curBufferSize);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_create(JNIEnv *env, jobject obj, jint format,
                                               jbyte channelCount, jint sampleRate, jint bufferSize) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) calloc(1, sizeof(AlsaStream));
    if (!s) return 0;
    s->format = format;
    s->channelCount = channelCount;
    s->sampleRate = sampleRate;
    s->curBufferSize = bufferSize;
    if (openStream(s) < 0) { free(s); return 0; }
    return (jlong) s;
}

JNIEXPORT jint JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_write(JNIEnv *env, jobject obj, jlong streamPtr, jobject buffer,
                                              jint numFrames) {
    (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (!s || !s->stream) return -1;
    void *buf = (*env)->GetDirectBufferAddress(env, buffer);

    adaptBuffer(s);   // grow the buffer if we've been underrunning
    aaudio_result_t n = AAudioStream_write(s->stream, buf, numFrames, WAIT_COMPLETION_TIMEOUT);
    if (n < 0) {
        // Route change (disconnect) or other stream error → reopen on the current device and retry once.
        LOGW("AAudioStream_write err=%d — reopening", (int) n);
        if (reopen(s) == 0)
            n = AAudioStream_write(s->stream, buf, numFrames, WAIT_COMPLETION_TIMEOUT);
    }
    return (jint) n;
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_start(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        s->started = 1;
        AAudioStream_requestStart(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_stop(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        s->started = 0;
        AAudioStream_requestStop(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STOPPING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_pause(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        s->started = 0;
        AAudioStream_requestPause(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_PAUSING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_flush(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        AAudioStream_requestFlush(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_FLUSHING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_close(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (!s) return;
    if (s->stream) AAudioStream_close(s->stream);
    free(s);
}
