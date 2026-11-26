package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.KeyframeEvaluator
import com.example.engine.ProceduralRigRenderer
import com.example.engine.SceneRenderer
import com.example.model.*
import com.example.ui.theme.*

/**
 * Clean, maximized animation stage viewport:
 * - Zero redundant top floating bars and zero wasted empty strips below the canvas.
 * - Full two-finger pinch-to-zoom & pan on the viewport (without interfering with 1-finger
 *   clip dragging, IK handle posing, camera panning, or freehand drawing).
 * - Minimal semi-transparent zoom/fit pill overlaid inside the bottom-right corner of the canvas.
 */
@Composable
fun EditorCanvasPane(
    project: ProjectDocument,
    currentFrame: Int,
    playbackFrameFlow: kotlinx.coroutines.flow.StateFlow<Int>,
    bitmapVersionFlow: kotlinx.coroutines.flow.StateFlow<Int>,
    isPlaying: Boolean,
    charactersById: Map<String, CharacterRigDefinition>,
    selectedClipId: String?,
    canvasZoom: Float,
    canvasPanX: Float,
    canvasPanY: Float,
    isDrawingMode: Boolean,
    isIkPosingMode: Boolean = false,
    isCameraKeyMode: Boolean = false,
    showGridOverlay: Boolean = false,
    brushColorHex: String,
    brushWidthRatio: Float,
    sceneRenderer: SceneRenderer,
    onSelectClip: (String?) -> Unit,
    onCommitClipTranslation: (clipId: String, totalDxNorm: Float, totalDyNorm: Float) -> Unit,
    onCommitIkPose: (clipId: String, finalOffsets: PoseKeyframeOffsets) -> Unit = { _, _ -> },
    onCommitCameraPan: (totalDxNorm: Float, totalDyNorm: Float) -> Unit = { _, _ -> },
    onFinishStroke: (List<NormalizedPoint>) -> Unit,
    onBrushStyleChange: (String, Float) -> Unit,
    onExitDrawingMode: () -> Unit,
    onZoomBy: (Float) -> Unit,
    onResetViewport: () -> Unit,
    onQuickAddCharacter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene
    val aspectRatio = (project.width.toFloat() / project.height.coerceAtLeast(1).toFloat()).coerceIn(0.4f, 2.5f)
    var isDraggingObject by remember { mutableStateOf(false) }

    // Local GPU-only pinch zoom & pan preview (zero Compose recompositions during pinch!)
    var localPinchZoom by remember { mutableFloatStateOf(1f) }
    var localPanX by remember { mutableFloatStateOf(0f) }
    var localPanY by remember { mutableFloatStateOf(0f) }

    // Keep local pan synced when user taps Fit/Reset
    LaunchedEffect(canvasZoom, canvasPanX, canvasPanY) {
        if (canvasZoom == 1f && canvasPanX == 0f && canvasPanY == 0f) {
            localPinchZoom = 1f
            localPanX = 0f
            localPanY = 0f
        }
    }

    val effectiveZoom = (canvasZoom * localPinchZoom).coerceIn(0.45f, 3.5f)
    val latestOnZoomBy by rememberUpdatedState(onZoomBy)

    val selectedClip = remember(scene.clips, selectedClipId) { scene.clips.firstOrNull { it.id == selectedClipId } }
    val selectedEval = remember(selectedClip, currentFrame) {
        selectedClip?.let { KeyframeEvaluator.evaluate(it, currentFrame) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioCanvasBackdrop)
            // Two-finger pinch-to-zoom & pan without stealing 1-finger EditorCanvas gestures
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pinched = false
                    var accumulatedZoom = 1f
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                pinched = true
                                accumulatedZoom = (accumulatedZoom * zoomChange).coerceIn(0.35f, 3.5f)
                                localPinchZoom = accumulatedZoom
                                val maxPan = size.width * 0.65f
                                localPanX = (localPanX + panChange.x).coerceIn(-maxPan, maxPan)
                                localPanY = (localPanY + panChange.y).coerceIn(-maxPan, maxPan)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (pinched) {
                        val commitFactor = localPinchZoom
                        localPinchZoom = 1f
                        if (commitFactor != 1f) {
                            latestOnZoomBy(commitFactor)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val availW = (maxWidth - 12.dp).coerceAtLeast(100.dp)
        val availH = (maxHeight - 10.dp).coerceAtLeast(88.dp)
        val fitWidth = minOf(availW, availH * aspectRatio).coerceAtLeast(100.dp)
        val fitHeight = (fitWidth / aspectRatio).coerceAtLeast(84.dp)

        // Maximized Aspect-Ratio Stage Container
        Box(
            modifier = Modifier
                .size(fitWidth, fitHeight)
                .graphicsLayer {
                    scaleX = effectiveZoom
                    scaleY = effectiveZoom
                    translationX = canvasPanX + localPanX
                    translationY = canvasPanY + localPanY
                }
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = when {
                        isDrawingMode -> PinkLayer
                        isIkPosingMode -> ElectricCyan
                        isCameraKeyMode -> AmberKeyframe
                        scene.chromaKeyMode != ChromaKeyMode.NONE -> EmeraldLayer
                        else -> StudioBorderSubtle
                    },
                    shape = RoundedCornerShape(6.dp)
                )
                .testTag("animation_stage_canvas")
        ) {
            EditorCanvas(
                project = project,
                playbackFrameFlow = playbackFrameFlow,
                bitmapVersionFlow = bitmapVersionFlow,
                isPlaying = isPlaying,
                charactersById = charactersById,
                selectedClipId = selectedClipId,
                canvasZoom = effectiveZoom,
                isDrawingMode = isDrawingMode,
                isIkPosingMode = isIkPosingMode,
                isCameraKeyMode = isCameraKeyMode,
                brushColorHex = brushColorHex,
                brushWidthRatio = brushWidthRatio,
                sceneRenderer = sceneRenderer,
                onSelectClip = onSelectClip,
                onCommitClipTranslation = onCommitClipTranslation,
                onCommitIkPose = onCommitIkPose,
                onCommitCameraPan = onCommitCameraPan,
                onDragStateChanged = { dragging -> isDraggingObject = dragging },
                onFinishStroke = onFinishStroke,
                modifier = Modifier.fillMaxSize()
            )

            // Optional Composition / Rule-of-Thirds Stage Grid Overlay (non-exported)
            if (showGridOverlay && !isPlaying) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val thirdColor = Color(0x4438BDF8)
                    val centerColor = Color(0x28F8FAFC)
                    val w = size.width
                    val h = size.height
                    drawLine(thirdColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1f)
                    drawLine(thirdColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), strokeWidth = 1f)
                    drawLine(thirdColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1f)
                    drawLine(thirdColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), strokeWidth = 1f)
                    drawLine(centerColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1f)
                    drawLine(centerColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
                }
            }

            // Friendly Empty Stage Callout
            val hasForegroundClips = scene.clips.any { clip ->
                val layer = scene.layers.firstOrNull { it.id == clip.layerId }
                layer != null && layer.type != LayerType.BACKGROUND
            }
            if (!hasForegroundClips && !isDrawingMode) {
                Surface(
                    color = StudioObsidian.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable(onClick = onQuickAddCharacter)
                        .testTag("empty_stage_add_char_cta")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(Icons.Rounded.PersonAdd, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Tap to Add Character",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary
                        )
                    }
                }
            }
        }

        // Minimal Semi-Transparent Zoom/Fit Pill overlaid inside the bottom-right corner of the canvas viewport
        Surface(
            color = StudioObsidian.copy(alpha = 0.74f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle.copy(alpha = 0.9f)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${(effectiveZoom * 100).toInt()}%",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StudioTextPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            // Quick step cycle: 100% -> 150% -> 100%
                            if (effectiveZoom in 0.95f..1.05f) {
                                onZoomBy(1.5f)
                            } else {
                                localPinchZoom = 1f
                                localPanX = 0f
                                localPanY = 0f
                                onResetViewport()
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                        .testTag("canvas_fit_reset_btn")
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable {
                            localPinchZoom = 1f
                            localPanX = 0f
                            localPanY = 0f
                            onResetViewport()
                        }
                        .testTag("canvas_center_btn")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = "Fit canvas",
                        tint = ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // Minimal Bottom-Left Active Mode Badge (shown ONLY when dragging or when a special overlay mode is active)
        if (isDraggingObject || isIkPosingMode || isCameraKeyMode || project.onionSkinEnabled || project.showMotionPaths) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isDraggingObject && selectedEval != null) {
                    CanvasStatusPill(
                        text = if (isIkPosingMode) "IK Pose"
                        else "${(selectedEval.x * project.width).toInt()}, ${(selectedEval.y * project.height).toInt()}",
                        accent = ElectricCyan
                    )
                }
                if (isIkPosingMode && !isDraggingObject) {
                    CanvasStatusPill(text = "IK ON", accent = ElectricCyan)
                }
                if (isCameraKeyMode) {
                    CanvasStatusPill(text = "CAM KEY", accent = AmberKeyframe)
                }
                if (project.onionSkinEnabled) {
                    CanvasStatusPill(text = "ONION", accent = ElectricCyan)
                }
                if (project.showMotionPaths) {
                    CanvasStatusPill(text = "PATH", accent = ElectricCyan)
                }
            }
        }

        // Compact Floating Brush Palette Bar ONLY when Freehand Drawing Mode is Active
        if (isDrawingMode) {
            val brushColors = listOf("#38BDF8", "#F59E0B", "#F43F5E", "#10B981", "#F8FAFC", "#A78BFA")
            Surface(
                color = StudioCharcoal.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PinkLayer),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Draw", style = MaterialTheme.typography.labelMedium, color = PinkLayer, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(brushColors) { hex ->
                            val c = Color(ProceduralRigRenderer.parseColorSafe(hex, 0xFF38BDF8.toInt()))
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (hex.equals(brushColorHex, true)) 2.dp else 1.dp,
                                        color = if (hex.equals(brushColorHex, true)) Color.White else StudioBorder,
                                        shape = CircleShape
                                    )
                                    .clickable { onBrushStyleChange(hex, brushWidthRatio) }
                            )
                        }
                    }
                    TextButton(
                        onClick = onExitDrawingMode,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Done", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasStatusPill(text: String, accent: Color) {
    Surface(
        color = StudioObsidian.copy(alpha = 0.78f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.65f))
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
