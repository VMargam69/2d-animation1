package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ProjectDocument
import com.example.ui.theme.*

/**
 * Compact Single-Row Top Bar (~50dp height) preserving comfortable touch targets
 * and sleek 20dp icons with zero horizontal clipping on 360dp portrait screens.
 */
@Composable
fun EditorTopToolbar(
    project: ProjectDocument,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleOnionSkin: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onOpenExport: () -> Unit,
    onResetProject: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenDirectorScript: () -> Unit = {}
) {
    var overflowOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioCharcoal)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(50.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Back arrow (48dp vertical touch target)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(36.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .testTag("editor_back_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = StudioTextPrimary,
                    modifier = Modifier.size(21.dp)
                )
            }

            // Compact Project Name (single line with ellipsis) + tiny aspect-ratio badge stacked neatly under it
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 3.dp)
                    .testTag("editor_settings_pill")
            ) {
                Text(
                    text = project.name,
                    fontSize = 12.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Surface(
                    color = ElectricCyanDim.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.8.dp, ElectricCyan.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = project.aspectRatioPreset.label,
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.5.dp)
                    )
                }
            }

            // Undo
            CompactTopIconAction(
                icon = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = "Undo",
                tint = if (canUndo) StudioTextPrimary else StudioTextMuted.copy(alpha = 0.45f),
                enabled = canUndo,
                testTag = "editor_undo_btn",
                onClick = onUndo
            )

            // Redo
            CompactTopIconAction(
                icon = Icons.AutoMirrored.Rounded.Redo,
                contentDescription = "Redo",
                tint = if (canRedo) StudioTextPrimary else StudioTextMuted.copy(alpha = 0.45f),
                enabled = canRedo,
                testTag = "editor_redo_btn",
                onClick = onRedo
            )

            // Onion Skin Toggle
            CompactTopIconAction(
                icon = Icons.Rounded.Layers,
                contentDescription = stringResource(R.string.editor_onion_skin),
                tint = if (project.onionSkinEnabled) ElectricCyan else StudioTextSecondary,
                enabled = true,
                testTag = "editor_onion_skin_btn",
                onClick = onToggleOnionSkin
            )

            // Save
            CompactTopIconAction(
                icon = Icons.Rounded.Save,
                contentDescription = stringResource(R.string.editor_save),
                tint = EmeraldLayer,
                enabled = true,
                testTag = "editor_save_btn",
                onClick = onSave
            )

            // Compact Primary "Export" Pill Button
            Surface(
                color = ElectricCyan,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenExport)
                    .testTag("editor_export_btn")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.IosShare,
                        contentDescription = null,
                        tint = StudioObsidian,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Export",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = StudioObsidian,
                        maxLines = 1
                    )
                }
            }

            // 3-dot Overflow Menu (Project Settings, Guide, Reset)
            Box {
                CompactTopIconAction(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = "More editor options",
                    tint = StudioTextPrimary,
                    enabled = true,
                    testTag = "editor_overflow_btn",
                    onClick = { overflowOpen = true }
                )
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                    containerColor = StudioPanel
                ) {
                    DropdownMenuItem(
                        text = { Text("Director Script Studio (.fcscript)", color = StudioTextPrimary, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(Icons.Rounded.MovieFilter, null, tint = EmeraldLayer) },
                        onClick = { overflowOpen = false; onOpenDirectorScript() }
                    )
                    DropdownMenuItem(
                        text = { Text("Project Settings (${project.fps} FPS)", color = StudioTextPrimary) },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null, tint = ElectricCyan) },
                        onClick = { overflowOpen = false; onOpenSettings() }
                    )
                    DropdownMenuItem(
                        text = { Text("Studio Guide", color = StudioTextPrimary) },
                        leadingIcon = { Icon(Icons.Rounded.HelpOutline, null, tint = AmberKeyframe) },
                        onClick = { overflowOpen = false; onOpenGuide() }
                    )
                    DropdownMenuItem(
                        text = { Text("Reset Stage", color = CoralPlayhead) },
                        leadingIcon = { Icon(Icons.Rounded.RestartAlt, null, tint = CoralPlayhead) },
                        onClick = { overflowOpen = false; confirmReset = true }
                    )
                }
            }
        }
        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = StudioPanel,
            title = { Text("Reset Animation Stage?", color = StudioTextPrimary) },
            text = {
                Text(
                    "This clears all clips and layers in the current scene. You can still Undo right after.",
                    color = StudioTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmReset = false; onResetProject() },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralPlayhead, contentColor = StudioTextPrimary)
                ) {
                    Text("Reset Stage")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = StudioTextSecondary) }
            }
        )
    }
}

@Composable
private fun CompactTopIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(34.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
