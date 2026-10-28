// Frontend libretro minimo: carrega um core (melonDS) e liga video/audio/input ao Android.
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cstdint>

#define TAG "NexDS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
struct retro_system_info { const char *library_name; const char *library_version; const char *valid_extensions; bool need_fullpath; bool block_extract; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };
typedef void (*retro_log_printf_t)(int level, const char *fmt, ...);
struct retro_log_callback { retro_log_printf_t log; };

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

static struct {
    void *h;
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*init)(void);
    void (*deinit)(void);
    unsigned (*api_version)(void);
    void (*get_system_info)(retro_system_info *);
    void (*get_system_av_info)(retro_system_av_info *);
    void (*reset)(void);
    void (*run)(void);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    bool (*load_game)(const retro_game_info *);
    void (*unload_game)(void);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
} C;

static std::mutex g_winMu;
static ANativeWindow *g_win = nullptr;
static bool g_dirty = true;
static unsigned g_w = 0, g_h = 0;
static int g_fmt = 1; // 1 = XRGB8888, 2 = RGB565
static std::atomic<int> g_buttons{0}, g_tx{0}, g_ty{0};
static std::atomic<bool> g_touch{false};
static std::vector<int16_t> g_audio;
static std::string g_sys, g_save, g_romPath;
static std::vector<uint8_t> g_rom;
static bool g_loaded = false;
static double g_fps = 60.0, g_rate = 32768.0;
static unsigned g_bw = 256, g_bh = 384;

static void log_cb(int level, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(level >= 3 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "NexDS-core", fmt, ap);
    va_end(ap);
}

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd & 0xFFFF) {
        case 3: *(bool *)data = true; return true;                        // CAN_DUPE
        case 9: *(const char **)data = g_sys.c_str(); return true;        // SYSTEM_DIRECTORY
        case 31: *(const char **)data = g_save.c_str(); return true;      // SAVE_DIRECTORY
        case 10: {                                                        // PIXEL_FORMAT
            int f = *(int *)data;
            if (f == 1 || f == 2) { g_fmt = f; return true; }
            return false;
        }
        case 17: *(bool *)data = false; return true;                      // VARIABLE_UPDATE
        case 24: *(uint64_t *)data = (1ull << 1) | (1ull << 6); return true; // INPUT_DEVICE_CAPABILITIES
        case 27: ((retro_log_callback *)data)->log = log_cb; return true; // LOG_INTERFACE
        case 39: *(unsigned *)data = 0; return true;                      // LANGUAGE (ingles)
        case 47: *(int *)data = 3; return true;                           // AUDIO_VIDEO_ENABLE
        case 51: return true;                                             // INPUT_BITMASKS
        case 52: *(unsigned *)data = 0; return true;                      // CORE_OPTIONS_VERSION
        case 59: *(unsigned *)data = 0; return true;                      // MESSAGE_INTERFACE_VERSION
        case 1: case 6: case 8: case 11: case 16: case 18: case 32:
        case 34: case 35: case 37: case 42: case 44:
            return true;
        default: return false;
    }
}

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !w || !h) return;
    std::lock_guard<std::mutex> lk(g_winMu);
    if (!g_win) return;
    if (g_dirty || w != g_w || h != g_h) {
        ANativeWindow_setBuffersGeometry(g_win, (int)w, (int)h,
            g_fmt == 2 ? WINDOW_FORMAT_RGB_565 : WINDOW_FORMAT_RGBA_8888);
        g_w = w; g_h = h; g_dirty = false;
    }
    ANativeWindow_Buffer b;
    if (ANativeWindow_lock(g_win, &b, nullptr) < 0) return;
    unsigned rows = std::min(h, (unsigned)b.height);
    unsigned cols = std::min(w, (unsigned)b.width);
    if (g_fmt == 2) {
        for (unsigned y = 0; y < rows; y++)
            memcpy((uint8_t *)b.bits + (size_t)y * b.stride * 2, (const uint8_t *)data + y * pitch, cols * 2);
    } else {
        for (unsigned y = 0; y < rows; y++) {
            const uint32_t *s = (const uint32_t *)((const uint8_t *)data + y * pitch);
            uint32_t *d = (uint32_t *)b.bits + (size_t)y * b.stride;
            for (unsigned x = 0; x < cols; x++) {
                uint32_t p = s[x];
                d[x] = 0xFF000000u | ((p & 0xFFu) << 16) | (p & 0xFF00u) | ((p >> 16) & 0xFFu);
            }
        }
    }
    ANativeWindow_unlockAndPost(g_win);
}

static void audio_cb(int16_t l, int16_t r) { g_audio.push_back(l); g_audio.push_back(r); }
static size_t audio_batch_cb(const int16_t *d, size_t frames) {
    if (g_audio.size() > (1u << 20)) g_audio.clear();
    g_audio.insert(g_audio.end(), d, d + frames * 2);
    return frames;
}
static void poll_cb(void) {}
static int16_t input_cb(unsigned port, unsigned dev, unsigned idx, unsigned id) {
    if (port != 0) return 0;
    if (dev == 1) {                                  // JOYPAD
        int m = g_buttons.load();
        if (id == 256) return (int16_t)m;            // JOYPAD_MASK
        return id < 16 ? (int16_t)((m >> id) & 1) : 0;
    }
    if (dev == 6 && idx == 0) {                      // POINTER
        if (id == 0) return (int16_t)g_tx.load();
        if (id == 1) return (int16_t)g_ty.load();
        if (id == 2) return g_touch.load() ? 1 : 0;
    }
    return 0;
}

static std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}
static bool readFile(const std::string &p, std::vector<uint8_t> &out) {
    FILE *f = fopen(p.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n < 0) { fclose(f); return false; }
    out.resize((size_t)n);
    size_t r = n ? fread(out.data(), 1, (size_t)n, f) : 0;
    fclose(f);
    return r == (size_t)n;
}
static bool saveBlob(const std::string &p, const void *d, size_t n) {
    std::string t = p + ".tmp";
    FILE *f = fopen(t.c_str(), "wb");
    if (!f) return false;
    size_t w = fwrite(d, 1, n, f);
    fclose(f);
    if (w != n) { remove(t.c_str()); return false; }
    return rename(t.c_str(), p.c_str()) == 0;
}

#define SYM(name) do { *(void **)&C.name = dlsym(C.h, "retro_" #name); \
    if (!C.name) { LOGE("simbolo ausente: retro_" #name); return JNI_FALSE; } } while (0)

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_nexds_pro_Native_nativeInit(JNIEnv *env, jclass, jstring jcore, jstring jsys, jstring jsave) {
    std::string core = jstr(env, jcore);
    g_sys = jstr(env, jsys);
    g_save = jstr(env, jsave);
    memset(&C, 0, sizeof(C));
    C.h = dlopen(core.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!C.h) { LOGE("dlopen falhou: %s", dlerror()); return JNI_FALSE; }
    SYM(set_environment); SYM(set_video_refresh); SYM(set_audio_sample); SYM(set_audio_sample_batch);
    SYM(set_input_poll); SYM(set_input_state); SYM(init); SYM(deinit); SYM(api_version);
    SYM(get_system_info); SYM(get_system_av_info); SYM(reset); SYM(run);
    SYM(serialize_size); SYM(serialize); SYM(unserialize);
    SYM(load_game); SYM(unload_game); SYM(get_memory_data); SYM(get_memory_size);
    if (C.api_version() != 1) { LOGE("api libretro incompativel"); return JNI_FALSE; }
    g_fmt = 1; g_audio.clear(); g_dirty = true;
    C.set_environment(env_cb);
    C.init();
    C.set_video_refresh(video_cb);
    C.set_audio_sample(audio_cb);
    C.set_audio_sample_batch(audio_batch_cb);
    C.set_input_poll(poll_cb);
    C.set_input_state(input_cb);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_nexds_pro_Native_nativeLoadGame(JNIEnv *env, jclass, jstring jpath) {
    if (!C.h) return JNI_FALSE;
    g_romPath = jstr(env, jpath);
    retro_system_info si;
    memset(&si, 0, sizeof(si));
    C.get_system_info(&si);
    retro_game_info gi;
    memset(&gi, 0, sizeof(gi));
    gi.path = g_romPath.c_str();
    if (!si.need_fullpath) {
        if (!readFile(g_romPath, g_rom)) return JNI_FALSE;
        gi.data = g_rom.data();
        gi.size = g_rom.size();
    }
    if (!C.load_game(&gi)) { LOGE("retro_load_game falhou"); return JNI_FALSE; }
    retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    C.get_system_av_info(&av);
    if (av.timing.fps > 0) g_fps = av.timing.fps;
    if (av.timing.sample_rate > 0) g_rate = av.timing.sample_rate;
    if (av.geometry.base_width) g_bw = av.geometry.base_width;
    if (av.geometry.base_height) g_bh = av.geometry.base_height;
    g_loaded = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_nexds_pro_Native_nativeRun(JNIEnv *, jclass) { if (g_loaded) C.run(); }

JNIEXPORT jint JNICALL Java_com_nexds_pro_Native_nativeAudio(JNIEnv *env, jclass, jshortArray arr) {
    size_t n = std::min((size_t)env->GetArrayLength(arr), g_audio.size());
    n &= ~(size_t)1;
    if (!n) return 0;
    env->SetShortArrayRegion(arr, 0, (jsize)n, g_audio.data());
    g_audio.erase(g_audio.begin(), g_audio.begin() + n);
    return (jint)n;
}

JNIEXPORT jdouble JNICALL Java_com_nexds_pro_Native_nativeFps(JNIEnv *, jclass) { return g_fps; }
JNIEXPORT jdouble JNICALL Java_com_nexds_pro_Native_nativeSampleRate(JNIEnv *, jclass) { return g_rate; }
JNIEXPORT jint JNICALL Java_com_nexds_pro_Native_nativeWidth(JNIEnv *, jclass) { return (jint)g_bw; }
JNIEXPORT jint JNICALL Java_com_nexds_pro_Native_nativeHeight(JNIEnv *, jclass) { return (jint)g_bh; }

JNIEXPORT void JNICALL Java_com_nexds_pro_Native_nativeSetSurface(JNIEnv *env, jclass, jobject surface) {
    std::lock_guard<std::mutex> lk(g_winMu);
    if (g_win) { ANativeWindow_release(g_win); g_win = nullptr; }
    if (surface) g_win = ANativeWindow_fromSurface(env, surface);
    g_dirty = true;
}

JNIEXPORT void JNICALL Java_com_nexds_pro_Native_nativeSetButtons(JNIEnv *, jclass, jint mask) { g_buttons = mask; }
JNIEXPORT void JNICALL Java_com_nexds_pro_Native_nativeSetTouch(JNIEnv *, jclass, jint x, jint y, jboolean p) {
    g_tx = x; g_ty = y; g_touch = (p == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL Java_com_nexds_pro_Native_nativeSaveState(JNIEnv *env, jclass, jstring jp) {
    if (!g_loaded) return JNI_FALSE;
    size_t n = C.serialize_size();
    if (!n) return JNI_FALSE;
    std::vector<uint8_t> b(n);
    if (!C.serialize(b.data(), n)) return JNI_FALSE;
    return saveBlob(jstr(env, jp), b.data(), n) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nexds_pro_Native_nativeLoadState(JNIEnv *env, jclass, jstring jp) {
    if (!g_loaded) return JNI_FALSE;
    std::vector<uint8_t> b;
    if (!readFile(jstr(env, jp), b) || b.empty()) return JNI_FALSE;
    return C.unserialize(b.data(), b.size()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nexds_pro_Native_nativeSaveSram(JNIEnv *env, jclass, jstring jp) {
    if (!g_loaded) return JNI_FALSE;
    size_t n = C.get_memory_size(0);
    void *p = C.get_memory_data(0);
    if (!p || !n) return JNI_FALSE;
    return saveBlob(jstr(env, jp), p, n) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nexds_pro_Native_nativeLoadSram(JNIEnv *env, jclass, jstring jp) {
    if (!g_loaded) return JNI_FALSE;
    std::vector<uint8_t> b;
    if (!readFile(jstr(env, jp), b) || b.empty()) return JNI_FALSE;
    size_t n = C.get_memory_size(0);
    void *p = C.get_memory_data(0);
    if (!p || !n) return JNI_FALSE;
    memcpy(p, b.data(), std::min(n, b.size()));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_nexds_pro_Native_nativeReset(JNIEnv *, jclass) { if (g_loaded) C.reset(); }

JNIEXPORT void JNICALL Java_com_nexds_pro_Native_nativeUnload(JNIEnv *, jclass) {
    if (!C.h) return;
    if (g_loaded) { C.unload_game(); g_loaded = false; }
    C.deinit();
    dlclose(C.h);
    memset(&C, 0, sizeof(C));
    g_rom.clear(); g_rom.shrink_to_fit(); g_audio.clear();
}

} // extern "C"
