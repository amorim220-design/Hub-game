package com.example

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * GpuProbeActivity runs inside the isolated ":gpu_probe" process.
 * If querying the Vulkan driver causes a GPU driver segfault or abort, only
 * this isolated worker process terminates, leaving the main LauncherActivity alive and stable.
 */
class GpuProbeActivity : Activity() {
    companion object {
        private const val TAG = "VulkanGpuProbe"
        const val PREFS_GPU_PROBE = "gpu_probe_prefs"
        const val KEY_LAST_PROBE_RESULT = "last_probe_result"
        const val KEY_LAST_PROBE_TIME = "last_probe_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "GpuProbeActivity running in process :gpu_probe (PID=${android.os.Process.myPid()})")

        var probeResult: String
        try {
            probeResult = NativeBridge.probeVulkanGpu()
            Log.i(TAG, "Vulkan GPU probe output: $probeResult")
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error executing Vulkan GPU probe", t)
            probeResult = "{\"status\":\"error\",\"message\":\"${t.message}\"}"
        }

        // Store result for LauncherActivity to read
        val prefs = getSharedPreferences(PREFS_GPU_PROBE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_PROBE_RESULT, probeResult)
            .putLong(KEY_LAST_PROBE_TIME, System.currentTimeMillis())
            .commit()

        setResult(RESULT_OK)
        finish()
    }
}
