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
        gesturePreview: StageGesturePreview? = null,
        inlineSvgAssets: Map<String, InlineSvgAssetDef> = emptyMap(),
        customAnimations: Map<String, CustomAnimationDef> = emptyMap(),
        customTransitions: Map<String, CustomTransitionDef> = emptyMap(),
        audioSyncTracks: List<AudioSyncMetadata> = emptyList(),
        directorStagePreview: Boolean = false
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

        val universalSnapshot = UniversalKeyframeEngine.evaluateAllTracks(scene.universalKeyframeTracks, frame)
        val bgGrading = UniversalKeyframeEngine.resolveBackgroundAndGrading(universalSnapshot)
        val (effectiveVfxConfig, effectiveWeatherLayers) = UniversalKeyframeEngine.resolveVfxAndWeather(
            snapshot = universalSnapshot,
            baseVfx = scene.vfxConfig,
            baseWeatherLayers = scene.weatherLayers
        )

        // Apply Keyframed Stage Camera Transform (Pan, Zoom, Rotation + optional Focus Tracking + live drag preview)
        val rawCamera = KeyframeEvaluator.evaluateCamera(
            scene = scene,
            globalFrame = frame,
            clipDeltaOverrideId = gesturePreview?.draggedClipId,
            clipDeltaX = gesturePreview?.clipDeltaX ?: 0f,
            clipDeltaY = gesturePreview?.clipDeltaY ?: 0f
        )
        val universalCamera = UniversalKeyframeEngine.resolveCameraOverrides(universalSnapshot, rawCamera)
        val camera = if (gesturePreview != null && (gesturePreview.cameraDeltaX != 0f || gesturePreview.cameraDeltaY != 0f)) {
            universalCamera.copy(
                x = (universalCamera.x - gesturePreview.cameraDeltaX).coerceIn(0.05f, 0.95f),
                y = (universalCamera.y - gesturePreview.cameraDeltaY).coerceIn(0.05f, 0.95f)
            )
        } else {
            universalCamera
        }

        val shake = if (!isChromaActive && effectiveVfxConfig.cameraShakeIntensity > 0.005f) {
            SceneVfxEngine.computeCameraShake(
                frame = frame,
                fps = fps,
                intensity = effectiveVfxConfig.cameraShakeIntensity,
                seed = scene.id.hashCode()
            )
        } else {
            CameraShakeOffset()
        }
        val isDirectorStage = directorStagePreview && drawEditorOverlays && !isPlaying
        val effectiveCamera = if (isDirectorStage) {
            EvaluatedCamera(x = 0.5f, y = 0.5f, zoom = CameraBoxEngine.DIRECTOR_STAGE_ZOOM, rotation = 0f)
        } else if (!shake.isZero) {
            camera.copy(
                x = (camera.x + shake.dxNorm).coerceIn(0.02f, 0.98f),
                y = (camera.y + shake.dyNorm).coerceIn(0.02f, 0.98f),
                rotation = camera.rotation + shake.rollDeg
            )
        } else {
            camera
        }

        if (isDirectorStage) {
            // Subtle dark off-screen margin surround around the wide stage canvas
            scratch.bgPaint.color = 0xFF0A0F18.toInt()
            canvas.drawRect(0f, 0f, w, h, scratch.bgPaint)
        }

        canvas.save()
        if (!effectiveCamera.isIdentity) {
            canvas.translate(w * 0.5f, h * 0.5f)
            canvas.scale(effectiveCamera.zoom, effectiveCamera.zoom)
            canvas.rotate(-effectiveCamera.rotation)
            canvas.translate(-effectiveCamera.x * w, -effectiveCamera.y * h)
        }

        if (isDirectorStage) {
            // Draw the true 0..1 scene canvas background inside stage bounds so off-screen margins are unmistakable
            scratch.bgPaint.color = baseBgColor
            canvas.drawRect(0f, 0f, w, h, scratch.bgPaint)
        }

        val sceneBgBitmap = if (isChromaActive) null else bitmapProvider(scene.backgroundImagePath, scene.backgroundBundledKey)
        if (sceneBgBitmap != null && !sceneBgBitmap.isRecycled) {
            val bgParallax = bgGrading.parallaxDepth
            val applyBgParallax = bgParallax != null && !effectiveCamera.isIdentity && !ParallaxCameraMath.isStagePlane(bgParallax)
            val bgSave = if (applyBgParallax) {
                val pCam = ParallaxCameraMath.computeLayerCamera(effectiveCamera, bgParallax!!)
                val sc = canvas.save()
                canvas.translate(effectiveCamera.x * w, effectiveCamera.y * h)
                canvas.rotate(effectiveCamera.rotation)
                val invZoom = 1f / effectiveCamera.zoom.coerceAtLeast(0.05f)
                canvas.scale(invZoom, invZoom)
                canvas.translate(-w * 0.5f, -h * 0.5f)
                canvas.translate(w * 0.5f, h * 0.5f)
                canvas.scale(pCam.zoom, pCam.zoom)
                canvas.rotate(-pCam.rotation)
                canvas.translate(-pCam.x * w, -pCam.y * h)
                sc
            } else -1

            drawBackgroundBitmapWithGrading(canvas, sceneBgBitmap, width, height, 255, bgGrading, scratch)

            if (bgSave >= 0) {
                canvas.restoreToCount(bgSave)
            }
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
            fps = fps,
            inlineSvgAssets = inlineSvgAssets,
            customAnimations = customAnimations,
            audioSyncTracks = audioSyncTracks,
            activeCamera = effectiveCamera,
            universalSnapshot = universalSnapshot,
            bgGrading = bgGrading
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

        if (isDirectorStage) {
            // Crisp hairline outline around the 0..1 main stage area inside Director Mode
            scratch.strokePaint.color = 0x5594A3B8
            scratch.strokePaint.strokeWidth = 1.6f / CameraBoxEngine.DIRECTOR_STAGE_ZOOM
            scratch.strokePaint.pathEffect = null
            canvas.drawRect(0f, 0f, w, h, scratch.strokePaint)
        }

        canvas.restore() // restore camera transform

        // 6. Scene VFX & Atmosphere (particles, weather layers, cinematic lighting moods, universal color grading, and vignette)
        if (!isChromaActive) {
            if (effectiveVfxConfig.hasActiveVfx) {
                SceneVfxEngine.drawSceneParticles(
                    canvas = canvas,
                    width = w,
                    height = h,
                    frame = frame,
                    fps = fps,
                    config = effectiveVfxConfig,
                    seed = scene.id.hashCode()
                )
                if (effectiveVfxConfig.weatherConfig.isActive) {
                    WeatherParticleEngine.drawWeather(
                        canvas = canvas,
                        width = w,
                        height = h,
                        frameIndex = frame,
                        fps = fps,
                        config = effectiveVfxConfig.weatherConfig
                    )
                }
                SceneVfxEngine.drawLightingAndVignette(
                    canvas = canvas,
                    width = w,
                    height = h,
                    config = effectiveVfxConfig
                )
            }
            for (wl in effectiveWeatherLayers) {
                if (wl.isActive) {
                    WeatherParticleEngine.drawWeather(
                        canvas = canvas,
                        width = w,
                        height = h,
                        frameIndex = frame,
                        fps = fps,
                        config = wl
                    )
                }
            }
            if (bgGrading.hasColorGrading) {
                drawUniversalColorGradingAndAtmosphere(canvas, w, h, bgGrading, scratch)
            }
            // 6B. Optional, Opt-In Animated Comic Speech Bubbles & Cinema Subtitles (Odia / Hindi / English)
            if (scene.dialogueOverlays.isNotEmpty()) {
                DialogueOverlayRenderer.drawDialogueOverlays(
                    canvas = canvas,
                    width = w,
                    height = h,
                    scene = scene,
                    frame = frame,
                    fps = fps,
                    effectiveCamera = effectiveCamera,
                    gesturePreview = gesturePreview
                )
            }
        }

        // 7. Universal Declarative Custom Scene Transitions (in / out wipe, iris, curtain, custom SVG path, flash)
        if (!isChromaActive && customTransitions.isNotEmpty()) {
            val transInDef = scene.customTransitionInId?.let { customTransitions[it] }
            if (transInDef != null) {
                val transFrames = (transInDef.durationSec * fps.coerceAtLeast(1)).toInt().coerceAtLeast(2)
                val tOverrides = UniversalKeyframeEngine.resolveTransitionOverrides(universalSnapshot, transInDef.id, isEntering = true)
                if (frame < transFrames || tOverrides.progressOverride != null) {
                    val rawP = tOverrides.progressOverride ?: (frame.toFloat() / transFrames.toFloat()).coerceIn(0f, 1f)
                    if (rawP < 0.999f || (tOverrides.flashAlphaOverride ?: 0f) > 0.01f) {
                        UniversalCustomTransitionEngine.applySingleSceneMaskAndFlash(
                            canvas = canvas,
                            width = width,
                            height = height,
                            def = transInDef,
                            rawProgress = rawP,
                            isEntering = true,
                            angleOverrideDeg = tOverrides.wipeAngleOverrideDeg,
                            flashAlphaOverride = tOverrides.flashAlphaOverride
                        )
                    }
                }
            }
            val transOutDef = scene.customTransitionOutId?.let { customTransitions[it] }
            if (transOutDef != null) {
                val transFrames = (transOutDef.durationSec * fps.coerceAtLeast(1)).toInt().coerceAtLeast(2)
                val outStart = (scene.durationFrames - transFrames).coerceAtLeast(0)
                val tOverrides = UniversalKeyframeEngine.resolveTransitionOverrides(universalSnapshot, transOutDef.id, isEntering = false)
                if ((frame >= outStart && scene.durationFrames > 0) || tOverrides.progressOverride != null) {
                    val rawP = tOverrides.progressOverride ?: ((frame - outStart).toFloat() / transFrames.toFloat()).coerceIn(0f, 1f)
                    if (rawP > 0.001f || (tOverrides.flashAlphaOverride ?: 0f) > 0.01f) {
                        UniversalCustomTransitionEngine.applySingleSceneMaskAndFlash(
                            canvas = canvas,
                            width = width,
                            height = height,
                            def = transOutDef,
                            rawProgress = rawP,
                            isEntering = false,
                            angleOverrideDeg = tOverrides.wipeAngleOverrideDeg,
                            flashAlphaOverride = tOverrides.flashAlphaOverride
                        )
                    }
                }
            }
        }

        if (drawEditorOverlays && !isPlaying && !isDirectorStage && (isCameraKeyMode || camera.trackedClipName != null)) {
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
        fps: Int = 24,
        inlineSvgAssets: Map<String, InlineSvgAssetDef> = emptyMap(),
        customAnimations: Map<String, CustomAnimationDef> = emptyMap(),
        audioSyncTracks: List<AudioSyncMetadata> = emptyList(),
        activeCamera: EvaluatedCamera? = null,
        universalSnapshot: UniversalFrameSnapshot = UniversalFrameSnapshot.EMPTY,
        bgGrading: EvaluatedBackgroundGrading = EvaluatedBackgroundGrading()
    ) {
        val baseScale = min(w, h) / 420f
        // Precompute parent layer active transform if any layer uses parentLayerId
        val hasParenting = visibleLayers.any { !it.parentLayerId.isNullOrBlank() }
        val layerAnchorById: Map<String, EvaluatedTransform> = if (hasParenting) {
            val map = HashMap<String, EvaluatedTransform>()
            for (l in visibleLayers) {
                val firstClip = clips.firstOrNull { it.layerId == l.id && frame in it.startFrame until it.endFrame }
                if (firstClip != null) {
                    map[l.id] = applyGesturePreview(KeyframeEvaluator.evaluate(firstClip, frame), firstClip.id, gesturePreview)
                }
            }
            map
        } else emptyMap()

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
                    val z = UniversalKeyframeEngine.resolveClipDepthOrder(universalSnapshot, c)
                    if (z != 0) hasNonZeroDepth = true
                }
            }
            if (activeLayerClips.isEmpty()) continue
            if (activeLayerClips.size > 1 && hasNonZeroDepth) {
                activeLayerClips.sortBy { UniversalKeyframeEngine.resolveClipDepthOrder(universalSnapshot, it) }
            }

            // Optional 2.5D Multi-Plane Parallax transform (supports dynamic universal keyframed parallaxDepth 0.1..1.5)
            val effectiveParallaxDepth = UniversalKeyframeEngine.resolveLayerParallaxDepth(universalSnapshot, layer)
            val applyParallax = activeCamera != null &&
                !activeCamera.isIdentity &&
                !ParallaxCameraMath.isStagePlane(effectiveParallaxDepth)
            val parallaxSaveCount = if (applyParallax) {
                val cam = activeCamera!!
                val pCam = ParallaxCameraMath.computeLayerCamera(cam, effectiveParallaxDepth)
                val sc = canvas.save()
                // Invert base stage camera transform
                canvas.translate(cam.x * w, cam.y * h)
                canvas.rotate(cam.rotation)
                val invZoom = 1f / cam.zoom.coerceAtLeast(0.05f)
                canvas.scale(invZoom, invZoom)
                canvas.translate(-w * 0.5f, -h * 0.5f)
                // Apply depth-scaled parallax camera transform
                canvas.translate(w * 0.5f, h * 0.5f)
                canvas.scale(pCam.zoom, pCam.zoom)
                canvas.rotate(-pCam.rotation)
                canvas.translate(-pCam.x * w, -pCam.y * h)
                sc
            } else -1

            // Optional Layer Blend Mode compositing
            val blendSaveCount = if (layer.blendMode != LayerBlendMode.NORMAL) {
                val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = toPorterDuffXfermode(layer.blendMode)
                }
                canvas.saveLayer(0f, 0f, w, h, blendPaint)
            } else -1

            // Optional Parent Layer transform inheritance
            val parentAnchor = layer.parentLayerId?.let { layerAnchorById[it] }

            for (clipIdx in activeLayerClips.indices) {
                val clip = activeLayerClips[clipIdx]

                val baseTransform = applyGesturePreview(
                    KeyframeEvaluator.evaluate(clip, frame),
                    clip.id,
                    gesturePreview
                )
                val localFrame = (frame - clip.startFrame).coerceAtLeast(0)
                val customAnimDef = clip.customAnimationId?.let { customAnimations[it] }
                val baseCustomNodeTracks = if (customAnimDef != null) {
                    UniversalCustomAnimationEngine.evaluateAllTracks(
                        def = customAnimDef,
                        localFrame = localFrame,
                        fps = fps,
                        speedMultiplier = clip.speedMultiplier
                    )
                } else emptyMap()
                val inlineSvgDef = clip.inlineSvgAssetId?.let { inlineSvgAssets[it] }
                val knownSvgNodes = inlineSvgDef?.subPartPivots?.map { it.nodeId }.orEmpty()
                val customNodeTracks = if (!universalSnapshot.isEmpty) {
                    UniversalKeyframeEngine.resolveInlineSvgNodeTransforms(
                        snapshot = universalSnapshot,
                        clipId = clip.id,
                        inlineSvgAssetId = clip.inlineSvgAssetId,
                        knownNodeIds = knownSvgNodes,
                        baseNodeTransforms = baseCustomNodeTracks
                    )
                } else {
                    baseCustomNodeTracks
                }
                val rootCustom = UniversalCustomAnimationEngine.rootTransformOrNull(customNodeTracks)

                val preUniversalTransform = if (rootCustom != null) {
                    baseTransform.copy(
                        x = baseTransform.x + rootCustom.dx,
                        y = baseTransform.y + rootCustom.dy,
                        rotation = baseTransform.rotation + rootCustom.rotation,
                        scaleX = baseTransform.scaleX * rootCustom.scaleX,
                        scaleY = baseTransform.scaleY * rootCustom.scaleY,
                        opacity = (baseTransform.opacity * rootCustom.opacity).coerceIn(0f, 1f)
                    )
                } else baseTransform

                val (transform, effFlipX, effFlipY) = UniversalKeyframeEngine.resolveGenericClipTransform(
                    snapshot = universalSnapshot,
                    clip = clip,
                    base = preUniversalTransform
                )

                val effectiveAlpha = (transform.opacity * alphaMultiplier).coerceIn(0f, 1f)
                if (effectiveAlpha <= 0.01f) continue

                if (layer.type == LayerType.BACKGROUND) {
                    val bgColorFilter = if (tintOverlay == null) ClipVisualFxEngine.createColorAdjustFilter(clip.colorAdjust) else null
                    val bgSaveCount = if (bgColorFilter != null) {
                        val layerPaint = Paint().apply { colorFilter = bgColorFilter }
                        canvas.saveLayer(0f, 0f, w, h, layerPaint)
                    } else -1
                    val bgMaskType = clip.effectiveMaskType
                    if (bgMaskType != ClipMaskType.NONE) {
                        canvas.save()
                        canvas.translate(w * 0.5f, h * 0.5f)
                        ClipVisualFxEngine.applyClipMask(
                            canvas = canvas,
                            type = bgMaskType,
                            config = clip.maskConfig,
                            legacyRadius = clip.maskRadius,
                            refW = w,
                            refH = h,
                            scratchPath = scratch.path
                        )
                        canvas.translate(-w * 0.5f, -h * 0.5f)
                    }
                    if (inlineSvgDef != null) {
                        canvas.save()
                        canvas.translate(w * 0.5f, h * 0.5f)
                        if (bgGrading.hasBackgroundTransform) {
                            canvas.rotate(bgGrading.bgRotation)
                            canvas.scale(bgGrading.bgScale, bgGrading.bgScale)
                            val ox = ((bgGrading.bgScrollX % 1f) + 1f) % 1f
                            val oy = ((bgGrading.bgScrollY % 1f) + 1f) % 1f
                            canvas.translate(-ox * w, -oy * h)
                        }
                        InlineSvgAssetSpawner.drawInlineSvgAsset(
                            canvas = canvas,
                            def = inlineSvgDef,
                            targetMaxDim = kotlin.math.max(w, h),
                            alpha = effectiveAlpha,
                            tintOverlay = tintOverlay,
                            nodeTransforms = customNodeTracks,
                            paint = scratch.bitmapPaint
                        )
                        canvas.restore()
                    } else {
                        val bgBmp = bitmapProvider(clip.imagePath, clip.bundledBgKey)
                        if (bgBmp != null && !bgBmp.isRecycled) {
                            drawBackgroundBitmapWithGrading(
                                canvas = canvas,
                                bitmap = bgBmp,
                                dstWInt = intW,
                                dstHInt = intH,
                                alphaInt = (effectiveAlpha * 255).toInt(),
                                grading = bgGrading,
                                scratch = scratch
                            )
                        }
                    }
                    if (bgMaskType != ClipMaskType.NONE) {
                        canvas.restore()
                    }
                    if (bgSaveCount >= 0) {
                        canvas.restoreToCount(bgSaveCount)
                    }
                    continue
                }

                if (layer.type == LayerType.DRAWING) {
                    val drawColorFilter = if (tintOverlay == null) ClipVisualFxEngine.createColorAdjustFilter(clip.colorAdjust) else null
                    val drawSaveCount = if (drawColorFilter != null) {
                        val layerPaint = Paint().apply { colorFilter = drawColorFilter }
                        canvas.saveLayer(0f, 0f, w, h, layerPaint)
                    } else -1
                    drawStrokesClip(canvas, intW, intH, w, h, clip, effectiveAlpha, tintOverlay, scratch)
                    if (drawSaveCount >= 0) {
                        canvas.restoreToCount(drawSaveCount)
                    }
                    continue
                }

                canvas.save()
                if (parentAnchor != null) {
                    canvas.translate((parentAnchor.x - 0.5f) * w, (parentAnchor.y - 0.5f) * h)
                }
                canvas.translate(transform.x * w, transform.y * h)
                canvas.rotate(transform.rotation + (parentAnchor?.rotation ?: 0f))
                val sx = transform.scaleX * (parentAnchor?.scaleX ?: 1f) * if (effFlipX) -1f else 1f
                val sy = transform.scaleY * (parentAnchor?.scaleY ?: 1f) * if (effFlipY) -1f else 1f
                canvas.scale(sx, sy)

                // Resolve Clip Masking (ClipMaskConfig + legacy maskType + universal mask overrides)
                val universalMask = UniversalKeyframeEngine.resolveClipMask(universalSnapshot, clip)
                val effMaskType = clip.effectiveMaskType
                val effMaskRadius = universalMask.radiusOverride ?: clip.maskRadius
                val resolvedMaskConfig = if (universalMask.hasCustomRectMask) {
                    clip.maskConfig.copy(
                        type = if (effMaskType != ClipMaskType.NONE) effMaskType else ClipMaskType.ROUNDED_RECT,
                        centerX = clip.maskConfig.centerX + universalMask.maskX * 0.5f,
                        centerY = clip.maskConfig.centerY + universalMask.maskY * 0.5f,
                        width = universalMask.maskW.coerceIn(0.1f, 1.5f),
                        height = universalMask.maskH.coerceIn(0.1f, 1.5f),
                        feather = kotlin.math.max(clip.maskConfig.feather, universalMask.feather)
                    )
                } else {
                    clip.maskConfig
                }
                val finalMaskType = resolvedMaskConfig.resolvedType(effMaskType)
                val clipRefW = min(w, h) * 0.52f
                val clipRefH = min(w, h) * 0.52f

                // Dynamic Ground / Drop Shadow (drawn cleanly behind the clip before mask & colorAdjust layers)
                if (clip.shadowConfig.isActive && tintOverlay == null) {
                    val isCharLayer = layer.type == LayerType.CHARACTER
                    val liveBodyBobY = if (isCharLayer) {
                        val cycleF = clip.animationPresetType.defaultDurationFrames.coerceAtLeast(8).toFloat()
                        val rawP = (localFrame * clip.speedMultiplier) / cycleF
                        val ph = if (clip.loopAnimation) rawP % 1f else rawP.coerceIn(0f, 0.999f)
                        ProceduralRigRenderer.computePose(clip.animationPresetType, ph).bodyBobY
                    } else 0f
                    ClipVisualFxEngine.drawClipShadow(
                        canvas = canvas,
                        config = clip.shadowConfig,
                        isCharacter = isCharLayer,
                        baseScale = baseScale,
                        bodyBobY = liveBodyBobY,
                        clipWidthPx = clipRefW,
                        clipHeightPx = clipRefH,
                        clipOpacity = effectiveAlpha
                    )
                }

                if (finalMaskType != ClipMaskType.NONE) {
                    ClipVisualFxEngine.applyClipMask(
                        canvas = canvas,
                        type = finalMaskType,
                        config = resolvedMaskConfig,
                        legacyRadius = effMaskRadius,
                        refW = clipRefW,
                        refH = clipRefH,
                        scratchPath = scratch.path
                    )
                }

                // Per-Clip Colour Adjust via fast ColorMatrixColorFilter
                val clipColorFilter = if (tintOverlay == null) {
                    ClipVisualFxEngine.createColorAdjustFilter(clip.colorAdjust)
                } else null
                val clipColorSaveCount = if (clipColorFilter != null) {
                    val colorPaint = Paint().apply { colorFilter = clipColorFilter }
                    canvas.saveLayer(null, colorPaint)
                } else -1

                when (layer.type) {
                    LayerType.CHARACTER -> {
                        val rig = clip.characterId?.let { charactersById[it] }
                        if (rig != null) {
                            val multiTrack = KeyframeEvaluator.evaluateSimultaneousCharacterTracks(
                                clip = clip,
                                eval = transform,
                                globalFrame = frame,
                                fps = fps,
                                audioSyncTracks = audioSyncTracks
                            )
                            val charState = UniversalKeyframeEngine.resolveCharacterOverrides(
                                snapshot = universalSnapshot,
                                clip = clip,
                                baseTransform = transform,
                                baseMultiTrack = multiTrack
                            )
                            val effPreset = charState.animationPreset
                            val cycleFrames = effPreset.defaultDurationFrames.coerceAtLeast(8).toFloat()
                            val rawPhase = (localFrame * clip.speedMultiplier) / cycleFrames
                            val phase = if (clip.loopAnimation) rawPhase % 1f else rawPhase.coerceIn(0f, 0.999f)
                            val isAtlasPro = rig.spriteAtlasRig != null || rig.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO
                            val customBmp = if (isAtlasPro) null else bitmapProvider(rig.customImagePath, null)
                            val atlasSlices = if (isAtlasPro) atlasSlicesProvider?.invoke(rig.customImagePath) else null
                            val customPropBmp = if (clip.propType == CharacterPropType.CUSTOM_PNG) {
                                bitmapProvider(clip.customPropImagePath, null)
                            } else null

                            val baseOrganicFace = FacialExpressionEngine.evaluateOrganicFace(
                                activePresetId = charState.facialPresetId,
                                previousPresetId = multiTrack.previousFacialPresetId,
                                fallbackExpression = charState.expression,
                                framesSinceKey = multiTrack.framesSinceFacialKey,
                                globalFrame = frame,
                                fps = fps,
                                blinkOverride = charState.blinkOverride,
                                characterSeed = clip.id.hashCode(),
                                mouthOverride = charState.mouthOverride,
                                eyeOverride = charState.eyeOverride,
                                eyebrowOverride = charState.eyebrowOverride
                            )
                            val organicFace = if (charState.gazeTargetX != null || charState.gazeTargetY != null) {
                                baseOrganicFace.copy(
                                    eyeLookX = charState.gazeTargetX ?: baseOrganicFace.eyeLookX,
                                    eyeLookY = charState.gazeTargetY ?: baseOrganicFace.eyeLookY
                                )
                            } else {
                                baseOrganicFace
                            }

                            val effectiveTint = tintOverlay
                                ?: charState.tintArgb
                                ?: if (clip.blackShade > 0.01f) {
                                    Color.argb((clip.blackShade.coerceIn(0f, 1f) * 215f).toInt(), 0, 0, 0)
                                } else null

                            val swayClip = if (
                                charState.propSwayConfig.stiffness != clip.propSwayStiffness ||
                                charState.propSwayConfig.damping != clip.propSwayDamping ||
                                charState.propSwayConfig.maxAngleDeg != clip.propSwayMaxAngleDeg ||
                                charState.propSwayStrength != clip.propSwayStrength ||
                                charState.propSwayEnabled != clip.propSwayEnabled
                            ) {
                                clip.copy(
                                    propSwayEnabled = charState.propSwayEnabled,
                                    propSwayStiffness = charState.propSwayConfig.stiffness,
                                    propSwayDamping = charState.propSwayConfig.damping,
                                    propSwayMaxAngleDeg = charState.propSwayConfig.maxAngleDeg,
                                    propSwayStrength = charState.propSwayStrength
                                )
                            } else {
                                clip
                            }

                            val propSwayState = if (tintOverlay == null && swayClip.propSwayEnabled) {
                                PropSwayPhysicsEngine.evaluatePropSway(
                                    clip = swayClip,
                                    rig = rig,
                                    frame = frame,
                                    fps = fps,
                                    gesturePreview = gesturePreview
                                )
                            } else {
                                PropSwayState.ZERO
                            }
                            val blendedPoseOffsets = if (customNodeTracks.isNotEmpty()) {
                                UniversalCustomAnimationEngine.blendIntoPoseOffsets(
                                    charState.poseOffsets,
                                    customNodeTracks
                                )
                            } else {
                                charState.poseOffsets
                            }
                            ProceduralRigRenderer.drawCharacter(
                                canvas = canvas,
                                rig = rig,
                                orientation = charState.orientation,
                                preset = effPreset,
                                phase = phase,
                                rigScale = baseScale,
                                alpha = effectiveAlpha,
                                customBitmap = customBmp,
                                tintOverlayColor = effectiveTint,
                                poseOffsets = blendedPoseOffsets,
                                facialExpression = charState.expression,
                                propType = clip.propType,
                                propAttachment = clip.propAttachment,
                                customPropBitmap = customPropBmp,
                                propSwayEnabled = swayClip.propSwayEnabled,
                                propScale = clip.propScale,
                                handPose = charState.handPose,
                                blinkOverride = charState.blinkOverride,
                                atlasSlices = atlasSlices,
                                organicFace = organicFace,
                                propSwayState = propSwayState,
                                tearsConfig = clip.tearsConfig,
                                globalFrame = frame,
                                fps = fps,
                                clipSeed = clip.id.hashCode()
                            )
                        }
                    }
                    LayerType.IMAGE -> {
                        if (inlineSvgDef != null) {
                            val maxDim = min(w, h) * 0.44f
                            InlineSvgAssetSpawner.drawInlineSvgAsset(
                                canvas = canvas,
                                def = inlineSvgDef,
                                targetMaxDim = maxDim,
                                alpha = effectiveAlpha,
                                tintOverlay = tintOverlay,
                                nodeTransforms = customNodeTracks,
                                paint = scratch.bitmapPaint
                            )
                        } else {
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
                    }
                    LayerType.SHAPE -> {
                        drawShapeClip(canvas, clip, baseScale, effectiveAlpha, tintOverlay, scratch)
                    }
                    LayerType.TEXT -> {
                        drawTextClip(canvas, clip, baseScale, effectiveAlpha, tintOverlay, scratch)
                    }
                    else -> Unit
                }
                if (clipColorSaveCount >= 0) {
                    canvas.restoreToCount(clipColorSaveCount)
                }
                if (finalMaskType != ClipMaskType.NONE && resolvedMaskConfig.feather > 0.02f && tintOverlay == null) {
                    ClipVisualFxEngine.drawMaskFeatherEdge(
                        canvas = canvas,
                        type = finalMaskType,
                        config = resolvedMaskConfig,
                        legacyRadius = effMaskRadius,
                        refW = clipRefW,
                        refH = clipRefH,
                        scratchPath = scratch.path,
                        bgColor = 0xFF162033.toInt()
                    )
                }
                canvas.restore()
            }

            if (blendSaveCount >= 0) {
                canvas.restoreToCount(blendSaveCount)
            }
            if (parallaxSaveCount >= 0) {
                canvas.restoreToCount(parallaxSaveCount)
            }
        }
    }

    private fun toPorterDuffXfermode(mode: LayerBlendMode): PorterDuffXfermode? = when (mode) {
        LayerBlendMode.NORMAL -> null
        LayerBlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        LayerBlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        LayerBlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        LayerBlendMode.ADD -> PorterDuffXfermode(PorterDuff.Mode.ADD)
        LayerBlendMode.DARKEN -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
        LayerBlendMode.LIGHTEN -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
    }

    private fun buildClipMaskPath(type: ClipMaskType, radius: Float, outPath: Path): Path? {
        if (type == ClipMaskType.NONE) return null
        ClipVisualFxEngine.buildClipMaskPath(
            type = type,
            config = ClipMaskConfig(),
            legacyRadius = 1f,
            refW = radius * 2f,
            refH = radius * 2f,
            outPath = outPath
        )
        return outPath
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
     * Renders a background bitmap with optional keyframed seamless scrolling (`bgScrollX`/`bgScrollY`),
     * `bgScale`, `bgRotation`, and hardware-canvas-compatible rack-focus depth blur (`bgDepthBlur`).
     * Fast-paths directly to [drawBitmapCenterCropFast] when no universal background transforms are active.
     */
    private fun drawBackgroundBitmapWithGrading(
        canvas: Canvas,
        bitmap: Bitmap,
        dstWInt: Int,
        dstHInt: Int,
        alphaInt: Int,
        grading: EvaluatedBackgroundGrading,
        scratch: RenderScratch
    ) {
        if (!grading.hasBackgroundTransform) {
            drawBitmapCenterCropFast(canvas, bitmap, dstWInt, dstHInt, alphaInt, scratch)
            return
        }
        val w = dstWInt.toFloat()
        val h = dstHInt.toFloat()
        canvas.save()
        if (abs(grading.bgScale - 1f) > 0.0005f || abs(grading.bgRotation) > 0.01f) {
            canvas.translate(w * 0.5f, h * 0.5f)
            canvas.rotate(grading.bgRotation)
            canvas.scale(grading.bgScale, grading.bgScale)
            canvas.translate(-w * 0.5f, -h * 0.5f)
        }

        val hasScrollX = abs(grading.bgScrollX) > 0.0001f
        val hasScrollY = abs(grading.bgScrollY) > 0.0001f
        val ox = if (hasScrollX) (((grading.bgScrollX % 1f) + 1f) % 1f) * w else 0f
        val oy = if (hasScrollY) (((grading.bgScrollY % 1f) + 1f) % 1f) * h else 0f

        fun drawTiledPass(passAlpha: Int, blurDx: Float = 0f, blurDy: Float = 0f) {
            val xOffsets = if (hasScrollX) floatArrayOf(-ox + blurDx, w - ox + blurDx) else floatArrayOf(blurDx)
            val yOffsets = if (hasScrollY) floatArrayOf(-oy + blurDy, h - oy + blurDy) else floatArrayOf(blurDy)
            for (tx in xOffsets) {
                for (ty in yOffsets) {
                    canvas.save()
                    canvas.translate(tx, ty)
                    drawBitmapCenterCropFast(canvas, bitmap, dstWInt, dstHInt, passAlpha, scratch)
                    canvas.restore()
                }
            }
        }

        drawTiledPass(alphaInt)

        // Hardware-canvas-safe rack-focus soften passes when bgDepthBlur > 0
        if (grading.bgDepthBlur > 0.02f) {
            val radius = (min(w, h) * 0.018f * grading.bgDepthBlur).coerceIn(1.5f, 22f)
            val tapAlpha = (alphaInt * (0.28f * grading.bgDepthBlur)).toInt().coerceIn(8, 120)
            drawTiledPass(tapAlpha, -radius, 0f)
            drawTiledPass(tapAlpha, radius, 0f)
            drawTiledPass(tapAlpha, 0f, -radius)
            drawTiledPass(tapAlpha, 0f, radius)
        }
        canvas.restore()
    }

    /**
     * Applies keyframed universal colour grading (`exposure`, `contrast`, `saturation`, `temperature`),
     * `dayNightBlend`, `ambientLightColor`, and radial `vignette` deterministically across both
     * live preview and MP4/GIF/PNG export canvases.
     */
    private fun drawUniversalColorGradingAndAtmosphere(
        canvas: Canvas,
        w: Float,
        h: Float,
        grading: EvaluatedBackgroundGrading,
        scratch: RenderScratch
    ) {
        val fill = scratch.fillPaint
        val prevShader = fill.shader
        val prevFilter = fill.colorFilter
        fill.shader = null
        fill.colorFilter = null

        try {
            // 1. Day-to-Night atmosphere blend (deep cinematic indigo night veil)
            if (grading.dayNightBlend > 0.005f) {
                val nightAlpha = (grading.dayNightBlend * 145f).toInt().coerceIn(0, 170)
                fill.color = Color.argb(nightAlpha, 11, 22, 54)
                canvas.drawRect(0f, 0f, w, h, fill)
            }

            // 2. Ambient Light Color tint
            val ambColor = grading.ambientLightColorArgb
            if (ambColor != null && grading.ambientLightAlpha > 0.005f) {
                val aInt = (grading.ambientLightAlpha * 255f).toInt().coerceIn(0, 215)
                fill.color = Color.argb(aInt, Color.red(ambColor), Color.green(ambColor), Color.blue(ambColor))
                canvas.drawRect(0f, 0f, w, h, fill)
            }

            // 3. Temperature shift (-1 cool cyan-blue .. +1 warm golden amber)
            if (abs(grading.temperature) > 0.005f) {
                val tMag = (abs(grading.temperature) * 68f).toInt().coerceIn(0, 90)
                fill.color = if (grading.temperature > 0f) {
                    Color.argb(tMag, 255, 168, 76)
                } else {
                    Color.argb(tMag, 72, 148, 255)
                }
                canvas.drawRect(0f, 0f, w, h, fill)
            }

            // 4. Exposure shift (-1 darken .. +1 brighten)
            if (abs(grading.exposure) > 0.005f) {
                val expAlpha = (abs(grading.exposure) * 140f).toInt().coerceIn(0, 185)
                fill.color = if (grading.exposure > 0f) {
                    Color.argb(expAlpha, 255, 255, 255)
                } else {
                    Color.argb(expAlpha, 0, 0, 0)
                }
                canvas.drawRect(0f, 0f, w, h, fill)
            }

            // 5. Saturation reduction (desaturate toward neutral slate veil when saturation < 1)
            if (grading.saturation < 0.98f) {
                val desat = ((1f - grading.saturation).coerceIn(0f, 1f) * 95f).toInt().coerceIn(0, 120)
                fill.color = Color.argb(desat, 128, 128, 128)
                canvas.drawRect(0f, 0f, w, h, fill)
            }

            // 6. Contrast crush/lift subtle grade
            if (abs(grading.contrast - 1f) > 0.03f) {
                val cDelta = grading.contrast - 1f
                if (cDelta > 0f) {
                    val a = (cDelta.coerceIn(0f, 1f) * 34f).toInt().coerceIn(0, 48)
                    fill.color = Color.argb(a, 10, 12, 18)
                    canvas.drawRect(0f, 0f, w, h, fill)
                } else {
                    val a = ((-cDelta).coerceIn(0f, 0.8f) * 58f).toInt().coerceIn(0, 72)
                    fill.color = Color.argb(a, 148, 154, 164)
                    canvas.drawRect(0f, 0f, w, h, fill)
                }
            }

            // 7. Cinematic radial vignette
            if (grading.vignette > 0.005f) {
                val vAlpha = (grading.vignette * 215f).toInt().coerceIn(0, 230)
                val radius = (kotlin.math.hypot(w * 0.5f, h * 0.5f)).coerceAtLeast(1f)
                fill.shader = RadialGradient(
                    w * 0.5f,
                    h * 0.5f,
                    radius,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(vAlpha, 0, 0, 0)),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
                fill.color = Color.BLACK
                canvas.drawRect(0f, 0f, w, h, fill)
            }
        } finally {
            fill.shader = prevShader
            fill.colorFilter = prevFilter
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
