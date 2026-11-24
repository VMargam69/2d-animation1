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
import com.example.engine.SceneRenderer
import com.example.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Core interactive 2D animation stage component using Jetpack Compose [Canvas] and
 * [Modifier.pointerInput] for touch-based hit-test object selection, direct coordinate
 * translation (normalized 0f..1f stage coordinates), and freehand vector drawing.
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
    brushColorHex: String,
    brushWidthRatio: Float,
    sceneRenderer: SceneRenderer,
    onSelectClip: (String?) -> Unit,
    onTranslateSelectedClip: (dxNormalized: Float, dyNormalized: Float) -> Unit,
    onDragStateChanged: (isDragging: Boolean) -> Unit,
    onCommitTranslation: () -> Unit,
    onFinishStroke: (List<NormalizedPoint>) -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene
    var activeStrokePoints by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }

    val latestScene by rememberUpdatedState(scene)
    val latestSelectedId by rememberUpdatedState(selectedClipId)
    val latestZoom by rememberUpdatedState(canvasZoom)
    val latestDrawingMode by rememberUpdatedState(isDrawingMode)
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
            // Drag gesture: either draw freehand stroke or select-and-translate object in stage coordinates
            .pointerInput(Unit) {
                var activeDragClipId: String? = null
                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (latestIsPlaying) return@detectDragGestures
                        val nx = (startOffset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ny = (startOffset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (latestDrawingMode) {
                            activeStrokePoints = listOf(NormalizedPoint(nx, ny))
                        } else {
                            // Hit-test on touch down so the user can immediately grab & translate any unlocked object
                            val hit = hitTestClipAtPoint(latestScene, liveFrameState.value, nx, ny)
                            activeDragClipId = hit?.id ?: latestSelectedId
                            if (hit != null && hit.id != latestSelectedId) {
                                onSelectClip(hit.id)
                            }
                            if (activeDragClipId != null) {
                                onDragStateChanged(true)
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (latestDrawingMode) {
                            val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            activeStrokePoints = activeStrokePoints + NormalizedPoint(nx, ny)
                        } else if (activeDragClipId != null) {
                            // Convert raw pointer pixels into normalized stage coordinate deltas
                            val zoomFactor = latestZoom.coerceAtLeast(0.25f)
                            val dxNorm = dragAmount.x / (size.width.coerceAtLeast(1) * zoomFactor)
                            val dyNorm = dragAmount.y / (size.height.coerceAtLeast(1) * zoomFactor)
                            onTranslateSelectedClip(dxNorm, dyNorm)
                        }
                    },
                    onDragEnd = {
                        if (latestDrawingMode) {
                            if (activeStrokePoints.size >= 2) {
                                onFinishStroke(activeStrokePoints)
                            }
                            activeStrokePoints = emptyList()
                        } else if (activeDragClipId != null) {
                            activeDragClipId = null
                            onDragStateChanged(false)
                            onCommitTranslation()
                        }
                    },
                    onDragCancel = {
                        activeStrokePoints = emptyList()
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
                isPlaying = isPlaying
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
