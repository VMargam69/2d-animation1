package com.example.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
    isCameraKeyMode: Boolean = false,
    brushColorHex: String,
    brushWidthRatio: Float,
    sceneRenderer: SceneRenderer,
    onSelectClip: (String?) -> Unit,
    onCommitClipTranslation: (clipId: String, totalDxNormalized: Float, totalDyNormalized: Float) -> Unit,
    onCommitIkPose: (clipId: String, finalOffsets: PoseKeyframeOffsets) -> Unit = { _, _ -> },
    onCommitCameraPan: (totalDxNormalized: Float, totalDyNormalized: Float) -> Unit = { _, _ -> },
    onDragStateChanged: (isDragging: Boolean) -> Unit,
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

    val latestScene by rememberUpdatedState(scene)
    val latestCharactersById by rememberUpdatedState(charactersById)
    val latestSelectedId by rememberUpdatedState(selectedClipId)
    val latestZoom by rememberUpdatedState(canvasZoom)
    val latestDrawingMode by rememberUpdatedState(isDrawingMode)
    val latestIkPosingMode by rememberUpdatedState(isIkPosingMode)
    val latestCameraKeyMode by rememberUpdatedState(isCameraKeyMode)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val liveFrameState = playbackFrameFlow.collectAsState()
    val bitmapVersionState = bitmapVersionFlow.collectAsState()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("editor_canvas")
            // Tap gesture: hit-test objects in normalized stage coordinates to select or deselect
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (latestDrawingMode || latestIsPlaying) return@detectTapGestures
                    val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val hitClip = hitTestClipAtPoint(latestScene, liveFrameState.value, nx, ny)
                    onSelectClip(hitClip?.id)
                }
            }
            // Drag gesture: freehand draw, IK limb/head handle drag, camera pan, or object translation
            .pointerInput(Unit) {
                var activeDragClipId: String? = null
                var draggingCamera = false
                var ikDragClip: TimelineClip? = null
                var ikDragRig: CharacterRigDefinition? = null
                var ikBaseEval: com.example.engine.EvaluatedTransform? = null
                var ikBasePose: ProceduralRigRenderer.PoseAngles? = null
                var lastStrokePxX = 0f
                var lastStrokePxY = 0f

                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (latestIsPlaying) return@detectDragGestures
                        val w = size.width.coerceAtLeast(1).toFloat()
                        val h = size.height.coerceAtLeast(1).toFloat()
                        val nx = (startOffset.x / w).coerceIn(0f, 1f)
                        val ny = (startOffset.y / h).coerceIn(0f, 1f)
                        val frame = liveFrameState.value

                        if (latestDrawingMode) {
                            liveStrokePoints.clear()
                            liveStrokePath.reset()
                            liveStrokePoints.add(NormalizedPoint(nx, ny))
                            liveStrokePath.moveTo(startOffset.x, startOffset.y)
                            lastStrokePxX = startOffset.x
                            lastStrokePxY = startOffset.y
                            strokeDrawVersion.intValue++
                            return@detectDragGestures
                        }

                        if (latestCameraKeyMode) {
                            draggingCamera = true
                            gesturePreviewState.value = StageGesturePreview()
                            onDragStateChanged(true)
                            return@detectDragGestures
                        }

                        // When IK Posing Mode is active, first check if user grabbed one of the 8 IK handles
                        if (latestIkPosingMode) {
                            val selClip = latestScene.clips.firstOrNull { it.id == latestSelectedId }
                            val rig = selClip?.characterId?.let { latestCharactersById[it] }
                            if (selClip != null && rig != null && frame in selClip.startFrame until selClip.endFrame) {
                                val hitHandle = hitTestIkHandleAtPoint(
                                    clip = selClip,
                                    rig = rig,
                                    frame = frame,
                                    nx = nx,
                                    ny = ny,
                                    stageWidth = w,
                                    stageHeight = h
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
                                    gesturePreviewState.value = StageGesturePreview(
                                        ikClipId = selClip.id,
                                        ikPoseOffsets = eval.poseOffsets
                                    )
                                    onDragStateChanged(true)
                                    return@detectDragGestures
                                }
                            }
                        }

                        // Standard object hit-test & translation
                        val hit = hitTestClipAtPoint(latestScene, frame, nx, ny)
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
                        } else if (draggingCamera) {
                            val zoomFactor = latestZoom.coerceAtLeast(0.25f)
                            val dxNorm = dragAmount.x / (w * zoomFactor)
                            val dyNorm = dragAmount.y / (h * zoomFactor)
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
                                val nx = (change.position.x / w).coerceIn(0.01f, 0.99f)
                                val ny = (change.position.y / h).coerceIn(0.01f, 0.99f)
                                val currentOffsets = gesturePreviewState.value.ikPoseOffsets ?: eval.poseOffsets
                                val solved = PropAndModularChartRenderer.solveIkDragToOffsets(
                                    handleId = handle,
                                    targetNx = nx,
                                    targetNy = ny,
                                    clip = clip,
                                    rig = rig,
                                    transform = eval.copy(poseOffsets = currentOffsets),
                                    basePose = basePose,
                                    currentOffsets = currentOffsets,
                                    stageWidth = w,
                                    stageHeight = h
                                )
                                gesturePreviewState.value = StageGesturePreview(
                                    ikClipId = clip.id,
                                    ikPoseOffsets = solved
                                )
                            }
                        } else if (activeDragClipId != null) {
                            val zoomFactor = latestZoom.coerceAtLeast(0.25f)
                            val dxNorm = dragAmount.x / (w * zoomFactor)
                            val dyNorm = dragAmount.y / (h * zoomFactor)
                            val cur = gesturePreviewState.value
                            gesturePreviewState.value = cur.copy(
                                draggedClipId = activeDragClipId,
                                clipDeltaX = cur.clipDeltaX + dxNorm,
                                clipDeltaY = cur.clipDeltaY + dyNorm
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
                            val finishedCam = draggingCamera
                            val finishedIkClipId = ikDragClip?.id
                            val finishedIkOffsets = preview.ikPoseOffsets
                            val finishedDragClipId = activeDragClipId

                            draggingCamera = false
                            activeIkHandleState.value = null
                            activeDragClipId = null
                            ikDragClip = null
                            ikDragRig = null
                            ikBaseEval = null
                            ikBasePose = null
                            gesturePreviewState.value = StageGesturePreview()

                            if (finishedCam) {
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
                        draggingCamera = false
                        activeIkHandleState.value = null
                        activeDragClipId = null
                        ikDragClip = null
                        ikDragRig = null
                        ikBaseEval = null
                        ikBasePose = null
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
                gesturePreview = if (preview.isActive) preview else null
            )
        }

        // Live in-progress vector drawing stroke (drawn directly from reusable Path, zero allocations!)
        if (strokeTick > 0 && liveStrokePoints.size >= 2) {
            drawPath(
                path = liveStrokePath,
                color = parsedBrushColor,
                style = Stroke(
                    width = (brushWidthRatio * min(size.width, size.height)).coerceAtLeast(3f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
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
    stageHeight: Float
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
    var bestDist = 0.085f
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
