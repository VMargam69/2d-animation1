package com.example.ui.screens.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.TimelineClip
import com.example.ui.theme.*

private enum class DockSubMenu {
    NONE,
    // Level 1 (Default Studio Dock) sub-groups
    ADD,
    CAMERA,
    AUDIO,
    SCENES,
    CANVAS,
    // Level 2 (Character selected) sub-groups
    CHAR_POSE,
    CHAR_MOTION,
    CHAR_EDIT,
    // Level 2 (Generic clip selected) sub-groups
    CLIP_EDIT,
    CLIP_MOTION,
    CLIP_STYLE
}

private data class DockToolAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val isActive: Boolean = false,
    val accentTint: Color? = null,
    val onClick: () -> Unit
)

/**
 * CapCut Pro-style Flat Hierarchical Contextual Bottom Dock (strictly ZERO AI tools):
 * - Sleek flat elevated surface (#141923) with crisp 22dp icons and 1-word 10.5sp labels (no boxed cards).
 * - Level 1 (nothing selected): Add | Camera | Draw | Audio | Scenes | Canvas.
 * - Level 2 (clip/character selected): smoothly swaps in contextual groups with a pinned '<' back button.
 */
@Composable
fun BottomEditingToolbar(
    selectedClip: TimelineClip?,
    isDrawingMode: Boolean,
    isIkPosingMode: Boolean = false,
    isCameraKeyMode: Boolean = false,
    onionSkinEnabled: Boolean = false,
    showMotionPaths: Boolean,
    showGridOverlay: Boolean = false,
    isAudioMuted: Boolean = false,
    hasActiveVfx: Boolean = false,
    onDeselectClip: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPresetsForSelectedChar: () -> Unit,
    onToggleIkPosing: () -> Unit,
    onStampPoseKey: () -> Unit,
    onStepAdjacentKeyframe: (Boolean) -> Unit,
    onResetPoseAtCurrentFrame: () -> Unit,
    onOpenCharacterActingProps: () -> Unit,
    onToggleCameraKeyMode: () -> Unit,
    onOpenCameraChroma: () -> Unit,
    onOpenSceneVfx: () -> Unit = {},
    onOpenEasingCurve: () -> Unit = {},
    onOpenMedia: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddScene: () -> Unit,
    onDuplicateScene: () -> Unit = {},
    onQuickAddImage: () -> Unit,
    onQuickAddAudio: () -> Unit,
    onToggleAudioMute: () -> Unit,
    onOpenAudioSpeed: () -> Unit,
    onExportWaveformJson: () -> Unit = {},
    onResetAudioTrim: () -> Unit = {},
    onOpenShapes: () -> Unit,
    onOpenText: () -> Unit,
    onToggleDrawing: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenLayers: () -> Unit,
    onToggleOnionSkin: () -> Unit,
    onToggleGridOverlay: () -> Unit,
    onOpenTransform: () -> Unit,
    onOpenSpeed: () -> Unit,
    onToggleMotionPath: () -> Unit,
    onOpenTransitions: () -> Unit,
    onOpenSceneTransitions: () -> Unit,
    onSplitClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onDeleteClip: () -> Unit
) {
    var activeSubMenu by remember { mutableStateOf(DockSubMenu.NONE) }
    val selectedClipId = selectedClip?.id
    val isCharSelected = selectedClip?.characterId != null
    val isAudioSelected = !selectedClip?.audioPath.isNullOrBlank()

    // Reset sub-menu whenever selection context changes
    LaunchedEffect(selectedClipId) {
        activeSubMenu = DockSubMenu.NONE
    }

    val dockModeKey = Triple(selectedClipId != null, if (isCharSelected) 1 else if (isAudioSelected) 2 else 0, activeSubMenu)
    val showBackButton = selectedClip != null || activeSubMenu != DockSubMenu.NONE

    val actions: List<DockToolAction> = when {
        // LEVEL 2: Contextual Selection Dock
        selectedClip != null && isCharSelected -> when (activeSubMenu) {
            DockSubMenu.CHAR_POSE -> listOf(
                DockToolAction("ik_pose", "IK Pose", Icons.Rounded.Hub, isActive = isIkPosingMode, onClick = onToggleIkPosing),
                DockToolAction("pose_key", "Pose Key", Icons.Rounded.Diamond, accentTint = AmberKeyframe, onClick = onStampPoseKey),
                DockToolAction("prev_kf", "Prev KF", Icons.Rounded.SkipPrevious, onClick = { onStepAdjacentKeyframe(false) }),
                DockToolAction("next_kf", "Next KF", Icons.Rounded.SkipNext, onClick = { onStepAdjacentKeyframe(true) }),
                DockToolAction("reset_pose", "Reset", Icons.Rounded.RestartAlt, onClick = onResetPoseAtCurrentFrame)
            )
            DockSubMenu.CHAR_MOTION -> listOf(
                DockToolAction("curve", "Curve", Icons.Rounded.ShowChart, accentTint = ElectricCyan, onClick = onOpenEasingCurve),
                DockToolAction("transform", "Transform", Icons.Rounded.OpenWith, onClick = onOpenTransform),
                DockToolAction("path", "Path", Icons.Rounded.Timeline, isActive = showMotionPaths, onClick = onToggleMotionPath),
                DockToolAction("speed", "Speed", Icons.Rounded.Speed, onClick = onOpenSpeed),
                DockToolAction("transitions", "Transitions", Icons.Rounded.AutoAwesomeMotion, onClick = onOpenTransitions)
            )
            DockSubMenu.CHAR_EDIT -> listOf(
                DockToolAction("split", "Split", Icons.Rounded.ContentCut, onClick = onSplitClip),
                DockToolAction("flip", "Flip", Icons.Rounded.Flip, onClick = onFlipHorizontal),
                DockToolAction("duplicate", "Duplicate", Icons.Rounded.ContentCopy, onClick = onDuplicateClip),
                DockToolAction("delete", "Delete", Icons.Rounded.DeleteOutline, accentTint = CoralPlayhead, onClick = onDeleteClip)
            )
            else -> listOf(
                DockToolAction("pose", "Pose", Icons.Rounded.Hub, isActive = isIkPosingMode, onClick = { activeSubMenu = DockSubMenu.CHAR_POSE }),
                DockToolAction("presets", "Presets", Icons.Rounded.DirectionsRun, onClick = onOpenPresetsForSelectedChar),
                DockToolAction("acting_props", "Face", Icons.Rounded.EmojiEmotions, onClick = onOpenCharacterActingProps),
                DockToolAction("motion", "Motion", Icons.Rounded.Timeline, isActive = showMotionPaths, onClick = { activeSubMenu = DockSubMenu.CHAR_MOTION }),
                DockToolAction("edit", "Edit", Icons.Rounded.Tune, onClick = { activeSubMenu = DockSubMenu.CHAR_EDIT })
            )
        }

        selectedClip != null && isAudioSelected -> listOf(
            DockToolAction("waveform_json", "Wave JSON", Icons.Rounded.DataObject, accentTint = EmeraldLayer, onClick = onExportWaveformJson),
            DockToolAction(
                "volume",
                if (isAudioMuted) "Muted" else "Volume",
                if (isAudioMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                isActive = isAudioMuted,
                onClick = onToggleAudioMute
            ),
            DockToolAction("speed", "Speed", Icons.Rounded.Speed, onClick = onOpenSpeed),
            DockToolAction("reset_trim", "Reset Trim", Icons.Rounded.RestartAlt, onClick = onResetAudioTrim),
            DockToolAction("delete", "Delete", Icons.Rounded.DeleteOutline, accentTint = CoralPlayhead, onClick = onDeleteClip)
        )

        selectedClip != null -> when (activeSubMenu) {
            DockSubMenu.CLIP_EDIT -> listOf(
                DockToolAction("split", "Split", Icons.Rounded.ContentCut, onClick = onSplitClip),
                DockToolAction("duplicate", "Duplicate", Icons.Rounded.ContentCopy, onClick = onDuplicateClip),
                DockToolAction("flip", "Flip", Icons.Rounded.Flip, onClick = onFlipHorizontal),
                DockToolAction("delete", "Delete", Icons.Rounded.DeleteOutline, accentTint = CoralPlayhead, onClick = onDeleteClip)
            )
            DockSubMenu.CLIP_MOTION -> listOf(
                DockToolAction("curve", "Curve", Icons.Rounded.ShowChart, accentTint = ElectricCyan, onClick = onOpenEasingCurve),
                DockToolAction("path", "Path", Icons.Rounded.Timeline, isActive = showMotionPaths, onClick = onToggleMotionPath),
                DockToolAction("speed", "Speed", Icons.Rounded.Speed, onClick = onOpenSpeed)
            )
            DockSubMenu.CLIP_STYLE -> listOf(
                DockToolAction("opacity", "Opacity", Icons.Rounded.Opacity, onClick = onOpenTransform),
                DockToolAction("transitions", "Transitions", Icons.Rounded.AutoAwesomeMotion, onClick = onOpenTransitions)
            )
            else -> listOf(
                DockToolAction("edit", "Edit", Icons.Rounded.ContentCut, onClick = { activeSubMenu = DockSubMenu.CLIP_EDIT }),
                DockToolAction("transform", "Transform", Icons.Rounded.OpenWith, onClick = onOpenTransform),
                DockToolAction("motion", "Motion", Icons.Rounded.Timeline, isActive = showMotionPaths, onClick = { activeSubMenu = DockSubMenu.CLIP_MOTION }),
                DockToolAction("style", "Style", Icons.Rounded.AutoAwesomeMotion, onClick = { activeSubMenu = DockSubMenu.CLIP_STYLE })
            )
        }

        // LEVEL 1: Default Studio Dock (nothing selected)
        else -> when (activeSubMenu) {
            DockSubMenu.ADD -> listOf(
                DockToolAction("character", "Character", Icons.Rounded.Groups, onClick = onOpenCharacters),
                DockToolAction("media", "Media", Icons.Rounded.PermMedia, onClick = onOpenMedia),
                DockToolAction("add_image", "Image", Icons.Rounded.AddPhotoAlternate, onClick = onQuickAddImage),
                DockToolAction("add_audio", "Audio", Icons.Rounded.MusicNote, onClick = onQuickAddAudio),
                DockToolAction("shapes", "Shapes", Icons.Rounded.Category, onClick = onOpenShapes),
                DockToolAction("text", "Text", Icons.Rounded.Title, onClick = onOpenText),
                DockToolAction("background", "BG", Icons.Rounded.Wallpaper, onClick = onOpenBackground)
            )
            DockSubMenu.CAMERA -> listOf(
                DockToolAction("cam_key", "Cam Key", Icons.Rounded.ControlCamera, isActive = isCameraKeyMode, onClick = onToggleCameraKeyMode),
                DockToolAction("camera_chroma", "Chroma", Icons.Rounded.Videocam, onClick = onOpenCameraChroma),
                DockToolAction("transitions", "Transitions", Icons.Rounded.AutoAwesomeMotion, onClick = onOpenSceneTransitions)
            )
            DockSubMenu.AUDIO -> listOf(
                DockToolAction("add_audio", "Add Audio", Icons.Rounded.LibraryMusic, onClick = onQuickAddAudio),
                DockToolAction(
                    "volume",
                    if (isAudioMuted) "Muted" else "Volume",
                    if (isAudioMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    isActive = isAudioMuted,
                    onClick = onToggleAudioMute
                ),
                DockToolAction("audio_speed", "Speed", Icons.Rounded.Speed, onClick = onOpenAudioSpeed),
                DockToolAction("waveform_json", "Wave JSON", Icons.Rounded.DataObject, accentTint = EmeraldLayer, onClick = onExportWaveformJson)
            )
            DockSubMenu.SCENES -> listOf(
                DockToolAction("add_scene", "Add Scene", Icons.Rounded.AddToPhotos, onClick = onAddScene),
                DockToolAction("dup_scene", "Dup Scene", Icons.Rounded.FileCopy, accentTint = AmberKeyframe, onClick = onDuplicateScene),
                DockToolAction("vfx_studio", "VFX", Icons.Rounded.AutoAwesome, isActive = hasActiveVfx, accentTint = VioletLayer, onClick = onOpenSceneVfx),
                DockToolAction("templates", "Templates", Icons.Rounded.DashboardCustomize, onClick = onOpenTemplates),
                DockToolAction("settings", "Settings", Icons.Rounded.Tune, onClick = onOpenSettings)
            )
            DockSubMenu.CANVAS -> listOf(
                DockToolAction("ratio", "Ratio", Icons.Rounded.AspectRatio, onClick = onOpenSettings),
                DockToolAction("layers", "Layers", Icons.Rounded.Layers, onClick = onOpenLayers),
                DockToolAction("onion", "Onion", Icons.Rounded.FilterNone, isActive = onionSkinEnabled, onClick = onToggleOnionSkin),
                DockToolAction("grid", "Grid", Icons.Rounded.GridOn, isActive = showGridOverlay, onClick = onToggleGridOverlay)
            )
            else -> listOf(
                DockToolAction("group_add", "Add", Icons.Rounded.AddCircleOutline, onClick = { activeSubMenu = DockSubMenu.ADD }),
                DockToolAction("vfx", "VFX", Icons.Rounded.AutoAwesome, isActive = hasActiveVfx, accentTint = if (hasActiveVfx) VioletLayer else null, onClick = onOpenSceneVfx),
                DockToolAction("group_camera", "Camera", Icons.Rounded.Videocam, isActive = isCameraKeyMode, onClick = { activeSubMenu = DockSubMenu.CAMERA }),
                DockToolAction("draw", "Draw", Icons.Rounded.Brush, isActive = isDrawingMode, onClick = onToggleDrawing),
                DockToolAction("group_audio", "Audio", Icons.Rounded.GraphicEq, onClick = { activeSubMenu = DockSubMenu.AUDIO }),
                DockToolAction("group_scenes", "Scenes", Icons.Rounded.MovieFilter, onClick = { activeSubMenu = DockSubMenu.SCENES }),
                DockToolAction("group_canvas", "Canvas", Icons.Rounded.AspectRatio, isActive = showGridOverlay, onClick = { activeSubMenu = DockSubMenu.CANVAS })
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioCharcoal)
    ) {
        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pinned '<' Back Button for sub-menus & Level-2 contextual deselection
            if (showBackButton) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(44.dp)
                        .fillMaxHeight()
                        .clickable {
                            if (activeSubMenu != DockSubMenu.NONE) {
                                activeSubMenu = DockSubMenu.NONE
                            } else {
                                onDeselectClip()
                            }
                        }
                        .testTag("toolbar_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = if (activeSubMenu != DockSubMenu.NONE) "Back to tools" else "Deselect clip",
                        tint = StudioTextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                VerticalDivider(
                    color = StudioBorderSubtle,
                    thickness = 1.dp,
                    modifier = Modifier
                        .height(28.dp)
                )
            }

            AnimatedContent(
                targetState = dockModeKey,
                transitionSpec = {
                    (fadeIn() + slideInHorizontally { it / 8 }) togetherWith
                        (fadeOut() + slideOutHorizontally { -it / 8 })
                },
                label = "bottom_dock_level",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) { _ ->
                if (actions.size <= 6) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        actions.forEach { action ->
                            FlatDockToolButton(
                                action = action,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(actions, key = { it.id }) { action ->
                            FlatDockToolButton(
                                action = action,
                                modifier = Modifier
                                    .widthIn(min = 58.dp)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlatDockToolButton(
    action: DockToolAction,
    modifier: Modifier = Modifier
) {
    val contentColor = when {
        action.isActive -> ElectricCyan
        action.accentTint != null -> action.accentTint
        else -> StudioTextPrimary
    }
    val labelColor = when {
        action.isActive -> ElectricCyan
        action.accentTint != null -> action.accentTint
        else -> StudioTextSecondary
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = action.onClick)
            .testTag("toolbar_${action.id}_btn")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = action.label,
                fontSize = 10.5.sp,
                fontWeight = if (action.isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (action.isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .width(18.dp)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ElectricCyan)
            )
        }
    }
}
