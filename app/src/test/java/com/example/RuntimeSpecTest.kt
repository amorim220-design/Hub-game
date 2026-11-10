package com.example

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeSpecTest {

    @Test
    fun `native spec is the default when the type is missing`() {
        val spec = RuntimeSpec.fromJson(JSONObject("""{"mainLibrary":"libgame.so"}"""))
        assertTrue(spec is RuntimeSpec.Native)
        assertEquals("libgame.so", (spec as RuntimeSpec.Native).mainLibrary)
        assertEquals("game_main_tick", spec.entryPoint)
    }

    @Test
    fun `native spec without mainLibrary is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuntimeSpec.fromJson(JSONObject("""{"type":"native"}"""))
        }
        assertTrue(error.message!!.contains("mainLibrary"))
    }

    @Test
    fun `unknown type is rejected with a helpful message`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuntimeSpec.fromJson(JSONObject("""{"type":"dosbox"}"""))
        }
        assertTrue(error.message!!.contains("dosbox"))
    }

    @Test
    fun `wine spec is parsed completely`() {
        val json = JSONObject(
            """
            {
              "type": "wine",
              "prefix": "prefix",
              "exe": "drive_c/jogo/Game.exe",
              "launcher": "bin/box64",
              "launcherArgs": ["wine64", "--x"],
              "env": {"DXVK_LOG_LEVEL": "info"},
              "displayWidth": 960,
              "displayHeight": 540,
              "inputBridge": "bin/pcshim_input.so"
            }
            """.trimIndent()
        )
        val spec = RuntimeSpec.fromJson(json) as RuntimeSpec.Wine

        assertEquals("prefix", spec.prefixDir)
        assertEquals("drive_c/jogo/Game.exe", spec.exe)
        assertEquals("bin/box64", spec.launcher)
        assertEquals(listOf("wine64", "--x"), spec.launcherArgs)
        assertEquals("info", spec.env["DXVK_LOG_LEVEL"])
        assertEquals(960, spec.displayWidth)
        assertEquals(540, spec.displayHeight)
        assertEquals("bin/pcshim_input.so", spec.inputBridge)
    }

    @Test
    fun `wine spec without launcher is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuntimeSpec.fromJson(
                JSONObject("""{"type":"wine","prefix":"prefix","exe":"drive_c/Game.exe"}""")
            )
        }
        assertTrue(error.message!!.contains("launcher"))
    }

    @Test
    fun `wine spec with an absurd display size is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuntimeSpec.fromJson(
                JSONObject(
                    """{"type":"wine","prefix":"p","exe":"drive_c/g.exe","launcher":"bin/b",
                       "displayWidth":10,"displayHeight":10}"""
                )
            )
        }
        assertTrue(error.message!!.contains("display"))
    }

    @Test
    fun `validateFiles reports the missing pieces of a wine package`() {
        val root = File.createTempFile("pcshim", "root")
        root.delete()
        root.mkdirs()

        val spec = RuntimeSpec.Wine(
            prefixDir = "prefix",
            exe = "drive_c/jogo/Game.exe",
            launcher = "bin/box64"
        )
        val problems = RuntimeSpec.validateFiles(spec, root)
        assertTrue(problems.any { it.contains("prefixo") })
        assertTrue(problems.any { it.contains("lançador") })
        assertTrue(problems.any { it.contains("executável") })

        root.deleteRecursively()
    }

    @Test
    fun `validateFiles accepts a complete wine package`() {
        val root = File.createTempFile("pcshim", "root")
        root.delete()
        root.mkdirs()
        File(root, "prefix/drive_c/jogo").mkdirs()
        File(root, "prefix/drive_c/jogo/Game.exe").writeText("MZ")
        File(root, "bin").mkdirs()
        File(root, "bin/box64").writeText("fake")

        val spec = RuntimeSpec.Wine(
            prefixDir = "prefix",
            exe = "drive_c/jogo/Game.exe",
            launcher = "bin/box64"
        )
        assertTrue(RuntimeSpec.validateFiles(spec, root).isEmpty())

        root.deleteRecursively()
    }

    @Test
    fun `validateFiles refuses paths outside the package`() {
        val root = File.createTempFile("pcshim", "root")
        root.delete()
        root.mkdirs()

        val spec = RuntimeSpec.Native(mainLibrary = "../../etc/passwd")
        val problems = RuntimeSpec.validateFiles(spec, root)
        assertTrue(problems.any { it.contains("fora do pacote") })

        root.deleteRecursively()
    }

    @Test
    fun `spec survives a json round trip`() {
        val spec = RuntimeSpec.Wine(
            prefixDir = "prefix",
            exe = "drive_c/jogo/Game.exe",
            launcher = "bin/box64",
            launcherArgs = listOf("wine64"),
            env = mapOf("A" to "B"),
            displayWidth = 800,
            displayHeight = 600
        )
        val json = RuntimeSpec.toJson(spec)
        val parsed = RuntimeSpec.fromJson(json)
        assertEquals(spec, parsed)
    }
}
