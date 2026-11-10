package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class VulkanDriverInfo(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val driverFileName: String,
    val driverFile: File,
    val minVulkanVersion: String
)

object DriverInstaller {
    private const val TAG = "VulkanDriverInstaller"
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024 // 200 MB

    fun installDriverFromUri(context: Context, zipUri: Uri): VulkanDriverInfo {
        val driversBaseDir = File(context.filesDir, "drivers")
        if (!driversBaseDir.exists()) driversBaseDir.mkdirs()

        val tempDir = File(context.cacheDir, "driver_temp_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        var totalBytes = 0L

        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (entryName.contains("..")) {
                            throw SecurityException("Tentativa de Zip Slip detectada: $entryName")
                        }

                        val targetFile = File(tempDir, entryName)
                        val canonical = targetFile.canonicalPath
                        if (!canonical.startsWith(tempDir.canonicalPath + File.separator) &&
                            canonical != tempDir.canonicalPath) {
                            throw SecurityException("Caminho inseguro detectado: $entryName")
                        }

                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { fos ->
                                val buffer = ByteArray(16384)
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    totalBytes += len
                                    if (totalBytes > MAX_ZIP_BYTES) {
                                        throw SecurityException("Driver ZIP excede o limite máximo permitido (200 MB).")
                                    }
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: throw IOException("Falha ao abrir ZIP do driver.")

            var driverName = "Custom Driver"
            var driverVersion = "1.0"
            var driverAuthor = "Comunidade"
            var minVulkan = "1.1"
            var driverFileName: String? = null

            val metaJsonFile = File(tempDir, "meta.json")
            if (metaJsonFile.exists()) {
                val json = JSONObject(metaJsonFile.readText())
                driverName = json.optString("name", driverName)
                driverVersion = json.optString("version", driverVersion)
                driverAuthor = json.optString("author", driverAuthor)
                minVulkan = json.optString("minVulkanVersion", minVulkan)
                driverFileName = if (json.has("driverFileName")) json.getString("driverFileName") else null
            }

            val soFiles = tempDir.walkTopDown().filter { it.isFile && it.extension == "so" }.toList()
            if (soFiles.isEmpty()) {
                throw IllegalArgumentException("Nenhuma biblioteca .so encontrada no pacote do driver Vulkan.")
            }

            val driverSo = if (driverFileName != null) {
                File(tempDir, driverFileName).takeIf { it.exists() } ?: soFiles.first()
            } else {
                soFiles.first()
            }

            // Verify ARM64 ELF
            PackageInstaller.validateArm64Elf(driverSo)

            val sanitizedId = driverName.replace("[^a-zA-Z0-9_-]".toRegex(), "_").lowercase()
            val finalDir = File(driversBaseDir, sanitizedId)
            if (finalDir.exists()) finalDir.deleteRecursively()

            if (!tempDir.renameTo(finalDir)) {
                tempDir.copyRecursively(finalDir, overwrite = true)
                tempDir.deleteRecursively()
            }

            val installedSo = File(finalDir, driverSo.name)
            installedSo.setExecutable(true, false)

            val metaOut = JSONObject().apply {
                put("id", sanitizedId)
                put("name", driverName)
                put("version", driverVersion)
                put("author", driverAuthor)
                put("minVulkanVersion", minVulkan)
                put("driverFileName", installedSo.name)
            }
            File(finalDir, "meta.json").writeText(metaOut.toString(2))

            Log.i(TAG, "Driver Vulkan instalado: $driverName (${installedSo.absolutePath})")

            return VulkanDriverInfo(
                id = sanitizedId,
                name = driverName,
                version = driverVersion,
                author = driverAuthor,
                driverFileName = installedSo.name,
                driverFile = installedSo,
                minVulkanVersion = minVulkan
            )
        } finally {
            if (tempDir.exists()) tempDir.deleteRecursively()
        }
    }

    fun listInstalledDrivers(context: Context): List<VulkanDriverInfo> {
        val baseDir = File(context.filesDir, "drivers")
        if (!baseDir.exists()) return emptyList()

        return baseDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            try {
                val metaFile = File(dir, "meta.json")
                if (metaFile.exists()) {
                    val json = JSONObject(metaFile.readText())
                    val soName = json.optString("driverFileName")
                    val soFile = File(dir, soName)
                    if (soFile.exists()) {
                        VulkanDriverInfo(
                            id = json.optString("id", dir.name),
                            name = json.optString("name", dir.name),
                            version = json.optString("version", "1.0"),
                            author = json.optString("author", "N/A"),
                            driverFileName = soName,
                            driverFile = soFile,
                            minVulkanVersion = json.optString("minVulkanVersion", "1.1")
                        )
                    } else null
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao ler driver em ${dir.name}", e)
                null
            }
        } ?: emptyList()
    }

    fun deleteDriver(driver: VulkanDriverInfo): Boolean {
        return driver.driverFile.parentFile?.deleteRecursively() ?: false
    }
}
