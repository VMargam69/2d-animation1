package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.engine.KeyframeEvaluator
import com.example.engine.ProceduralRigRenderer
import com.example.engine.SceneRenderer
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun EditorCanvasPane(
    project: ProjectDocument,
    currentFrame: Int,
    charactersById: Map<String, CharacterRigDefinition>,
    selectedClipId: String?,
    canvasZoom: Float,
    canvasPanX: Float,
    canvasPanY: Float,
    isDrawingMode: Boolean,
    brushColorHex: String,
    brushWidthRatio: Float,
    sceneRenderer: SceneRenderer,
    onSelectClip: (String?) -> Unit,
    onDragSelectedClip: (dxNorm: Float, dyNorm: Float) -> Unit,
    onCommitDrag: () -> Unit,
    onFinishStroke: (List<NormalizedPoint>) -> Unit,
    onBrushStyleChange: (String, Float) -> Unit,
    onExitDrawingMode: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetViewport: () -> Unit,
    onQuickAddCharacter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene
    val aspectRatio = (project.width.toFloat() / project.height.coerceAtLeast(1).toFloat()).coerceIn(0.4f, 2.5f)
    var isDraggingObject by remember { mutableStateOf(false) }

    val selectedClip = scene.clips.firstOrNull { it.id == selectedClipId }
    val selectedEval = remember(selectedClip, currentFrame) {
        selectedClip?.let { KeyframeEvaluator.evaluate(it, currentFrame) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioCanvasBackdrop),
        contentAlignment = Alignment.Center
    ) {
        val availW = maxWidth - 20.dp
        val availH = maxHeight - 20.dp
        val fitWidth = minOf(availW, availH * aspectRatio).coerceAtLeast(120.dp)
        val fitHeight = (fitWidth / aspectRatio).coerceAtLeast(100.dp)

        // Centered Aspect-Ratio Stage Container hosting EditorCanvas
        Box(
            modifier = Modifier
                .size(fitWidth, fitHeight)
                .graphicsLayer {
                    scaleX = canvasZoom
                    scaleY = canvasZoom
                    translationX = canvasPanX
                    translationY = canvasPanY
                }
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.5.dp,
                    color = if (isDrawingMode) PinkLayer else StudioBorder,
                    shape = RoundedCornerShape(8.dp)
                )
                .testTag("animation_stage_canvas")
        ) {
            EditorCanvas(
                project = project,
                currentFrame = currentFrame,
                charactersById = charactersById,
                selectedClipId = selectedClipId,
                canvasZoom = canvasZoom,
                isDrawingMode = isDrawingMode,
                brushColorHex = brushColorHex,
                brushWidthRatio = brushWidthRatio,
                sceneRenderer = sceneRenderer,
                onSelectClip = onSelectClip,
                onTranslateSelectedClip = onDragSelectedClip,
                onDragStateChanged = { dragging -> isDraggingObject = dragging },
                onCommitTranslation = onCommitDrag,
                onFinishStroke = onFinishStroke,
                modifier = Modifier.fillMaxSize()
            )

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
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.PersonAdd, null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = "Tap to Add Character",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary
                        )
                    }
                }
            }
        }

        // Top-Left Active Mode & Live Coordinate Translation HUD
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isDraggingObject && selectedEval != null) {
                Surface(
                    color = StudioObsidian.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ElectricCyan)
                ) {
                    Text(
                        text = "X:${(selectedEval.x * project.width).toInt()}px  Y:${(selectedEval.y * project.height).toInt()}px",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            if (project.onionSkinEnabled) {
                Surface(
                    color = AmberKeyframeDim.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AmberKeyframe)
                ) {
                    Text(
                        text = "ONION SKIN",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberKeyframe,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            if (project.showMotionPaths) {
                Surface(
                    color = ElectricCyanDim.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ElectricCyan)
                ) {
                    Text(
                        text = "MOTION PATH",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Floating Brush Palette Bar when Freehand Drawing Mode is Active
        if (isDrawingMode) {
            val brushColors = listOf("#38BDF8", "#F59E0B", "#F43F5E", "#10B981", "#F8FAFC", "#A78BFA")
            Surface(
                color = StudioCharcoal.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PinkLayer),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Draw", style = MaterialTheme.typography.labelLarge, color = PinkLayer)
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Done", color = ElectricCyan)
                    }
                }
            }
        }

        // Floating Canvas Zoom & Fit Controls (Bottom-Right)
        Surface(
            color = StudioCharcoal.copy(alpha = 0.88f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                IconButton(
                    onClick = onZoomOut,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("canvas_zoom_out_btn")
                ) {
                    Icon(Icons.Rounded.ZoomOut, contentDescription = "Zoom out canvas", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "${(canvasZoom * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextPrimary,
                    modifier = Modifier
                        .clickable(onClick = onResetViewport)
                        .padding(horizontal = 4.dp)
                        .testTag("canvas_fit_reset_btn")
                )
                IconButton(
                    onClick = onZoomIn,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("canvas_zoom_in_btn")
                ) {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = "Zoom in canvas", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = onResetViewport,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("canvas_center_btn")
                ) {
                    Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Fit and center canvas", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
