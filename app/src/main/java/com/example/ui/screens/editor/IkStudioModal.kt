package com.example.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.*
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen IK Studio Modal combining:
 * 1. Live Interactive IK Rig Canvas (10 concentric IK handles, Eye-Look Gaze Target, Head Tilt, Pan/Zoom rail)
 * 2. 3-Tab Bottom Workspace:
 *    - Tab 0 (`Timeline`): Playhead step, Mirror, Flip Bend, Head Tilt, & Stamp IK Keyframe
 *    - Tab 1 (`Actions`): Modular Action Preview Mode (Non-destructive 30fps loop + scrub slider + Insert at Playhead + Cancel)
 *    - Tab 2 (`Poses`): IK Pose Library (Save Current Pose, 2-column grid, category/search, 1-tap Apply with Undo, Rename/Delete)
 */
@Composable
fun IkStudioModal(
    clip: TimelineClip,
    rig: CharacterRigDefinition,
    currentFrame: Int,
    fps: Int,
    initialTab: Int,
    savedPoses: List<SavedPose>,
    customBitmap: Bitmap?,
    atlasSlices: Map<AtlasPartSlot, Bitmap>?,
    thumbnailLoader: (String?) -> Bitmap?,
    onStepFrame: (Int) -> Unit,
    onSavePoseToLibrary: (
        name: String,
        category: PoseCategory,
        offsets: PoseKeyframeOffsets,
        gaze: GazeTarget,
        headTilt: Float,
        expression: FacialExpression,
        handPose: HandPoseType
    ) -> Unit,
    onRenameSavedPose: (poseId: String, newName: String, newCategory: PoseCategory) -> Unit,
    onDeleteSavedPose: (poseId: String) -> Unit,
    onDeleteSavedPoses: (Set<String>) -> Unit = { ids -> ids.forEach(onDeleteSavedPose) },
    onApplySavedPose: (SavedPose) -> Unit,
    onInsertModularAction: (clipId: String, action: ModularActionDef) -> Unit,
    onApplyModularControl: (clipId: String, selection: ModularControlSelection) -> Unit = { _, _ -> },
    onCommitIkPose: (
        clipId: String,
        offsets: PoseKeyframeOffsets,
        expression: FacialExpression,
        handPose: HandPoseType
    ) -> Unit,
    onDismiss: () -> Unit
) {
    // Evaluate clip's existing pose at currentFrame as the exact baseline
    val evalTransform = remember(clip, currentFrame) {
        KeyframeEvaluator.evaluate(clip, currentFrame)
    }

    var activeTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var workingOffsets by remember(clip.id, currentFrame) { mutableStateOf(evalTransform.poseOffsets) }
    var workingGaze by remember(clip.id, currentFrame) { mutableStateOf(GazeTarget(0f, 0f)) }
    var workingHeadTilt by remember(clip.id, currentFrame) { mutableFloatStateOf(0f) }
    var workingExpression by remember(clip.id, currentFrame) {
        mutableStateOf(evalTransform.expressionOverride ?: clip.facialExpression)
    }
    var workingHandPose by remember(clip.id, currentFrame) {
        mutableStateOf(evalTransform.handPoseOverride ?: clip.handPose)
    }
    var ikFlipBend by remember { mutableStateOf(false) }
    var ikJointLimitsEnabled by remember { mutableStateOf(true) }

    // Viewport pan / zoom controls
    var viewportZoom by remember { mutableFloatStateOf(1.15f) }
    var viewportPanX by remember { mutableFloatStateOf(0f) }
    var viewportPanY by remember { mutableFloatStateOf(0f) }
    var isPanMode by remember { mutableStateOf(false) }
    var activeDragHandle by remember { mutableStateOf<IkHandleId?>(null) }
    var isDraggingGaze by remember { mutableStateOf(false) }

    // Pro "Control" Modular Action Library live preview state (strictly non-destructive!)
    var controlSelectionPreview by remember(clip.id) {
        mutableStateOf(ModularControlSelection.fromClip(clip))
    }
    var previewAction by remember { mutableStateOf<ModularActionDef?>(null) }
    var previewTickFrame by remember { mutableIntStateOf(0) }

    // Save Pose Dialog state
    var showSavePoseDialog by remember { mutableStateOf(false) }

    // Safety: reset preview ticker cleanly on tab switch or character change
    LaunchedEffect(activeTab, clip.id) {
        if (activeTab != 1) {
            previewAction = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewAction = null
        }
    }

    // Smooth 24fps live preview ticker on the big IK rig whenever Control tab is active
    LaunchedEffect(activeTab) {
        if (activeTab != 1) return@LaunchedEffect
        while (true) {
            delay(42L) // ~24fps live multi-track rig preview
            previewTickFrame = (previewTickFrame + 1) % 100_000
        }
    }

    // Compute displayed pose: either base pose or non-destructively layered Control preview pose
    val activeCombinedBaseOffsets = remember(workingOffsets, workingHeadTilt) {
        workingOffsets.copy(
            headNodOffset = (workingOffsets.headNodOffset + workingHeadTilt).coerceIn(-65f, 65f)
        )
    }

    val isControlPreviewActive = activeTab == 1 && (
        controlSelectionPreview.activeCount > 0 ||
            controlSelectionPreview.speakingMode != CharacterSpeakingMode.AUTO_LIP_SYNC ||
            controlSelectionPreview.eyeblinkMode != CharacterEyeblinkMode.ENABLE
        )

    val displayedPose: BlendedPreviewPose = remember(
        activeCombinedBaseOffsets,
        workingExpression,
        evalTransform.mouthShapeOverride,
        evalTransform.eyeShapeOverride,
        evalTransform.eyebrowShapeOverride,
        workingHandPose,
        workingGaze,
        workingHeadTilt,
        isControlPreviewActive,
        controlSelectionPreview,
        previewTickFrame
    ) {
        if (isControlPreviewActive) {
            ModularActionEngine.blendControlSelectionOntoBase(
                basePose = activeCombinedBaseOffsets,
                baseExpression = workingExpression,
                baseFacialPresetId = evalTransform.facialPresetId,
                baseMouthOverride = evalTransform.mouthShapeOverride ?: MouthShapeOverride.AUTO,
                baseEyeOverride = evalTransform.eyeShapeOverride ?: EyeShapeOverride.AUTO,
                baseEyebrowOverride = evalTransform.eyebrowShapeOverride ?: EyebrowShapeOverride.AUTO,
                baseHandPose = workingHandPose,
                baseBlinkOverride = evalTransform.blinkOverride,
                baseGaze = workingGaze,
                baseHeadTilt = workingHeadTilt,
                selection = controlSelectionPreview,
                localFrame = previewTickFrame,
                fps = fps.coerceAtLeast(12),
                speedMultiplier = clip.speedMultiplier
            )
        } else {
            BlendedPreviewPose(
                poseOffsets = activeCombinedBaseOffsets,
                expression = workingExpression,
                facialPresetId = evalTransform.facialPresetId,
                mouthOverride = evalTransform.mouthShapeOverride ?: MouthShapeOverride.AUTO,
                eyeOverride = evalTransform.eyeShapeOverride ?: EyeShapeOverride.AUTO,
                eyebrowOverride = evalTransform.eyebrowShapeOverride ?: EyebrowShapeOverride.AUTO,
                handPose = workingHandPose,
                blinkOverride = evalTransform.blinkOverride,
                gazeTarget = workingGaze,
                headTilt = workingHeadTilt
            )
        }
    }

    Dialog(
        onDismissRequest = {
            previewAction = null
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            color = StudioObsidian,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .testTag("ik_studio_modal")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ─── 1. TOP STUDIO HEADER BAR ───
                Surface(
                    color = StudioCharcoal,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                previewAction = null
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("ik_studio_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close IK Studio",
                                tint = StudioTextPrimary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "IK Studio",
                                color = StudioTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "${rig.name} • F$currentFrame",
                                color = StudioTextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Quick "+ Pose" button to save current pose from any tab
                        OutlinedButton(
                            onClick = { showSavePoseDialog = true },
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("ik_studio_quick_save_pose_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BookmarkAdd,
                                contentDescription = "Save pose to library",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Save Pose", color = ElectricCyan, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // Primary Commit Save button
                        Button(
                            onClick = {
                                previewAction = null
                                onCommitIkPose(
                                    clip.id,
                                    activeCombinedBaseOffsets,
                                    workingExpression,
                                    workingHandPose
                                )
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = StudioObsidian
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("ik_studio_commit_save_btn")
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // ─── 2. INTERACTIVE IK RIG VIEWPORT (UPPER ~42%) ───
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.44f)
                        .background(Color(0xFF0B111B))
                ) {
                    IkStudioInteractiveCanvas(
                        clip = clip,
                        rig = rig,
                        currentFrame = currentFrame,
                        fps = fps,
                        displayedPose = displayedPose,
                        customBitmap = customBitmap,
                        atlasSlices = atlasSlices,
                        viewportZoom = viewportZoom,
                        viewportPanX = viewportPanX,
                        viewportPanY = viewportPanY,
                        isPanMode = isPanMode,
                        isPreviewingAction = isControlPreviewActive,
                        previewTickFrame = previewTickFrame,
                        activeDragHandle = activeDragHandle,
                        isDraggingGaze = isDraggingGaze,
                        ikFlipBend = ikFlipBend,
                        ikJointLimitsEnabled = ikJointLimitsEnabled,
                        onPanDelta = { dx, dy ->
                            viewportPanX = (viewportPanX + dx).coerceIn(-0.45f, 0.45f)
                            viewportPanY = (viewportPanY + dy).coerceIn(-0.45f, 0.45f)
                        },
                        onActiveDragChanged = { handle, gaze ->
                            activeDragHandle = handle
                            isDraggingGaze = gaze
                        },
                        onOffsetsDragged = { updated ->
                            workingOffsets = updated
                        },
                        onGazeDragged = { gx, gy ->
                            workingGaze = GazeTarget(gx.coerceIn(-1f, 1f), gy.coerceIn(-1f, 1f))
                        }
                    )

                    // Top-Left Live Preview Mode Indicator Badge
                    if (isControlPreviewActive) {
                        val badgeLabel = previewAction?.let { "${it.name} (${it.track.displayName})" }
                            ?: "${controlSelectionPreview.activeCount} Control Layers Active"
                        Surface(
                            color = StudioObsidian.copy(alpha = 0.90f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AmberKeyframe),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .testTag("ik_action_preview_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(AmberKeyframe)
                                )
                                Text(
                                    text = "LIVE CONTROL: $badgeLabel",
                                    color = AmberKeyframe,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else if (activeDragHandle != null || isDraggingGaze) {
                        Surface(
                            color = StudioObsidian.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ElectricCyan),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (isDraggingGaze) {
                                    "Gaze (${(workingGaze.x * 100).roundToInt()}%, ${(workingGaze.y * 100).roundToInt()}%)"
                                } else {
                                    "Posing: ${activeDragHandle?.label ?: ""}"
                                },
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // Right-Side Floating Viewport Rail (Pan, Reset, Zoom +, Zoom -)
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ViewportRailButton(
                            icon = Icons.Rounded.PanTool,
                            contentDescription = "Toggle viewport pan mode",
                            isActive = isPanMode,
                            testTag = "ik_viewport_pan_btn",
                            onClick = { isPanMode = !isPanMode }
                        )
                        ViewportRailButton(
                            icon = Icons.Rounded.RestartAlt,
                            contentDescription = "Reset pose and viewport",
                            isActive = false,
                            testTag = "ik_viewport_reset_btn",
                            onClick = {
                                viewportZoom = 1.15f
                                viewportPanX = 0f
                                viewportPanY = 0f
                                workingOffsets = PoseKeyframeOffsets()
                                workingGaze = GazeTarget()
                                workingHeadTilt = 0f
                            }
                        )
                        ViewportRailButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "Zoom in viewport",
                            isActive = false,
                            testTag = "ik_viewport_zoom_in_btn",
                            onClick = { viewportZoom = (viewportZoom + 0.2f).coerceIn(0.65f, 2.4f) }
                        )
                        ViewportRailButton(
                            icon = Icons.Rounded.Remove,
                            contentDescription = "Zoom out viewport",
                            isActive = false,
                            testTag = "ik_viewport_zoom_out_btn",
                            onClick = { viewportZoom = (viewportZoom - 0.2f).coerceIn(0.65f, 2.4f) }
                        )
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // ─── 3. 3-TAB BOTTOM WORKSPACE (Timeline | Actions | Poses) ───
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = StudioCharcoal,
                    contentColor = ElectricCyan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("ik_studio_tab_timeline"),
                        text = { Text("Timeline", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("ik_studio_tab_actions"),
                        text = { Text("Control", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("ik_studio_tab_poses"),
                        text = {
                            Text(
                                text = "Poses (${savedPoses.size})",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.56f)
                        .background(StudioObsidian)
                ) {
                    when (activeTab) {
                        0 -> IkTimelineWorkspaceTab(
                            currentFrame = currentFrame,
                            workingHeadTilt = workingHeadTilt,
                            workingGaze = workingGaze,
                            workingExpression = workingExpression,
                            workingHandPose = workingHandPose,
                            ikFlipBend = ikFlipBend,
                            ikJointLimitsEnabled = ikJointLimitsEnabled,
                            onStepFrame = onStepFrame,
                            onHeadTiltChange = { workingHeadTilt = it },
                            onResetGaze = { workingGaze = GazeTarget() },
                            onToggleFlipBend = { ikFlipBend = !ikFlipBend },
                            onToggleJointLimits = { ikJointLimitsEnabled = !ikJointLimitsEnabled },
                            onMirrorPose = { workingOffsets = IkRigSolver.mirrorPoseOffsets(workingOffsets) },
                            onResetPose = {
                                workingOffsets = PoseKeyframeOffsets()
                                workingGaze = GazeTarget()
                                workingHeadTilt = 0f
                            },
                            onStampKeyframe = {
                                onCommitIkPose(
                                    clip.id,
                                    activeCombinedBaseOffsets,
                                    workingExpression,
                                    workingHandPose
                                )
                            }
                        )

                        1 -> ModularControlLibraryPanel(
                            initialSelection = controlSelectionPreview,
                            onSelectionPreviewChanged = { nextSel, tapped ->
                                controlSelectionPreview = nextSel
                                previewAction = tapped
                            },
                            onApplyChanges = { finalSel ->
                                onApplyModularControl(clip.id, finalSel)
                                onDismiss()
                            }
                        )

                        2 -> IkPoseLibraryPanel(
                            savedPoses = savedPoses,
                            thumbnailLoader = thumbnailLoader,
                            onOpenSaveDialog = { showSavePoseDialog = true },
                            onApplyPose = { pose ->
                                // Update live canvas immediately + commit with Undo snapshot to timeline
                                workingOffsets = pose.ikJointOffsets
                                workingGaze = pose.gazeTarget
                                workingHeadTilt = pose.headTilt
                                workingExpression = pose.expression
                                workingHandPose = pose.handPose
                                onApplySavedPose(pose)
                            },
                            onRenamePose = onRenameSavedPose,
                            onDeletePose = onDeleteSavedPose,
                            onDeletePoses = onDeleteSavedPoses
                        )
                    }
                }
            }
        }
    }

    if (showSavePoseDialog) {
        SaveOrRenamePoseDialog(
            title = "Save Current IK Pose",
            confirmLabel = "Save Pose",
            initialName = "${rig.name.substringBefore(" ")} Pose ${savedPoses.size + 1}",
            initialCategory = PoseCategory.CUSTOM,
            onDismiss = { showSavePoseDialog = false },
            onConfirm = { name, category ->
                onSavePoseToLibrary(
                    name,
                    category,
                    workingOffsets,
                    workingGaze,
                    workingHeadTilt,
                    workingExpression,
                    workingHandPose
                )
                showSavePoseDialog = false
                activeTab = 2 // Jump to Poses tab so animator sees their new saved pose immediately!
            }
        )
    }
}

@Composable
private fun ViewportRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (isActive) ElectricCyanDim else StudioCharcoal.copy(alpha = 0.88f),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (isActive) ElectricCyan else StudioBorderSubtle),
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) ElectricCyan else StudioTextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun IkStudioInteractiveCanvas(
    clip: TimelineClip,
    rig: CharacterRigDefinition,
    currentFrame: Int,
    fps: Int,
    displayedPose: BlendedPreviewPose,
    customBitmap: Bitmap?,
    atlasSlices: Map<AtlasPartSlot, Bitmap>?,
    viewportZoom: Float,
    viewportPanX: Float,
    viewportPanY: Float,
    isPanMode: Boolean,
    isPreviewingAction: Boolean,
    previewTickFrame: Int = 0,
    activeDragHandle: IkHandleId?,
    isDraggingGaze: Boolean,
    ikFlipBend: Boolean,
    ikJointLimitsEnabled: Boolean = true,
    onPanDelta: (Float, Float) -> Unit,
    onActiveDragChanged: (IkHandleId?, Boolean) -> Unit,
    onOffsetsDragged: (PoseKeyframeOffsets) -> Unit,
    onGazeDragged: (Float, Float) -> Unit
) {
    val latestDisplayed by rememberUpdatedState(displayedPose)
    val latestZoom by rememberUpdatedState(viewportZoom)
    val latestPanX by rememberUpdatedState(viewportPanX)
    val latestPanY by rememberUpdatedState(viewportPanY)
    val latestPanMode by rememberUpdatedState(isPanMode)
    val latestPreviewing by rememberUpdatedState(isPreviewingAction)
    val latestFlipBend by rememberUpdatedState(ikFlipBend)
    val latestJointLimits by rememberUpdatedState(ikJointLimitsEnabled)

    val phase = remember(clip, currentFrame) {
        val local = (currentFrame - clip.startFrame).coerceAtLeast(0)
        val cycle = 30f / clip.speedMultiplier.coerceIn(0.25f, 4f)
        ((local % cycle) / cycle).coerceIn(0f, 1f)
    }
    val basePose = remember(clip.animationPresetType, phase) {
        ProceduralRigRenderer.computePose(clip.animationPresetType, phase)
    }

    var localDragOffsets by remember { mutableStateOf<PoseKeyframeOffsets?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(clip.id, rig.id, phase) {
                var draggingHandle: IkHandleId? = null
                var draggingGaze = false

                detectDragGestures(
                    onDragStart = { startPos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        if (latestPanMode || latestPreviewing) {
                            draggingHandle = null
                            draggingGaze = false
                            return@detectDragGestures
                        }

                        val cx = w * (0.5f + latestPanX)
                        val cy = h * (0.54f + latestPanY)
                        val virtualTransform = EvaluatedTransform(
                            x = cx / w,
                            y = cy / h,
                            scaleX = latestZoom,
                            scaleY = latestZoom,
                            rotation = 0f,
                            opacity = 1f,
                            poseOffsets = latestDisplayed.poseOffsets
                        )
                        val posed = basePose.withOffsets(latestDisplayed.poseOffsets, latestDisplayed.expression)
                        val (handles, anchors) = IkRigSolver.computeStageIkHandlesWithAnchors(
                            clip = clip,
                            rig = rig,
                            transform = virtualTransform,
                            pose = posed,
                            stageWidth = w,
                            stageHeight = h
                        )

                        // Check Gaze Target Handle first
                        val gazeAnchorX = (anchors.neckNx * w) + (latestDisplayed.gazeTarget.x * 74f) + 56f
                        val gazeAnchorY = (anchors.neckNy * h) - 24f + (latestDisplayed.gazeTarget.y * 56f)
                        if (hypot(startPos.x - gazeAnchorX, startPos.y - gazeAnchorY) <= 44f) {
                            draggingGaze = true
                            draggingHandle = null
                            onActiveDragChanged(null, true)
                            return@detectDragGestures
                        }

                        // Hit-test closest IK handle within generous 46px radius
                        var best: IkHandleId? = null
                        var bestDist = 46f
                        for (hdl in handles) {
                            val dist = hypot((startPos.x - hdl.nx * w).toDouble(), (startPos.y - hdl.ny * h).toDouble()).toFloat()
                            if (dist < bestDist) {
                                bestDist = dist
                                best = hdl.id
                            }
                        }
                        draggingHandle = best
                        draggingGaze = false
                        localDragOffsets = latestDisplayed.poseOffsets
                        onActiveDragChanged(best, false)
                    },
                    onDragEnd = {
                        draggingHandle = null
                        draggingGaze = false
                        localDragOffsets = null
                        onActiveDragChanged(null, false)
                    },
                    onDragCancel = {
                        draggingHandle = null
                        draggingGaze = false
                        localDragOffsets = null
                        onActiveDragChanged(null, false)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    if (latestPanMode) {
                        onPanDelta(dragAmount.x / w, dragAmount.y / h)
                        return@detectDragGestures
                    }
                    if (latestPreviewing) return@detectDragGestures

                    val cx = w * (0.5f + latestPanX)
                    val cy = h * (0.54f + latestPanY)
                    val virtualTransform = EvaluatedTransform(
                        x = cx / w,
                        y = cy / h,
                        scaleX = latestZoom,
                        scaleY = latestZoom,
                        rotation = 0f,
                        opacity = 1f,
                        poseOffsets = localDragOffsets ?: latestDisplayed.poseOffsets
                    )

                    if (draggingGaze) {
                        val posed = basePose.withOffsets(latestDisplayed.poseOffsets, latestDisplayed.expression)
                        val (_, anchors) = IkRigSolver.computeStageIkHandlesWithAnchors(
                            clip, rig, virtualTransform, posed, w, h
                        )
                        val originX = anchors.neckNx * w + 56f
                        val originY = anchors.neckNy * h - 24f
                        val gx = ((change.position.x - originX) / 74f).coerceIn(-1f, 1f)
                        val gy = ((change.position.y - originY) / 56f).coerceIn(-1f, 1f)
                        onGazeDragged(gx, gy)
                    } else if (draggingHandle != null) {
                        val solved = IkRigSolver.solveIkDragToOffsets(
                            handleId = draggingHandle!!,
                            targetNx = (change.position.x / w).coerceIn(0.02f, 0.98f),
                            targetNy = (change.position.y / h).coerceIn(0.02f, 0.98f),
                            clip = clip,
                            rig = rig,
                            transform = virtualTransform,
                            basePose = basePose,
                            currentOffsets = localDragOffsets ?: latestDisplayed.poseOffsets,
                            stageWidth = w,
                            stageHeight = h,
                            bendFlip = latestFlipBend,
                            enforceJointLimits = latestJointLimits
                        )
                        localDragOffsets = solved
                        onOffsetsDragged(solved)
                    }
                }
            }
            .testTag("ik_studio_rig_canvas")
    ) {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val cx = w * (0.5f + viewportPanX)
        val cy = h * (0.54f + viewportPanY)

        // Subtle studio grid lines
        val gridStep = 36f
        val gridColor = Color(0xFF1A2436)
        var gx = 0f
        while (gx < w) {
            drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            gx += gridStep
        }
        var gy = 0f
        while (gy < h) {
            drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            gy += gridStep
        }

        // Ground baseline
        val groundY = cy + (min(w, h) / 420f) * 86f * viewportZoom
        drawLine(
            color = ElectricCyan.copy(alpha = 0.25f),
            start = Offset(w * 0.12f, groundY),
            end = Offset(w * 0.88f, groundY),
            strokeWidth = 2f
        )

        val baseScale = (min(w, h) / 420f) * viewportZoom
        val effectiveAnimFrame = if (isPreviewingAction) previewTickFrame else currentFrame
        val organicFace = FacialExpressionEngine.evaluateOrganicFace(
            activePresetId = displayedPose.facialPresetId,
            previousPresetId = null,
            fallbackExpression = displayedPose.expression,
            framesSinceKey = 18,
            globalFrame = effectiveAnimFrame,
            fps = fps,
            blinkOverride = displayedPose.blinkOverride,
            mouthOverride = displayedPose.mouthOverride,
            eyeOverride = displayedPose.eyeOverride,
            eyebrowOverride = displayedPose.eyebrowOverride
        ).copy(
            eyeLookX = displayedPose.gazeTarget.x.coerceIn(-1f, 1f),
            eyeLookY = displayedPose.gazeTarget.y.coerceIn(-1f, 1f)
        )

        // Render active character rig via ProceduralRigRenderer
        drawIntoCanvas { composeCanvas ->
            val nativeCanvas = composeCanvas.nativeCanvas
            nativeCanvas.save()
            nativeCanvas.translate(cx, cy)
            if (clip.flipHorizontal) nativeCanvas.scale(-1f, 1f)
            ProceduralRigRenderer.drawCharacter(
                canvas = nativeCanvas,
                rig = rig,
                orientation = clip.orientation,
                preset = clip.animationPresetType,
                phase = phase,
                rigScale = baseScale,
                alpha = 1f,
                customBitmap = customBitmap,
                poseOffsets = displayedPose.poseOffsets,
                facialExpression = displayedPose.expression,
                handPose = displayedPose.handPose,
                blinkOverride = displayedPose.blinkOverride,
                atlasSlices = atlasSlices,
                organicFace = organicFace,
                globalFrame = effectiveAnimFrame,
                fps = fps,
                clipSeed = clip.id.hashCode(),
                bodyBobDelta = displayedPose.bodyBobDelta,
                squashOverrideX = displayedPose.squashX,
                squashOverrideY = displayedPose.squashY,
                overrideHandTrack = displayedPose.overrideHandTrack,
                overrideLegTrack = displayedPose.overrideLegTrack
            )
            nativeCanvas.restore()
        }

        // Draw IK Skeleton Overlay & Concentric Handles (dimmed gently during preview mode)
        val virtualTransform = EvaluatedTransform(
            x = cx / w,
            y = cy / h,
            scaleX = viewportZoom,
            scaleY = viewportZoom,
            rotation = 0f,
            opacity = 1f,
            poseOffsets = displayedPose.poseOffsets
        )
        val isolatedBaseForHandles = basePose.copy(
            leftArmDeg = if (displayedPose.overrideHandTrack) 0f else basePose.leftArmDeg,
            leftForearmDeg = if (displayedPose.overrideHandTrack) 0f else basePose.leftForearmDeg,
            rightArmDeg = if (displayedPose.overrideHandTrack) 0f else basePose.rightArmDeg,
            rightForearmDeg = if (displayedPose.overrideHandTrack) 0f else basePose.rightForearmDeg,
            leftLegDeg = if (displayedPose.overrideLegTrack) 0f else basePose.leftLegDeg,
            leftKneeDeg = if (displayedPose.overrideLegTrack) 0f else basePose.leftKneeDeg,
            rightLegDeg = if (displayedPose.overrideLegTrack) 0f else basePose.rightLegDeg,
            rightKneeDeg = if (displayedPose.overrideLegTrack) 0f else basePose.rightKneeDeg,
            bodyTiltDeg = if (displayedPose.overrideLegTrack) 0f else basePose.bodyTiltDeg,
            bodyBobY = if (displayedPose.overrideLegTrack) 0f else basePose.bodyBobY
        )
        val posed = isolatedBaseForHandles
            .withOffsets(displayedPose.poseOffsets, displayedPose.expression)
            .let { it.copy(bodyBobY = it.bodyBobY + displayedPose.bodyBobDelta) }
        val (handles, anchors) = IkRigSolver.computeStageIkHandlesWithAnchors(
            clip = clip,
            rig = rig,
            transform = virtualTransform,
            pose = posed,
            stageWidth = w,
            stageHeight = h
        )
        val handleMap = handles.associateBy { it.id }
        val overlayAlpha = if (isPreviewingAction) 0.25f else 0.92f
        val boneColor = ElectricCyan.copy(alpha = 0.55f * overlayAlpha)

        fun pt(nx: Float, ny: Float) = Offset(nx * w, ny * h)
        fun hPt(id: IkHandleId): Offset? = handleMap[id]?.let { Offset(it.nx * w, it.ny * h) }

        val neckPt = pt(anchors.neckNx, anchors.neckNy)
        val lShPt = pt(anchors.leftShoulderNx, anchors.leftShoulderNy)
        val rShPt = pt(anchors.rightShoulderNx, anchors.rightShoulderNy)
        val lHpPt = pt(anchors.leftHipNx, anchors.leftHipNy)
        val rHpPt = pt(anchors.rightHipNx, anchors.rightHipNy)

        hPt(IkHandleId.HEAD)?.let { drawLine(boneColor, neckPt, it, 2.5f) }
        hPt(IkHandleId.TORSO)?.let { drawLine(boneColor, neckPt, it, 2.5f) }
        drawLine(boneColor, lShPt, rShPt, 2f)
        drawLine(boneColor, lHpPt, rHpPt, 2f)
        hPt(IkHandleId.LEFT_ELBOW)?.let { el ->
            drawLine(boneColor, lShPt, el, 2.5f)
            hPt(IkHandleId.LEFT_HAND)?.let { drawLine(boneColor, el, it, 2.5f) }
        }
        hPt(IkHandleId.RIGHT_ELBOW)?.let { el ->
            drawLine(boneColor, rShPt, el, 2.5f)
            hPt(IkHandleId.RIGHT_HAND)?.let { drawLine(boneColor, el, it, 2.5f) }
        }
        hPt(IkHandleId.LEFT_KNEE)?.let { kn ->
            drawLine(boneColor, lHpPt, kn, 2.5f)
            hPt(IkHandleId.LEFT_FOOT)?.let { drawLine(boneColor, kn, it, 2.5f) }
        }
        hPt(IkHandleId.RIGHT_KNEE)?.let { kn ->
            drawLine(boneColor, rHpPt, kn, 2.5f)
            hPt(IkHandleId.RIGHT_FOOT)?.let { drawLine(boneColor, kn, it, 2.5f) }
        }

        // Draw Concentric IK Joint Handles
        for (hdl in handles) {
            val pos = Offset(hdl.nx * w, hdl.ny * h)
            val isSelected = activeDragHandle == hdl.id
            val ringColor = Color(hdl.color).copy(alpha = overlayAlpha)
            val radius = if (isSelected) 16f else 12f
            drawCircle(
                color = StudioObsidian.copy(alpha = 0.85f * overlayAlpha),
                radius = radius,
                center = pos
            )
            drawCircle(
                color = ringColor,
                radius = radius,
                center = pos,
                style = Stroke(width = if (isSelected) 3.5f else 2.2f)
            )
            drawCircle(
                color = if (isSelected) AmberKeyframe else Color.White.copy(alpha = overlayAlpha),
                radius = if (isSelected) 5.5f else 4f,
                center = pos
            )
        }

        // Draw Eye-Look (Gaze) Target Crosshair Handle connected by dashed line to eyes
        if (!isPreviewingAction) {
            val eyeOrigin = Offset(neckPt.x, neckPt.y - 24f)
            val gazeCenter = Offset(
                x = (neckPt.x + 56f + displayedPose.gazeTarget.x * 74f).coerceIn(24f, w - 58f),
                y = (neckPt.y - 24f + displayedPose.gazeTarget.y * 56f).coerceIn(24f, h - 24f)
            )
            val gazeColor = if (isDraggingGaze) AmberKeyframe else Color(0xFF38BDF8)
            drawLine(
                color = gazeColor.copy(alpha = 0.7f),
                start = eyeOrigin,
                end = gazeCenter,
                strokeWidth = 1.8f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            )
            drawCircle(color = StudioObsidian.copy(alpha = 0.85f), radius = 14f, center = gazeCenter)
            drawCircle(color = gazeColor, radius = 14f, center = gazeCenter, style = Stroke(width = 2.2f))
            drawLine(gazeColor, Offset(gazeCenter.x - 8f, gazeCenter.y), Offset(gazeCenter.x + 8f, gazeCenter.y), 1.6f)
            drawLine(gazeColor, Offset(gazeCenter.x, gazeCenter.y - 8f), Offset(gazeCenter.x, gazeCenter.y + 8f), 1.6f)
            drawCircle(color = gazeColor, radius = 3.2f, center = gazeCenter)
        }
    }
}

/**
 * Tab 1: Modular Action Preview Mode (Scrub Before Inserting)
 */
@Composable
private fun IkModularActionsTab(
    selectedTrack: ModularActionTrack,
    previewAction: ModularActionDef?,
    previewProgress: Float,
    isPreviewPlaying: Boolean,
    onSelectTrack: (ModularActionTrack) -> Unit,
    onTapActionCard: (ModularActionDef) -> Unit,
    onTogglePlayPause: () -> Unit,
    onScrubProgress: (Float) -> Unit,
    onInsertAtPlayhead: (ModularActionDef) -> Unit,
    onCancelPreview: () -> Unit
) {
    val actions = remember(selectedTrack) { ModularActionEngine.actionsForTrack(selectedTrack) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ─── INTERACTIVE FLOATING PREVIEW SCRUB BAR (Visible when previewing an action) ───
        if (previewAction != null) {
            val currentPreviewFrame = (previewProgress * previewAction.durationFrames).roundToInt()
            val percent = (previewProgress * 100f).roundToInt()

            Surface(
                color = StudioCharcoal,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.2.dp, AmberKeyframe.copy(alpha = 0.85f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("modular_action_preview_bar")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${previewAction.name} • ${previewAction.track.timelineTrackLabel} Track",
                                color = StudioTextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Frame $currentPreviewFrame / ${previewAction.durationFrames} ($percent%) — Non-destructive preview",
                                color = AmberKeyframe,
                                fontSize = 10.5.sp
                            )
                        }

                        // Play/Pause 30fps Loop Toggle (48dp touch target)
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("action_preview_play_pause_btn")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(ElectricCyanDim)
                            ) {
                                Icon(
                                    imageVector = if (isPreviewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPreviewPlaying) "Pause preview loop" else "Play preview loop",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Manual Frame-by-Frame Scrub Slider (0%..100%)
                    Slider(
                        value = previewProgress,
                        onValueChange = onScrubProgress,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = AmberKeyframe,
                            activeTrackColor = AmberKeyframe,
                            inactiveTrackColor = StudioBorderSubtle
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("action_preview_scrub_slider")
                    )

                    // Insert at Playhead + Cancel Controls (48dp touch targets)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelPreview,
                            border = BorderStroke(1.dp, StudioBorderSubtle),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(0.38f)
                                .height(48.dp)
                                .testTag("action_preview_cancel_btn")
                        ) {
                            Text("Cancel", color = StudioTextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { onInsertAtPlayhead(previewAction) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(0.62f)
                                .height(48.dp)
                                .testTag("action_preview_insert_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddTask,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Insert at Playhead", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Track Filter Chips (Facial | Hands | Legs)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModularActionTrack.entries.forEach { track ->
                val isSelected = selectedTrack == track
                val accent = when (track) {
                    ModularActionTrack.FACIAL -> AmberKeyframe
                    ModularActionTrack.HANDS -> ElectricCyan
                    ModularActionTrack.LEGS -> EmeraldLayer
                }
                Surface(
                    color = if (isSelected) accent.copy(alpha = 0.2f) else StudioPanel,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isSelected) accent else StudioBorderSubtle),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectTrack(track) }
                        .testTag("action_track_tab_${track.name.lowercase()}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = track.displayName,
                            color = if (isSelected) accent else StudioTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2-Column Modular Action Cards Grid (Tap to Preview)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("modular_actions_grid")
        ) {
            items(actions, key = { it.id }) { action ->
                val isPreviewed = previewAction?.id == action.id
                val trackColor = when (action.track) {
                    ModularActionTrack.FACIAL -> AmberKeyframe
                    ModularActionTrack.HANDS -> ElectricCyan
                    ModularActionTrack.LEGS -> EmeraldLayer
                }
                Surface(
                    color = if (isPreviewed) trackColor.copy(alpha = 0.16f) else StudioPanel,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = if (isPreviewed) 1.5.dp else 1.dp,
                        color = if (isPreviewed) trackColor else StudioBorderSubtle
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTapActionCard(action) }
                        .testTag("modular_action_card_${action.id}")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(trackColor)
                                )
                                Text(
                                    text = action.name,
                                    color = StudioTextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${action.durationFrames}f",
                                color = trackColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = if (isPreviewed) "Previewing live — scrub above" else action.subtitle,
                            color = if (isPreviewed) trackColor else StudioTextSecondary,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 0: Compact IK Timeline & Fine Pose Controls
 */
@Composable
private fun IkTimelineWorkspaceTab(
    currentFrame: Int,
    workingHeadTilt: Float,
    workingGaze: GazeTarget,
    workingExpression: FacialExpression,
    workingHandPose: HandPoseType,
    ikFlipBend: Boolean,
    ikJointLimitsEnabled: Boolean = true,
    onStepFrame: (Int) -> Unit,
    onHeadTiltChange: (Float) -> Unit,
    onResetGaze: () -> Unit,
    onToggleFlipBend: () -> Unit,
    onToggleJointLimits: () -> Unit = {},
    onMirrorPose: () -> Unit,
    onResetPose: () -> Unit,
    onStampKeyframe: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Transport & Keyframe Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedIconButton(
                onClick = { onStepFrame(-1) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("ik_timeline_prev_frame_btn")
            ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous frame", tint = StudioTextPrimary)
            }

            Surface(
                color = StudioPanel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StudioBorderSubtle),
                modifier = Modifier.height(48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = "Frame $currentFrame",
                        color = ElectricCyan,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedIconButton(
                onClick = { onStepFrame(1) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("ik_timeline_next_frame_btn")
            ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next frame", tint = StudioTextPrimary)
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onStampKeyframe,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberKeyframe,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("ik_timeline_stamp_key_btn")
            ) {
                Icon(Icons.Rounded.Diamond, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stamp Key", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        // Quick Pose Rig Utility Row (Flip Bend | Limits | Mirror L<->R | Reset)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onToggleFlipBend,
                border = BorderStroke(1.dp, if (ikFlipBend) AmberKeyframe else StudioBorderSubtle),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("ik_timeline_flip_bend_btn")
            ) {
                Text(
                    text = if (ikFlipBend) "Flipped" else "Flip Bend",
                    color = if (ikFlipBend) AmberKeyframe else StudioTextPrimary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onToggleJointLimits,
                border = BorderStroke(1.dp, if (ikJointLimitsEnabled) ElectricCyan else AmberKeyframe),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("ik_timeline_joint_limits_btn")
            ) {
                Text(
                    text = if (ikJointLimitsEnabled) "Limits: ON" else "Limits: Free",
                    color = if (ikJointLimitsEnabled) ElectricCyan else AmberKeyframe,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onMirrorPose,
                border = BorderStroke(1.dp, StudioBorderSubtle),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("ik_timeline_mirror_btn")
            ) {
                Text("Mirror", color = StudioTextPrimary, fontSize = 11.sp, maxLines = 1)
            }

            OutlinedButton(
                onClick = onResetPose,
                border = BorderStroke(1.dp, CoralPlayhead.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("ik_timeline_reset_btn")
            ) {
                Text("Reset", color = CoralPlayhead, fontSize = 11.sp, maxLines = 1)
            }
        }

        // Head Tilt Slider Card
        Surface(
            color = StudioPanel,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Head Tilt: ${workingHeadTilt.roundToInt()}°",
                        color = StudioTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { onHeadTiltChange(0f) },
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Text("Center", color = ElectricCyan, fontSize = 11.sp)
                    }
                }
                Slider(
                    value = workingHeadTilt,
                    onValueChange = onHeadTiltChange,
                    valueRange = -45f..45f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan,
                        inactiveTrackColor = StudioBorderSubtle
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ik_head_tilt_slider")
                )
            }
        }

        // Active Tracks Readout Summary
        Surface(
            color = StudioPanel,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Expression: ${workingExpression.displayName} • Hands: ${workingHandPose.displayName}",
                        color = StudioTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Gaze Target: X=${(workingGaze.x * 100).roundToInt()}%, Y=${(workingGaze.y * 100).roundToInt()}%",
                        color = StudioTextSecondary,
                        fontSize = 11.sp
                    )
                }
                TextButton(
                    onClick = onResetGaze,
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Text("Reset Gaze", color = ElectricCyan, fontSize = 11.sp)
                }
            }
        }
    }
}
