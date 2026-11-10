#pragma once

#include <android/native_window.h>
#include <cstdint>
#include <string>

#ifdef __cplusplus
extern "C" {
#endif

// Function pointer signatures matching DXVK Android extensions
typedef void (*PFN_dxvkAndroidSetWindow)(ANativeWindow* window);
typedef void (*PFN_dxvkAndroidSetRenderSize)(uint32_t width, uint32_t height);
typedef ANativeWindow* (*PFN_dxvkAndroidGetWindowHandle)(void);
typedef void (*PFN_dxvkAndroidSetInstanceProcAddr)(void* pfn);

// Initializes the DXVK bridge by attempting to resolve entry points from loaded libraries
bool dxvk_bridge_init(void* dxvk_module_handle);

// Forwards window handle to DXVK
void dxvk_bridge_set_window(ANativeWindow* window);

// Forwards internal render resolution to DXVK
void dxvk_bridge_set_render_size(uint32_t width, uint32_t height);

// Returns current active window handle reported by DXVK
ANativeWindow* dxvk_bridge_get_window_handle();

// Configures Vulkan vkGetInstanceProcAddr entry point
void dxvk_bridge_set_instance_proc_addr(void* pfn);

// Writes dxvk.conf into target game directory
bool dxvk_bridge_write_config(
    const std::string& game_dir,
    int fps_limit,
    bool enable_pipeline_cache,
    bool widescreen_fix,
    bool cpu_textures,
    bool triple_buffering
);

// Returns true if DXVK symbols were resolved successfully
bool dxvk_bridge_is_active();

#ifdef __cplusplus
}
#endif
