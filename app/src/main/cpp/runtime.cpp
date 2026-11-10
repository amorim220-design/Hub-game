#include <android/native_activity.h>
#include <android/native_window.h>
#include <android/input.h>
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

// --- Vulkan Initialization & Setup ---
static bool init_vulkan_surface(ANativeWindow* window) {
    if (!window) return false;

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

        // 1. Process DXVK frame / dispatch commands if module loaded
        // When user runtime library has exported game entry points:
        typedef void (*game_tick_fn)();
        if (g_engine.runtime_module_handle) {
            static game_tick_fn tick = (game_tick_fn)dlsym(g_engine.runtime_module_handle, "game_main_tick");
            if (tick) {
                tick();
            }
        }

        frame_counter++;
        g_engine.total_frames++;

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
        if (limit > 0) {
            std::chrono::microseconds target_frame_time(1000000 / limit);
            auto frame_duration = std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - frame_start);
            if (frame_duration < target_frame_time) {
                std::this_thread::sleep_for(target_frame_time - frame_duration);
            }
        }
    }

    LOGI("Game loop thread terminating gracefully");
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
    if (target_w > 0 && target_h > 0) {
        ANativeWindow_setBuffersGeometry(window, target_w, target_h, WINDOW_FORMAT_RGBA_8888);
    }

    // Forward to DXVK bridge
    dxvk_bridge_set_window(window);
    dxvk_bridge_set_render_size(target_w, target_h);

    // Initialize Vulkan surface
    init_vulkan_surface(window);

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
}

static void onInputQueueDestroyed(ANativeActivity* activity, AInputQueue* queue) {
    LOGI("ANativeActivity callback: onInputQueueDestroyed");
}

static int32_t onInputEvent(ANativeActivity* activity, AInputEvent* event) {
    int32_t event_type = AInputEvent_getType(event);

    if (event_type == AINPUT_EVENT_TYPE_KEY) {
        int32_t key_code = AKeyEvent_getKeyCode(event);
        int32_t action = AKeyEvent_getAction(event);
        // Handle Gamepad / Keyboard inputs (WASD, Arrows, Buttons)
        if (action == AKEY_EVENT_ACTION_DOWN || action == AKEY_EVENT_ACTION_UP) {
            // Forward input events to game runtime if registered
        }
        return 1;
    } else if (event_type == AINPUT_EVENT_TYPE_MOTION) {
        int32_t action = AMotionEvent_getAction(event) & AMOTION_EVENT_ACTION_MASK;
        size_t pointer_count = AMotionEvent_getPointerCount(event);
        for (size_t i = 0; i < pointer_count; i++) {
            float x = AMotionEvent_getX(event, i);
            float y = AMotionEvent_getY(event, i);
            (void)x; (void)y; (void)action;
        }
        return 1;
    }
    return 0;
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

    // Load with RTLD_NOW | RTLD_GLOBAL to let game symbols resolve into our hooks
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
    }

    // Try resolving DXVK symbols inside the loaded runtime module
    dxvk_bridge_init(handle);

    env->ReleaseStringUTFChars(runtime_so_path, path);
    return JNI_TRUE;
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
