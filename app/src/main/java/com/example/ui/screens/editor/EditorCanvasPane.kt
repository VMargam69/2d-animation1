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
    ikFlipBend: Boolean = false,
    ikJointLimitsEnabled: Boolean = true,
    isCameraKeyMode: Boolean = false,
    selectedCameraBoxId: String? = null,
    isCameraPreviewMode: Boolean = false,
    showGridOverlay: Boolean = false,
    brushColorHex: String,
    brushWidthRatio: Float,
    brushStyle: BrushStyle = BrushStyle.SOLID,
    sceneRenderer: SceneRenderer,
    onSelectClip: (String?) -> Unit,
    onCommitClipTranslation: (clipId: String, totalDxNorm: Float, totalDyNorm: Float) -> Unit,
    onCommitIkPose: (clipId: String, finalOffsets: PoseKeyframeOffsets) -> Unit = { _, _ -> },
    onCommitCameraPan: (totalDxNorm: Float, totalDyNorm: Float) -> Unit = { _, _ -> },
    onSelectCameraBox: (String?) -> Unit = {},
    onAddCameraBox: () -> Unit = {},
    onToggleCameraViewportMode: () -> Unit = {},
    onCommitCameraBoxTransform: (boxId: String, centerX: Float, centerY: Float, zoom: Float, rotation: Float) -> Unit = { _, _, _, _, _ -> },
    onUpdateCamera: ((CameraKeyframe) -> CameraKeyframe) -> Unit = {},
    onOpenCameraStudio: () -> Unit = {},
    onResetCamera: () -> Unit = {},
    onExitCameraKeyMode: () -> Unit = {},
    onToggleIkFlipBend: () -> Unit = {},
    onToggleIkJointLimits: () -> Unit = {},
    onMirrorIkPose: () -> Unit = {},
    onStampPoseKey: () -> Unit = {},
    onResetPoseAtFrame: () -> Unit = {},
    onOpenIkStudio: (initialTab: Int) -> Unit = {},
    onExitIkPosingMode: () -> Unit = {},
    onFinishStroke: (List<NormalizedPoint>) -> Unit,
    onBrushStyleChange: (String, Float, BrushStyle) -> Unit,
    onUndoStroke: () -> Unit = {},
    onClearStrokes: () -> Unit = {},
    onExitDrawingMode: () -> Unit,
    onZoomBy: (Float) -> Unit,
    onResetViewport: () -> Unit,
    onQuickAddCharacter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene
    val aspectRatio = (project.width.toFloat() / project.height.coerceAtLeast(1).toFloat()).coerceIn(0.4f, 2.5f)
    var isDraggingObject by remember { mutableStateOf(false) }
    var activeIkHandle by remember { mutableStateOf<IkHandleId?>(null) }

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
                ikFlipBend = ikFlipBend,
                ikJointLimitsEnabled = ikJointLimitsEnabled,
                isCameraKeyMode = isCameraKeyMode,
                selectedCameraBoxId = selectedCameraBoxId,
                isCameraPreviewMode = isCameraPreviewMode,
                brushColorHex = brushColorHex,
                brushWidthRatio = brushWidthRatio,
                brushStyle = brushStyle,
                sceneRenderer = sceneRenderer,
                onSelectClip = onSelectClip,
                onCommitClipTranslation = onCommitClipTranslation,
                onCommitIkPose = onCommitIkPose,
                onCommitCameraPan = onCommitCameraPan,
                onSelectCameraBox = onSelectCameraBox,
                onCommitCameraBoxTransform = onCommitCameraBoxTransform,
                onDragStateChanged = { dragging -> isDraggingObject = dragging },
                onActiveIkHandleChanged = { handle -> activeIkHandle = handle },
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
        if (isDraggingObject || isIkPosingMode || isCameraKeyMode || project.onionSkinEnabled || project.showMotionPaths || scene.vfxConfig.hasActiveVfx) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isDraggingObject && selectedEval != null) {
                    CanvasStatusPill(
                        text = if (isIkPosingMode) {
                            activeIkHandle?.let { "IK: ${it.label}" } ?: "IK Pose"
                        } else {
                            "${(selectedEval.x * project.width).toInt()}, ${(selectedEval.y * project.height).toInt()}"
                        },
                        accent = ElectricCyan
                    )
                }
                if (isIkPosingMode && !isDraggingObject) {
                    CanvasStatusPill(text = "IK ON", accent = ElectricCyan)
                }
                if (isCameraKeyMode) {
                    val activeCamBox = scene.cameraBoxes.firstOrNull { it.id == selectedCameraBoxId }
                    val modeText = when {
                        scene.cameraBoxes.isEmpty() -> "CAM KEY"
                        isCameraPreviewMode -> "CAM PREVIEW"
                        activeCamBox != null -> "DIRECTOR • CAM ${activeCamBox.label}"
                        else -> "DIRECTOR STAGE"
                    }
                    CanvasStatusPill(text = modeText, accent = Color(0xFFF59E6C))
                }
                if (project.onionSkinEnabled) {
                    CanvasStatusPill(text = "ONION ±2", accent = ElectricCyan)
                }
                if (project.showMotionPaths) {
                    CanvasStatusPill(text = "PATH", accent = ElectricCyan)
                }
                if (scene.vfxConfig.hasActiveVfx) {
                    CanvasStatusPill(text = "VFX", accent = VioletLayer)
                }
            }
        }

        // Sleek Floating On-Stage IK Posing HUD Pill (ONLY when IK Mode is active & not playing)
        if (isIkPosingMode && !isDrawingMode && !isPlaying) {
            Surface(
                color = StudioCharcoal.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.85f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp, start = 8.dp, end = 8.dp)
                    .testTag("floating_ik_hud")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccessibilityNew,
                        contentDescription = "IK Posing",
                        tint = ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = activeIkHandle?.label ?: "IK",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeIkHandle != null) AmberKeyframe else ElectricCyan
                    )

                    VerticalDivider(color = StudioBorderSubtle, modifier = Modifier.height(16.dp))

                    // Flip Bend Pole toggle (switches elbow/knee bend direction)
                    Surface(
                        color = if (ikFlipBend) AmberKeyframe.copy(alpha = 0.22f) else StudioPanel,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, if (ikFlipBend) AmberKeyframe else StudioBorderSubtle),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onToggleIkFlipBend)
                            .testTag("ik_hud_flip_bend_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = "Flip limb bend direction",
                                tint = if (ikFlipBend) AmberKeyframe else StudioTextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Bend",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (ikFlipBend) AmberKeyframe else StudioTextSecondary
                            )
                        }
                    }

                    // Anatomical Joint Limits Toggle (ON = natural limits, OFF = cartoon rubber-hose)
                    Surface(
                        color = if (ikJointLimitsEnabled) EmeraldLayer.copy(alpha = 0.2f) else StudioPanel,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, if (ikJointLimitsEnabled) EmeraldLayer else StudioBorderSubtle),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onToggleIkJointLimits)
                            .testTag("ik_hud_joint_limits_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (ikJointLimitsEnabled) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = "Toggle anatomical IK joint limits",
                                tint = if (ikJointLimitsEnabled) EmeraldLayer else StudioTextSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (ikJointLimitsEnabled) "Limits" else "Free",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (ikJointLimitsEnabled) EmeraldLayer else StudioTextSecondary
                            )
                        }
                    }

                    // Mirror L <-> R Pose button
                    Surface(
                        color = StudioPanel,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onMirrorIkPose)
                            .testTag("ik_hud_mirror_pose_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Flip,
                                contentDescription = "Mirror left and right limb pose",
                                tint = StudioTextPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Mirror",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StudioTextPrimary
                            )
                        }
                    }

                    // Stamp Pose Keyframe button
                    Surface(
                        color = ElectricCyanDim,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, ElectricCyan),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onStampPoseKey)
                            .testTag("ik_hud_stamp_key_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Stamp pose keyframe",
                                tint = ElectricCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Key",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                        }
                    }

                    // Open IK Pose Library / Actions Studio directly from on-stage IK HUD
                    Surface(
                        color = StudioPanel,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable { onOpenIkStudio(2) }
                            .testTag("ik_hud_poses_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bookmarks,
                                contentDescription = "Open IK Pose Library",
                                tint = ElectricCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Poses",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                        }
                    }

                    // Reset Pose at Frame
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable(onClick = onResetPoseAtFrame)
                            .testTag("ik_hud_reset_pose_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = "Reset pose at current frame",
                            tint = CoralPlayhead,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Done (exit IK mode)
                    TextButton(
                        onClick = onExitIkPosingMode,
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("ik_hud_done_btn")
                    ) {
                        Text("Done", color = ElectricCyan, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 1-Tap Viewport Viewing Mode Toggle on Stage: Director/Stage Mode <-> Camera Preview Mode
        if (!isPlaying && !isDrawingMode && (isCameraKeyMode || scene.cameraBoxes.isNotEmpty())) {
            val isDirectorView = isCameraKeyMode && !isCameraPreviewMode && scene.cameraBoxes.isNotEmpty()
            Surface(
                color = StudioObsidian.copy(alpha = 0.88f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (isDirectorView) Color(0xFFF59E6C).copy(alpha = 0.85f) else ElectricCyan.copy(alpha = 0.75f)
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 44.dp)
                    .clickable(onClick = onToggleCameraViewportMode)
                    .testTag("camera_viewport_mode_toggle_btn")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = if (isDirectorView) Icons.Rounded.GridView else Icons.Rounded.Videocam,
                        contentDescription = if (isDirectorView) "Switch to Camera Preview Mode" else "Switch to Director Stage Mode",
                        tint = if (isDirectorView) Color(0xFFF59E6C) else ElectricCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isDirectorView) "Stage" else "Preview",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDirectorView) Color(0xFFF59E6C) else ElectricCyan
                    )
                }
            }
        }

        // Sleek Floating On-Stage Camera Control HUD Pill (ONLY when Camera Key Mode is active & not playing)
        if (isCameraKeyMode && !isDrawingMode && !isPlaying) {
            val activeBox = remember(scene.cameraBoxes, selectedCameraBoxId, currentFrame) {
                scene.cameraBoxes.firstOrNull { it.id == selectedCameraBoxId }
                    ?: com.example.engine.CameraBoxEngine.activeCameraBoxAtFrame(scene.cameraBoxes, currentFrame)
            }
            val evalCam = remember(scene, currentFrame, activeBox) {
                activeBox?.let {
                    com.example.engine.EvaluatedCamera(it.centerX, it.centerY, it.zoom, it.rotation)
                } ?: KeyframeEvaluator.evaluateCamera(scene, currentFrame)
            }
            val peachAccent = Color(0xFFF59E6C)
            Surface(
                color = StudioCharcoal.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, peachAccent.copy(alpha = 0.85f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp, start = 8.dp, end = 8.dp)
                    .testTag("floating_camera_hud")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (activeBox != null) {
                        Surface(
                            color = peachAccent,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(22.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = activeBox.label,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1A1410)
                                )
                            }
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = "Camera Control HUD",
                            tint = peachAccent,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // + Cam button to add Camera Box A, B, C...
                    Surface(
                        color = peachAccent.copy(alpha = 0.20f),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, peachAccent),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onAddCameraBox)
                            .testTag("cam_hud_add_box_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Add, "Add Camera Box", tint = peachAccent, modifier = Modifier.size(13.dp))
                            Text("Cam", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = peachAccent)
                        }
                    }

                    // Zoom - / readout / +
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable { onUpdateCamera { it.copy(zoom = (it.zoom - 0.15f).coerceIn(0.35f, 3.5f)) } }
                            .testTag("cam_hud_zoom_out_btn")
                    ) {
                        Icon(Icons.Rounded.ZoomOut, "Camera zoom out", tint = StudioTextPrimary, modifier = Modifier.size(14.dp))
                    }
                    Text(
                        text = "${(evalCam.zoom * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = peachAccent
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable { onUpdateCamera { it.copy(zoom = (it.zoom + 0.15f).coerceIn(0.35f, 3.5f)) } }
                            .testTag("cam_hud_zoom_in_btn")
                    ) {
                        Icon(Icons.Rounded.ZoomIn, "Camera zoom in", tint = StudioTextPrimary, modifier = Modifier.size(14.dp))
                    }

                    VerticalDivider(color = StudioBorderSubtle, modifier = Modifier.height(16.dp))

                    // Roll -5° / readout / +5°
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable { onUpdateCamera { it.copy(rotation = (it.rotation - 5f).coerceIn(-180f, 180f)) } }
                            .testTag("cam_hud_roll_ccw_btn")
                    ) {
                        Icon(Icons.Rounded.RotateLeft, "Rotate camera left", tint = StudioTextPrimary, modifier = Modifier.size(14.dp))
                    }
                    Text(
                        text = "${evalCam.rotation.toInt()}°",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioTextPrimary
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable { onUpdateCamera { it.copy(rotation = (it.rotation + 5f).coerceIn(-180f, 180f)) } }
                            .testTag("cam_hud_roll_cw_btn")
                    ) {
                        Icon(Icons.Rounded.RotateRight, "Rotate camera right", tint = StudioTextPrimary, modifier = Modifier.size(14.dp))
                    }

                    // Presets shortcut
                    Surface(
                        color = StudioPanel,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, peachAccent.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onOpenCameraStudio)
                            .testTag("cam_hud_presets_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Rounded.MovieFilter, "Camera presets", tint = peachAccent, modifier = Modifier.size(13.dp))
                            Text("Presets", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = peachAccent)
                        }
                    }

                    // Reset camera
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable(onClick = onResetCamera)
                            .testTag("cam_hud_reset_btn")
                    ) {
                        Icon(Icons.Rounded.RestartAlt, "Reset camera", tint = CoralPlayhead, modifier = Modifier.size(14.dp))
                    }

                    // Done
                    TextButton(
                        onClick = onExitCameraKeyMode,
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("cam_hud_done_btn")
                    ) {
                        Text("Done", color = AmberKeyframe, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Compact Floating Brush Palette Bar ONLY when Freehand Drawing Mode is Active
        if (isDrawingMode) {
            val brushColors = listOf("#38BDF8", "#F59E0B", "#F43F5E", "#10B981", "#F8FAFC", "#A78BFA")
            val widthStops = listOf(0.005f to "S", 0.009f to "M", 0.016f to "L")
            Surface(
                color = StudioCharcoal.copy(alpha = 0.95f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PinkLayer),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp, start = 8.dp, end = 8.dp)
                    .testTag("floating_draw_toolbar")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Row 1: Brush Style Pills (Solid / Neon / Sketch) + Stroke Undo / Clear + Done
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BrushStyle.entries.forEach { style ->
                            val selected = brushStyle == style
                            Surface(
                                color = if (selected) PinkLayer.copy(alpha = 0.25f) else StudioPanel,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (selected) PinkLayer else StudioBorderSubtle),
                                modifier = Modifier
                                    .height(26.dp)
                                    .clickable { onBrushStyleChange(brushColorHex, brushWidthRatio, style) }
                                    .testTag("brush_style_${style.name.lowercase()}")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Text(
                                        text = style.displayName,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) PinkLayer else StudioTextSecondary
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(2.dp))

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(StudioPanel)
                                .clickable(onClick = onUndoStroke)
                                .testTag("draw_undo_stroke_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Undo,
                                contentDescription = "Undo last stroke",
                                tint = StudioTextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(StudioPanel)
                                .clickable(onClick = onClearStrokes)
                                .testTag("draw_clear_strokes_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Clear sketch strokes",
                                tint = CoralPlayhead,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        TextButton(
                            onClick = onExitDrawingMode,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("Done", color = ElectricCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Row 2: Color Swatches + S/M/L Width Chips
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                                        .clickable { onBrushStyleChange(hex, brushWidthRatio, brushStyle) }
                                )
                            }
                        }

                        VerticalDivider(
                            color = StudioBorderSubtle,
                            modifier = Modifier.height(16.dp)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            widthStops.forEach { (ratio, label) ->
                                val active = kotlin.math.abs(brushWidthRatio - ratio) < 0.002f
                                Surface(
                                    color = if (active) ElectricCyanDim else StudioPanel,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, if (active) ElectricCyan else StudioBorderSubtle),
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clickable { onBrushStyleChange(brushColorHex, ratio, brushStyle) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (active) ElectricCyan else StudioTextSecondary
                                        )
                                    }
                                }
                            }
                        }
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
