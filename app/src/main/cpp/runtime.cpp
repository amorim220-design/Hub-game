#include <android/native_activity.h>
#include <android/native_window.h>
#include <android/input.h>
#include <android/looper.h>
#include <android/keycodes.h>
#include <android/log.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <dlfcn.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "hook_impl.h"
#include "dxvk_bridge.h"
#include "patcher.h"
#include "process_runtime.h"

#undef LOG_TAG
#define LOG_TAG "VulkanCompatRuntime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct GraphicsSettings {
    uint32_t render_width = 1280;
    uint32_t render_height = 720;
    int fps_limit = 60;
    bool widescreen = true;
    bool minimap_fix = true;
    bool pipeline_cache = true;
    bool cpu_textures = false;
    bool triple_buffering = true;
};

// Entry point exported by the user runtime package (optional).
typedef void (*game_tick_fn)();

struct EngineRuntimeState {
    std::mutex state_mutex;
    ANativeActivity* activity = nullptr;
    ANativeWindow* window = nullptr;
    GraphicsSettings settings;
    std::string runtime_so_path;
    void* runtime_module_handle = nullptr;

    std::atomic<bool> is_running{false};
    std::atomic<bool> is_paused{false};
    std::thread game_thread;

    game_tick_fn game_tick = nullptr;

    // Vulkan objects
    VkInstance vk_instance = VK_NULL_HANDLE;
    VkSurfaceKHR vk_surface = VK_NULL_HANDLE;
    VkPhysicalDevice vk_physical_device = VK_NULL_HANDLE;
    VkDevice vk_device = VK_NULL_HANDLE;

    // Performance telemetry
    std::atomic<float> current_fps{0.0f};
    std::atomic<uint64_t> total_frames{0};
};

static EngineRuntimeState g_engine;

// ---------------------------------------------------------------------------
// Runtime em processo separado (modelo B)
// ---------------------------------------------------------------------------
static ProcessRuntime g_process_runtime;
static std::atomic<bool> g_process_mode{false};
static std::atomic<bool> g_frame_pending{false};

// ---------------------------------------------------------------------------
// Apresentação do framebuffer compartilhado (modo processo)
// ---------------------------------------------------------------------------
// No modo processo não há Vulkan: o guest desenha num framebuffer de CPU e o
// app publica na surface com ANativeWindow_lock/unlockAndPost. É o caminho
// simples e previsível; o modo nativo (DXVK no processo) continua usando a
// surface Vulkan.
static bool present_framebuffer(ANativeWindow* window, const uint8_t* fb,
                                uint32_t srcWidth, uint32_t srcHeight) {
    if (!window || !fb || srcWidth == 0 || srcHeight == 0) return false;

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) {
        LOGW("ANativeWindow_lock falhou");
        return false;
    }

    uint8_t* dst = static_cast<uint8_t*>(buffer.bits);
    const size_t dstStride = (size_t)buffer.stride * 4;

    if ((uint32_t)buffer.width == srcWidth && (uint32_t)buffer.height == srcHeight) {
        for (int y = 0; y < (int)srcHeight; y++) {
            memcpy(dst + (size_t)y * dstStride, fb + (size_t)y * srcWidth * 4, (size_t)srcWidth * 4);
        }
    } else {
        // Escala nearest-neighbour: a resolução interna pode ser menor que a
        // janela (é o modo "resolução interna 720p" da interface).
        for (int y = 0; y < buffer.height; y++) {
            uint32_t sy = (uint32_t)((int64_t)y * srcHeight / buffer.height);
            if (sy >= srcHeight) sy = srcHeight - 1;
            const uint8_t* srcRow = fb + (size_t)sy * srcWidth * 4;
            uint8_t* dstRow = dst + (size_t)y * dstStride;
            for (int x = 0; x < buffer.width; x++) {
                uint32_t sx = (uint32_t)((int64_t)x * srcWidth / buffer.width);
                if (sx >= srcWidth) sx = srcWidth - 1;
                memcpy(dstRow + (size_t)x * 4, srcRow + (size_t)sx * 4, 4);
            }
        }
    }

    ANativeWindow_unlockAndPost(window);
    return true;
}

// ---------------------------------------------------------------------------
// Carregamento das libs de hook (ordem obrigatória)
// ---------------------------------------------------------------------------
// As libs de hook precisam entrar no ESCOPO GLOBAL (RTLD_GLOBAL) e ser
// carregadas ANTES de qualquer biblioteca do runtime do jogo. Só assim os
// símbolos que elas exportam (android_dlopen_ext, fopen, open,
// gsl_memory_alloc_pure...) são os primeiros candidatos na resolução do
// linker para as chamadas feitas pelo runtime do jogo.
//
// System.loadLibrary() NÃO garante RTLD_GLOBAL, por isso o carregamento é
// feito aqui, em JNI_OnLoad — o primeiro código nosso a rodar no processo.
static bool g_hooks_loaded = false;

static bool load_hook_libraries() {
    static const char* kHookLibs[] = {
        "libhook_impl.so",
        "libmain_hook.so",
        "libfile_redirect_hook.so",
        "libgsl_alloc_hook.so",
    };

    bool all_ok = true;
    for (const char* name : kHookLibs) {
        void* handle = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            LOGE("hook library could not be loaded: %s (%s)", name, dlerror());
            all_ok = false;
            continue;
        }
        LOGI("hook library loaded (RTLD_GLOBAL): %s", name);
    }
    g_hooks_loaded = all_ok;
    return all_ok;
}

// --- Vulkan Initialization & Setup ---
static bool init_vulkan_surface(ANativeWindow* window) {
    if (!window) return false;

    // Se o runtime do jogo (DXVK embutido) está ativo, ELE é o dono da
    // VkInstance/VkSurface. Criar uma segunda instância aqui quebraria a
    // apresentação (a surface pertence a outra instância) — neste caso o
    // app só entrega a ANativeWindow para o DXVK.
    if (dxvk_bridge_is_active()) {
        LOGI("DXVK runtime active: the runtime owns the Vulkan instance/surface");
        return false;
    }

    // Query available instance extensions
    uint32_t ext_count = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &ext_count, nullptr);
    std::vector<VkExtensionProperties> available_exts(ext_count);
    vkEnumerateInstanceExtensionProperties(nullptr, &ext_count, available_exts.data());

    std::vector<const char*> enabled_exts;
    enabled_exts.push_back("VK_KHR_surface");
    enabled_exts.push_back("VK_KHR_android_surface");

    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "VulkanCompatShell";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "DXVK-Compat";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo create_info = {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;
    create_info.enabledExtensionCount = static_cast<uint32_t>(enabled_exts.size());
    create_info.ppEnabledExtensionNames = enabled_exts.data();

    VkResult res = vkCreateInstance(&create_info, nullptr, &g_engine.vk_instance);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateInstance failed with error %d", res);
        return false;
    }
    LOGI("Vulkan 1.1 instance created successfully: %p", (void*)g_engine.vk_instance);

    // Create Android surface
    VkAndroidSurfaceCreateInfoKHR surface_info = {};
    surface_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surface_info.window = window;

    res = vkCreateAndroidSurfaceKHR(g_engine.vk_instance, &surface_info, nullptr, &g_engine.vk_surface);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed with error %d", res);
        return false;
    }

    LOGI("Vulkan Android Surface created: %p for ANativeWindow=%p", (void*)g_engine.vk_surface, window);
    return true;
}

static void cleanup_vulkan() {
    if (g_engine.vk_instance != VK_NULL_HANDLE) {
        if (g_engine.vk_surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(g_engine.vk_instance, g_engine.vk_surface, nullptr);
            g_engine.vk_surface = VK_NULL_HANDLE;
        }
        vkDestroyInstance(g_engine.vk_instance, nullptr);
        g_engine.vk_instance = VK_NULL_HANDLE;
    }
}

// --- Dedicated Game Execution Loop ---
static void game_loop_worker() {
    LOGI("Game execution loop thread started (TID=%d)", gettid());

    using clock = std::chrono::high_resolution_clock;
    auto last_fps_update = clock::now();
    uint32_t frame_counter = 0;

    while (g_engine.is_running.load()) {
        if (g_engine.is_paused.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        auto frame_start = clock::now();

        game_tick_fn tick = nullptr;

        // 1a. Modo processo: o guest desenha no framebuffer compartilhado e
        //     avisa pelo socket. O app publica o quadro na surface.
        if (g_process_mode.load()) {
            char message = 0;
            while (g_process_runtime.pollGuestMessage(message)) {
                if (message == 'F') {
                    g_frame_pending.store(true);
                }
            }
            if (g_frame_pending.exchange(false)) {
                ANativeWindow* window = nullptr;
                {
                    std::lock_guard<std::mutex> lock(g_engine.state_mutex);
                    window = g_engine.window;
                }
                present_framebuffer(window,
                                   g_process_runtime.framebuffer(),
                                   g_process_runtime.framebufferWidth(),
                                   g_process_runtime.framebufferHeight());
                frame_counter++;
                g_engine.total_frames++;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
        } else {
            // 1b. Modo nativo: dirige o runtime carregado no próprio processo.
            //     O runtime pode não exportar um tick: nesse caso ele conduz o
            //     próprio loop e aqui só mantemos a janela viva.
            {
                std::lock_guard<std::mutex> lock(g_engine.state_mutex);
                tick = g_engine.game_tick;
            }
            if (tick) {
                tick();
                frame_counter++;
                g_engine.total_frames++;
            } else {
                // Nada a dirigir: dormir em vez de queimar um núcleo.
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }

        // 2. Telemetry update every second
        auto now = clock::now();
        std::chrono::duration<float> elapsed = now - last_fps_update;
        if (elapsed.count() >= 1.0f) {
            float current = static_cast<float>(frame_counter) / elapsed.count();
            g_engine.current_fps.store(current);
            frame_counter = 0;
            last_fps_update = now;
        }

        // 3. FPS Limiter calculation
        int limit = g_engine.settings.fps_limit;
        if (limit > 0 && tick) {
            std::chrono::microseconds target_frame_time(1000000 / limit);
            auto frame_duration = std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - frame_start);
            if (frame_duration < target_frame_time) {
                std::this_thread::sleep_for(target_frame_time - frame_duration);
            }
        }
    }

    LOGI("Game loop thread terminating gracefully");
}

// --- Input dispatch ---
// ANativeActivity does not deliver input events through a callback: the
// application owns the AInputQueue and has to pump it. The game runtime
// package receives the events through an optional exported entry point:
//
//     int pcshim_input_event(const AInputEvent* event);   // return non-zero to consume
//
// Without it the events are only logged (useful to debug the mapping).
typedef int (*pcshim_input_fn)(const AInputEvent* event);

static const int kInputIdent = 1;
static AInputQueue* g_input_queue = nullptr;
static std::thread g_input_thread;
static std::mutex g_input_mutex;
static pcshim_input_fn g_runtime_input_fn = nullptr;

static int32_t onInputEvent(ANativeActivity* activity, AInputEvent* event) {
    if (!event) return 0;

    int32_t event_type = AInputEvent_getType(event);

    if (event_type == AINPUT_EVENT_TYPE_KEY) {
        int32_t key_code = AKeyEvent_getKeyCode(event);
        int32_t action = AKeyEvent_getAction(event);
        if (action == AKEY_EVENT_ACTION_DOWN || action == AKEY_EVENT_ACTION_UP) {
            LOGI("key event: code=%d action=%d", key_code, action);
        }
    } else if (event_type == AINPUT_EVENT_TYPE_MOTION) {
        int32_t action = AMotionEvent_getAction(event) & AMOTION_EVENT_ACTION_MASK;
        size_t pointer_count = AMotionEvent_getPointerCount(event);
        for (size_t i = 0; i < pointer_count; i++) {
            float x = AMotionEvent_getX(event, i);
            float y = AMotionEvent_getY(event, i);
            (void)x; (void)y; (void)action;
        }
    }

    pcshim_input_fn fn = g_runtime_input_fn;
    if (fn) {
        return fn(event) ? 1 : 0;
    }

    // Modo processo: o evento vai para o guest pelo socket.
    if (g_process_mode.load()) {
        PcshimInputEvent out;
        out.ts = (int64_t)std::chrono::high_resolution_clock::now().time_since_epoch().count() / 1000000;
        if (event_type == AINPUT_EVENT_TYPE_KEY) {
            out.type = (AKeyEvent_getAction(event) == AKEY_EVENT_ACTION_DOWN)
                           ? PCSHIM_INPUT_KEY_DOWN
                           : PCSHIM_INPUT_KEY_UP;
            out.action = (uint32_t)AKeyEvent_getAction(event);
            out.a = AKeyEvent_getKeyCode(event);
        } else if (event_type == AINPUT_EVENT_TYPE_MOTION) {
            out.type = PCSHIM_INPUT_MOTION;
            out.action = (uint32_t)(AMotionEvent_getAction(event) & AMOTION_EVENT_ACTION_MASK);
            out.a = 0;
            out.b = (int32_t)(AMotionEvent_getX(event, 0) * 65536.0f);
            out.c = (int32_t)(AMotionEvent_getY(event, 0) * 65536.0f);
        } else {
            out.type = PCSHIM_INPUT_FOCUS;
        }
        g_process_runtime.sendInput(out);
    }

    return 1;
}

static int input_callback(int ident, int fd, void* data) {
    (void)ident;
    (void)fd;
    AInputQueue* queue = (AInputQueue*)data;
    if (!queue) return 0;

    AInputEvent* event = nullptr;
    if (AInputQueue_getEvent(queue, &event) >= 0 && event) {
        onInputEvent(g_engine.activity, event);
        AInputQueue_finishEvent(queue, event, 1);
    }
    return 1;
}

static void input_pump_thread() {
    // AInputQueue_getEvent() BLOCKS when there is nothing to read, so it must
    // not be called directly in a loop: the thread would never notice the
    // shutdown flag. The documented pattern is to attach the queue to a looper
    // with a callback and poll the looper with a timeout: the callback runs
    // exactly when an event is pending.
    ALooper* looper = ALooper_prepare(0);
    LOGI("Input pump thread started (looper=%p)", (void*)looper);

    bool attached = false;
    while (g_engine.is_running.load()) {
        AInputQueue* queue = nullptr;
        {
            std::lock_guard<std::mutex> lock(g_input_mutex);
            queue = g_input_queue;
        }
        if (!queue) {
            attached = false;
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            continue;
        }

        if (!attached) {
            AInputQueue_attachLooper(queue, looper, kInputIdent, input_callback, queue);
            attached = true;
        }

        // Wait up to 50 ms, so the thread stays responsive to the shutdown flag.
        int ident = ALooper_pollOnce(50, nullptr, nullptr, nullptr);
        if (ident < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }
    }
    LOGI("Input pump thread terminated");
}

// --- Native Activity Lifecycle Callbacks ---
static void onNativeWindowCreated(ANativeActivity* activity, ANativeWindow* window) {
    LOGI("ANativeActivity callback: onNativeWindowCreated window=%p", window);
    std::lock_guard<std::mutex> lock(g_engine.state_mutex);
    g_engine.window = window;

    int32_t win_width = ANativeWindow_getWidth(window);
    int32_t win_height = ANativeWindow_getHeight(window);
    LOGI("Window dimensions: %dx%d", win_width, win_height);

    // Set buffer geometry according to chosen render resolution
    uint32_t target_w = g_engine.settings.render_width;
    uint32_t target_h = g_engine.settings.render_height;

    // No modo processo o guest entrega um framebuffer de CPU: a surface é
    // usada só para publicar pixels, então não há surface Vulkan a criar.
    if (g_process_mode.load()) {
        ANativeWindow_setBuffersGeometry(window, target_w, target_h, WINDOW_FORMAT_RGBA_8888);
    } else {
        if (target_w > 0 && target_h > 0) {
            ANativeWindow_setBuffersGeometry(window, target_w, target_h, WINDOW_FORMAT_RGBA_8888);
        }

        // Forward to DXVK bridge
        dxvk_bridge_set_window(window);
        dxvk_bridge_set_render_size(target_w, target_h);

        // Initialize Vulkan surface
        init_vulkan_surface(window);
    }

    // Start game thread if not running
    if (!g_engine.is_running.load()) {
        g_engine.is_running.store(true);
        g_engine.is_paused.store(false);
        g_engine.game_thread = std::thread(game_loop_worker);
    } else {
        g_engine.is_paused.store(false);
    }
}

static void onNativeWindowDestroyed(ANativeActivity* activity, ANativeWindow* window) {
    LOGI("ANativeActivity callback: onNativeWindowDestroyed window=%p", window);
    std::lock_guard<std::mutex> lock(g_engine.state_mutex);
    g_engine.is_paused.store(true);
    dxvk_bridge_set_window(nullptr);
    cleanup_vulkan();
    g_engine.window = nullptr;
}

static void onNativeWindowResized(ANativeActivity* activity, ANativeWindow* window) {
    int32_t w = ANativeWindow_getWidth(window);
    int32_t h = ANativeWindow_getHeight(window);
    LOGI("ANativeActivity callback: onNativeWindowResized %dx%d", w, h);
}

static void onNativeWindowRedrawNeeded(ANativeActivity* activity, ANativeWindow* window) {
    LOGI("ANativeActivity callback: onNativeWindowRedrawNeeded");
}

static void onInputQueueCreated(ANativeActivity* activity, AInputQueue* queue) {
    LOGI("ANativeActivity callback: onInputQueueCreated");
    {
        std::lock_guard<std::mutex> lock(g_input_mutex);
        g_input_queue = queue;
    }
    // Start pumping the queue: NativeActivity has no input callback, the
    // application is responsible for it.
    if (!g_engine.is_running.load()) {
        g_engine.is_running.store(true);
        g_engine.game_thread = std::thread(game_loop_worker);
    }
    static std::once_flag input_flag;
    std::call_once(input_flag, []() {
        g_input_thread = std::thread(input_pump_thread);
    });
}

static void onInputQueueDestroyed(ANativeActivity* activity, AInputQueue* queue) {
    LOGI("ANativeActivity callback: onInputQueueDestroyed");
    std::lock_guard<std::mutex> lock(g_input_mutex);
    g_input_queue = nullptr;
}

static void onStart(ANativeActivity* activity) {
    LOGI("ANativeActivity onStart");
}

static void onResume(ANativeActivity* activity) {
    LOGI("ANativeActivity onResume");
    g_engine.is_paused.store(false);
}

static void onPause(ANativeActivity* activity) {
    LOGI("ANativeActivity onPause");
    g_engine.is_paused.store(true);
}

static void onStop(ANativeActivity* activity) {
    LOGI("ANativeActivity onStop");
}

static void onDestroy(ANativeActivity* activity) {
    LOGI("ANativeActivity onDestroy");
    g_engine.is_running.store(false);
    if (g_engine.game_thread.joinable()) {
        g_engine.game_thread.join();
    }
    if (g_input_thread.joinable()) {
        g_input_thread.join();
    }
    if (g_process_mode.load()) {
        g_process_runtime.stop();
        g_process_mode.store(false);
    }
    cleanup_vulkan();
    if (g_engine.runtime_module_handle) {
        dlclose(g_engine.runtime_module_handle);
        g_engine.runtime_module_handle = nullptr;
    }
}

// Entry point declared in AndroidManifest.xml
extern "C" __attribute__((visibility("default")))
void ANativeActivity_onCreate(ANativeActivity* activity, void* savedState, size_t savedStateSize) {
    LOGI("ANativeActivity_onCreate entry: activity=%p", activity);

    // Safety net: JNI_OnLoad already did this, but the activity can be
    // started without the Java bridge having touched NativeBridge first.
    if (!g_hooks_loaded) {
        load_hook_libraries();
    }

    g_engine.activity = activity;
    activity->callbacks->onStart = onStart;
    activity->callbacks->onResume = onResume;
    activity->callbacks->onPause = onPause;
    activity->callbacks->onStop = onStop;
    activity->callbacks->onDestroy = onDestroy;
    activity->callbacks->onNativeWindowCreated = onNativeWindowCreated;
    activity->callbacks->onNativeWindowDestroyed = onNativeWindowDestroyed;
    activity->callbacks->onNativeWindowResized = onNativeWindowResized;
    activity->callbacks->onNativeWindowRedrawNeeded = onNativeWindowRedrawNeeded;
    activity->callbacks->onInputQueueCreated = onInputQueueCreated;
    activity->callbacks->onInputQueueDestroyed = onInputQueueDestroyed;

    // Pre-initialize DXVK bridge
    dxvk_bridge_init(nullptr);
}

// --- JNI Bridge APIs for Launcher & Management ---

// Runs as soon as the library is loaded: the earliest point we control inside
// the process, so the hook libraries enter the global scope before anything
// else is dlopen'ed.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad: installing hook libraries into the global scope");
    load_hook_libraries();
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_NativeBridge_setGameDirectory(JNIEnv* env, jclass clazz, jstring game_dir) {
    if (!game_dir) return;
    const char* str = env->GetStringUTFChars(game_dir, nullptr);
    hook_set_game_dir(str);
    env->ReleaseStringUTFChars(game_dir, str);
}

JNIEXPORT void JNICALL
Java_com_example_NativeBridge_setCustomDriverPath(JNIEnv* env, jclass clazz, jstring driver_path) {
    if (!driver_path) {
        hook_set_custom_driver_path("");
        return;
    }
    const char* str = env->GetStringUTFChars(driver_path, nullptr);
    hook_set_custom_driver_path(str);
    env->ReleaseStringUTFChars(driver_path, str);
}

JNIEXPORT void JNICALL
Java_com_example_NativeBridge_setGraphicsOptions(
    JNIEnv* env, jclass clazz,
    jint width, jint height, jint fps_limit,
    jboolean widescreen, jboolean minimap_fix,
    jboolean pipeline_cache, jboolean cpu_textures, jboolean triple_buffering
) {
    std::lock_guard<std::mutex> lock(g_engine.state_mutex);
    g_engine.settings.render_width = (uint32_t)width;
    g_engine.settings.render_height = (uint32_t)height;
    g_engine.settings.fps_limit = (int)fps_limit;
    g_engine.settings.widescreen = (bool)widescreen;
    g_engine.settings.minimap_fix = (bool)minimap_fix;
    g_engine.settings.pipeline_cache = (bool)pipeline_cache;
    g_engine.settings.cpu_textures = (bool)cpu_textures;
    g_engine.settings.triple_buffering = (bool)triple_buffering;

    LOGI("Updated Graphics Options: %ux%u @ %d FPS, Widescreen=%d, Minimap=%d, Cache=%d",
         width, height, fps_limit, widescreen, minimap_fix, pipeline_cache);

    // Apply to DXVK config
    const char* game_dir = hook_get_game_dir();
    if (game_dir && game_dir[0] != '\0') {
        dxvk_bridge_write_config(
            game_dir,
            fps_limit,
            pipeline_cache,
            widescreen,
            cpu_textures,
            triple_buffering
        );
    }

    if (g_engine.window) {
        ANativeWindow_setBuffersGeometry(g_engine.window, width, height, WINDOW_FORMAT_RGBA_8888);
        dxvk_bridge_set_render_size(width, height);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_NativeBridge_loadGameRuntime(JNIEnv* env, jclass clazz, jstring runtime_so_path) {
    if (!runtime_so_path) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(runtime_so_path, nullptr);
    LOGI("NativeBridge: Loading game runtime: %s", path);

    // The hooks must be in place BEFORE the runtime is loaded, otherwise the
    // runtime resolves the symbols it imports (fopen, android_dlopen_ext,
    // gsl_memory_alloc_pure) to the system libraries and the interposition
    // never happens.
    if (!g_hooks_loaded) {
        load_hook_libraries();
    }

    // The runtime directory lets the hooks find the patched counterpart of the
    // engine library when the runtime asks for it by bare soname.
    {
        std::string p(path);
        size_t slash = p.find_last_of('/');
        hook_set_runtime_dir(slash == std::string::npos ? "" : p.substr(0, slash).c_str());
    }

    // Load with RTLD_NOW | RTLD_GLOBAL so the runtime's own symbols are
    // visible to the rest of the process and the DXVK bridge can find them.
    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOGE("Failed to dlopen game runtime (%s): %s", path, dlerror());
        env->ReleaseStringUTFChars(runtime_so_path, path);
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> lock(g_engine.state_mutex);
        g_engine.runtime_so_path = path;
        g_engine.runtime_module_handle = handle;
        // Optional entry point: a runtime that drives itself does not export it.
        g_engine.game_tick = (game_tick_fn)dlsym(handle, "game_main_tick");
        // Optional input entry point of the runtime package.
        g_runtime_input_fn = (pcshim_input_fn)dlsym(handle, "pcshim_input_event");
    }
    LOGI("Game runtime loaded, tick entry point: %s",
         g_engine.game_tick ? "found" : "absent (runtime drives its own loop)");

    // Try resolving DXVK symbols inside the loaded runtime module
    dxvk_bridge_init(handle);

    env->ReleaseStringUTFChars(runtime_so_path, path);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_NativeBridge_launchProcessRuntime(
    JNIEnv* env, jclass clazz,
    jstring runtime_dir, jstring launcher_rel, jstring prefix_rel, jstring exe_rel,
    jstring launcher_args, jstring env_pairs, jint width, jint height) {

    if (!runtime_dir || !launcher_rel || !prefix_rel || !exe_rel) return nullptr;

    const char* c_runtime = env->GetStringUTFChars(runtime_dir, nullptr);
    const char* c_launcher = env->GetStringUTFChars(launcher_rel, nullptr);
    const char* c_prefix = env->GetStringUTFChars(prefix_rel, nullptr);
    const char* c_exe = env->GetStringUTFChars(exe_rel, nullptr);

    std::string runtimeDir(c_runtime);
    std::string prefixDir = runtimeDir + "/" + std::string(c_prefix);
    std::string launcherPath = runtimeDir + "/" + std::string(c_launcher);

    // O executável do jogo vive no drive C do prefixo do Wine.
    std::string exePath = std::string(c_exe);
    if (exePath.rfind("drive_c/", 0) != 0) {
        exePath = prefixDir + "/drive_c/" + exePath;
    } else {
        exePath = prefixDir + "/" + exePath;
    }

    ProcessRuntimeConfig config;
    config.launcher = launcherPath;
    config.workingDir = prefixDir;
    config.width = width > 0 ? width : 1280;
    config.height = height > 0 ? height : 720;

    // Argumentos do lançador (separados por ';') e o executável do jogo por
    // último: "box64 wine64 /caminho/Game.exe".
    if (launcher_args) {
        const char* c_args = env->GetStringUTFChars(launcher_args, nullptr);
        std::string args(c_args);
        env->ReleaseStringUTFChars(launcher_args, c_args);
        size_t start = 0;
        while (start <= args.size()) {
            size_t sep = args.find(';', start);
            std::string piece = args.substr(start, sep == std::string::npos ? std::string::npos : sep - start);
            if (!piece.empty()) config.args.push_back(piece);
            if (sep == std::string::npos) break;
            start = sep + 1;
        }
    }
    config.args.push_back(exePath);

    // Variáveis de ambiente do pacote ("KEY=VAL;KEY=VAL").
    if (env_pairs) {
        const char* c_env = env->GetStringUTFChars(env_pairs, nullptr);
        std::string pairs(c_env);
        env->ReleaseStringUTFChars(env_pairs, c_env);
        size_t start = 0;
        while (start <= pairs.size()) {
            size_t sep = pairs.find(';', start);
            std::string piece = pairs.substr(start, sep == std::string::npos ? std::string::npos : sep - start);
            size_t eq = piece.find('=');
            if (eq != std::string::npos && eq > 0) {
                config.env[piece.substr(0, eq)] = piece.substr(eq + 1);
            }
            if (sep == std::string::npos) break;
            start = sep + 1;
        }
    }
    // O prefixo do Wine e as DLLs do DXVK ficam sempre acessíveis.
    config.env["WINEPREFIX"] = prefixDir;
    if (config.env.find("WINEDLLPATH") == config.env.end()) {
        config.env["WINEDLLPATH"] = prefixDir + "/drive_c/windows/system32:" +
                                    prefixDir + "/drive_c/windows/syswow64";
    }
    config.env["PCSHIM_EXE"] = exePath;

    env->ReleaseStringUTFChars(runtime_dir, c_runtime);
    env->ReleaseStringUTFChars(launcher_rel, c_launcher);
    env->ReleaseStringUTFChars(prefix_rel, c_prefix);
    env->ReleaseStringUTFChars(exe_rel, c_exe);

    std::string error;
    if (!g_process_runtime.start(config, error)) {
        LOGE("Falha ao iniciar o runtime em processo: %s", error.c_str());
        return env->NewStringUTF(error.c_str());
    }

    g_process_mode.store(true);
    {
        std::lock_guard<std::mutex> lock(g_engine.state_mutex);
        g_engine.runtime_so_path = launcherPath;
    }
    LOGI("Runtime em processo iniciado: %s (prefixo=%s, exe=%s)", launcherPath.c_str(),
         prefixDir.c_str(), exePath.c_str());
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_com_example_NativeBridge_hookStatus(JNIEnv* env, jclass clazz) {
    bool runtime_loaded = false;
    bool tick_present = false;
    std::string runtime_path;
    {
        std::lock_guard<std::mutex> lock(g_engine.state_mutex);
        runtime_loaded = g_engine.runtime_module_handle != nullptr;
        tick_present = g_engine.game_tick != nullptr;
        runtime_path = g_engine.runtime_so_path;
    }

    std::string json = "{";
    json += "\"hooksLoaded\":" + std::string(g_hooks_loaded ? "true" : "false");
    json += ",\"dxvkActive\":" + std::string(dxvk_bridge_is_active() ? "true" : "false");
    json += ",\"runtimeLoaded\":" + std::string(runtime_loaded ? "true" : "false");
    json += ",\"runtimeTick\":" + std::string(tick_present ? "true" : "false");
    json += ",\"processMode\":" + std::string(g_process_mode.load() ? "true" : "false");
    json += ",\"guestReady\":" + std::string(g_process_runtime.isGuestReady() ? "true" : "false");
    json += ",\"childPid\":" + std::to_string(g_process_runtime.childPid());
    json += ",\"runtimePath\":\"" + runtime_path + "\"";
    json += ",\"gameDir\":\"" + std::string(hook_get_game_dir()) + "\"";
    json += ",\"patchDir\":\"" + std::string(hook_get_patch_dir()) + "\"";
    json += ",\"customDriver\":\"" + std::string(hook_get_custom_driver_path()) + "\"";
    json += "}";
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_NativeBridge_probeVulkanGpu(JNIEnv* env, jclass clazz) {
    LOGI("Probing Vulkan GPU capabilities...");

    uint32_t inst_ext_count = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &inst_ext_count, nullptr);
    std::vector<VkExtensionProperties> inst_exts(inst_ext_count);
    vkEnumerateInstanceExtensionProperties(nullptr, &inst_ext_count, inst_exts.data());

    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "GPUProbe";
    app_info.applicationVersion = 1;
    app_info.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo create_info = {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance probe_instance = VK_NULL_HANDLE;
    VkResult res = vkCreateInstance(&create_info, nullptr, &probe_instance);
    if (res != VK_SUCCESS) {
        std::string err_json = "{\"status\":\"error\",\"message\":\"Failed to create Vulkan 1.1 instance\"}";
        return env->NewStringUTF(err_json.c_str());
    }

    uint32_t device_count = 0;
    vkEnumeratePhysicalDevices(probe_instance, &device_count, nullptr);
    if (device_count == 0) {
        vkDestroyInstance(probe_instance, nullptr);
        std::string err_json = "{\"status\":\"error\",\"message\":\"No Vulkan physical devices found\"}";
        return env->NewStringUTF(err_json.c_str());
    }

    std::vector<VkPhysicalDevice> devices(device_count);
    vkEnumeratePhysicalDevices(probe_instance, &device_count, devices.data());

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(devices[0], &props);

    uint32_t dev_ext_count = 0;
    vkEnumerateDeviceExtensionProperties(devices[0], nullptr, &dev_ext_count, nullptr);
    std::vector<VkExtensionProperties> dev_exts(dev_ext_count);
    vkEnumerateDeviceExtensionProperties(devices[0], nullptr, &dev_ext_count, dev_exts.data());

    vkDestroyInstance(probe_instance, nullptr);

    char json_buffer[4096];
    snprintf(json_buffer, sizeof(json_buffer),
        "{"
        "\"status\":\"ok\","
        "\"deviceName\":\"%s\","
        "\"vendorId\":%u,"
        "\"deviceId\":%u,"
        "\"apiVersion\":\"%u.%u.%u\","
        "\"driverVersion\":\"0x%x\","
        "\"deviceType\":%u,"
        "\"extensionCount\":%u"
        "}",
        props.deviceName,
        props.vendorID,
        props.deviceID,
        VK_VERSION_MAJOR(props.apiVersion),
        VK_VERSION_MINOR(props.apiVersion),
        VK_VERSION_PATCH(props.apiVersion),
        props.driverVersion,
        props.deviceType,
        dev_ext_count
    );

    LOGI("GPU Probe JSON output: %s", json_buffer);
    return env->NewStringUTF(json_buffer);
}

JNIEXPORT jstring JNICALL
Java_com_example_NativeBridge_applyBinaryPatches(
    JNIEnv* env, jclass clazz,
    jstring src_elf, jstring out_dir, jint patch_id, jstring patch_json
) {
    if (!src_elf || !out_dir || !patch_json) return nullptr;

    const char* c_src = env->GetStringUTFChars(src_elf, nullptr);
    const char* c_out = env->GetStringUTFChars(out_dir, nullptr);
    const char* c_json = env->GetStringUTFChars(patch_json, nullptr);

    std::vector<BinaryPatchEntry> patches;
    parse_patches_from_json(c_json, patches);

    std::string patched_path;
    bool ok = patch_elf_library(c_src, c_out, (int)patch_id, patches, patched_path);

    env->ReleaseStringUTFChars(src_elf, c_src);
    env->ReleaseStringUTFChars(out_dir, c_out);
    env->ReleaseStringUTFChars(patch_json, c_json);

    if (ok) {
        hook_set_patch_dir(c_out);
        return env->NewStringUTF(patched_path.c_str());
    }
    return nullptr;
}

JNIEXPORT jfloat JNICALL
Java_com_example_NativeBridge_getTelemetryFps(JNIEnv* env, jclass clazz) {
    return g_engine.current_fps.load();
}

}
