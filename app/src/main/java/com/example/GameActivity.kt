package com.example

import android.app.NativeActivity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * GameActivity extends android.app.NativeActivity and runs in the isolated process ":game".
 * This replicates the observed behavior from native Android PC compatibility shells,
 * separating game execution and crashes from the launcher UI.
 */
class GameActivity : NativeActivity() {
    companion object {
        private const val TAG = "VulkanGameActivity"
        const val EXTRA_RUNTIME_PATH = "extra_runtime_path"
        const val EXTRA_GAME_DIR = "extra_game_dir"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "GameActivity starting in process :game (PID=${android.os.Process.myPid()})")

        // 1. Keep screen on and hide navigation bars for immersive gaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveMode()

        // 2. Synchronize current options before native ANativeActivity_onCreate executes
        val options = OptionsManager.loadOptions(this)
        OptionsManager.syncToNative(this, options)

        // 3. Load user-selected runtime package if provided
        val runtimePath = intent.getStringExtra(EXTRA_RUNTIME_PATH)
            ?: options.activeRuntimeId.takeIf { it.isNotEmpty() }?.let { id ->
                PackageInstaller.listInstalledPackages(this).find { it.id == id }?.mainLibraryFile?.absolutePath
            }

        if (!runtimePath.isNullOrEmpty()) {
            Log.i(TAG, "Loading game runtime module: $runtimePath")
            val success = NativeBridge.loadGameRuntime(runtimePath)
            if (!success) {
                Log.e(TAG, "Failed to load game runtime module!")
            }
        } else {
            Log.w(TAG, "No custom game runtime specified; starting native Vulkan presentation loop.")
        }

        // 4. Delegate to NativeActivity native lifecycle
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupImmersiveMode()
        }
    }

    private fun setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }
}
