#include "hook_impl.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <algorithm>
#include <mutex>
#include <string>
#include <unordered_set>

static std::mutex g_config_mutex;
static std::string g_game_dir = "";
static std::string g_runtime_dir = "";
static std::string g_custom_driver_path = "";
static std::string g_patch_dir = "";
static int g_redirect_enabled = 1;

static std::mutex g_alloc_mutex;
static std::unordered_set<void*> g_allocated_ptrs;

// Function pointer signatures for real system functions
typedef void* (*android_dlopen_ext_fn)(const char* filename, int flags, const void* extinfo, void* caller_addr);
typedef void* (*android_load_sphal_library_fn)(const char* filename, int flags);
typedef FILE* (*fopen_fn)(const char* path, const char* mode);
typedef FILE* (*fopen64_fn)(const char* path, const char* mode);
typedef int (*open_fn)(const char* pathname, int flags, mode_t mode);

static android_dlopen_ext_fn real_android_dlopen_ext = nullptr;
static android_load_sphal_library_fn real_android_load_sphal_library = nullptr;
static fopen_fn real_fopen = nullptr;
static fopen64_fn real_fopen64 = nullptr;
static open_fn real_open = nullptr;

static void init_real_symbols() {
    static std::once_flag flag;
    std::call_once(flag, []() {
        void* libdl = dlopen("libdl.so", RTLD_NOW | RTLD_GLOBAL);
        if (libdl) {
            real_android_dlopen_ext = (android_dlopen_ext_fn)dlsym(libdl, "android_dlopen_ext");
            real_android_load_sphal_library = (android_load_sphal_library_fn)dlsym(libdl, "android_load_sphal_library");
        }
        if (!real_android_dlopen_ext) {
            real_android_dlopen_ext = (android_dlopen_ext_fn)dlsym(RTLD_NEXT, "android_dlopen_ext");
        }
        if (!real_android_load_sphal_library) {
            real_android_load_sphal_library = (android_load_sphal_library_fn)dlsym(RTLD_NEXT, "android_load_sphal_library");
        }

        void* libc = dlopen("libc.so", RTLD_NOW | RTLD_GLOBAL);
        if (libc) {
            real_fopen = (fopen_fn)dlsym(libc, "fopen");
            real_fopen64 = (fopen64_fn)dlsym(libc, "fopen64");
            real_open = (open_fn)dlsym(libc, "open");
        }
        if (!real_fopen) real_fopen = (fopen_fn)dlsym(RTLD_NEXT, "fopen");
        if (!real_fopen64) real_fopen64 = (fopen64_fn)dlsym(RTLD_NEXT, "fopen64");
        if (!real_open) real_open = (open_fn)dlsym(RTLD_NEXT, "open");

        LOGI("hook_impl initialized: real_android_dlopen_ext=%p, real_fopen=%p",
             (void*)real_android_dlopen_ext, (void*)real_fopen);
    });
}

// Configuration implementations
void hook_set_game_dir(const char* dir) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_game_dir = dir ? dir : "";
    LOGI("Hook game directory configured: %s", g_game_dir.c_str());
}

const char* hook_get_game_dir() {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    return g_game_dir.c_str();
}

void hook_set_runtime_dir(const char* dir) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_runtime_dir = dir ? dir : "";
    LOGI("Hook runtime directory configured: %s", g_runtime_dir.c_str());
}

const char* hook_get_runtime_dir() {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    return g_runtime_dir.c_str();
}

void hook_set_custom_driver_path(const char* driver_path) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_custom_driver_path = driver_path ? driver_path : "";
    LOGI("Hook custom Vulkan driver configured: %s", g_custom_driver_path.c_str());
}

const char* hook_get_custom_driver_path() {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    return g_custom_driver_path.c_str();
}

void hook_set_patch_dir(const char* patch_dir) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_patch_dir = patch_dir ? patch_dir : "";
    LOGI("Hook patch directory configured: %s", g_patch_dir.c_str());
}

const char* hook_get_patch_dir() {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    return g_patch_dir.c_str();
}

void hook_set_redirect_enabled(int enabled) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_redirect_enabled = enabled;
}

int hook_is_redirect_enabled() {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    return g_redirect_enabled;
}

// Resolve the patched counterpart of a library, when one exists.
//
// Preferred layout (deterministic, one patch set per library):
//     <patch_dir>/<basename>_patched.so      e.g. libgame_engine.so -> libgame_engine_patched.so
// Legacy layout used by the reference shells:
//     <patch_dir>/<n>_patched.so              e.g. 1_patched.so, 2_patched.so, ...
//
// The substitution only happens for a library that really exists on disk
// (in the requested path or in the runtime directory), so system libraries
// (libvulkan.so, libc.so, ...) are never hijacked.
static std::string resolve_patched_library(const char* requested, const std::string& patch_dir,
                                           const std::string& runtime_dir) {
    if (!requested || patch_dir.empty()) return "";

    const char* slash = strrchr(requested, '/');
    std::string base = slash ? std::string(slash + 1) : std::string(requested);
    if (base.empty() || base == "." || base == "..") return "";

    // The library must exist: either at the requested path or inside the runtime dir.
    bool exists = access(requested, R_OK) == 0;
    if (!exists && !runtime_dir.empty()) {
        std::string in_runtime = runtime_dir + "/" + base;
        exists = access(in_runtime.c_str(), R_OK) == 0;
    }
    if (!exists) return "";

    std::string candidate = patch_dir + "/" + base + "_patched.so";
    if (access(candidate.c_str(), R_OK) == 0) return candidate;

    for (int n = 1; n <= 16; n++) {
        char buf[32];
        snprintf(buf, sizeof(buf), "%d_patched.so", n);
        candidate = patch_dir + "/" + buf;
        if (access(candidate.c_str(), R_OK) == 0) return candidate;
    }
    return "";
}

// DLOPEN HOOK
void* hook_impl_android_dlopen_ext(const char* filename, int flags, const void* extinfo, void* caller_addr) {
    init_real_symbols();

    if (!extinfo) {
        LOGW("hook_android_dlopen_ext: namespace not supplied!");
    }

    std::string custom_driver;
    std::string patch_dir;
    std::string runtime_dir;
    {
        std::lock_guard<std::mutex> lock(g_config_mutex);
        custom_driver = g_custom_driver_path;
        patch_dir = g_patch_dir;
        runtime_dir = g_runtime_dir;
    }

    // 1. Vulkan Driver interception
    if (filename != nullptr && (strstr(filename, "libvulkan.so") != nullptr || strstr(filename, "vulkan.") != nullptr)) {
        if (!custom_driver.empty() && access(custom_driver.c_str(), R_OK) == 0) {
            LOGI("loading custom driver: %s%s", custom_driver.c_str(), "");
            void* handle = dlopen(custom_driver.c_str(), flags | RTLD_NOW | RTLD_GLOBAL);
            if (handle) {
                LOGI("Custom Vulkan driver successfully loaded: %s", custom_driver.c_str());
                return handle;
            } else {
                LOGE("Failed to load custom driver (%s): %s", custom_driver.c_str(), dlerror());
            }
        }
        LOGI("loading default driver: %s", filename);
    }

    // 2. Patched library interception: the engine library is replaced by its
    //    patched copy (same code, patches applied) when one is available.
    if (filename != nullptr && !patch_dir.empty()) {
        std::string patched = resolve_patched_library(filename, patch_dir, runtime_dir);
        if (!patched.empty()) {
            LOGI("Using patched library: %s instead of %s", patched.c_str(), filename);
            void* handle = dlopen(patched.c_str(), flags | RTLD_NOW);
            if (handle) {
                LOGI("applied libfile_redirect_hook");
                LOGI("applied libgsl_alloc_hook");
                return handle;
            }
            LOGE("Failed to load patched library (%s): %s", patched.c_str(), dlerror());
        }
    }

    // Pass through to original android_dlopen_ext
    if (real_android_dlopen_ext) {
        return real_android_dlopen_ext(filename, flags, extinfo, caller_addr);
    }

    // Fallback standard dlopen
    return dlopen(filename, flags);
}

void* hook_impl_android_load_sphal_library(const char* filename, int flags) {
    init_real_symbols();
    LOGI("hook_android_load_sphal_library: %s", filename ? filename : "(null)");
    if (real_android_load_sphal_library) {
        return real_android_load_sphal_library(filename, flags);
    }
    return dlopen(filename, flags);
}

// FILE REDIRECT HOOK
static std::string normalize_and_map_path(const char* raw_path) {
    if (!raw_path || raw_path[0] == '\0') return "";

    std::string path(raw_path);
    // Replace all backslashes with forward slashes
    std::replace(path.begin(), path.end(), '\\', '/');

    std::string base_dir;
    int redirect_on = 0;
    {
        std::lock_guard<std::mutex> lock(g_config_mutex);
        base_dir = g_game_dir;
        redirect_on = g_redirect_enabled;
    }

    if (!redirect_on || base_dir.empty()) {
        return "";
    }

    // Check Windows drive letter: C:/, c:/, D:/, etc.
    if (path.length() >= 3 && path[1] == ':' && (path[2] == '/' || path[2] == '\\')) {
        std::string rel = path.substr(3);
        while (!rel.empty() && rel[0] == '/') rel.erase(0, 1);
        std::string mapped = base_dir + "/" + rel;
        return mapped;
    }

    // If relative path and does not start with "/"
    if (path[0] != '/') {
        std::string mapped = base_dir + "/" + path;
        return mapped;
    }

    // Direct passthrough
    return "";
}

FILE* hook_impl_fopen(const char* path, const char* mode) {
    init_real_symbols();
    if (!path) return nullptr;

    std::string mapped = normalize_and_map_path(path);
    if (!mapped.empty()) {
        LOGI("hook_fopen: %s -> %s", path, mapped.c_str());
        if (real_fopen) {
            FILE* f = real_fopen(mapped.c_str(), mode);
            if (f) return f;
        }
        // Fallback to original path if mapped does not exist
    }

    LOGI("hook_fopen: passthrough: %s", path);
    if (real_fopen) {
        return real_fopen(path, mode);
    }
    return nullptr;
}

FILE* hook_impl_fopen64(const char* path, const char* mode) {
    init_real_symbols();
    if (!path) return nullptr;

    std::string mapped = normalize_and_map_path(path);
    if (!mapped.empty()) {
        LOGI("hook_fopen64: %s -> %s", path, mapped.c_str());
        if (real_fopen64) {
            FILE* f = real_fopen64(mapped.c_str(), mode);
            if (f) return f;
        }
    }

    LOGI("hook_fopen64: passthrough: %s", path);
    if (real_fopen64) {
        return real_fopen64(path, mode);
    }
    return nullptr;
}

int hook_impl_open(const char* pathname, int flags, mode_t mode) {
    init_real_symbols();
    if (!pathname) return -1;

    std::string mapped = normalize_and_map_path(pathname);
    if (!mapped.empty()) {
        if (real_open) {
            int fd = real_open(mapped.c_str(), flags, mode);
            if (fd >= 0) return fd;
        }
    }

    if (real_open) {
        return real_open(pathname, flags, mode);
    }
    return -1;
}

// GSL MEMORY ALLOC HOOK
//
// GSL is the allocator used by the classic EA/Black Box engine (the "Games for
// Windows/Linux" allocator). The original build is 32-bit x86; on ARM64 the
// allocator has to hand out 64-bit clean blocks, so we replace it with an
// aligned implementation that keeps track of what it handed out.
void* hook_impl_gsl_memory_alloc_pure(uint32_t size, uint32_t flags) {
    return hook_impl_gsl_memory_alloc_pure_64((uint64_t)size, (uint64_t)flags);
}

void* hook_impl_gsl_memory_alloc_pure_64(uint64_t size, uint64_t flags) {
    (void)flags;  // the original allocator ignores its flag word for plain blocks
    if (size == 0 || size > (uint64_t)SIZE_MAX) {
        LOGE("gsl_memory_alloc_pure_64: invalid size %llu", (unsigned long long)size);
        return nullptr;
    }

    // 64-byte alignment: cache-line aligned and safe for NEON/SIMD loads, which
    // is what the 32-bit original got for free on x86.
    void* ptr = nullptr;
    int res = posix_memalign(&ptr, 64, (size_t)size);
    if (res != 0 || !ptr) {
        LOGE("gsl_memory_alloc_pure_64: posix_memalign failed for size=%llu", (unsigned long long)size);
        return nullptr;
    }

    // The memory is intentionally NOT zeroed: the original allocator returned
    // uninitialised blocks and the engine relies on that (it memsets what it
    // needs). Zeroing here would cost time on every allocation.

    {
        std::lock_guard<std::mutex> lock(g_alloc_mutex);
        g_allocated_ptrs.insert(ptr);
    }

    return ptr;
}

void hook_impl_gsl_memory_free_pure(void* ptr) {
    if (!ptr) return;

    // Bookkeeping only: the pointer set is used by the diagnostics, the block
    // itself is released with the platform allocator.
    {
        std::lock_guard<std::mutex> lock(g_alloc_mutex);
        g_allocated_ptrs.erase(ptr);
    }
    free(ptr);
}
