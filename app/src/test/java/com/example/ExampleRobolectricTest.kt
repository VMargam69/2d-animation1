package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.example.data.BuiltInCatalog
import com.example.engine.*
import com.example.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FrameCraft", appName)
    }

    @Test
    fun `render scene and encode valid animated GIF89a offline`() {
        val project = BuiltInCatalog.sceneTemplates.first().buildProject("Gif Test")
        val charsById = BuiltInCatalog.builtInCharacters.associateBy { it.id }
        val renderer = SceneRenderer { _, _ -> null }

        val w = 160
        val h = 90
        val encoder = GifEncoder(width = w, height = h, delayCentiseconds = 4, loopCount = 0)
        encoder.start()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (frame in 0 until 6) {
            renderer.renderFrame(
                canvas = canvas,
                width = w,
                height = h,
                scene = project.activeScene,
                frame = frame * 4,
                fps = 24,
                charactersById = charsById,
                drawEditorOverlays = false
            )
            encoder.addFrame(bitmap)
        }
        bitmap.recycle()

        val out = ByteArrayOutputStream()
        encoder.finish(out)
        val bytes = out.toByteArray()
        assertTrue("GIF output should be non-empty", bytes.size > 800)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B.toByte(), bytes.last())
    }

    @Test
    fun `export PNG sequence writes valid PNG files to offline storage`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val project = BuiltInCatalog.sceneTemplates.first().buildProject("Png Test")
        val charsById = BuiltInCatalog.builtInCharacters.associateBy { it.id }
        val renderer = SceneRenderer { _, _ -> null }
        val exporter = AnimationExporter(context, renderer)

        val result = exporter.export(
            project = project,
            charactersById = charsById,
            request = ExportRequest(
                format = ExportFormat.PNG_SEQUENCE,
                resolution = ExportResolution.SD_480,
                fps = 12,
                durationFrames = 4
            )
        ) { _, _ -> }

        assertEquals(4, result.totalFrames)
        assertTrue(result.sizeBytes > 0)
        val dir = File(result.filePath)
        assertTrue(dir.exists() && dir.isDirectory)
        val firstPng = File(dir, "frame_0001.png")
        assertTrue(firstPng.exists())
        val header = firstPng.readBytes().take(4)
        assertEquals(listOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()), header)
    }
}
