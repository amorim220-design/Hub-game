package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class RuntimePackage(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val mainLibraryName: String,
    val installDir: File,
    val mainLibraryFile: File,
    val installedAtTimestamp: Long
)

object PackageInstaller {
    private const val TAG = "VulkanCompatInstaller"
    private const val MAX_ZIP_BYTES = 500L * 1024 * 1024 // 500 MB limit
    private const val MAX_ENTRY_COUNT = 5000

    private const val ELF_MAGIC_0 = 0x7F.toByte()
    private const val ELF_MAGIC_1 = 'E'.code.toByte()
    private const val ELF_MAGIC_2 = 'L'.code.toByte()
    private const val ELF_MAGIC_3 = 'F'.code.toByte()
    private const val ELF_CLASS_64 = 2.toByte()
    private const val EM_AARCH64 = 183 // 0x00B7
    private const val EM_386 = 3
    private const val EM_X86_64 = 62

    /**
     * Inspects binary header to verify ARM64 ELF architecture and reject PE / x86 files.
     */
    fun validateArm64Elf(file: File): Boolean {
        FileInputStream(file).use { fis ->
            val header = ByteArray(24)
            val read = fis.read(header)
            if (read < 20) {
                throw IllegalArgumentException("Arquivo binário corrompido ou menor que 20 bytes.")
            }

            // Check DOS/PE Executable header (MZ)
            if (header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()) {
                throw IllegalArgumentException(
                    "O arquivo é um executável de PC Windows (formato PE .exe/.dll). " +
                    "Jogos de PC x86 precisam ser recompilados para ARM64 ou conter um runtime tradutor ARM64."
                )
            }

            // Validate ELF Magic \x7fELF
            if (header[0] != ELF_MAGIC_0 || header[1] != ELF_MAGIC_1 ||
                header[2] != ELF_MAGIC_2 || header[3] != ELF_MAGIC_3) {
                throw IllegalArgumentException("O arquivo fornecido não é um binário ELF válido.")
            }

            // Check 64-bit ELF class
            if (header[4] != ELF_CLASS_64) {
                throw IllegalArgumentException("O binário ELF é 32-bit. É obrigatório um runtime compilado para ARM64 (64-bit).")
            }

            // Check e_machine (little-endian at byte 18-19)
            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            when (machine) {
                EM_AARCH64 -> {
                    Log.i(TAG, "ELF validado com sucesso: Arquitetura ARM64 (AArch64 - 0xB7)")
                    return true
                }
                EM_386 -> {
                    throw IllegalArgumentException("O binário é para arquitetura Intel x86 (32-bit). É necessário um build ARM64.")
                }
                EM_X86_64 -> {
                    throw IllegalArgumentException("O binário é para arquitetura AMD64 / x86_64 de PC e não roda diretamente em ARM64.")
                }
                else -> {
                    throw IllegalArgumentException("Arquitetura ELF não suportada (código 0x${Integer.toHexString(machine)}). Esperado ARM64 (0xB7).")
                }
            }
        }
    }

    /**
     * Installs a runtime package ZIP safely with Zip Slip protection and file size checks.
     */
    fun installPackageFromUri(context: Context, zipUri: Uri): RuntimePackage {
        val runtimesBaseDir = File(context.filesDir, "runtimes")
        if (!runtimesBaseDir.exists()) runtimesBaseDir.mkdirs()

        val tempExtractDir = File(context.cacheDir, "pkg_temp_${System.currentTimeMillis()}")
        tempExtractDir.mkdirs()

        var totalBytes = 0L
        var totalEntries = 0

        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        totalEntries++
                        if (totalEntries > MAX_ENTRY_COUNT) {
                            throw SecurityException("Pacote ZIP contém mais de $MAX_ENTRY_COUNT arquivos (possível Zip Bomb).")
                        }

                        // ZIP SLIP PROTECTION: Prevent relative traversal outside target directory
                        val entryName = entry.name
                        if (entryName.contains("..")) {
                            throw SecurityException("Tentativa de Zip Slip detectada no arquivo: $entryName")
                        }

                        val targetFile = File(tempExtractDir, entryName)
                        val canonicalDest = targetFile.canonicalPath
                        if (!canonicalDest.startsWith(tempExtractDir.canonicalPath + File.separator) &&
                            canonicalDest != tempExtractDir.canonicalPath) {
                            throw SecurityException("Caminho do arquivo fora do diretório de destino: $entryName")
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
                                        throw SecurityException("O pacote descompactado excede o limite máximo permitido (500 MB).")
                                    }
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: throw IOException("Não foi possível abrir o arquivo ZIP selecionado.")

            // Locate main library and metadata
            var packageName = "Runtime_${System.currentTimeMillis()}"
            var packageVersion = "1.0.0"
            var packageAuthor = "Desconhecido"
            var mainLibName: String? = null

            val metaJsonFile = File(tempExtractDir, "meta.json")
            if (metaJsonFile.exists()) {
                val jsonStr = metaJsonFile.readText()
                val json = JSONObject(jsonStr)
                packageName = json.optString("name", packageName)
                packageVersion = json.optString("version", packageVersion)
                packageAuthor = json.optString("author", packageAuthor)
                mainLibName = if (json.has("mainLibrary")) json.getString("mainLibrary") else null
            }

            // Find candidates .so files
            val soFiles = tempExtractDir.walkTopDown().filter { it.isFile && it.extension == "so" }.toList()
            if (soFiles.isEmpty()) {
                throw IllegalArgumentException("O pacote ZIP não contém nenhuma biblioteca compartilhada (.so).")
            }

            val targetLib = if (mainLibName != null) {
                File(tempExtractDir, mainLibName).takeIf { it.exists() }
                    ?: soFiles.first()
            } else {
                soFiles.maxByOrNull { it.length() } ?: soFiles.first()
            }

            // Enforce ARM64 ELF verification
            validateArm64Elf(targetLib)

            // Move to final installation directory
            val sanitizedId = packageName.replace("[^a-zA-Z0-9_-]".toRegex(), "_").lowercase()
            val finalDir = File(runtimesBaseDir, sanitizedId)
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            if (!tempExtractDir.renameTo(finalDir)) {
                // Fallback copy
                tempExtractDir.copyRecursively(finalDir, overwrite = true)
                tempExtractDir.deleteRecursively()
            }

            val installedLibFile = File(finalDir, targetLib.name)
            installedLibFile.setExecutable(true, false)

            // Write metadata
            val installedMeta = JSONObject().apply {
                put("id", sanitizedId)
                put("name", packageName)
                put("version", packageVersion)
                put("author", packageAuthor)
                put("mainLibrary", installedLibFile.name)
                put("installedAt", System.currentTimeMillis())
            }
            File(finalDir, "meta.json").writeText(installedMeta.toString(2))

            Log.i(TAG, "Pacote de runtime instalado com sucesso em: ${finalDir.absolutePath}")

            return RuntimePackage(
                id = sanitizedId,
                name = packageName,
                version = packageVersion,
                author = packageAuthor,
                mainLibraryName = installedLibFile.name,
                installDir = finalDir,
                mainLibraryFile = installedLibFile,
                installedAtTimestamp = System.currentTimeMillis()
            )
        } finally {
            if (tempExtractDir.exists()) {
                tempExtractDir.deleteRecursively()
            }
        }
    }

    /**
     * Lists all installed runtime packages.
     */
    fun listInstalledPackages(context: Context): List<RuntimePackage> {
        val runtimesBaseDir = File(context.filesDir, "runtimes")
        if (!runtimesBaseDir.exists()) return emptyList()

        return runtimesBaseDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            try {
                val metaFile = File(dir, "meta.json")
                if (metaFile.exists()) {
                    val json = JSONObject(metaFile.readText())
                    val mainLib = json.optString("mainLibrary")
                    val libFile = File(dir, mainLib)
                    if (libFile.exists()) {
                        RuntimePackage(
                            id = json.optString("id", dir.name),
                            name = json.optString("name", dir.name),
                            version = json.optString("version", "1.0"),
                            author = json.optString("author", "N/A"),
                            mainLibraryName = mainLib,
                            installDir = dir,
                            mainLibraryFile = libFile,
                            installedAtTimestamp = json.optLong("installedAt", 0L)
                        )
                    } else null
                } else {
                    val so = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "so" }
                    if (so != null) {
                        RuntimePackage(
                            id = dir.name,
                            name = dir.name,
                            version = "1.0",
                            author = "Manual",
                            mainLibraryName = so.name,
                            installDir = dir,
                            mainLibraryFile = so,
                            installedAtTimestamp = dir.lastModified()
                        )
                    } else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao ler pacote em ${dir.name}", e)
                null
            }
        } ?: emptyList()
    }

    fun deletePackage(pkg: RuntimePackage): Boolean {
        return pkg.installDir.deleteRecursively()
    }
}
