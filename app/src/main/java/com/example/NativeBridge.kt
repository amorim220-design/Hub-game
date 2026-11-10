package com.example

import android.util.Log

/**
 * NativeBridge is the central gateway between Android/Kotlin and the C/C++ native layer.
 *
 * CRITICAL INTERPOSITION LOAD ORDER:
 * 1. libhook_impl.so:
 *    The core interception engine. It caches real system function pointers (via dlsym/RTLD_NEXT)
 *    and provides thread-safe implementations of android_dlopen_ext, fopen, and gsl allocators.
 *
 * 2. libmain_hook.so:
 *    Exports "android_dlopen_ext" and "android_load_sphal_library". Because it is loaded into the
 *    global symbol namespace before the game runtime or graphics drivers, any call from game code
 *    resolves to our hook instead of the system bionic linker.
 *
 * 3. libfile_redirect_hook.so:
 *    Exports "fopen", "fopen64", "open". Redirects legacy Windows-style paths (e.g. "C:\Games\...")
 *    to the user-selected game data directory on Android internal/external storage.
 *
 * 4. libgsl_alloc_hook.so:
 *    Exports "gsl_memory_alloc_pure", "gsl_memory_alloc_pure_64", and "gsl_memory_free_pure".
 *    Enforces safe 64-bit aligned buffer allocations for GPU drivers on ARM64.
 *
 * 5. libcompat_runtime.so:
 *    The main NativeActivity and Vulkan presentation runtime. It initializes the Vulkan surface,
 *    invokes the DXVK bridge, manages frame rate limiting, and handles native input events.
 */
object NativeBridge {
    private const val TAG = "VulkanCompatNative"

    init {
        try {
            Log.i(TAG, "Loading Hook & Compat native libraries in strict interposition order...")
            System.loadLibrary("hook_impl")
            Log.i(TAG, "Loaded libhook_impl.so")

            System.loadLibrary("main_hook")
            Log.i(TAG, "Loaded libmain_hook.so (DT_NEEDED on libhook_impl.so)")

            System.loadLibrary("file_redirect_hook")
            Log.i(TAG, "Loaded libfile_redirect_hook.so")

            System.loadLibrary("gsl_alloc_hook")
            Log.i(TAG, "Loaded libgsl_alloc_hook.so")

            System.loadLibrary("compat_runtime")
            Log.i(TAG, "Loaded libcompat_runtime.so")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load native libraries", t)
        }
    }

    @JvmStatic
    external fun setGameDirectory(gameDir: String)

    @JvmStatic
    external fun setCustomDriverPath(driverPath: String)

    @JvmStatic
    external fun setGraphicsOptions(
        width: Int,
        height: Int,
        fpsLimit: Int,
        widescreen: Boolean,
        minimapFix: Boolean,
        pipelineCache: Boolean,
        cpuTextures: Boolean,
        tripleBuffering: Boolean
    )

    @JvmStatic
    external fun loadGameRuntime(runtimeSoPath: String): Boolean

    @JvmStatic
    external fun probeVulkanGpu(): String

    @JvmStatic
    external fun applyBinaryPatches(
        srcElf: String,
        outDir: String,
        patchId: Int,
        patchJson: String
    ): String?

    @JvmStatic
    external fun getTelemetryFps(): Float
}
