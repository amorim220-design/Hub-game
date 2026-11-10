package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VulkanCompatShell", appName)
    }

    @Test
    fun `test options persistence and default values`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaultOptions = OptionsManager.loadOptions(context)
        assertTrue(defaultOptions.widescreenHud)
        assertTrue(defaultOptions.minimapFix)
        assertTrue(defaultOptions.pipelineCache)
        assertEquals(60, defaultOptions.fpsLimit)

        val custom = defaultOptions.copy(
            gameDirectory = "/storage/emulated/0/Games/ClassicPC",
            fpsLimit = 120,
            resolutionPreset = 1 // 1080p
        )
        OptionsManager.saveOptions(context, custom)

        val loaded = OptionsManager.loadOptions(context)
        assertEquals("/storage/emulated/0/Games/ClassicPC", loaded.gameDirectory)
        assertEquals(120, loaded.fpsLimit)
        assertEquals(Pair(1920, 1080), loaded.getDimensions())
    }

    @Test
    fun `test validateArm64Elf rejects PE Windows executables`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeExe = File(context.cacheDir, "test_game.exe")
        FileOutputStream(fakeExe).use { fos ->
            // MZ header
            fos.write(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))
            fos.write(ByteArray(30))
        }

        try {
            PackageInstaller.validateArm64Elf(fakeExe)
            fail("Deveria ter lançado IllegalArgumentException para executável PE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("PE"))
        } finally {
            fakeExe.delete()
        }
    }
}
