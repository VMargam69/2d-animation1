package com.example.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.example.engine.CameraBoxEngine
import com.example.engine.CameraBoxHandle
import com.example.engine.IkRigSolver
import com.example.engine.KeyframeEvaluator
import com.example.engine.ProceduralRigRenderer
import com.example.engine.PropAndModularChartRenderer
import com.example.engine.SceneRenderer
import com.example.engine.StageGesturePreview
import com.example.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Core interactive 2D animation stage component using Jetpack Compose [Canvas] and
 * [Modifier.pointerInput] for touch-based hit-test object selection, zero-recomposition
 * direct coordinate translation, Direct Stage IK limb/head posing, Camera Keyframe panning,
 * and low-allocation freehand vector drawing.
 */
@Composable
fun EditorCanvas(
    project: ProjectDocument,
    playbackFrameFlow: StateFlow<Int>,
    bitmapVersionFlow: StateFlow<Int> = remember { MutableStateFlow(0) },
    isPlaying: Boolean,
    charactersById: Map<String, CharacterRigDefinition>,
    selectedClipId: String?,
    canvasZoom: Float,
    isDrawingMode: Boolean,
    isIkPosingMode: Boolean = false,
    ikFlipBend: Boolean = false,
    ikJointLimitsEnabled: Boolean = true,
    isCameraKeyMode: Boolean = false,
    selectedCameraBoxId: String? = null,
    isCameraPreviewMode: Boolean = false,
    brushColorHex: String,
    brushWidthRatio: Float,
    brushStyle: BrushStyle = BrushStyle.SOLID,
    sceneRenderer: SceneRenderer,
    onSelectClip: (String?) -> Unit,
    onCommitClipTranslation: (clipId: String, totalDxNormalized: Float, totalDyNormalized: Float) -> Unit,
    onCommitIkPose: (clipId: String, finalOffsets: PoseKeyframeOffsets) -> Unit = { _, _ -> },
    onCommitCameraPan: (totalDxNormalized: Float, totalDyNormalized: Float) -> Unit = { _, _ -> },
    onSelectCameraBox: (String?) -> Unit = {},
    onCommitCameraBoxTransform: (boxId: String, centerX: Float, centerY: Float, zoom: Float, rotation: Float) -> Unit = { _, _, _, _, _ -> },
    onDragStateChanged: (isDragging: Boolean) -> Unit,
    onActiveIkHandleChanged: (IkHandleId?) -> Unit = {},
    onFinishStroke: (List<NormalizedPoint>) -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene

    // Reusable stroke buffers + draw-phase-only invalidation counter (zero list copies per drag pixel)
    val liveStrokePoints = remember { ArrayList<NormalizedPoint>(128) }
    val liveStrokePath = remember { Path() }
    val strokeDrawVersion = remember { mutableIntStateOf(0) }
    val parsedBrushColor = remember(brushColorHex) {
        Color(ProceduralRigRenderer.parseColorSafe(brushColorHex, 0xFF38BDF8.toInt()))
    }

    // Local gesture preview read ONLY inside Canvas draw scope -> 0 Compose recompositions during drag!
    val gesturePreviewState = remember { mutableStateOf(StageGesturePreview()) }
    val activeIkHandleState = remember { mutableStateOf<IkHandleId?>(null) }
    val liveCameraBoxPreview = remember { mutableStateOf<CameraBoxClip?>(null) }
    val activeCameraBoxHandle = remember { mutableStateOf<CameraBoxHandle?>(null) }
    val cameraBoxPaints = remember { CameraBoxOverlayPaints() }
    val densityPx = LocalDensity.current.density

    val latestScene by rememberUpdatedState(scene)
    val latestCharactersById by rememberUpdatedState(charactersById)
    val latestSelectedId by rememberUpdatedState(selectedClipId)
    val latestDrawingMode by rememberUpdatedState(isDrawingMode)
    val latestIkPosingMode by rememberUpdatedState(isIkPosingMode)
    val latestIkFlipBend by rememberUpdatedState(ikFlipBend)
    val latestIkJointLimits by rememberUpdatedState(ikJointLimitsEnabled)
    val latestCameraKeyMode by rememberUpdatedState(isCameraKeyMode)
    val latestSelectedCamBoxId by rememberUpdatedState(selectedCameraBoxId)
    val latestCameraPreviewMode by rememberUpdatedState(isCameraPreviewMode)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestOnActiveIkHandleChanged by rememberUpdatedState(onActiveIkHandleChanged)
    val latestOnSelectCameraBox by rememberUpdatedState(onSelectCameraBox)
    val latestOnCommitCameraBoxTransform by rememberUpdatedState(onCommitCameraBoxTransform)
    val liveFrameState = playbackFrameFlow.collectAsState()
    val bitmapVersionState = bitmapVersionFlow.collectAsState()

    val isDirectorStageActive = !isPlaying && isCameraKeyMode && !isCameraPreviewMode && scene.cameraBoxes.isNotEmpty()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("editor_canvas")
            // Tap gesture: hit-test camera boxes in Director Mode, or objects in camera-world space
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (latestDrawingMode || latestIsPlaying) return@detectTapGestures
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()

                    val directorModeNow = latestCameraKeyMode && !latestCameraPreviewMode && latestScene.cameraBoxes.isNotEmpty()
                    if (directorModeNow) {
                        val hit = CameraBoxEngine.hitTestBoxes(
                            boxes = latestScene.cameraBoxes,
                            selectedBoxId = latestSelectedCamBoxId,
                            touchPxX = offset.x,
                            touchPxY = offset.y,
                            canvasWidth = w,
                            canvasHeight = h,
                            handleRadiusPx = 22f * densityPx,
                            stemLengthPx = 24f * densityPx
                        )
                        if (hit != null) {
                            latestOnSelectCameraBox(hit.first.id)
                            return@detectTapGestures
                        }
                    }

                    val frame = liveFrameState.value
                    val camera = KeyframeEvaluator.evaluateCamera(latestScene, frame)
                    val (wx, wy) = IkRigSolver.screenNormToWorldNorm(
                        screenNx = (offset.x / w).coerceIn(0f, 1f),
                        screenNy = (offset.y / h).coerceIn(0f, 1f),
                        camera = camera,
                        stageWidth = w,
                        stageHeight = h
                    )
                    val hitClip = hitTestClipAtPoint(latestScene, frame, wx, wy)
                    onSelectClip(hitClip?.id)
                }
            }
            // Drag gesture: freehand draw, Camera Box handles (A/B/C), IK limb handles, camera pan, or object translation
            .pointerInput(Unit) {
                var activeDragClipId: String? = null
                var draggingCamera = false
                var camBoxDragBase: CameraBoxClip? = null
                var camBoxDragHandle: CameraBoxHandle? = null
                var camBoxTotalDxPx = 0f
                var camBoxTotalDyPx = 0f
                var ikDragClip: TimelineClip? = null
                var ikDragRig: CharacterRigDefinition? = null
                var ikBaseEval: com.example.engine.EvaluatedTransform? = null
                var ikBasePose: ProceduralRigRenderer.PoseAngles? = null
                var dragCameraSnapshot: com.example.engine.EvaluatedCamera = com.example.engine.EvaluatedCamera()
                var smoothVelX = 0f
                var smoothVelY = 0f
                var lastStrokePxX = 0f
                var lastStrokePxY = 0f

                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (latestIsPlaying) return@detectDragGestures
                        val w = size.width.coerceAtLeast(1).toFloat()
                        val h = size.height.coerceAtLeast(1).toFloat()
                        val sx = (startOffset.x / w).coerceIn(0f, 1f)
                        val sy = (startOffset.y / h).coerceIn(0f, 1f)
                        val frame = liveFrameState.value
                        val camera = KeyframeEvaluator.evaluateCamera(latestScene, frame)
                        dragCameraSnapshot = camera
                        smoothVelX = 0f
                        smoothVelY = 0f

                        if (latestDrawingMode) {
                            liveStrokePoints.clear()
                            liveStrokePath.reset()
                            liveStrokePoints.add(NormalizedPoint(sx, sy))
                            liveStrokePath.moveTo(startOffset.x, startOffset.y)
                            lastStrokePxX = startOffset.x
                            lastStrokePxY = startOffset.y
                            strokeDrawVersion.intValue++
                            return@detectDragGestures
                        }

                        // Director / Stage Mode: direct manipulation of Camera Boxes A, B, C...
                        val directorModeNow = latestCameraKeyMode && !latestCameraPreviewMode && latestScene.cameraBoxes.isNotEmpty()
                        if (directorModeNow) {
                            if (latestScene.cameraLayerLocked) return@detectDragGestures
                            val hit = CameraBoxEngine.hitTestBoxes(
                                boxes = latestScene.cameraBoxes,
                                selectedBoxId = latestSelectedCamBoxId,
                                touchPxX = startOffset.x,
                                touchPxY = startOffset.y,
                                canvasWidth = w,
                                canvasHeight = h,
                                handleRadiusPx = 24f * densityPx,
                                stemLengthPx = 24f * densityPx
                            )
                            if (hit != null) {
                                val (hitBox, hitHandle) = hit
                                if (hitBox.id != latestSelectedCamBoxId) {
                                    latestOnSelectCameraBox(hitBox.id)
                                }
                                camBoxDragBase = hitBox
                                camBoxDragHandle = hitHandle
                                camBoxTotalDxPx = 0f
                                camBoxTotalDyPx = 0f
                                activeCameraBoxHandle.value = hitHandle
                                liveCameraBoxPreview.value = hitBox
                                onDragStateChanged(true)
                                return@detectDragGestures
                            }
                            return@detectDragGestures
                        }

                        if (latestCameraKeyMode) {
                            if (latestScene.cameraLayerLocked) return@detectDragGestures
                            draggingCamera = true
                            gesturePreviewState.value = StageGesturePreview()
                            onDragStateChanged(true)
                            return@detectDragGestures
                        }

                        val (wx, wy) = IkRigSolver.screenNormToWorldNorm(sx, sy, camera, w, h)

                        // When IK Posing Mode is active, first check if user grabbed any of the 10 IK handles
                        if (latestIkPosingMode) {
                            val selClip = latestScene.clips.firstOrNull { it.id == latestSelectedId }
                            val rig = selClip?.characterId?.let { latestCharactersById[it] }
                            if (selClip != null && rig != null && frame in selClip.startFrame until selClip.endFrame) {
                                val hitRadius = (0.095f / camera.zoom.coerceIn(0.6f, 2.5f)).coerceIn(0.045f, 0.14f)
                                val hitHandle = hitTestIkHandleAtPoint(
                                    clip = selClip,
                                    rig = rig,
                                    frame = frame,
                                    nx = wx,
                                    ny = wy,
                                    stageWidth = w,
                                    stageHeight = h,
                                    hitRadius = hitRadius
                                )
                                if (hitHandle != null) {
                                    val eval = KeyframeEvaluator.evaluate(selClip, frame)
                                    val localFrame = (frame - selClip.startFrame).coerceAtLeast(0)
                                    val cycleFrames = selClip.animationPresetType.defaultDurationFrames.coerceAtLeast(8).toFloat()
                                    val rawPhase = (localFrame * selClip.speedMultiplier) / cycleFrames
                                    val phase = if (selClip.loopAnimation) rawPhase % 1f else rawPhase.coerceIn(0f, 0.999f)
                                    ikDragClip = selClip
                                    ikDragRig = rig
                                    ikBaseEval = eval
                                    ikBasePose = ProceduralRigRenderer.computePose(selClip.animationPresetType, phase)
                                    activeIkHandleState.value = hitHandle
                                    latestOnActiveIkHandleChanged(hitHandle)
                                    gesturePreviewState.value = StageGesturePreview(
                                        ikClipId = selClip.id,
                                        ikPoseOffsets = eval.poseOffsets
                                    )
                                    onDragStateChanged(true)
                                    return@detectDragGestures
                                }
                            }
                        }

                        // Standard object hit-test & translation in camera-world space
                        val hit = hitTestClipAtPoint(latestScene, frame, wx, wy)
                        activeDragClipId = hit?.id ?: latestSelectedId
                        if (hit != null && hit.id != latestSelectedId) {
                            onSelectClip(hit.id)
                        }
                        if (activeDragClipId != null) {
                            gesturePreviewState.value = StageGesturePreview(draggedClipId = activeDragClipId)
                            onDragStateChanged(true)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val w = size.width.coerceAtLeast(1).toFloat()
                        val h = size.height.coerceAtLeast(1).toFloat()
                        if (latestDrawingMode) {
                            val px = change.position.x.coerceIn(0f, w)
                            val py = change.position.y.coerceIn(0f, h)
                            if (hypot(px - lastStrokePxX, py - lastStrokePxY) >= 2.5f) {
                                liveStrokePoints.add(NormalizedPoint(px / w, py / h))
                                liveStrokePath.lineTo(px, py)
                                lastStrokePxX = px
                                lastStrokePxY = py
                                strokeDrawVersion.intValue++
                            }
                        } else if (camBoxDragBase != null && camBoxDragHandle != null) {
                            val base = camBoxDragBase!!
                            val handle = camBoxDragHandle!!
                            camBoxTotalDxPx += dragAmount.x
                            camBoxTotalDyPx += dragAmount.y
                            val updatedBox = when (handle) {
                                CameraBoxHandle.CENTER -> CameraBoxEngine.applyPanDrag(
                                    baseBox = base,
                                    totalDeltaPxX = camBoxTotalDxPx,
                                    totalDeltaPxY = camBoxTotalDyPx,
                                    canvasWidth = w,
                                    canvasHeight = h
                                )
                                CameraBoxHandle.ROTATE_STEM -> CameraBoxEngine.applyRotationDrag(
                                    box = base,
                                    pointerPxX = change.position.x,
                                    pointerPxY = change.position.y,
                                    canvasWidth = w,
                                    canvasHeight = h
                                )
                                else -> CameraBoxEngine.applyAspectLockedResize(
                                    box = base,
                                    handle = handle,
                                    pointerPxX = change.position.x,
                                    pointerPxY = change.position.y,
                                    canvasWidth = w,
                                    canvasHeight = h
                                )
                            }
                            liveCameraBoxPreview.value = updatedBox
                        } else if (draggingCamera) {
                            // Canvas already receives unscaled local coordinates inside graphicsLayer;
                            // apply only the scene camera zoom/rotation inverse transform.
                            val (dxNorm, dyNorm) = IkRigSolver.screenDeltaToWorldNormDelta(
                                dxPx = dragAmount.x,
                                dyPx = dragAmount.y,
                                camera = dragCameraSnapshot,
                                stageWidth = w,
                                stageHeight = h
                            )
                            val cur = gesturePreviewState.value
                            gesturePreviewState.value = cur.copy(
                                cameraDeltaX = cur.cameraDeltaX + dxNorm,
                                cameraDeltaY = cur.cameraDeltaY + dyNorm
                            )
                        } else if (activeIkHandleState.value != null) {
                            val clip = ikDragClip
                            val rig = ikDragRig
                            val eval = ikBaseEval
                            val basePose = ikBasePose
                            val handle = activeIkHandleState.value
                            if (clip != null && rig != null && eval != null && basePose != null && handle != null) {
                                val sx = (change.position.x / w).coerceIn(-0.15f, 1.15f)
                                val sy = (change.position.y / h).coerceIn(-0.15f, 1.15f)
                                val (wx, wy) = IkRigSolver.screenNormToWorldNorm(sx, sy, dragCameraSnapshot, w, h)
                                val currentOffsets = gesturePreviewState.value.ikPoseOffsets ?: eval.poseOffsets
                                val solved = PropAndModularChartRenderer.solveIkDragToOffsets(
                                    handleId = handle,
                                    targetNx = wx,
                                    targetNy = wy,
                                    clip = clip,
                                    rig = rig,
                                    transform = eval.copy(poseOffsets = currentOffsets),
                                    basePose = basePose,
                                    currentOffsets = currentOffsets,
                                    stageWidth = w,
                                    stageHeight = h,
                                    bendFlip = latestIkFlipBend,
                                    enforceJointLimits = latestIkJointLimits
                                )
                                gesturePreviewState.value = StageGesturePreview(
                                    ikClipId = clip.id,
                                    ikPoseOffsets = solved
                                )
                            }
                        } else if (activeDragClipId != null) {
                            val (dxNorm, dyNorm) = IkRigSolver.screenDeltaToWorldNormDelta(
                                dxPx = dragAmount.x,
                                dyPx = dragAmount.y,
                                camera = dragCameraSnapshot,
                                stageWidth = w,
                                stageHeight = h
                            )
                            // Exponential moving average of drag velocity drives real-time prop sway feedback
                            smoothVelX = smoothVelX * 0.62f + (dxNorm * 32f) * 0.38f
                            smoothVelY = smoothVelY * 0.62f + (dyNorm * 32f) * 0.38f
                            val cur = gesturePreviewState.value
                            gesturePreviewState.value = cur.copy(
                                draggedClipId = activeDragClipId,
                                clipDeltaX = cur.clipDeltaX + dxNorm,
                                clipDeltaY = cur.clipDeltaY + dyNorm,
                                dragVelocityX = smoothVelX.coerceIn(-1.2f, 1.2f),
                                dragVelocityY = smoothVelY.coerceIn(-1.2f, 1.2f)
                            )
                        }
                    },
                    onDragEnd = {
                        if (latestDrawingMode) {
                            if (liveStrokePoints.size >= 2) {
                                onFinishStroke(liveStrokePoints.toList())
                            }
                            liveStrokePoints.clear()
                            liveStrokePath.reset()
                            strokeDrawVersion.intValue++
                        } else {
                            val preview = gesturePreviewState.value
                            val finishedCamBox = liveCameraBoxPreview.value
                            val finishedCam = draggingCamera
                            val finishedIkClipId = ikDragClip?.id
                            val finishedIkOffsets = preview.ikPoseOffsets
                            val finishedDragClipId = activeDragClipId

                            camBoxDragBase = null
                            camBoxDragHandle = null
                            camBoxTotalDxPx = 0f
                            camBoxTotalDyPx = 0f
                            activeCameraBoxHandle.value = null
                            liveCameraBoxPreview.value = null
                            draggingCamera = false
                            activeIkHandleState.value = null
                            latestOnActiveIkHandleChanged(null)
                            activeDragClipId = null
                            ikDragClip = null
                            ikDragRig = null
                            ikBaseEval = null
                            ikBasePose = null
                            smoothVelX = 0f
                            smoothVelY = 0f
                            gesturePreviewState.value = StageGesturePreview()

                            if (finishedCamBox != null) {
                                onDragStateChanged(false)
                                latestOnCommitCameraBoxTransform(
                                    finishedCamBox.id,
                                    finishedCamBox.centerX,
                                    finishedCamBox.centerY,
                                    finishedCamBox.zoom,
                                    finishedCamBox.rotation
                                )
                            } else if (finishedCam) {
                                onDragStateChanged(false)
                                onCommitCameraPan(preview.cameraDeltaX, preview.cameraDeltaY)
                            } else if (finishedIkClipId != null && finishedIkOffsets != null) {
                                onDragStateChanged(false)
                                onCommitIkPose(finishedIkClipId, finishedIkOffsets)
                            } else if (finishedDragClipId != null) {
                                onDragStateChanged(false)
                                onCommitClipTranslation(finishedDragClipId, preview.clipDeltaX, preview.clipDeltaY)
                            }
                        }
                    },
                    onDragCancel = {
                        liveStrokePoints.clear()
                        liveStrokePath.reset()
                        strokeDrawVersion.intValue++
                        camBoxDragBase = null
                        camBoxDragHandle = null
                        camBoxTotalDxPx = 0f
                        camBoxTotalDyPx = 0f
                        activeCameraBoxHandle.value = null
                        liveCameraBoxPreview.value = null
                        draggingCamera = false
                        activeIkHandleState.value = null
                        latestOnActiveIkHandleChanged(null)
                        activeDragClipId = null
                        ikDragClip = null
                        ikDragRig = null
                        ikBaseEval = null
                        ikBasePose = null
                        smoothVelX = 0f
                        smoothVelY = 0f
                        gesturePreviewState.value = StageGesturePreview()
                        onDragStateChanged(false)
                    }
                )
            }
    ) {
        // Reading liveFrameState, bitmapVersionState, gesturePreviewState, and strokeDrawVersion
        // INSIDE the DrawScope invalidates ONLY the draw phase (zero Compose recomposition!).
        val drawFrame = liveFrameState.value
        @Suppress("UNUSED_VARIABLE")
        val bmpTick = bitmapVersionState.value
        val preview = gesturePreviewState.value
        val activeHandle = activeIkHandleState.value
        val camBoxPreview = liveCameraBoxPreview.value
        val camBoxHandle = activeCameraBoxHandle.value
        val strokeTick = strokeDrawVersion.intValue

        drawIntoCanvas { composeCanvas ->
            sceneRenderer.renderFrame(
                canvas = composeCanvas.nativeCanvas,
                width = size.width.toInt().coerceAtLeast(1),
                height = size.height.toInt().coerceAtLeast(1),
                scene = scene,
                frame = drawFrame,
                fps = project.fps,
                charactersById = charactersById,
                onionSkinEnabled = project.onionSkinEnabled,
                showMotionPaths = project.showMotionPaths,
                selectedClipId = selectedClipId,
                drawEditorOverlays = true,
                isPlaying = isPlaying,
                isIkPosingMode = isIkPosingMode,
                isCameraKeyMode = isCameraKeyMode,
                activeIkHandle = activeHandle,
                gesturePreview = if (preview.isActive) preview else null,
                inlineSvgAssets = project.inlineSvgAssets,
                customAnimations = project.customAnimations,
                customTransitions = project.customTransitions,
                audioSyncTracks = project.audioSyncTracks,
                directorStagePreview = isDirectorStageActive
            )
        }

        // Visual On-Canvas Multi-Camera Boxes (A, B, C...) in Director / Stage Mode only (hidden during Play & Export)
        if (isDirectorStageActive) {
            drawDirectorCameraBoxesOverlay(
                boxes = scene.cameraBoxes,
                selectedBoxId = selectedCameraBoxId,
                livePreviewBox = camBoxPreview,
                activeHandle = camBoxHandle,
                paints = cameraBoxPaints,
                densityPx = densityPx
            )
        }

        // Live in-progress vector drawing stroke (drawn directly from reusable Path, zero allocations!)
        if (strokeTick > 0 && liveStrokePoints.size >= 2) {
            val baseW = (brushWidthRatio * min(size.width, size.height)).coerceAtLeast(3f)
            when (brushStyle) {
                BrushStyle.SOLID -> {
                    drawPath(
                        path = liveStrokePath,
                        color = parsedBrushColor,
                        style = Stroke(width = baseW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                BrushStyle.NEON_GLOW -> {
                    drawPath(
                        path = liveStrokePath,
                        color = parsedBrushColor.copy(alpha = 0.24f),
                        style = Stroke(width = baseW * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = liveStrokePath,
                        color = parsedBrushColor.copy(alpha = 0.55f),
                        style = Stroke(width = baseW * 1.85f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = liveStrokePath,
                        color = Color.White.copy(alpha = 0.92f),
                        style = Stroke(width = (baseW * 0.68f).coerceAtLeast(2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                BrushStyle.SKETCH_DASHED -> {
                    drawPath(
                        path = liveStrokePath,
                        color = parsedBrushColor,
                        style = Stroke(
                            width = baseW,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf((baseW * 2.4f).coerceAtLeast(8f), (baseW * 1.6f).coerceAtLeast(6f)),
                                0f
                            )
                        )
                    )
                }
            }
        }
    }
}

private fun hitTestIkHandleAtPoint(
    clip: TimelineClip,
    rig: CharacterRigDefinition,
    frame: Int,
    nx: Float,
    ny: Float,
    stageWidth: Float,
    stageHeight: Float,
    hitRadius: Float = 0.095f
): IkHandleId? {
    val eval = KeyframeEvaluator.evaluate(clip, frame)
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
        stageWidth = stageWidth,
        stageHeight = stageHeight
    )
    var bestId: IkHandleId? = null
    var bestDist = hitRadius
    for (h in handles) {
        val d = hypot(nx - h.nx, ny - h.ny)
        if (d < bestDist) {
            bestDist = d
            bestId = h.id
        }
    }
    return bestId
}

/**
 * Top-down hit-testing in normalized stage coordinates (0f..1f, 0f..1f) respecting
 * layer stacking order, visibility, and lock status.
 */
fun hitTestClipAtPoint(
    scene: AnimationScene,
    frame: Int,
    nx: Float,
    ny: Float
): TimelineClip? {
    val interactiveLayers = scene.layers
        .filter { it.isVisible && !it.isLocked && it.type != LayerType.BACKGROUND && it.type != LayerType.AUDIO }
        .sortedByDescending { it.orderIndex }

    for (layer in interactiveLayers) {
        val activeClips = scene.clips.filter {
            it.layerId == layer.id && frame in it.startFrame until it.endFrame
        }
        for (clip in activeClips) {
            val eval = KeyframeEvaluator.evaluate(clip, frame)
            val dist = hypot(nx - eval.x, ny - eval.y)
            val avgScale = (abs(eval.scaleX) + abs(eval.scaleY)) / 2f
            val hitRadius = (0.17f * avgScale).coerceIn(0.10f, 0.36f)
            if (dist <= hitRadius) {
                return clip
            }
        }
    }
    return null
}
