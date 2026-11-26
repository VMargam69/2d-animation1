package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * High-performance 2D animation frame renderer with:
 * - Thread-local reusable Paint / Path / RectF pools (zero per-frame GC churn)
 * - Pre-scaled center-crop background bitmap cache (1:1 fast blit during playback & export)
 * - Playback-aware fast path that skips heavy editor overlays while video is playing
 */
class SceneRenderer(
    private val bitmapProvider: (path: String?, bundledKey: String?) -> Bitmap?
) {

    private class RenderScratch {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val motionDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        val gizmoDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = 0xFF38BDF8.toInt()
            pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
        }
        val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF0A0D12.toInt()
        }
        val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = 0xFF38BDF8.toInt()
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x16FFFFFF
            strokeWidth = 1f
        }
        val rect = RectF()
        val path = Path()
    }

    private data class ScaledBgEntry(
        val sourceGenId: Int,
        val width: Int,
        val height: Int,
        val scaledBitmap: Bitmap
    )

    private val scratchLocal = ThreadLocal.withInitial { RenderScratch() }
    private val bgCacheLock = Any()
    private var cachedBg: ScaledBgEntry? = null

    /** Creates an isolated [SceneRenderer] sharing the same bitmap loader but with its own export-sized background cache. */
    fun createIndependentCopy(): SceneRenderer = SceneRenderer(bitmapProvider)

    fun clearScaledCache() {
        synchronized(bgCacheLock) {
            cachedBg?.scaledBitmap?.recycle()
            cachedBg = null
        }
    }

    fun renderFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        scene: AnimationScene,
        frame: Int,
        fps: Int,
        charactersById: Map<String, CharacterRigDefinition>,
        onionSkinEnabled: Boolean = false,
        showMotionPaths: Boolean = false,
        selectedClipId: String? = null,
        drawEditorOverlays: Boolean = false,
        isPlaying: Boolean = false,
        isIkPosingMode: Boolean = false,
        isCameraKeyMode: Boolean = false,
        activeIkHandle: IkHandleId? = null
    ) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val scratch = scratchLocal.get()!!

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        val chromaHex = scene.chromaKeyMode.colorHex
        val isChromaActive = chromaHex != null

        // 1. Base Scene Background (or Chroma Key Green/Blue screen override)
        val baseBgColor = if (chromaHex != null) {
            ProceduralRigRenderer.parseColorSafe(chromaHex, 0xFF00FF00.toInt())
        } else {
            ProceduralRigRenderer.parseColorSafe(scene.backgroundColorHex, 0xFF151B28.toInt())
        }
        scratch.bgPaint.color = baseBgColor
        canvas.drawRect(0f, 0f, w, h, scratch.bgPaint)

        // Apply Keyframed Stage Camera Transform (Pan, Zoom, Rotation)
        val camera = KeyframeEvaluator.evaluateCamera(scene.cameraKeyframes, frame)
        canvas.save()
        if (!camera.isIdentity) {
            canvas.translate(w * 0.5f, h * 0.5f)
            canvas.scale(camera.zoom, camera.zoom)
            canvas.rotate(-camera.rotation)
            canvas.translate(-camera.x * w, -camera.y * h)
        }

        val sceneBgBitmap = if (isChromaActive) null else bitmapProvider(scene.backgroundImagePath, scene.backgroundBundledKey)
        if (sceneBgBitmap != null && !sceneBgBitmap.isRecycled) {
            drawBitmapCenterCropFast(canvas, sceneBgBitmap, width, height, 255, scratch)
        }

        // 2. Subtle stage grid when empty & in editor mode (paused)
        if (drawEditorOverlays && !isPlaying && sceneBgBitmap == null && !isChromaActive) {
            drawSubtleStageGrid(canvas, w, h, scratch)
        }

        val visibleLayers = scene.layers
            .filter { it.isVisible }
            .sortedBy { it.orderIndex }

        // 3. Onion Skinning (only when paused/scrubbing so live video playback stays locked at target FPS)
        if (onionSkinEnabled && drawEditorOverlays && !isPlaying) {
            drawGhostOffset(canvas, w, h, visibleLayers, scene, frame - 2, 0.16f, 0xFFF43F5E.toInt(), charactersById, scratch)
            drawGhostOffset(canvas, w, h, visibleLayers, scene, frame - 1, 0.28f, 0xFFF43F5E.toInt(), charactersById, scratch)
            drawGhostOffset(canvas, w, h, visibleLayers, scene, frame + 1, 0.20f, 0xFF10B981.toInt(), charactersById, scratch)
        }

        // 4. Current Frame Layers
        drawLayersAtFrame(
            canvas = canvas,
            intW = width,
            intH = height,
            w = w,
            h = h,
            visibleLayers = visibleLayers,
            clips = scene.clips,
            frame = frame,
            charactersById = charactersById,
            alphaMultiplier = 1f,
            tintOverlay = null,
            skipBackgroundLayers = isChromaActive,
            scratch = scratch
        )

        // 5. Editor Overlays (skip heavy dashed gizmo/motion-path overlays while actively playing)
        if (drawEditorOverlays && !isPlaying) {
            if (showMotionPaths) {
                drawMotionPaths(canvas, w, h, scene, visibleLayers, selectedClipId, scratch)
            }
            if (selectedClipId != null) {
                val selectedClip = scene.clips.firstOrNull { it.id == selectedClipId }
                val layerVisible = visibleLayers.any { it.id == selectedClip?.layerId }
                if (selectedClip != null && layerVisible && frame in selectedClip.startFrame until selectedClip.endFrame) {
                    val eval = KeyframeEvaluator.evaluate(selectedClip, frame)
                    drawSelectionGizmo(canvas, w, h, eval, scratch)
                    if (isIkPosingMode && selectedClip.characterId != null) {
                        val rig = charactersById[selectedClip.characterId]
                        if (rig != null) {
                            drawIkHandlesOverlay(
                                canvas = canvas,
                                w = w,
                                h = h,
                                clip = selectedClip,
                                rig = rig,
                                eval = eval,
                                frame = frame,
                                activeIkHandle = activeIkHandle,
                                scratch = scratch
                            )
                        }
                    }
                }
            }
        }

        canvas.restore() // restore camera transform

        if (drawEditorOverlays && !isPlaying && isCameraKeyMode) {
            drawCameraViewfinderOverlay(canvas, w, h, camera, scratch)
        }

        canvas.restore()
    }

    private fun drawGhostOffset(
        canvas: Canvas,
        w: Float,
        h: Float,
        visibleLayers: List<AnimationLayer>,
        scene: AnimationScene,
        ghostFrame: Int,
        ghostAlpha: Float,
        tintColor: Int,
        charactersById: Map<String, CharacterRigDefinition>,
        scratch: RenderScratch
    ) {
        if (ghostFrame in 0 until scene.durationFrames) {
            drawLayersAtFrame(
                canvas, w.toInt(), h.toInt(), w, h, visibleLayers, scene.clips, ghostFrame,
                charactersById, alphaMultiplier = ghostAlpha, tintOverlay = tintColor,
                skipBackgroundLayers = true, scratch = scratch
            )
        }
    }

    private fun drawLayersAtFrame(
        canvas: Canvas,
        intW: Int,
        intH: Int,
        w: Float,
        h: Float,
        visibleLayers: List<AnimationLayer>,
        clips: List<TimelineClip>,
        frame: Int,
        charactersById: Map<String, CharacterRigDefinition>,
        alphaMultiplier: Float,
        tintOverlay: Int?,
        skipBackgroundLayers: Boolean,
        scratch: RenderScratch
    ) {
        val baseScale = min(w, h) / 420f

        for (layerIdx in visibleLayers.indices) {
            val layer = visibleLayers[layerIdx]
            if (skipBackgroundLayers && layer.type == LayerType.BACKGROUND) continue
            if (layer.type == LayerType.AUDIO) continue

            for (clipIdx in clips.indices) {
                val clip = clips[clipIdx]
                if (clip.layerId != layer.id || frame < clip.startFrame || frame >= clip.endFrame) continue

                val transform = KeyframeEvaluator.evaluate(clip, frame)
                val effectiveAlpha = (transform.opacity * alphaMultiplier).coerceIn(0f, 1f)
                if (effectiveAlpha <= 0.01f) continue

                if (layer.type == LayerType.BACKGROUND) {
                    val bgBmp = bitmapProvider(clip.imagePath, clip.bundledBgKey)
                    if (bgBmp != null && !bgBmp.isRecycled) {
                        drawBitmapCenterCropFast(canvas, bgBmp, intW, intH, (effectiveAlpha * 255).toInt(), scratch)
                    }
                    continue
                }

                if (layer.type == LayerType.DRAWING) {
                    drawStrokesClip(canvas, w, h, clip, effectiveAlpha, tintOverlay, scratch)
                    continue
                }

                canvas.save()
                canvas.translate(transform.x * w, transform.y * h)
                canvas.rotate(transform.rotation)
                val sx = transform.scaleX * if (clip.flipHorizontal) -1f else 1f
                val sy = transform.scaleY * if (clip.flipVertical) -1f else 1f
                canvas.scale(sx, sy)

                when (layer.type) {
                    LayerType.CHARACTER -> {
                        val rig = clip.characterId?.let { charactersById[it] }
                        if (rig != null) {
                            val localFrame = (frame - clip.startFrame).coerceAtLeast(0)
                            val cycleFrames = clip.animationPresetType.defaultDurationFrames.coerceAtLeast(8).toFloat()
                            val rawPhase = (localFrame * clip.speedMultiplier) / cycleFrames
                            val phase = if (clip.loopAnimation) rawPhase % 1f else rawPhase.coerceIn(0f, 0.999f)
                            val customBmp = bitmapProvider(rig.customImagePath, null)
                            val customPropBmp = if (clip.propType == CharacterPropType.CUSTOM_PNG) {
                                bitmapProvider(clip.customPropImagePath, null)
                            } else null
                            val effectiveExpression = transform.expressionOverride ?: clip.facialExpression
                            ProceduralRigRenderer.drawCharacter(
                                canvas = canvas,
                                rig = rig,
                                orientation = clip.orientation,
                                preset = clip.animationPresetType,
                                phase = phase,
                                rigScale = baseScale,
                                alpha = effectiveAlpha,
                                customBitmap = customBmp,
                                tintOverlayColor = tintOverlay,
                                poseOffsets = transform.poseOffsets,
                                facialExpression = effectiveExpression,
                                propType = clip.propType,
                                propAttachment = clip.propAttachment,
                                customPropBitmap = customPropBmp,
                                propSwayEnabled = clip.propSwayEnabled,
                                propScale = clip.propScale
                            )
                        }
                    }
                    LayerType.IMAGE -> {
                        val bmp = bitmapProvider(clip.imagePath, clip.bundledBgKey)
                        if (bmp != null && !bmp.isRecycled) {
                            val maxDim = min(w, h) * 0.42f
                            val ratio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
                            val dw = if (ratio >= 1f) maxDim else maxDim * ratio
                            val dh = if (ratio >= 1f) maxDim / ratio else maxDim
                            val paint = scratch.bitmapPaint
                            paint.alpha = (effectiveAlpha * 255).toInt()
                            paint.colorFilter = if (tintOverlay != null) {
                                PorterDuffColorFilter(tintOverlay, PorterDuff.Mode.SRC_ATOP)
                            } else null
                            scratch.rect.set(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f)
                            canvas.drawBitmap(bmp, null, scratch.rect, paint)
                            paint.colorFilter = null
                        }
                    }
                    LayerType.SHAPE -> {
                        drawShapeClip(canvas, clip, baseScale, effectiveAlpha, tintOverlay, scratch)
                    }
                    LayerType.TEXT -> {
                        drawTextClip(canvas, clip, baseScale, effectiveAlpha, tintOverlay, scratch)
                    }
                    else -> Unit
                }
                canvas.restore()
            }
        }
    }

    private fun drawShapeClip(
        canvas: Canvas,
        clip: TimelineClip,
        s: Float,
        alpha: Float,
        tintOverlay: Int?,
        scratch: RenderScratch
    ) {
        val kind = clip.shapeKind ?: ShapeKind.CIRCLE
        val alphaInt = (alpha * 255).toInt()
        val fillColor = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(clip.shapeFillHex, 0xFF38BDF8.toInt())
        val strokeColor = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(clip.shapeStrokeHex, 0xFFF8FAFC.toInt())

        val fill = scratch.fillPaint
        fill.color = Color.argb(alphaInt, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor))
        val stroke = scratch.strokePaint
        stroke.strokeWidth = 4f * s
        stroke.color = Color.argb(alphaInt, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))

        val r = 56f * s
        when (kind) {
            ShapeKind.RECTANGLE -> {
                scratch.rect.set(-r, -r * 0.72f, r, r * 0.72f)
                canvas.drawRoundRect(scratch.rect, 14f * s, 14f * s, fill)
                canvas.drawRoundRect(scratch.rect, 14f * s, 14f * s, stroke)
            }
            ShapeKind.CIRCLE -> {
                canvas.drawCircle(0f, 0f, r * 0.85f, fill)
                canvas.drawCircle(0f, 0f, r * 0.85f, stroke)
            }
            ShapeKind.STAR -> {
                val path = scratch.path
                path.reset()
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) r else r * 0.44f
                    val angle = (-PI / 2.0 + i * PI / 5.0)
                    val px = (cos(angle) * rad).toFloat()
                    val py = (sin(angle) * rad).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
            ShapeKind.SPEECH_BUBBLE -> {
                scratch.rect.set(-r * 1.2f, -r * 0.72f, r * 1.2f, r * 0.52f)
                canvas.drawRoundRect(scratch.rect, 20f * s, 20f * s, fill)
                canvas.drawRoundRect(scratch.rect, 20f * s, 20f * s, stroke)
                val path = scratch.path
                path.reset()
                path.moveTo(-22f * s, r * 0.48f)
                path.lineTo(-42f * s, r * 0.92f)
                path.lineTo(4f * s, r * 0.48f)
                path.close()
                canvas.drawPath(path, fill)
            }
            ShapeKind.ARROW -> {
                val path = scratch.path
                path.reset()
                path.moveTo(-r, -r * 0.25f)
                path.lineTo(r * 0.2f, -r * 0.25f)
                path.lineTo(r * 0.2f, -r * 0.6f)
                path.lineTo(r, 0f)
                path.lineTo(r * 0.2f, r * 0.6f)
                path.lineTo(r * 0.2f, r * 0.25f)
                path.lineTo(-r, r * 0.25f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawTextClip(
        canvas: Canvas,
        clip: TimelineClip,
        s: Float,
        alpha: Float,
        tintOverlay: Int?,
        scratch: RenderScratch
    ) {
        val text = clip.textContent?.ifBlank { "Text" } ?: "Text"
        val baseColor = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(clip.textColorHex, 0xFFFFFFFF.toInt())
        val alphaInt = (alpha * 255).toInt()
        val textSizePx = clip.textSizeSp * s * 1.15f

        val shadowPaint = scratch.textShadowPaint
        shadowPaint.color = Color.argb((alphaInt * 0.65f).toInt(), 0, 0, 0)
        shadowPaint.textSize = textSizePx

        val paint = scratch.textPaint
        paint.color = Color.argb(alphaInt, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        paint.textSize = textSizePx

        canvas.drawText(text, 2.5f * s, 2.5f * s, shadowPaint)
        canvas.drawText(text, 0f, 0f, paint)
    }

    private fun drawStrokesClip(
        canvas: Canvas,
        w: Float,
        h: Float,
        clip: TimelineClip,
        alpha: Float,
        tintOverlay: Int?,
        scratch: RenderScratch
    ) {
        val alphaInt = (alpha * 255).toInt()
        val minDim = min(w, h)
        val paint = scratch.strokePaint
        val path = scratch.path
        for (strokeData in clip.drawingStrokes) {
            if (strokeData.points.size < 2) continue
            val col = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(strokeData.colorHex, 0xFF38BDF8.toInt())
            paint.strokeWidth = (strokeData.strokeWidthRatio * minDim).coerceAtLeast(3f)
            paint.color = Color.argb(alphaInt, Color.red(col), Color.green(col), Color.blue(col))
            path.reset()
            strokeData.points.forEachIndexed { idx, pt ->
                val px = pt.x * w
                val py = pt.y * h
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawMotionPaths(
        canvas: Canvas,
        w: Float,
        h: Float,
        scene: AnimationScene,
        visibleLayers: List<AnimationLayer>,
        selectedClipId: String?,
        scratch: RenderScratch
    ) {
        val visibleIds = visibleLayers.map { it.id }.toSet()
        val pathPaint = scratch.motionDashPaint
        val dotPaint = scratch.fillPaint
        val path = scratch.path

        for (clip in scene.clips) {
            if (clip.layerId !in visibleIds || clip.keyframes.size < 2) continue
            val isSel = clip.id == selectedClipId
            val pts = KeyframeEvaluator.sampleMotionPath(clip, 24)
            pathPaint.strokeWidth = if (isSel) 3.5f else 2f
            pathPaint.color = if (isSel) 0xFFF59E0B.toInt() else 0x8838BDF8.toInt()
            path.reset()
            pts.forEachIndexed { i, pt ->
                if (i == 0) path.moveTo(pt.x * w, pt.y * h) else path.lineTo(pt.x * w, pt.y * h)
            }
            canvas.drawPath(path, pathPaint)

            dotPaint.color = if (isSel) 0xFFF59E0B.toInt() else 0xFF38BDF8.toInt()
            for (kf in clip.keyframes) {
                canvas.drawCircle(kf.x * w, kf.y * h, if (isSel) 6.5f else 4.5f, dotPaint)
            }
        }
    }

    private fun drawSelectionGizmo(
        canvas: Canvas,
        w: Float,
        h: Float,
        eval: EvaluatedTransform,
        scratch: RenderScratch
    ) {
        val cx = eval.x * w
        val cy = eval.y * h
        val halfBox = min(w, h) * 0.16f * ((abs(eval.scaleX) + abs(eval.scaleY)) / 2f).coerceIn(0.4f, 2.2f)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(eval.rotation)
        scratch.rect.set(-halfBox, -halfBox * 1.15f, halfBox, halfBox * 1.15f)
        canvas.drawRect(scratch.rect, scratch.gizmoDashPaint)

        val l = scratch.rect.left
        val r = scratch.rect.right
        val t = scratch.rect.top
        val b = scratch.rect.bottom
        canvas.drawCircle(l, t, 7f, scratch.handleFillPaint)
        canvas.drawCircle(l, t, 7f, scratch.handleStrokePaint)
        canvas.drawCircle(r, t, 7f, scratch.handleFillPaint)
        canvas.drawCircle(r, t, 7f, scratch.handleStrokePaint)
        canvas.drawCircle(l, b, 7f, scratch.handleFillPaint)
        canvas.drawCircle(l, b, 7f, scratch.handleStrokePaint)
        canvas.drawCircle(r, b, 7f, scratch.handleFillPaint)
        canvas.drawCircle(r, b, 7f, scratch.handleStrokePaint)
        canvas.drawCircle(0f, 0f, 5f, scratch.handleStrokePaint)
        canvas.restore()
    }

    private fun drawIkHandlesOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        clip: TimelineClip,
        rig: CharacterRigDefinition,
        eval: EvaluatedTransform,
        frame: Int,
        activeIkHandle: IkHandleId?,
        scratch: RenderScratch
    ) {
        val localFrame = (frame - clip.startFrame).coerceAtLeast(0)
        val cycleFrames = clip.animationPresetType.defaultDurationFrames.coerceAtLeast(8).toFloat()
        val rawPhase = (localFrame * clip.speedMultiplier) / cycleFrames
        val phase = if (clip.loopAnimation) rawPhase % 1f else rawPhase.coerceIn(0f, 0.999f)
        val basePose = ProceduralRigRenderer.computePose(clip.animationPresetType, phase)
        val posed = basePose.withOffsets(eval.poseOffsets, eval.expressionOverride ?: clip.facialExpression)

        val handles = PropAndModularChartRenderer.computeStageIkHandles(
            clip = clip,
            rig = rig,
            transform = eval,
            pose = posed,
            stageWidth = w,
            stageHeight = h
        )

        val byId = handles.associateBy { it.id }
        val linkPaint = scratch.strokePaint
        linkPaint.strokeWidth = 2.8f
        linkPaint.color = 0xAA38BDF8.toInt()

        fun drawLink(a: IkHandleId, b: IkHandleId) {
            val ha = byId[a] ?: return
            val hb = byId[b] ?: return
            canvas.drawLine(ha.nx * w, ha.ny * h, hb.nx * w, hb.ny * h, linkPaint)
        }

        drawLink(IkHandleId.TORSO, IkHandleId.HEAD)
        drawLink(IkHandleId.TORSO, IkHandleId.LEFT_ELBOW)
        drawLink(IkHandleId.LEFT_ELBOW, IkHandleId.LEFT_HAND)
        drawLink(IkHandleId.TORSO, IkHandleId.RIGHT_ELBOW)
        drawLink(IkHandleId.RIGHT_ELBOW, IkHandleId.RIGHT_HAND)
        drawLink(IkHandleId.TORSO, IkHandleId.LEFT_FOOT)
        drawLink(IkHandleId.TORSO, IkHandleId.RIGHT_FOOT)

        val fill = scratch.fillPaint
        val ring = scratch.strokePaint
        val labelPaint = scratch.textPaint
        labelPaint.textSize = (min(w, h) * 0.022f).coerceIn(14f, 22f)

        for (hnd in handles) {
            val px = hnd.nx * w
            val py = hnd.ny * h
            val isActive = hnd.id == activeIkHandle
            val radius = if (isActive) 13f else 9.5f

            // Outer glow
            fill.color = Color.argb(if (isActive) 110 else 65, Color.red(hnd.color), Color.green(hnd.color), Color.blue(hnd.color))
            canvas.drawCircle(px, py, radius + 6f, fill)

            // Handle core
            fill.color = hnd.color
            canvas.drawCircle(px, py, radius, fill)

            // Crisp white ring
            ring.strokeWidth = if (isActive) 3.2f else 2.2f
            ring.color = Color.WHITE
            canvas.drawCircle(px, py, radius, ring)

            // Label pill above active or primary hand/head handles
            if (isActive || hnd.id == IkHandleId.HEAD || hnd.id == IkHandleId.LEFT_HAND || hnd.id == IkHandleId.RIGHT_HAND) {
                labelPaint.color = Color.WHITE
                canvas.drawText(hnd.id.label, px, py - radius - 8f, labelPaint)
            }
        }
    }

    private fun drawCameraViewfinderOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        camera: EvaluatedCamera,
        scratch: RenderScratch
    ) {
        val stroke = scratch.strokePaint
        stroke.strokeWidth = 3f
        stroke.color = 0xFFFACC15.toInt()
        val inset = min(w, h) * 0.04f
        val corner = min(w, h) * 0.06f

        // Corner brackets
        canvas.drawLine(inset, inset, inset + corner, inset, stroke)
        canvas.drawLine(inset, inset, inset, inset + corner, stroke)
        canvas.drawLine(w - inset, inset, w - inset - corner, inset, stroke)
        canvas.drawLine(w - inset, inset, w - inset, inset + corner, stroke)
        canvas.drawLine(inset, h - inset, inset + corner, h - inset, stroke)
        canvas.drawLine(inset, h - inset, inset, h - inset - corner, stroke)
        canvas.drawLine(w - inset, h - inset, w - inset - corner, h - inset, stroke)
        canvas.drawLine(w - inset, h - inset, w - inset, h - inset - corner, stroke)

        // Center crosshair
        val cx = w * 0.5f
        val cy = h * 0.5f
        canvas.drawCircle(cx, cy, 10f, stroke)
        canvas.drawLine(cx - 18f, cy, cx + 18f, cy, stroke)
        canvas.drawLine(cx, cy - 18f, cx, cy + 18f, stroke)

        // HUD status badge
        val label = scratch.textPaint
        label.textSize = (min(w, h) * 0.024f).coerceIn(16f, 24f)
        label.color = 0xFFFACC15.toInt()
        val zoomPct = (camera.zoom * 100).toInt()
        canvas.drawText("CAM ${zoomPct}% • (${"%.2f".format(camera.x)}, ${"%.2f".format(camera.y)})", cx, inset + label.textSize + 4f, label)
    }

    private fun drawSubtleStageGrid(canvas: Canvas, w: Float, h: Float, scratch: RenderScratch) {
        val step = (min(w, h) / 10f).coerceAtLeast(24f)
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, scratch.gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, scratch.gridPaint)
            y += step
        }
    }

    /**
     * Pre-scales and caches the background bitmap once per `(bitmap, dstW, dstH)` so
     * playback & export frames perform an ultra-fast 1:1 blit without per-frame resampling.
     */
    private fun drawBitmapCenterCropFast(
        canvas: Canvas,
        bitmap: Bitmap,
        dstWInt: Int,
        dstHInt: Int,
        alphaInt: Int,
        scratch: RenderScratch
    ) {
        val paint = scratch.bitmapPaint
        paint.alpha = alphaInt
        paint.colorFilter = null

        val targetW = dstWInt.coerceIn(16, 1920)
        val targetH = dstHInt.coerceIn(16, 1920)
        val genId = bitmap.generationId

        val preScaled = synchronized(bgCacheLock) {
            val cur = cachedBg
            if (cur != null && !cur.scaledBitmap.isRecycled &&
                cur.sourceGenId == genId && cur.width == targetW && cur.height == targetH
            ) {
                cur.scaledBitmap
            } else {
                cur?.scaledBitmap?.recycle()
                runCatching {
                    val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
                    val c = Canvas(out)
                    val srcW = bitmap.width.toFloat().coerceAtLeast(1f)
                    val srcH = bitmap.height.toFloat().coerceAtLeast(1f)
                    val scale = maxOf(targetW / srcW, targetH / srcH)
                    val drawW = srcW * scale
                    val drawH = srcH * scale
                    val left = (targetW - drawW) / 2f
                    val top = (targetH - drawH) / 2f
                    c.drawBitmap(
                        bitmap,
                        null,
                        RectF(left, top, left + drawW, top + drawH),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                    cachedBg = ScaledBgEntry(genId, targetW, targetH, out)
                    out
                }.getOrNull()
            }
        }

        if (preScaled != null && !preScaled.isRecycled) {
            if (targetW == dstWInt && targetH == dstHInt) {
                canvas.drawBitmap(preScaled, 0f, 0f, paint)
            } else {
                scratch.rect.set(0f, 0f, dstWInt.toFloat(), dstHInt.toFloat())
                canvas.drawBitmap(preScaled, null, scratch.rect, paint)
            }
        } else {
            val srcW = bitmap.width.toFloat().coerceAtLeast(1f)
            val srcH = bitmap.height.toFloat().coerceAtLeast(1f)
            val scale = maxOf(dstWInt / srcW, dstHInt / srcH)
            val drawW = srcW * scale
            val drawH = srcH * scale
            val left = (dstWInt - drawW) / 2f
            val top = (dstHInt - drawH) / 2f
            scratch.rect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bitmap, null, scratch.rect, paint)
        }
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
