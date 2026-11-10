package com.example

import android.util.Log

/**
 * NativeBridge is the central gateway between Android/Kotlin and the C/C++ native layer.
 *
 * CRITICAL INTERPOSITION LOAD ORDER:
 * 1. libcompat_runtime.so (loaded here):
 *    The main NativeActivity and Vulkan presentation runtime. Its JNI_OnLoad is
 *    the first code of ours that runs in the process, and it is what loads the
 *    hook libraries into the GLOBAL scope.
 *
 * 2. libhook_impl.so:
 *    The core interception engine (implementation, not interposition). It caches
 *    the real system function pointers (via dlsym on libc/libdl) and provides
 *    thread-safe implementations of android_dlopen_ext, fopen and the GSL
 *    allocators.
 *
 * 3. libmain_hook.so:
 *    Exports "android_dlopen_ext" and "android_load_sphal_library".
 *
 * 4. libfile_redirect_hook.so:
 *    Exports "fopen", "fopen64", "open". Redirects legacy Windows-style paths
 *    (e.g. "C:\Games\...") to the user-selected game data directory.
 *
 * 5. libgsl_alloc_hook.so:
 *    Exports "gsl_memory_alloc_pure", "gsl_memory_alloc_pure_64" and
 *    "gsl_memory_free_pure" (the allocator used by the classic EA engine).
 *
 * IMPORTANT: the three interposition libraries are NOT loaded from Java.
 * System.loadLibrary() does not use RTLD_GLOBAL, and in bionic a library that
 * was already loaded is not promoted to the global scope by a later
 * dlopen(..., RTLD_GLOBAL). Loading them from JNI_OnLoad (native, RTLD_GLOBAL)
 * is the only ordering that reliably installs the interposition.
 */
object NativeBridge {
    private const val TAG = "VulkanCompatNative"

    init {
        try {
            // Loading compat_runtime runs JNI_OnLoad, which installs the hook
            // libraries in the global scope.
            System.loadLibrary("compat_runtime")
            Log.i(TAG, "Loaded libcompat_runtime.so (hook libraries installed by JNI_OnLoad)")
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

    /**
     * JSON with the state of the native layer: which hook libraries were
     * installed, whether the DXVK bridge is active, the loaded runtime, and the
     * configured directories. Used by the diagnostics screen.
     */
    @JvmStatic
    external fun hookStatus(): String

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
