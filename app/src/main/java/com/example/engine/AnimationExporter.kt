package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportRequest(
    val format: ExportFormat,
    val resolution: ExportResolution,
    val fps: Int,
    val durationFrames: Int
)

data class ExportOutput(
    val displayPath: String,
    val filePath: String,
    val format: ExportFormat,
    val totalFrames: Int,
    val sizeBytes: Long
)

class AnimationExporter(
    private val context: Context,
    private val sceneRenderer: SceneRenderer
) {

    suspend fun export(
        project: ProjectDocument,
        charactersById: Map<String, CharacterRigDefinition>,
        request: ExportRequest,
        onProgress: (currentFrame: Int, totalFrames: Int) -> Unit
    ): ExportOutput = withContext(Dispatchers.Default) {
        val scene = project.activeScene
        val totalFrames = request.durationFrames.coerceIn(1, scene.durationFrames.coerceAtLeast(1))
        val (outW, outH) = computeExportDimensions(project.width, project.height, request.resolution.longEdge)
        val safeName = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(28).ifBlank { "FrameCraft" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseFileName = "${safeName}_$stamp"

        // Dedicated export renderer keeps export-sized background bitmap cached across all frames
        val exportRenderer = sceneRenderer.createIndependentCopy()

        // Throttle Compose UI progress callbacks (~15 updates/sec max) so UI stays buttery smooth
        var lastReportMs = 0L
        val throttledProgress: (Int, Int) -> Unit = { cur, tot ->
            val now = SystemClock.uptimeMillis()
            if (cur == tot || cur == 1 || (now - lastReportMs) >= 65L) {
                lastReportMs = now
                onProgress(cur, tot)
            }
        }

        try {
            when (request.format) {
                ExportFormat.PNG_SEQUENCE -> exportPngSequence(
                    exportRenderer, project, scene, charactersById, totalFrames, request.fps, outW, outH, baseFileName, throttledProgress
                )
                ExportFormat.GIF -> exportAnimatedGif(
                    exportRenderer, project, scene, charactersById, totalFrames, request.fps, outW, outH, baseFileName, throttledProgress
                )
                ExportFormat.MP4 -> exportMp4Video(
                    exportRenderer, project, scene, charactersById, totalFrames, request.fps, outW, outH, baseFileName, throttledProgress
                )
            }
        } finally {
            exportRenderer.clearScaledCache()
        }
    }

    private suspend fun exportPngSequence(
        renderer: SceneRenderer,
        project: ProjectDocument,
        scene: AnimationScene,
        charactersById: Map<String, CharacterRigDefinition>,
        totalFrames: Int,
        fps: Int,
        outW: Int,
        outH: Int,
        baseName: String,
        onProgress: (Int, Int) -> Unit
    ): ExportOutput {
        val rootDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "FrameCraft/$baseName")
        rootDir.mkdirs()

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var totalBytes = 0L

        try {
            for (frame in 0 until totalFrames) {
                currentCoroutineContext().ensureActive()
                renderer.renderFrame(
                    canvas = canvas,
                    width = outW,
                    height = outH,
                    scene = scene,
                    frame = frame,
                    fps = fps,
                    charactersById = charactersById,
                    onionSkinEnabled = false,
                    showMotionPaths = false,
                    selectedClipId = null,
                    drawEditorOverlays = false,
                    isPlaying = true,
                    inlineSvgAssets = project.inlineSvgAssets,
                    customAnimations = project.customAnimations,
                    customTransitions = project.customTransitions
                )
                val frameFile = File(rootDir, String.format(Locale.US, "frame_%04d.png", frame + 1))
                BufferedOutputStream(FileOutputStream(frameFile), 32 * 1024).use { bos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                }
                totalBytes += frameFile.length()
                onProgress(frame + 1, totalFrames)
            }
        } finally {
            bitmap.recycle()
        }

        return ExportOutput(
            displayPath = "Movies/FrameCraft/$baseName ($totalFrames PNGs)",
            filePath = rootDir.absolutePath,
            format = ExportFormat.PNG_SEQUENCE,
            totalFrames = totalFrames,
            sizeBytes = totalBytes
        )
    }

    private suspend fun exportAnimatedGif(
        renderer: SceneRenderer,
        project: ProjectDocument,
        scene: AnimationScene,
        charactersById: Map<String, CharacterRigDefinition>,
        totalFrames: Int,
        fps: Int,
        outW: Int,
        outH: Int,
        baseName: String,
        onProgress: (Int, Int) -> Unit
    ): ExportOutput {
        val (gifW, gifH) = computeExportDimensions(outW, outH, minOf(outW.coerceAtLeast(outH), 640))
        val delayCs = (100 / fps.coerceIn(6, 30)).coerceAtLeast(3)
        val encoder = GifEncoder(width = gifW, height = gifH, delayCentiseconds = delayCs, loopCount = 0)
        encoder.start()

        val bitmap = Bitmap.createBitmap(gifW, gifH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        try {
            for (frame in 0 until totalFrames) {
                currentCoroutineContext().ensureActive()
                renderer.renderFrame(
                    canvas = canvas,
                    width = gifW,
                    height = gifH,
                    scene = scene,
                    frame = frame,
                    fps = fps,
                    charactersById = charactersById,
                    onionSkinEnabled = false,
                    showMotionPaths = false,
                    selectedClipId = null,
                    drawEditorOverlays = false,
                    isPlaying = true,
                    inlineSvgAssets = project.inlineSvgAssets,
                    customAnimations = project.customAnimations,
                    customTransitions = project.customTransitions
                )
                encoder.addFrame(bitmap)
                onProgress(frame + 1, totalFrames)
            }
        } finally {
            bitmap.recycle()
        }

        val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "FrameCraft")
        outDir.mkdirs()
        val localFile = File(outDir, "$baseName.gif")
        withContext(Dispatchers.IO) {
            BufferedOutputStream(FileOutputStream(localFile), 64 * 1024).use { bos ->
                encoder.finish(bos)
            }
        }

        val displayLocation = withContext(Dispatchers.IO) {
            publishToMediaStoreIfSupported(
                sourceFile = localFile,
                fileName = "$baseName.gif",
                mimeType = "image/gif",
                isVideo = false
            )
        } ?: "Pictures/FrameCraft/$baseName.gif"

        return ExportOutput(
            displayPath = displayLocation,
            filePath = localFile.absolutePath,
            format = ExportFormat.GIF,
            totalFrames = totalFrames,
            sizeBytes = localFile.length()
        )
    }

    private suspend fun exportMp4Video(
        renderer: SceneRenderer,
        project: ProjectDocument,
        scene: AnimationScene,
        charactersById: Map<String, CharacterRigDefinition>,
        totalFrames: Int,
        fps: Int,
        rawW: Int,
        rawH: Int,
        baseName: String,
        onProgress: (Int, Int) -> Unit
    ): ExportOutput {
        // H.264 requires dimensions divisible by 16 for universal hardware encoder compatibility
        val outW = ((rawW / 16) * 16).coerceAtLeast(160)
        val outH = ((rawH / 16) * 16).coerceAtLeast(160)

        val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "FrameCraft")
        outDir.mkdirs()
        val localFile = File(outDir, "$baseName.mp4")

        val safeFps = fps.coerceIn(12, 60)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * 4).coerceIn(1_500_000, 10_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(localFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        var writtenFrames = 0L

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / safeFps

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder.signalEndOfInputStream()
                }
                // Non-blocking drain (0us) during active frame loop eliminates artificial 10ms/frame stalls!
                val timeoutUs = if (endOfStream) 5_000L else 0L
                var eosRetries = 0
                while (true) {
                    val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                            eosRetries++
                            if (eosRetries > 400) break
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outputIndex >= 0 -> {
                            eosRetries = 0
                            val encodedData = encoder.getOutputBuffer(outputIndex)
                            if (encodedData != null && bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                muxerStarted
                            ) {
                                // Stamp exact deterministic frame PTS so playback is 100% smooth at target FPS
                                // with zero Thread.sleep() stalls!
                                bufferInfo.presentationTimeUs = writtenFrames * frameDurationUs
                                writtenFrames++
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
            }

            for (frame in 0 until totalFrames) {
                currentCoroutineContext().ensureActive()
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }
                try {
                    renderer.renderFrame(
                        canvas = canvas,
                        width = outW,
                        height = outH,
                        scene = scene,
                        frame = frame,
                        fps = safeFps,
                        charactersById = charactersById,
                        onionSkinEnabled = false,
                        showMotionPaths = false,
                        selectedClipId = null,
                        drawEditorOverlays = false,
                        isPlaying = true,
                        inlineSvgAssets = project.inlineSvgAssets,
                        customAnimations = project.customAnimations,
                        customTransitions = project.customTransitions
                    )
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drainEncoder(endOfStream = false)
                onProgress(frame + 1, totalFrames)
            }

            drainEncoder(endOfStream = true)
            inputSurface.release()
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }

        val displayLocation = withContext(Dispatchers.IO) {
            publishToMediaStoreIfSupported(
                sourceFile = localFile,
                fileName = "$baseName.mp4",
                mimeType = "video/mp4",
                isVideo = true
            )
        } ?: "Movies/FrameCraft/$baseName.mp4"

        return ExportOutput(
            displayPath = displayLocation,
            filePath = localFile.absolutePath,
            format = ExportFormat.MP4,
            totalFrames = totalFrames,
            sizeBytes = localFile.length()
        )
    }

    private fun publishToMediaStoreIfSupported(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        isVideo: Boolean
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !sourceFile.exists()) return null
        return runCatching {
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val relDir = if (isVideo) {
                "${Environment.DIRECTORY_MOVIES}/FrameCraft"
            } else {
                "${Environment.DIRECTORY_PICTURES}/FrameCraft"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            "$relDir/$fileName"
        }.getOrNull()
    }

    companion object {
        fun computeExportDimensions(srcW: Int, srcH: Int, targetLongEdge: Int): Pair<Int, Int> {
            val w = srcW.coerceAtLeast(16)
            val h = srcH.coerceAtLeast(16)
            val ratio = w.toFloat() / h.toFloat()
            val (rawW, rawH) = if (w >= h) {
                targetLongEdge to (targetLongEdge / ratio).toInt()
            } else {
                (targetLongEdge * ratio).toInt() to targetLongEdge
            }
            val evenW = (rawW / 2) * 2
            val evenH = (rawH / 2) * 2
            return evenW.coerceAtLeast(64) to evenH.coerceAtLeast(64)
        }
    }
}
