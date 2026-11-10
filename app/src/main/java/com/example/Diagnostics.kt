package com.example

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticsManager {
    private const val TAG = "VulkanDiagnostics"

    fun captureDeviceInfo(context: Context): JSONObject {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("board", Build.BOARD)
            put("hardware", Build.HARDWARE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(", "))
            put("totalMemoryBytes", Runtime.getRuntime().totalMemory())
            put("maxMemoryBytes", Runtime.getRuntime().maxMemory())
            put("availableProcessors", Runtime.getRuntime().availableProcessors())
        }
    }

    fun captureRecentLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -v time VulkanCompatHook:V VulkanCompatNative:V VulkanCompatRuntime:V VulkanCompatDXVK:V *:W")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            var count = 0
            while (reader.readLine().also { line = it } != null && count < 1000) {
                sb.append(line).append("\n")
                count++
            }
            reader.close()
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao capturar logcat", e)
            "Erro ao capturar logcat: ${e.message}"
        }
    }

    fun generateDiagnosticsZip(context: Context, gpuProbeResult: String?): File {
        val outDir = File(context.cacheDir, "diagnostics")
        if (!outDir.exists()) outDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(outDir, "diagnostics_$timestamp.zip")

        val deviceInfo = captureDeviceInfo(context).toString(2)
        val logs = captureRecentLogcat()
        val options = OptionsManager.loadOptions(context)
        val optionsJson = JSONObject().apply {
            put("gameDir", options.gameDirectory)
            put("runtimeId", options.activeRuntimeId)
            put("driverId", options.activeDriverId)
            put("resolutionPreset", options.resolutionPreset)
            put("fpsLimit", options.fpsLimit)
            put("widescreen", options.widescreenHud)
            put("minimapFix", options.minimapFix)
            put("pipelineCache", options.pipelineCache)
            put("cpuTextures", options.cpuTextures)
            put("tripleBuffering", options.tripleBuffering)
        }.toString(2)

        // State of the native layer: which hook libraries were installed in the
        // global scope, whether the DXVK bridge is active, which runtime is
        // loaded. This is the first thing to look at when a game does not
        // start: "hooksLoaded": false means the interposition is not installed.
        val nativeState = try {
            NativeBridge.hookStatus()
        } catch (t: Throwable) {
            "{\"error\":\"${t.javaClass.simpleName}: ${t.message}\"}"
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 1. device_info.json
            zos.putNextEntry(ZipEntry("device_info.json"))
            zos.write(deviceInfo.toByteArray())
            zos.closeEntry()

            // 1b. native_state.json
            zos.putNextEntry(ZipEntry("native_state.json"))
            zos.write(nativeState.toByteArray())
            zos.closeEntry()

            // 2. options.json
            zos.putNextEntry(ZipEntry("options.json"))
            zos.write(optionsJson.toByteArray())
            zos.closeEntry()

            // 3. gpu_probe.json
            zos.putNextEntry(ZipEntry("gpu_probe.json"))
            val probeContent = gpuProbeResult ?: "{\"status\":\"not_probed\"}"
            zos.write(probeContent.toByteArray())
            zos.closeEntry()

            // 4. logcat.txt
            zos.putNextEntry(ZipEntry("logcat.txt"))
            zos.write(logs.toByteArray())
            zos.closeEntry()
        }

        Log.i(TAG, "Pacote de diagnóstico gerado com sucesso: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        return zipFile
    }
}
