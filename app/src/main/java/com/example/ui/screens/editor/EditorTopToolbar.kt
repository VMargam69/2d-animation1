package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ProjectDocument
import com.example.ui.theme.*

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
    onOpenGuide: () -> Unit
) {
    var overflowOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Surface(
        color = StudioCharcoal,
        border = BorderStroke(1.dp, StudioBorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("editor_back_btn")
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = StudioTextPrimary
                )
            }

            // Project Title & Quick Settings Pill (Aspect Ratio, FPS, Quality)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 4.dp)
                    .testTag("editor_settings_pill")
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = project.aspectRatioPreset.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    Text("•", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Text(
                        text = "${project.fps}FPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Text("•", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Text(
                        text = project.previewQuality.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted
                    )
                }
            }

            // Undo / Redo
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("editor_undo_btn")
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) StudioTextPrimary else StudioTextMuted,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("editor_redo_btn")
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) StudioTextPrimary else StudioTextMuted,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Onion Skin Toggle
            IconButton(
                onClick = onToggleOnionSkin,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("editor_onion_skin_btn")
            ) {
                Icon(
                    Icons.Rounded.Layers,
                    contentDescription = stringResource(R.string.editor_onion_skin),
                    tint = if (project.onionSkinEnabled) AmberKeyframe else StudioTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Save Button
            IconButton(
                onClick = onSave,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("editor_save_btn")
            ) {
                Icon(
                    Icons.Rounded.Save,
                    contentDescription = stringResource(R.string.editor_save),
                    tint = EmeraldLayer,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Export CTA
            Button(
                onClick = onOpenExport,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("editor_export_btn")
            ) {
                Icon(Icons.Rounded.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }

            // Overflow (Settings, Frame Rate, Guide, Clear/Reset)
            Box {
                IconButton(
                    onClick = { overflowOpen = true },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("editor_overflow_btn")
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More editor options", tint = StudioTextPrimary)
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                    containerColor = StudioPanel
                ) {
                    DropdownMenuItem(
                        text = { Text("Project & Frame Settings", color = StudioTextPrimary) },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null, tint = ElectricCyan) },
                        onClick = { overflowOpen = false; onOpenSettings() }
                    )
                    DropdownMenuItem(
                        text = { Text("Studio Guide", color = StudioTextPrimary) },
                        leadingIcon = { Icon(Icons.Rounded.HelpOutline, null, tint = AmberKeyframe) },
                        onClick = { overflowOpen = false; onOpenGuide() }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear / Reset Stage", color = CoralPlayhead) },
                        leadingIcon = { Icon(Icons.Rounded.RestartAlt, null, tint = CoralPlayhead) },
                        onClick = { overflowOpen = false; confirmReset = true }
                    )
                }
            }
        }
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
