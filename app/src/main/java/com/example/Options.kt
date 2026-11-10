package com.example

import android.content.Context
import android.content.SharedPreferences

data class AppOptions(
    val gameDirectory: String = "",
    val activeRuntimeId: String = "",
    val activeDriverId: String = "",
    val resolutionPreset: Int = 0, // 0: 720p, 1: 1080p, 2: 1440p, 3: Native
    val fpsLimit: Int = 60,
    val widescreenHud: Boolean = true,
    val minimapFix: Boolean = true,
    val pipelineCache: Boolean = true,
    val cpuTextures: Boolean = false,
    val tripleBuffering: Boolean = true
) {
    fun getDimensions(): Pair<Int, Int> {
        return when (resolutionPreset) {
            0 -> Pair(1280, 720)
            1 -> Pair(1920, 1080)
            2 -> Pair(2560, 1440)
            else -> Pair(0, 0) // Native display resolution
        }
    }
}

object OptionsManager {
    private const val PREFS_NAME = "vulkan_compat_shell_prefs"

    private const val KEY_GAME_DIR = "key_game_dir"
    private const val KEY_ACTIVE_RUNTIME = "key_active_runtime"
    private const val KEY_ACTIVE_DRIVER = "key_active_driver"
    private const val KEY_RESOLUTION_PRESET = "key_res_preset"
    private const val KEY_FPS_LIMIT = "key_fps_limit"
    private const val KEY_WIDESCREEN_HUD = "key_widescreen_hud"
    private const val KEY_MINIMAP_FIX = "key_minimap_fix"
    private const val KEY_PIPELINE_CACHE = "key_pipeline_cache"
    private const val KEY_CPU_TEXTURES = "key_cpu_textures"
    private const val KEY_TRIPLE_BUFFERING = "key_triple_buffering"

    fun loadOptions(context: Context): AppOptions {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppOptions(
            gameDirectory = prefs.getString(KEY_GAME_DIR, "") ?: "",
            activeRuntimeId = prefs.getString(KEY_ACTIVE_RUNTIME, "") ?: "",
            activeDriverId = prefs.getString(KEY_ACTIVE_DRIVER, "") ?: "",
            resolutionPreset = prefs.getInt(KEY_RESOLUTION_PRESET, 0),
            fpsLimit = prefs.getInt(KEY_FPS_LIMIT, 60),
            widescreenHud = prefs.getBoolean(KEY_WIDESCREEN_HUD, true),
            minimapFix = prefs.getBoolean(KEY_MINIMAP_FIX, true),
            pipelineCache = prefs.getBoolean(KEY_PIPELINE_CACHE, true),
            cpuTextures = prefs.getBoolean(KEY_CPU_TEXTURES, false),
            tripleBuffering = prefs.getBoolean(KEY_TRIPLE_BUFFERING, true)
        )
    }

    fun saveOptions(context: Context, options: AppOptions) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_GAME_DIR, options.gameDirectory)
            .putString(KEY_ACTIVE_RUNTIME, options.activeRuntimeId)
            .putString(KEY_ACTIVE_DRIVER, options.activeDriverId)
            .putInt(KEY_RESOLUTION_PRESET, options.resolutionPreset)
            .putInt(KEY_FPS_LIMIT, options.fpsLimit)
            .putBoolean(KEY_WIDESCREEN_HUD, options.widescreenHud)
            .putBoolean(KEY_MINIMAP_FIX, options.minimapFix)
            .putBoolean(KEY_PIPELINE_CACHE, options.pipelineCache)
            .putBoolean(KEY_CPU_TEXTURES, options.cpuTextures)
            .putBoolean(KEY_TRIPLE_BUFFERING, options.tripleBuffering)
            .apply()

        syncToNative(context, options)
    }

    fun syncToNative(context: Context, options: AppOptions) {
        try {
            // Set game folder
            if (options.gameDirectory.isNotEmpty()) {
                NativeBridge.setGameDirectory(options.gameDirectory)
            }

            // Resolve active driver path
            if (options.activeDriverId.isNotEmpty()) {
                val drivers = DriverInstaller.listInstalledDrivers(context)
                val driver = drivers.find { it.id == options.activeDriverId }
                if (driver != null && driver.driverFile.exists()) {
                    NativeBridge.setCustomDriverPath(driver.driverFile.absolutePath)
                } else {
                    NativeBridge.setCustomDriverPath("")
                }
            } else {
                NativeBridge.setCustomDriverPath("")
            }

            // Send graphics settings
            val (w, h) = options.getDimensions()
            NativeBridge.setGraphicsOptions(
                width = w,
                height = h,
                fpsLimit = options.fpsLimit,
                widescreen = options.widescreenHud,
                minimapFix = options.minimapFix,
                pipelineCache = options.pipelineCache,
                cpuTextures = options.cpuTextures,
                tripleBuffering = options.tripleBuffering
            )
        } catch (ignored: UnsatisfiedLinkError) {
            // Native libraries are arm64-v8a; during local JVM Robolectric tests on host x86_64, ignore JNI link
        } catch (ignored: NoClassDefFoundError) {
            // Class initialization ignored if native lib not loaded
        }
    }
}
