package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.TimelineClip
import com.example.ui.theme.*

data class BottomToolItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color = StudioTextPrimary,
    val isActive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Context-aware offline Bottom Editing Toolbar (strictly ZERO AI tools).
 * Adapts to selected character/image/clip while keeping all core creation tools one tap away.
 */
@Composable
fun BottomEditingToolbar(
    selectedClip: TimelineClip?,
    isDrawingMode: Boolean,
    isIkPosingMode: Boolean = false,
    isCameraKeyMode: Boolean = false,
    showMotionPaths: Boolean,
    onOpenGuide: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPresetsForSelectedChar: () -> Unit,
    onToggleIkPosing: () -> Unit = {},
    onOpenCharacterActingProps: () -> Unit = {},
    onOpenCameraChroma: () -> Unit = {},
    onOpenMedia: () -> Unit,
    onOpenTemplates: () -> Unit,
    onQuickAddImage: () -> Unit,
    onQuickAddAudio: () -> Unit,
    onOpenShapes: () -> Unit,
    onOpenText: () -> Unit,
    onToggleDrawing: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenLayers: () -> Unit,
    onOpenTransform: () -> Unit,
    onOpenSpeed: () -> Unit,
    onToggleMotionPath: () -> Unit,
    onOpenTransitions: () -> Unit,
    onSplitClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onDeleteClip: () -> Unit
) {
    val isCharSelected = selectedClip?.characterId != null

    val tools = buildList {
        // Contextual Selection Tools first when an object/clip is active
        if (selectedClip != null) {
            if (isCharSelected) {
                add(BottomToolItem("presets", "Presets", Icons.Rounded.DirectionsRun, ElectricCyan, false, onOpenPresetsForSelectedChar))
                add(BottomToolItem("ik_pose", "IK Pose", Icons.Rounded.Hub, ElectricCyan, isIkPosingMode, onToggleIkPosing))
                add(BottomToolItem("acting_props", "Face & Prop", Icons.Rounded.EmojiEmotions, VioletLayer, false, onOpenCharacterActingProps))
            }
            add(BottomToolItem("transform", "Transform", Icons.Rounded.OpenWith, ElectricCyan, false, onOpenTransform))
            add(BottomToolItem("speed", "Speed", Icons.Rounded.Speed, AmberKeyframe, false, onOpenSpeed))
            add(BottomToolItem("path", "Path", Icons.Rounded.Timeline, if (showMotionPaths) AmberKeyframe else StudioTextPrimary, showMotionPaths, onToggleMotionPath))
            add(BottomToolItem("transitions", "Transitions", Icons.Rounded.AutoAwesomeMotion, VioletLayer, false, onOpenTransitions))
            add(BottomToolItem("split", "Split", Icons.Rounded.ContentCut, StudioTextPrimary, false, onSplitClip))
            add(BottomToolItem("duplicate", "Duplicate", Icons.Rounded.ContentCopy, StudioTextPrimary, false, onDuplicateClip))
            add(BottomToolItem("flip", "Flip", Icons.Rounded.Flip, StudioTextPrimary, false, onFlipHorizontal))
            add(BottomToolItem("delete", "Delete", Icons.Rounded.DeleteOutline, CoralPlayhead, false, onDeleteClip))
        }

        // Core Offline Studio Creation Tools
        add(BottomToolItem("character", "Character", Icons.Rounded.Groups, ElectricCyan, false, onOpenCharacters))
        add(BottomToolItem("camera_chroma", "Cam/Chroma", Icons.Rounded.Videocam, EmeraldLayer, isCameraKeyMode, onOpenCameraChroma))
        add(BottomToolItem("media", "Media", Icons.Rounded.PermMedia, VioletLayer, false, onOpenMedia))
        add(BottomToolItem("add_image", "Add Image", Icons.Rounded.AddPhotoAlternate, EmeraldLayer, false, onQuickAddImage))
        add(BottomToolItem("add_audio", "Add Audio", Icons.Rounded.MusicNote, EmeraldLayer, false, onQuickAddAudio))
        add(BottomToolItem("shapes", "Shapes", Icons.Rounded.Category, OrangeLayer, false, onOpenShapes))
        add(BottomToolItem("text", "Text", Icons.Rounded.Title, AmberKeyframe, false, onOpenText))
        add(BottomToolItem("draw", "Draw", Icons.Rounded.Brush, PinkLayer, isDrawingMode, onToggleDrawing))
        add(BottomToolItem("background", "Background", Icons.Rounded.Wallpaper, SkyLayer, false, onOpenBackground))
        add(BottomToolItem("layers", "Layer", Icons.Rounded.Layers, StudioTextPrimary, false, onOpenLayers))
        add(BottomToolItem("templates", "Templates", Icons.Rounded.DashboardCustomize, StudioTextSecondary, false, onOpenTemplates))
        add(BottomToolItem("guide", "Guide", Icons.Rounded.MenuBook, StudioTextSecondary, false, onOpenGuide))
    }

    Surface(
        color = StudioObsidian,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tools, key = { it.id }) { tool ->
                Surface(
                    color = if (tool.isActive) ElectricCyanDim else StudioPanel,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (tool.isActive) ElectricCyan else StudioBorderSubtle
                    ),
                    modifier = Modifier
                        .widthIn(min = 58.dp)
                        .height(52.dp)
                        .clickable(onClick = tool.onClick)
                        .testTag("toolbar_${tool.id}_btn")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tool.icon,
                            contentDescription = tool.label,
                            tint = tool.tint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tool.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tool.isActive) StudioTextPrimary else StudioTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
