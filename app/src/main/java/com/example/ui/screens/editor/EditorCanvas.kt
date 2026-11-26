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
import com.example.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Core interactive 2D animation stage component using Jetpack Compose [Canvas] and
 * [Modifier.pointerInput] for touch-based hit-test object selection, direct coordinate
 * translation (normalized 0f..1f stage coordinates), Direct Stage IK limb/head posing,
 * Camera Keyframe stage panning, and freehand vector drawing.
 */
@Composable
fun EditorCanvas(
    project: ProjectDocument,
    playbackFrameFlow: kotlinx.coroutines.flow.StateFlow<Int>,
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
    onTranslateSelectedClip: (dxNormalized: Float, dyNormalized: Float) -> Unit,
    onDragIkHandle: (handleId: IkHandleId, targetNx: Float, targetNy: Float, stageW: Float, stageH: Float) -> Unit = { _, _, _, _, _ -> },
    onPanCamera: (dxNormalized: Float, dyNormalized: Float) -> Unit = { _, _ -> },
    onDragStateChanged: (isDragging: Boolean) -> Unit,
    onCommitTranslation: () -> Unit,
    onFinishStroke: (List<NormalizedPoint>) -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene
    var activeStrokePoints by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }
    var activeIkHandle by remember { mutableStateOf<IkHandleId?>(null) }

    val latestScene by rememberUpdatedState(scene)
    val latestCharactersById by rememberUpdatedState(charactersById)
    val latestSelectedId by rememberUpdatedState(selectedClipId)
    val latestZoom by rememberUpdatedState(canvasZoom)
    val latestDrawingMode by rememberUpdatedState(isDrawingMode)
    val latestIkPosingMode by rememberUpdatedState(isIkPosingMode)
    val latestCameraKeyMode by rememberUpdatedState(isCameraKeyMode)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val liveFrameState = playbackFrameFlow.collectAsState()

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
                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (latestIsPlaying) return@detectDragGestures
                        val w = size.width.coerceAtLeast(1).toFloat()
                        val h = size.height.coerceAtLeast(1).toFloat()
                        val nx = (startOffset.x / w).coerceIn(0f, 1f)
                        val ny = (startOffset.y / h).coerceIn(0f, 1f)
                        val frame = liveFrameState.value

                        if (latestDrawingMode) {
                            activeStrokePoints = listOf(NormalizedPoint(nx, ny))
                            return@detectDragGestures
                        }

                        if (latestCameraKeyMode) {
                            draggingCamera = true
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
                                    activeIkHandle = hitHandle
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
                            onDragStateChanged(true)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val w = size.width.coerceAtLeast(1).toFloat()
                        val h = size.height.coerceAtLeast(1).toFloat()
                        if (latestDrawingMode) {
                            val nx = (change.position.x / w).coerceIn(0f, 1f)
                            val ny = (change.position.y / h).coerceIn(0f, 1f)
                            activeStrokePoints = activeStrokePoints + NormalizedPoint(nx, ny)
                        } else if (draggingCamera) {
                            val zoomFactor = latestZoom.coerceAtLeast(0.25f)
                            val dxNorm = dragAmount.x / (w * zoomFactor)
                            val dyNorm = dragAmount.y / (h * zoomFactor)
                            onPanCamera(dxNorm, dyNorm)
                        } else if (activeIkHandle != null) {
                            val nx = (change.position.x / w).coerceIn(0.01f, 0.99f)
                            val ny = (change.position.y / h).coerceIn(0.01f, 0.99f)
                            onDragIkHandle(activeIkHandle!!, nx, ny, w, h)
                        } else if (activeDragClipId != null) {
                            val zoomFactor = latestZoom.coerceAtLeast(0.25f)
                            val dxNorm = dragAmount.x / (w * zoomFactor)
                            val dyNorm = dragAmount.y / (h * zoomFactor)
                            onTranslateSelectedClip(dxNorm, dyNorm)
                        }
                    },
                    onDragEnd = {
                        if (latestDrawingMode) {
                            if (activeStrokePoints.size >= 2) {
                                onFinishStroke(activeStrokePoints)
                            }
                            activeStrokePoints = emptyList()
                        } else if (draggingCamera || activeIkHandle != null || activeDragClipId != null) {
                            draggingCamera = false
                            activeIkHandle = null
                            activeDragClipId = null
                            onDragStateChanged(false)
                            onCommitTranslation()
                        }
                    },
                    onDragCancel = {
                        activeStrokePoints = emptyList()
                        draggingCamera = false
                        activeIkHandle = null
                        activeDragClipId = null
                        onDragStateChanged(false)
                    }
                )
            }
    ) {
        // Reading liveFrameState.value INSIDE the DrawScope invalidates ONLY the draw phase,
        // achieving zero Compose recomposition during video playback!
        val drawFrame = liveFrameState.value
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
                activeIkHandle = activeIkHandle
            )
        }

        // Live in-progress vector drawing stroke
        if (activeStrokePoints.size >= 2) {
            val path = Path()
            activeStrokePoints.forEachIndexed { idx, pt ->
                val px = pt.x * size.width
                val py = pt.y * size.height
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val col = Color(ProceduralRigRenderer.parseColorSafe(brushColorHex, 0xFF38BDF8.toInt()))
            drawPath(
                path = path,
                color = col,
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
