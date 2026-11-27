package com.example.engine

import android.graphics.*
import android.util.LruCache
import com.example.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Local drag preview state kept inside EditorCanvas during active finger gestures so
 * moving clips, posing IK handles, or panning the camera renders at full refresh rate
 * without copying ProjectDocument or triggering Compose recompositions.
 */
data class StageGesturePreview(
    val draggedClipId: String? = null,
    val clipDeltaX: Float = 0f,
    val clipDeltaY: Float = 0f,
    val dragVelocityX: Float = 0f,
    val dragVelocityY: Float = 0f,
    val ikClipId: String? = null,
    val ikPoseOffsets: PoseKeyframeOffsets? = null,
    val cameraDeltaX: Float = 0f,
    val cameraDeltaY: Float = 0f
) {
    val isActive: Boolean
        get() = draggedClipId != null || ikClipId != null || cameraDeltaX != 0f || cameraDeltaY != 0f
}

/**
 * High-performance 2D animation frame renderer with:
 * - Thread-local reusable Paint / Path / RectF pools (zero per-frame GC churn)
 * - Pre-scaled center-crop background bitmap cache (1:1 fast blit during playback & export)
 * - Cached freehand stroke Paths & parsed colors (LruCache)
 * - Offscreen cached onion-skin composite bitmap (1 blit per redraw/drag)
 * - Playback-aware fast path that skips heavy editor overlays while video is playing
 */
class SceneRenderer(
    private val bitmapProvider: (path: String?, bundledKey: String?) -> Bitmap?,
    private val blockingBitmapProvider: ((path: String?, bundledKey: String?) -> Bitmap?)? = null,
    private val atlasSlicesProvider: ((path: String?) -> Map<AtlasPartSlot, Bitmap>?)? = null,
    private val blockingAtlasSlicesProvider: ((path: String?) -> Map<AtlasPartSlot, Bitmap>?)? = null
) {
    constructor(bitmapProvider: (path: String?, bundledKey: String?) -> Bitmap?) : this(bitmapProvider, null, null, null)

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
        val layerClipBuf = ArrayList<TimelineClip>(8)
    }

    private data class ScaledBgEntry(
        val sourceGenId: Int,
        val width: Int,
        val height: Int,
        val scaledBitmap: Bitmap
    )

    private data class StrokeCacheKey(
        val strokeId: String,
        val pointCount: Int,
        val colorHex: String,
        val brushStyle: BrushStyle,
        val width: Int,
        val height: Int
    )

    private data class CachedStroke(
        val path: Path,
        val colorInt: Int
    )

    private data class OnionCacheEntry(
        val sceneId: String,
        val frame: Int,
        val width: Int,
        val height: Int,
        val clipsIdentity: Int,
        val layersIdentity: Int,
        val charsIdentity: Int,
        val bitmap: Bitmap
    )

    private val scratchLocal = ThreadLocal.withInitial { RenderScratch() }
    private val bgCacheLock = Any()
    private var cachedBg: ScaledBgEntry? = null
    private val onionCacheLock = Any()
    private var cachedOnion: OnionCacheEntry? = null
    private val strokeCache = LruCache<StrokeCacheKey, CachedStroke>(256)

    /** Creates an isolated [SceneRenderer] using blocking disk decode for background export threads. */
    fun createIndependentCopy(): SceneRenderer = SceneRenderer(
        bitmapProvider = blockingBitmapProvider ?: bitmapProvider,
        blockingBitmapProvider = blockingBitmapProvider,
        atlasSlicesProvider = blockingAtlasSlicesProvider ?: atlasSlicesProvider,
        blockingAtlasSlicesProvider = blockingAtlasSlicesProvider
    )

    fun clearScaledCache() {
        synchronized(bgCacheLock) {
            cachedBg?.scaledBitmap?.recycle()
            cachedBg = null
        }
        synchronized(onionCacheLock) {
            cachedOnion?.bitmap?.recycle()
            cachedOnion = null
        }
        strokeCache.evictAll()
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
        activeIkHandle: IkHandleId? = null,
        gesturePreview: StageGesturePreview? = null
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

        // Apply Keyframed Stage Camera Transform (Pan, Zoom, Rotation + optional Focus Tracking + live drag preview)
        val rawCamera = KeyframeEvaluator.evaluateCamera(
            scene = scene,
            globalFrame = frame,
            clipDeltaOverrideId = gesturePreview?.draggedClipId,
            clipDeltaX = gesturePreview?.clipDeltaX ?: 0f,
            clipDeltaY = gesturePreview?.clipDeltaY ?: 0f
        )
        val camera = if (gesturePreview != null && (gesturePreview.cameraDeltaX != 0f || gesturePreview.cameraDeltaY != 0f)) {
            rawCamera.copy(
                x = (rawCamera.x - gesturePreview.cameraDeltaX).coerceIn(0.05f, 0.95f),
                y = (rawCamera.y - gesturePreview.cameraDeltaY).coerceIn(0.05f, 0.95f)
            )
        } else {
            rawCamera
        }

        val shake = if (!isChromaActive && scene.vfxConfig.cameraShakeIntensity > 0.005f) {
            SceneVfxEngine.computeCameraShake(
                frame = frame,
                fps = fps,
                intensity = scene.vfxConfig.cameraShakeIntensity,
                seed = scene.id.hashCode()
            )
        } else {
            CameraShakeOffset()
        }
        val effectiveCamera = if (!shake.isZero) {
            camera.copy(
                x = (camera.x + shake.dxNorm).coerceIn(0.02f, 0.98f),
                y = (camera.y + shake.dyNorm).coerceIn(0.02f, 0.98f),
                rotation = camera.rotation + shake.rollDeg
            )
        } else {
            camera
        }

        canvas.save()
        if (!effectiveCamera.isIdentity) {
            canvas.translate(w * 0.5f, h * 0.5f)
            canvas.scale(effectiveCamera.zoom, effectiveCamera.zoom)
            canvas.rotate(-effectiveCamera.rotation)
            canvas.translate(-effectiveCamera.x * w, -effectiveCamera.y * h)
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

        // 3. Cached Onion Skinning (1 previous + 1 next ghost composited once per frame change into offscreen cache)
        if (onionSkinEnabled && drawEditorOverlays && !isPlaying) {
            drawCachedOnionSkin(canvas, width, height, w, h, visibleLayers, scene, frame, charactersById, scratch)
        }

        // 4. Current Frame Layers (with zero-recomposition live gesture preview overrides)
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
            gesturePreview = gesturePreview,
            scratch = scratch,
            fps = fps
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
                    val eval = applyGesturePreview(
                        KeyframeEvaluator.evaluate(selectedClip, frame),
                        selectedClip.id,
                        gesturePreview
                    )
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

        // 6. Scene VFX & Atmosphere (particles, cinematic lighting moods, and vignette in full-frame stage space)
        if (!isChromaActive && scene.vfxConfig.hasActiveVfx) {
            SceneVfxEngine.drawSceneParticles(
                canvas = canvas,
                width = w,
                height = h,
                frame = frame,
                fps = fps,
                config = scene.vfxConfig,
                seed = scene.id.hashCode()
            )
            SceneVfxEngine.drawLightingAndVignette(
                canvas = canvas,
                width = w,
                height = h,
                config = scene.vfxConfig
            )
        }

        if (drawEditorOverlays && !isPlaying && (isCameraKeyMode || camera.trackedClipName != null)) {
            drawCameraViewfinderOverlay(canvas, w, h, camera, isCameraKeyMode, scratch)
        }

        canvas.restore()
    }

    private fun applyGesturePreview(
        base: EvaluatedTransform,
        clipId: String,
        gesturePreview: StageGesturePreview?
    ): EvaluatedTransform {
        if (gesturePreview == null || !gesturePreview.isActive) return base
        var out = base
        if (gesturePreview.draggedClipId == clipId && (gesturePreview.clipDeltaX != 0f || gesturePreview.clipDeltaY != 0f)) {
            out = out.copy(
                x = (out.x + gesturePreview.clipDeltaX).coerceIn(0.02f, 0.98f),
                y = (out.y + gesturePreview.clipDeltaY).coerceIn(0.02f, 0.98f)
            )
        }
        if (gesturePreview.ikClipId == clipId && gesturePreview.ikPoseOffsets != null) {
            out = out.copy(poseOffsets = gesturePreview.ikPoseOffsets)
        }
        return out
    }

    private fun drawCachedOnionSkin(
        canvas: Canvas,
        intW: Int,
        intH: Int,
        w: Float,
        h: Float,
        visibleLayers: List<AnimationLayer>,
        scene: AnimationScene,
        frame: Int,
        charactersById: Map<String, CharacterRigDefinition>,
        scratch: RenderScratch
    ) {
        val targetW = intW.coerceIn(16, 1280)
        val targetH = intH.coerceIn(16, 1280)
        val clipsId = System.identityHashCode(scene.clips)
        val layersId = System.identityHashCode(scene.layers)
        val charsId = System.identityHashCode(charactersById)

        val onionBmp = synchronized(onionCacheLock) {
            val cur = cachedOnion
            if (cur != null && !cur.bitmap.isRecycled &&
                cur.sceneId == scene.id &&
                cur.frame == frame &&
                cur.width == targetW &&
                cur.height == targetH &&
                cur.clipsIdentity == clipsId &&
                cur.layersIdentity == layersId &&
                cur.charsIdentity == charsId
            ) {
                cur.bitmap
            } else {
                val bmp = if (cur != null && !cur.bitmap.isRecycled && cur.width == targetW && cur.height == targetH) {
                    cur.bitmap.eraseColor(Color.TRANSPARENT)
                    cur.bitmap
                } else {
                    cur?.bitmap?.recycle()
                    runCatching { Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888) }.getOrNull()
                }
                if (bmp != null) {
                    val offCanvas = Canvas(bmp)
                    val tw = targetW.toFloat()
                    val th = targetH.toFloat()
                    // Multi-frame +/-2 onion skinning: frame-2 & frame-1 (coral/rose past), frame+1 & frame+2 (emerald/cyan future)
                    if (frame - 2 in 0 until scene.durationFrames) {
                        drawLayersAtFrame(
                            offCanvas, targetW, targetH, tw, th, visibleLayers, scene.clips, frame - 2,
                            charactersById, alphaMultiplier = 0.13f, tintOverlay = 0xFFFB7185.toInt(),
                            skipBackgroundLayers = true, gesturePreview = null, scratch = scratch
                        )
                    }
                    if (frame - 1 in 0 until scene.durationFrames) {
                        drawLayersAtFrame(
                            offCanvas, targetW, targetH, tw, th, visibleLayers, scene.clips, frame - 1,
                            charactersById, alphaMultiplier = 0.27f, tintOverlay = 0xFFF43F5E.toInt(),
                            skipBackgroundLayers = true, gesturePreview = null, scratch = scratch
                        )
                    }
                    if (frame + 1 in 0 until scene.durationFrames) {
                        drawLayersAtFrame(
                            offCanvas, targetW, targetH, tw, th, visibleLayers, scene.clips, frame + 1,
                            charactersById, alphaMultiplier = 0.23f, tintOverlay = 0xFF10B981.toInt(),
                            skipBackgroundLayers = true, gesturePreview = null, scratch = scratch
                        )
                    }
                    if (frame + 2 in 0 until scene.durationFrames) {
                        drawLayersAtFrame(
                            offCanvas, targetW, targetH, tw, th, visibleLayers, scene.clips, frame + 2,
                            charactersById, alphaMultiplier = 0.11f, tintOverlay = 0xFF38BDF8.toInt(),
                            skipBackgroundLayers = true, gesturePreview = null, scratch = scratch
                        )
                    }
                    cachedOnion = OnionCacheEntry(
                        sceneId = scene.id,
                        frame = frame,
                        width = targetW,
                        height = targetH,
                        clipsIdentity = clipsId,
                        layersIdentity = layersId,
                        charsIdentity = charsId,
                        bitmap = bmp
                    )
                }
                bmp
            }
        }

        if (onionBmp != null && !onionBmp.isRecycled) {
            val paint = scratch.bitmapPaint
            paint.alpha = 255
            paint.colorFilter = null
            if (targetW == intW && targetH == intH) {
                canvas.drawBitmap(onionBmp, 0f, 0f, paint)
            } else {
                scratch.rect.set(0f, 0f, w, h)
                canvas.drawBitmap(onionBmp, null, scratch.rect, paint)
            }
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
        gesturePreview: StageGesturePreview?,
        scratch: RenderScratch,
        fps: Int = 24
    ) {
        val baseScale = min(w, h) / 420f

        for (layerIdx in visibleLayers.indices) {
            val layer = visibleLayers[layerIdx]
            if (skipBackgroundLayers && layer.type == LayerType.BACKGROUND) continue
            if (layer.type == LayerType.AUDIO) continue

            // Reuse scratch buffer to avoid per-layer/per-frame List allocations during 24-60fps playback
            val activeLayerClips = scratch.layerClipBuf
            activeLayerClips.clear()
            var hasNonZeroDepth = false
            for (i in clips.indices) {
                val c = clips[i]
                if (c.layerId == layer.id && frame >= c.startFrame && frame < c.endFrame) {
                    activeLayerClips.add(c)
                    if (c.depthOrder != 0) hasNonZeroDepth = true
                }
            }
            if (activeLayerClips.isEmpty()) continue
            if (activeLayerClips.size > 1 && hasNonZeroDepth) {
                activeLayerClips.sortBy { it.depthOrder }
            }

            for (clipIdx in activeLayerClips.indices) {
                val clip = activeLayerClips[clipIdx]

                val transform = applyGesturePreview(
                    KeyframeEvaluator.evaluate(clip, frame),
                    clip.id,
                    gesturePreview
                )
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
                    drawStrokesClip(canvas, intW, intH, w, h, clip, effectiveAlpha, tintOverlay, scratch)
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
                            val isAtlasPro = rig.spriteAtlasRig != null || rig.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO
                            val customBmp = if (isAtlasPro) null else bitmapProvider(rig.customImagePath, null)
                            val atlasSlices = if (isAtlasPro) atlasSlicesProvider?.invoke(rig.customImagePath) else null
                            val customPropBmp = if (clip.propType == CharacterPropType.CUSTOM_PNG) {
                                bitmapProvider(clip.customPropImagePath, null)
                            } else null
                            val effectiveExpression = transform.expressionOverride ?: clip.facialExpression
                            val effectiveHandPose = transform.handPoseOverride ?: clip.handPose
                            val effectiveBlink = transform.blinkOverride ?: if (clip.forceBlink) true else null
                            val effectiveMouthOverride = transform.mouthShapeOverride ?: clip.mouthShapeOverride
                            val effectiveEyeOverride = transform.eyeShapeOverride ?: clip.eyeShapeOverride
                            val effectiveEyebrowOverride = transform.eyebrowShapeOverride ?: clip.eyebrowShapeOverride
                            val organicFace = FacialExpressionEngine.evaluateOrganicFace(
                                activePresetId = transform.facialPresetId,
                                previousPresetId = transform.previousFacialPresetId,
                                fallbackExpression = effectiveExpression,
                                framesSinceKey = transform.framesSinceFacialKey,
                                globalFrame = frame,
                                fps = fps,
                                blinkOverride = effectiveBlink,
                                characterSeed = clip.id.hashCode(),
                                mouthOverride = effectiveMouthOverride,
                                eyeOverride = effectiveEyeOverride,
                                eyebrowOverride = effectiveEyebrowOverride
                            )
                            val effectiveTint = tintOverlay ?: if (clip.blackShade > 0.01f) {
                                Color.argb((clip.blackShade.coerceIn(0f, 1f) * 215f).toInt(), 0, 0, 0)
                            } else null
                            val propSwayState = if (tintOverlay == null && clip.propSwayEnabled) {
                                PropSwayPhysicsEngine.evaluatePropSway(
                                    clip = clip,
                                    rig = rig,
                                    frame = frame,
                                    fps = fps,
                                    gesturePreview = gesturePreview
                                )
                            } else {
                                PropSwayState.ZERO
                            }
                            ProceduralRigRenderer.drawCharacter(
                                canvas = canvas,
                                rig = rig,
                                orientation = clip.orientation,
                                preset = clip.animationPresetType,
                                phase = phase,
                                rigScale = baseScale,
                                alpha = effectiveAlpha,
                                customBitmap = customBmp,
                                tintOverlayColor = effectiveTint,
                                poseOffsets = transform.poseOffsets,
                                facialExpression = effectiveExpression,
                                propType = clip.propType,
                                propAttachment = clip.propAttachment,
                                customPropBitmap = customPropBmp,
                                propSwayEnabled = clip.propSwayEnabled,
                                propScale = clip.propScale,
                                handPose = effectiveHandPose,
                                blinkOverride = effectiveBlink,
                                atlasSlices = atlasSlices,
                                organicFace = organicFace,
                                propSwayState = propSwayState
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
        stroke.pathEffect = null
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
        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        val textSizePx = clip.textSizeSp * s * 1.15f

        val paint = scratch.textPaint
        paint.color = Color.argb(alphaInt, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        paint.textSize = textSizePx

        val bubbleStyle = clip.textBubbleStyle
        if (bubbleStyle != TextBubbleStyle.NONE) {
            val textWidth = paint.measureText(text).coerceAtLeast(textSizePx * 2.2f)
            val fm = paint.fontMetrics
            val textHeight = (fm.descent - fm.ascent).coerceAtLeast(textSizePx)
            val padX = textSizePx * 0.72f
            val padY = textSizePx * 0.48f
            val halfW = textWidth * 0.5f + padX
            val halfH = textHeight * 0.5f + padY
            val centerOffsetY = (fm.ascent + fm.descent) * 0.5f

            val bubbleFillRaw = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(clip.textBubbleFillHex, 0xFFFFFFFF.toInt())
            val fill = scratch.fillPaint
            val stroke = scratch.strokePaint
            stroke.pathEffect = null

            when (bubbleStyle) {
                TextBubbleStyle.SPEECH -> {
                    fill.color = Color.argb(alphaInt, Color.red(bubbleFillRaw), Color.green(bubbleFillRaw), Color.blue(bubbleFillRaw))
                    stroke.color = Color.argb(alphaInt, 15, 23, 42)
                    stroke.strokeWidth = (3.5f * s).coerceAtLeast(2f)
                    val top = centerOffsetY - halfH
                    val bottom = centerOffsetY + halfH
                    scratch.rect.set(-halfW, top, halfW, bottom)
                    val corner = (22f * s).coerceAtLeast(10f)
                    canvas.drawRoundRect(scratch.rect, corner, corner, fill)
                    canvas.drawRoundRect(scratch.rect, corner, corner, stroke)

                    // Speech pointer tail
                    val tail = scratch.path
                    tail.reset()
                    tail.moveTo(-halfW * 0.28f, bottom - 2f * s)
                    tail.lineTo(-halfW * 0.44f, bottom + halfH * 0.62f)
                    tail.lineTo(-halfW * 0.04f, bottom - 2f * s)
                    tail.close()
                    canvas.drawPath(tail, fill)
                    canvas.drawLine(-halfW * 0.28f, bottom, -halfW * 0.44f, bottom + halfH * 0.62f, stroke)
                    canvas.drawLine(-halfW * 0.44f, bottom + halfH * 0.62f, -halfW * 0.04f, bottom, stroke)
                }
                TextBubbleStyle.THOUGHT -> {
                    fill.color = Color.argb(alphaInt, Color.red(bubbleFillRaw), Color.green(bubbleFillRaw), Color.blue(bubbleFillRaw))
                    stroke.color = Color.argb(alphaInt, 30, 41, 59)
                    stroke.strokeWidth = (3f * s).coerceAtLeast(1.8f)

                    // Cloud perimeter puffs (stroke behind, clean white fill in front)
                    val puffCount = 10
                    val rx = halfW * 1.06f
                    val ry = halfH * 1.12f
                    val puffR = (halfH * 0.56f).coerceAtLeast(14f * s)
                    for (i in 0 until puffCount) {
                        val ang = (i.toFloat() / puffCount) * (2f * PI.toFloat())
                        val px = cos(ang) * (rx * 0.78f)
                        val py = centerOffsetY + sin(ang) * (ry * 0.70f)
                        canvas.drawCircle(px, py, puffR, stroke)
                    }
                    for (i in 0 until puffCount) {
                        val ang = (i.toFloat() / puffCount) * (2f * PI.toFloat())
                        val px = cos(ang) * (rx * 0.78f)
                        val py = centerOffsetY + sin(ang) * (ry * 0.70f)
                        canvas.drawCircle(px, py, puffR - stroke.strokeWidth * 0.45f, fill)
                    }
                    scratch.rect.set(-halfW * 0.85f, centerOffsetY - halfH * 0.82f, halfW * 0.85f, centerOffsetY + halfH * 0.82f)
                    canvas.drawOval(scratch.rect, fill)

                    // Descending thought bubbles
                    val r1 = (9f * s).coerceAtLeast(5f)
                    val r2 = (6f * s).coerceAtLeast(3.5f)
                    val d1x = -halfW * 0.34f
                    val d1y = centerOffsetY + halfH + r1 * 1.5f
                    val d2x = -halfW * 0.48f
                    val d2y = d1y + r1 * 1.7f
                    canvas.drawCircle(d1x, d1y, r1, fill)
                    canvas.drawCircle(d1x, d1y, r1, stroke)
                    canvas.drawCircle(d2x, d2y, r2, fill)
                    canvas.drawCircle(d2x, d2y, r2, stroke)
                }
                TextBubbleStyle.SHOUT -> {
                    fill.color = Color.argb(alphaInt, Color.red(bubbleFillRaw), Color.green(bubbleFillRaw), Color.blue(bubbleFillRaw))
                    stroke.color = Color.argb(alphaInt, 15, 23, 42)
                    stroke.strokeWidth = (4f * s).coerceAtLeast(2.2f)
                    val spikes = 16
                    val path = scratch.path
                    path.reset()
                    for (i in 0 until spikes * 2) {
                        val isOuter = i % 2 == 0
                        val ang = (i.toFloat() / (spikes * 2)) * (2f * PI.toFloat())
                        val mult = if (isOuter) 1.32f else 0.94f
                        val px = cos(ang) * halfW * mult
                        val py = centerOffsetY + sin(ang) * halfH * (if (isOuter) 1.48f else 0.98f)
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, stroke)
                }
                TextBubbleStyle.SUBTITLE -> {
                    val subAlpha = (alphaInt * 0.78f).toInt().coerceIn(0, 220)
                    fill.color = Color.argb(subAlpha, 10, 14, 22)
                    scratch.rect.set(-halfW * 1.08f, centerOffsetY - halfH * 0.85f, halfW * 1.08f, centerOffsetY + halfH * 0.85f)
                    canvas.drawRoundRect(scratch.rect, 10f * s, 10f * s, fill)
                }
                TextBubbleStyle.NONE -> Unit
            }
        }

        if (bubbleStyle == TextBubbleStyle.NONE || bubbleStyle == TextBubbleStyle.SUBTITLE) {
            val shadowPaint = scratch.textShadowPaint
            shadowPaint.color = Color.argb((alphaInt * 0.65f).toInt(), 0, 0, 0)
            shadowPaint.textSize = textSizePx
            canvas.drawText(text, 2.5f * s, 2.5f * s, shadowPaint)
        }
        canvas.drawText(text, 0f, 0f, paint)
    }

    private fun drawStrokesClip(
        canvas: Canvas,
        intW: Int,
        intH: Int,
        w: Float,
        h: Float,
        clip: TimelineClip,
        alpha: Float,
        tintOverlay: Int?,
        scratch: RenderScratch
    ) {
        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        val minDim = min(w, h)
        val paint = scratch.strokePaint
        for (strokeData in clip.drawingStrokes) {
            if (strokeData.points.size < 2) continue
            val key = StrokeCacheKey(
                strokeId = strokeData.id,
                pointCount = strokeData.points.size,
                colorHex = strokeData.colorHex,
                brushStyle = strokeData.brushStyle,
                width = intW,
                height = intH
            )
            var cached = strokeCache.get(key)
            if (cached == null) {
                val builtPath = Path()
                val pts = strokeData.points
                for (idx in pts.indices) {
                    val pt = pts[idx]
                    val px = pt.x * w
                    val py = pt.y * h
                    if (idx == 0) builtPath.moveTo(px, py) else builtPath.lineTo(px, py)
                }
                val parsedCol = ProceduralRigRenderer.parseColorSafe(strokeData.colorHex, 0xFF38BDF8.toInt())
                cached = CachedStroke(builtPath, parsedCol)
                strokeCache.put(key, cached)
            }
            val col = tintOverlay ?: cached.colorInt
            val baseWidth = (strokeData.strokeWidthRatio * minDim).coerceAtLeast(3f)
            val r = Color.red(col)
            val g = Color.green(col)
            val b = Color.blue(col)

            when (strokeData.brushStyle) {
                BrushStyle.SOLID -> {
                    paint.pathEffect = null
                    paint.strokeWidth = baseWidth
                    paint.color = Color.argb(alphaInt, r, g, b)
                    canvas.drawPath(cached.path, paint)
                }
                BrushStyle.NEON_GLOW -> {
                    paint.pathEffect = null
                    // 3-layer luminous neon bloom (outer halo -> mid glow -> hot white-tinted core)
                    paint.strokeWidth = baseWidth * 3.2f
                    paint.color = Color.argb((alphaInt * 0.22f).toInt(), r, g, b)
                    canvas.drawPath(cached.path, paint)

                    paint.strokeWidth = baseWidth * 1.85f
                    paint.color = Color.argb((alphaInt * 0.52f).toInt(), r, g, b)
                    canvas.drawPath(cached.path, paint)

                    paint.strokeWidth = (baseWidth * 0.72f).coerceAtLeast(2f)
                    val cr = (r + (255 - r) * 0.72f).toInt().coerceIn(0, 255)
                    val cg = (g + (255 - g) * 0.72f).toInt().coerceIn(0, 255)
                    val cb = (b + (255 - b) * 0.72f).toInt().coerceIn(0, 255)
                    paint.color = Color.argb(alphaInt, cr, cg, cb)
                    canvas.drawPath(cached.path, paint)
                }
                BrushStyle.SKETCH_DASHED -> {
                    val dashLen = (baseWidth * 2.4f).coerceAtLeast(8f)
                    val gapLen = (baseWidth * 1.6f).coerceAtLeast(6f)
                    paint.pathEffect = DashPathEffect(floatArrayOf(dashLen, gapLen), 0f)
                    paint.strokeWidth = baseWidth
                    paint.color = Color.argb(alphaInt, r, g, b)
                    canvas.drawPath(cached.path, paint)
                    paint.pathEffect = null
                }
            }
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

        val (handles, anchors) = IkRigSolver.computeStageIkHandlesWithAnchors(
            clip = clip,
            rig = rig,
            transform = eval,
            pose = posed,
            stageWidth = w,
            stageHeight = h
        )

        val byId = handles.associateBy { it.id }
        val linkPaint = scratch.strokePaint
        linkPaint.pathEffect = null
        val fill = scratch.fillPaint
        val scaleRef = (min(w, h) / 720f).coerceIn(0.65f, 1.5f)

        fun drawTaperedBone(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, isHighlighted: Boolean) {
            val dx = x2 - x1
            val dy = y2 - y1
            val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len < 2f) return
            val nx = -dy / len
            val ny = dx / len
            val halfW = (if (isHighlighted) 5.2f else 3.6f) * scaleRef
            val mx = x1 + dx * 0.24f
            val my = y1 + dy * 0.24f

            val bonePath = scratch.path
            bonePath.reset()
            bonePath.moveTo(x1, y1)
            bonePath.lineTo(mx + nx * halfW, my + ny * halfW)
            bonePath.lineTo(x2, y2)
            bonePath.lineTo(mx - nx * halfW, my - ny * halfW)
            bonePath.close()

            fill.color = Color.argb(if (isHighlighted) 145 else 88, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(bonePath, fill)
            linkPaint.strokeWidth = (if (isHighlighted) 2.4f else 1.6f) * scaleRef
            linkPaint.color = Color.argb(if (isHighlighted) 245 else 185, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(bonePath, linkPaint)
        }

        val torso = byId[IkHandleId.TORSO]
        val head = byId[IkHandleId.HEAD]
        val lElbow = byId[IkHandleId.LEFT_ELBOW]
        val lHand = byId[IkHandleId.LEFT_HAND]
        val rElbow = byId[IkHandleId.RIGHT_ELBOW]
        val rHand = byId[IkHandleId.RIGHT_HAND]
        val lKnee = byId[IkHandleId.LEFT_KNEE]
        val lFoot = byId[IkHandleId.LEFT_FOOT]
        val rKnee = byId[IkHandleId.RIGHT_KNEE]
        val rFoot = byId[IkHandleId.RIGHT_FOOT]

        // Spine & clavicle/pelvis structural cage
        if (torso != null) {
            val tx = torso.nx * w
            val ty = torso.ny * h
            val nkx = anchors.neckNx * w
            val nky = anchors.neckNy * h
            drawTaperedBone(tx, ty, nkx, nky, 0xFF10B981.toInt(), activeIkHandle == IkHandleId.TORSO)
            if (head != null) {
                drawTaperedBone(nkx, nky, head.nx * w, head.ny * h, 0xFF38BDF8.toInt(), activeIkHandle == IkHandleId.HEAD)
            }

            // Shoulders & Arms
            val lsx = anchors.leftShoulderNx * w
            val lsy = anchors.leftShoulderNy * h
            val rsx = anchors.rightShoulderNx * w
            val rsy = anchors.rightShoulderNy * h
            linkPaint.strokeWidth = 2.0f * scaleRef
            linkPaint.color = 0x9994A3B8.toInt()
            canvas.drawLine(lsx, lsy, rsx, rsy, linkPaint)
            if (lElbow != null) {
                val activeLArm = activeIkHandle == IkHandleId.LEFT_ELBOW || activeIkHandle == IkHandleId.LEFT_HAND
                drawTaperedBone(lsx, lsy, lElbow.nx * w, lElbow.ny * h, 0xFFF59E0B.toInt(), activeLArm)
                if (lHand != null) {
                    drawTaperedBone(lElbow.nx * w, lElbow.ny * h, lHand.nx * w, lHand.ny * h, 0xFFFB923C.toInt(), activeIkHandle == IkHandleId.LEFT_HAND)
                }
            }
            if (rElbow != null) {
                val activeRArm = activeIkHandle == IkHandleId.RIGHT_ELBOW || activeIkHandle == IkHandleId.RIGHT_HAND
                drawTaperedBone(rsx, rsy, rElbow.nx * w, rElbow.ny * h, 0xFFEC4899.toInt(), activeRArm)
                if (rHand != null) {
                    drawTaperedBone(rElbow.nx * w, rElbow.ny * h, rHand.nx * w, rHand.ny * h, 0xFFF472B6.toInt(), activeIkHandle == IkHandleId.RIGHT_HAND)
                }
            }

            // Hips & Legs (Hip -> Knee -> Foot)
            val lhx = anchors.leftHipNx * w
            val lhy = anchors.leftHipNy * h
            val rhx = anchors.rightHipNx * w
            val rhy = anchors.rightHipNy * h
            linkPaint.strokeWidth = 2.0f * scaleRef
            linkPaint.color = 0x9994A3B8.toInt()
            canvas.drawLine(lhx, lhy, rhx, rhy, linkPaint)
            if (lKnee != null) {
                val activeLLeg = activeIkHandle == IkHandleId.LEFT_KNEE || activeIkHandle == IkHandleId.LEFT_FOOT
                drawTaperedBone(lhx, lhy, lKnee.nx * w, lKnee.ny * h, 0xFF8B5CF6.toInt(), activeLLeg)
                if (lFoot != null) {
                    drawTaperedBone(lKnee.nx * w, lKnee.ny * h, lFoot.nx * w, lFoot.ny * h, 0xFFA78BFA.toInt(), activeIkHandle == IkHandleId.LEFT_FOOT)
                }
            }
            if (rKnee != null) {
                val activeRLeg = activeIkHandle == IkHandleId.RIGHT_KNEE || activeIkHandle == IkHandleId.RIGHT_FOOT
                drawTaperedBone(rhx, rhy, rKnee.nx * w, rKnee.ny * h, 0xFF14B8A6.toInt(), activeRLeg)
                if (rFoot != null) {
                    drawTaperedBone(rKnee.nx * w, rKnee.ny * h, rFoot.nx * w, rFoot.ny * h, 0xFF34D399.toInt(), activeIkHandle == IkHandleId.RIGHT_FOOT)
                }
            }

            // Small pivot pins at shoulders & hips
            fill.color = 0xDDE2E8F0.toInt()
            val pinR = 3.2f * scaleRef
            canvas.drawCircle(lsx, lsy, pinR, fill)
            canvas.drawCircle(rsx, rsy, pinR, fill)
            canvas.drawCircle(lhx, lhy, pinR, fill)
            canvas.drawCircle(rhx, rhy, pinR, fill)
        }

        val ring = scratch.strokePaint
        ring.pathEffect = null
        val labelPaint = scratch.textPaint
        labelPaint.textSize = (min(w, h) * 0.022f).coerceIn(13f, 22f)

        for (hnd in handles) {
            val px = hnd.nx * w
            val py = hnd.ny * h
            val isActive = hnd.id == activeIkHandle
            val isEndEffector = hnd.id == IkHandleId.HEAD ||
                hnd.id == IkHandleId.LEFT_HAND ||
                hnd.id == IkHandleId.RIGHT_HAND ||
                hnd.id == IkHandleId.LEFT_FOOT ||
                hnd.id == IkHandleId.RIGHT_FOOT
            val baseR = (if (isEndEffector) 10.5f else 8.2f) * scaleRef
            val radius = if (isActive) baseR * 1.38f else baseR

            // Soft outer halo
            fill.color = Color.argb(if (isActive) 130 else 65, Color.red(hnd.color), Color.green(hnd.color), Color.blue(hnd.color))
            canvas.drawCircle(px, py, radius + 6.5f * scaleRef, fill)

            if (isEndEffector) {
                // Target ring style for IK end-effectors (Hands, Feet, Head)
                fill.color = 0xE60B1120.toInt()
                canvas.drawCircle(px, py, radius, fill)
                fill.color = hnd.color
                canvas.drawCircle(px, py, radius * 0.52f, fill)
                ring.strokeWidth = (if (isActive) 3.4f else 2.5f) * scaleRef
                ring.color = if (isActive) Color.WHITE else hnd.color
                canvas.drawCircle(px, py, radius, ring)
                if (isActive) {
                    ring.strokeWidth = 1.8f * scaleRef
                    ring.color = Color.argb(210, 255, 255, 255)
                    canvas.drawCircle(px, py, radius + 5f * scaleRef, ring)
                }
            } else {
                // Sleek rotated diamond for mid-joints (Elbows, Knees, Torso)
                val dPath = scratch.path
                dPath.reset()
                dPath.moveTo(px, py - radius)
                dPath.lineTo(px + radius, py)
                dPath.lineTo(px, py + radius)
                dPath.lineTo(px - radius, py)
                dPath.close()
                fill.color = hnd.color
                canvas.drawPath(dPath, fill)
                ring.strokeWidth = (if (isActive) 2.8f else 2.0f) * scaleRef
                ring.color = Color.WHITE
                canvas.drawPath(dPath, ring)
            }

            // Crisp pill label above active handle or primary end-effectors
            if (isActive || hnd.id == IkHandleId.HEAD || hnd.id == IkHandleId.LEFT_HAND || hnd.id == IkHandleId.RIGHT_HAND) {
                val labelText = hnd.id.label
                val tw = labelPaint.measureText(labelText)
                val pillPadX = 6f * scaleRef
                val pillH = labelPaint.textSize + 6f * scaleRef
                val pillBottom = py - radius - 6f * scaleRef
                val pillTop = pillBottom - pillH
                scratch.rect.set(px - tw * 0.5f - pillPadX, pillTop, px + tw * 0.5f + pillPadX, pillBottom)
                fill.color = if (isActive) Color.argb(235, Color.red(hnd.color), Color.green(hnd.color), Color.blue(hnd.color)) else 0xC80F172A.toInt()
                canvas.drawRoundRect(scratch.rect, pillH * 0.5f, pillH * 0.5f, fill)
                labelPaint.color = if (isActive) 0xFF0A0D12.toInt() else Color.WHITE
                canvas.drawText(labelText, px, pillBottom - 5f * scaleRef, labelPaint)
            }
        }
    }

    private fun drawCameraViewfinderOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        camera: EvaluatedCamera,
        showFullViewfinder: Boolean,
        scratch: RenderScratch
    ) {
        val stroke = scratch.strokePaint
        stroke.strokeWidth = 3f
        stroke.color = 0xFFFACC15.toInt()
        val inset = min(w, h) * 0.04f
        val corner = min(w, h) * 0.06f
        val cx = w * 0.5f
        val cy = h * 0.5f

        if (showFullViewfinder) {
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
            canvas.drawCircle(cx, cy, 10f, stroke)
            canvas.drawLine(cx - 18f, cy, cx + 18f, cy, stroke)
            canvas.drawLine(cx, cy - 18f, cx, cy + 18f, stroke)
        }

        // HUD status badge (Zoom/Coords + optional Focus Tracking subject)
        val label = scratch.textPaint
        label.textSize = (min(w, h) * 0.024f).coerceIn(16f, 24f)
        label.color = if (camera.trackedClipName != null) 0xFF38BDF8.toInt() else 0xFFFACC15.toInt()
        val zoomPct = (camera.zoom * 100).toInt()
        val hudText = if (camera.trackedClipName != null) {
            "TRACKING: ${camera.trackedClipName.take(18)} • ${zoomPct}%"
        } else {
            "CAM ${zoomPct}% • (${"%.2f".format(camera.x)}, ${"%.2f".format(camera.y)})"
        }
        canvas.drawText(hudText, cx, inset + label.textSize + 4f, label)
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
