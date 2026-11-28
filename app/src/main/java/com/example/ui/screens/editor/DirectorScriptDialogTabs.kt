package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DirectorScriptHistoryEntry
import com.example.engine.DirectorScriptDryRunReport
import com.example.engine.DirectorScriptImportMode
import com.example.engine.DirectorScriptTrackSelection
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ImportDirectorScriptTabContent(
    scriptInput: String,
    onScriptInputChange: (String) -> Unit,
    dryRunReport: DirectorScriptDryRunReport?,
    importMode: DirectorScriptImportMode,
    onImportModeChange: (DirectorScriptImportMode) -> Unit,
    trackSelection: DirectorScriptTrackSelection,
    onTrackSelectionChange: (DirectorScriptTrackSelection) -> Unit,
    feedbackMessage: String?,
    onPickFile: () -> Unit,
    onPasteClipboard: () -> Unit,
    onLoadDemoScript: () -> Unit,
    onCopyFixPrompt: (DirectorScriptDryRunReport) -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Quick Load Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onPickFile,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("script_open_file_btn")
            ) {
                Icon(Icons.Rounded.FolderOpen, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("File", fontSize = 11.5.sp, color = ElectricCyan, maxLines = 1)
            }
            OutlinedButton(
                onClick = onPasteClipboard,
                border = BorderStroke(1.dp, StudioBorderSubtle),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("script_paste_btn")
            ) {
                Icon(Icons.Rounded.ContentPaste, null, tint = StudioTextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Paste", fontSize = 11.5.sp, color = StudioTextPrimary, maxLines = 1)
            }
            OutlinedButton(
                onClick = onLoadDemoScript,
                border = BorderStroke(1.dp, AmberKeyframe.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("script_load_demo_btn")
            ) {
                Icon(Icons.Rounded.Bolt, null, tint = AmberKeyframe, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Demo", fontSize = 11.5.sp, color = AmberKeyframe, maxLines = 1)
            }
        }

        // Script Input Field
        OutlinedTextField(
            value = scriptInput,
            onValueChange = onScriptInputChange,
            label = { Text("Paste AI response or .fcscript.json (auto-cleaned)", fontSize = 11.5.sp) },
            placeholder = {
                Text(
                    """Paste raw LLM output — markdown ```json fences, comments & trailing commas are cleaned automatically.""",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp
                )
            },
            trailingIcon = {
                if (scriptInput.isNotEmpty()) {
                    IconButton(
                        onClick = { onScriptInputChange("") },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Clear,
                            contentDescription = "Clear script input",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = StudioTextPrimary
            ),
            minLines = 4,
            maxLines = 7,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = StudioBorderSubtle,
                focusedContainerColor = StudioObsidian,
                unfocusedContainerColor = StudioObsidian
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("script_json_input")
        )

        if (!feedbackMessage.isNullOrBlank()) {
            Text(
                text = feedbackMessage,
                fontSize = 11.sp,
                color = ElectricCyan
            )
        }

        // Auto-Sanitizer Applied Banner
        if (dryRunReport != null && dryRunReport.sanitizationFixes.isNotEmpty()) {
            Surface(
                color = ElectricCyanDim,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, null, tint = ElectricCyan, modifier = Modifier.size(15.dp))
                    Text(
                        text = "Auto-sanitized: ${dryRunReport.sanitizationFixes.joinToString(" • ")}",
                        fontSize = 10.5.sp,
                        color = ElectricCyan,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        // Interactive Dry-Run Summary / Error Diagnostics Card
        if (dryRunReport != null) {
            val borderColor = if (dryRunReport.isValid) EmeraldLayer else CoralPlayhead
            Surface(
                color = borderColor.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, borderColor.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (dryRunReport.isValid) Icons.Rounded.Verified else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = borderColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (dryRunReport.isValid) {
                                    "Dry-Run OK • ${dryRunReport.scriptTitle ?: "Director Script"}"
                                } else {
                                    "Validation Failed"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = borderColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (dryRunReport.isValid && dryRunReport.warnings.isNotEmpty()) {
                            Text(
                                text = "${dryRunReport.warnings.size} warn",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AmberKeyframe
                            )
                        }
                    }

                    if (dryRunReport.isValid) {
                        // Row 1: Scenes, Total Duration, Camera Shots
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BlueprintStatChip("Scenes", "${dryRunReport.sceneCount}", StudioTextPrimary, Modifier.weight(1f))
                            BlueprintStatChip(
                                "Duration",
                                String.format(Locale.US, "%.1fs", dryRunReport.totalDurationSec),
                                ElectricCyan,
                                Modifier.weight(1f)
                            )
                            BlueprintStatChip("Cam Shots", "${dryRunReport.cameraShotCount}", AmberKeyframe, Modifier.weight(1f))
                        }
                        // Row 2: Characters, Inline SVGs, Custom Anims/Transitions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BlueprintStatChip(
                                "Characters",
                                "${dryRunReport.characterClipCount} (${dryRunReport.clipCount} clips)",
                                EmeraldLayer,
                                Modifier.weight(1f)
                            )
                            BlueprintStatChip("Inline SVGs", "${dryRunReport.inlineSvgCount}", ElectricCyan, Modifier.weight(1f))
                            BlueprintStatChip(
                                "Anim / Trans",
                                "${dryRunReport.customAnimationCount} / ${dryRunReport.customTransitionCount}",
                                EmeraldLayer,
                                Modifier.weight(1f)
                            )
                        }
                        // Row 3: Weather/VFX, Subtitles/Dialogues, Universal Tracks
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BlueprintStatChip(
                                "Weather / VFX",
                                "${dryRunReport.weatherLayerCount} / ${dryRunReport.vfxSceneCount}",
                                AmberKeyframe,
                                Modifier.weight(1f)
                            )
                            BlueprintStatChip("Dialogues", "${dryRunReport.dialogueOverlayCount}", StudioTextPrimary, Modifier.weight(1f))
                            BlueprintStatChip(
                                "Key Tracks",
                                "${dryRunReport.universalKeyframeTrackCount} (${dryRunReport.keyframeCount}k)",
                                ElectricCyan,
                                Modifier.weight(1f)
                            )
                        }
                    } else {
                        // Error Reason + Line/Field Hint + Offending Snippet + 1-Tap Copy Fix Prompt
                        dryRunReport.errors.forEach { err ->
                            Text("• $err", fontSize = 11.sp, color = CoralPlayhead, lineHeight = 15.sp)
                        }
                        if (!dryRunReport.errorHint.isNullOrBlank()) {
                            Text(
                                text = "Hint: ${dryRunReport.errorHint}",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = AmberKeyframe
                            )
                        }
                        if (!dryRunReport.errorSnippet.isNullOrBlank()) {
                            Surface(
                                color = StudioObsidian,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.8.dp, CoralPlayhead.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = dryRunReport.errorSnippet,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    lineHeight = 13.sp,
                                    color = StudioTextPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                )
                            }
                        }
                        Button(
                            onClick = { onCopyFixPrompt(dryRunReport) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CoralPlayhead,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("script_copy_fix_prompt_btn")
                        ) {
                            Icon(Icons.Rounded.BuildCircle, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Copy Fix Prompt for AI",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    dryRunReport.warnings.take(4).forEach { warn ->
                        Text("⚠ $warn", fontSize = 10.5.sp, color = AmberKeyframe, lineHeight = 14.sp)
                    }
                    if (dryRunReport.warnings.size > 4) {
                        Text(
                            "… +${dryRunReport.warnings.size - 4} more warning(s)",
                            fontSize = 10.5.sp,
                            color = AmberKeyframe
                        )
                    }
                }
            }
        }

        // Selective Track Import Checkboxes (All 6 checked by default)
        Surface(
            color = StudioCharcoal,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(Icons.Rounded.Tune, null, tint = ElectricCyan, modifier = Modifier.size(15.dp))
                        Text(
                            text = "Selective Track Import",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioTextPrimary
                        )
                    }
                    TextButton(
                        onClick = {
                            onTrackSelectionChange(
                                if (trackSelection.allSelected) {
                                    DirectorScriptTrackSelection(
                                        charactersAndActing = false,
                                        cameraShots = false,
                                        backgroundsAndParallax = false,
                                        vfxWeatherAndGrading = false,
                                        audioAndDucking = false,
                                        subtitlesAndBubbles = false
                                    )
                                } else {
                                    DirectorScriptTrackSelection()
                                }
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (trackSelection.allSelected) "Uncheck All" else "Select All",
                            fontSize = 10.5.sp,
                            color = ElectricCyan
                        )
                    }
                }

                // 2-Column Checkbox Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SelectiveTrackCheckboxPill(
                        label = "Characters & Acting",
                        checked = trackSelection.charactersAndActing,
                        onToggle = { onTrackSelectionChange(trackSelection.copy(charactersAndActing = !trackSelection.charactersAndActing)) },
                        modifier = Modifier.weight(1f).testTag("script_track_characters")
                    )
                    SelectiveTrackCheckboxPill(
                        label = "Camera Shots",
                        checked = trackSelection.cameraShots,
                        onToggle = { onTrackSelectionChange(trackSelection.copy(cameraShots = !trackSelection.cameraShots)) },
                        modifier = Modifier.weight(1f).testTag("script_track_camera")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SelectiveTrackCheckboxPill(
                        label = "BGs & 2.5D Parallax",
                        checked = trackSelection.backgroundsAndParallax,
                        onToggle = { onTrackSelectionChange(trackSelection.copy(backgroundsAndParallax = !trackSelection.backgroundsAndParallax)) },
                        modifier = Modifier.weight(1f).testTag("script_track_backgrounds")
                    )
                    SelectiveTrackCheckboxPill(
                        label = "VFX, Weather & Grade",
                        checked = trackSelection.vfxWeatherAndGrading,
                        onToggle = { onTrackSelectionChange(trackSelection.copy(vfxWeatherAndGrading = !trackSelection.vfxWeatherAndGrading)) },
                        modifier = Modifier.weight(1f).testTag("script_track_vfx")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SelectiveTrackCheckboxPill(
                        label = "Audio & Ducking",
                        checked = trackSelection.audioAndDucking,
                        onToggle = { onTrackSelectionChange(trackSelection.copy(audioAndDucking = !trackSelection.audioAndDucking)) },
                        modifier = Modifier.weight(1f).testTag("script_track_audio")
                    )
                    SelectiveTrackCheckboxPill(
                        label = "Subtitles / Bubbles",
                        checked = trackSelection.subtitlesAndBubbles,
                        onToggle = { onTrackSelectionChange(trackSelection.copy(subtitlesAndBubbles = !trackSelection.subtitlesAndBubbles)) },
                        modifier = Modifier.weight(1f).testTag("script_track_subtitles")
                    )
                }
            }
        }

        // Import Mode Selector (Replace Timeline vs Append Scenes)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DirectorScriptImportMode.entries.forEach { mode ->
                val selected = importMode == mode
                Surface(
                    color = if (selected) ElectricCyanDim else StudioCharcoal,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) ElectricCyan else StudioBorderSubtle
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onImportModeChange(mode) }
                        .testTag("script_mode_${mode.name.lowercase()}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (mode == DirectorScriptImportMode.REPLACE_TIMELINE) {
                                Icons.Rounded.SwapHoriz
                            } else {
                                Icons.Rounded.PlaylistAdd
                            },
                            contentDescription = null,
                            tint = if (selected) ElectricCyan else StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = mode.label,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) ElectricCyan else StudioTextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Undo safety notice
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.History, null, tint = EmeraldLayer, modifier = Modifier.size(14.dp))
            Text(
                text = "Auto-saves Undo snapshot & records script to offline History. Unchecked tracks stay untouched.",
                fontSize = 10.5.sp,
                color = StudioTextSecondary,
                lineHeight = 14.sp
            )
        }

        // Primary Apply Button
        Button(
            onClick = onApply,
            enabled = dryRunReport?.isValid == true && trackSelection.anySelected,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldLayer,
                contentColor = StudioObsidian,
                disabledContainerColor = StudioCharcoal,
                disabledContentColor = StudioTextMuted
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("script_apply_btn")
        ) {
            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (trackSelection.allSelected) {
                    "Compile & Apply Director Script"
                } else {
                    "Merge Checked Tracks Into Timeline"
                },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SelectiveTrackCheckboxPill(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (checked) EmeraldLayer.copy(alpha = 0.14f) else StudioObsidian,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(
            0.9.dp,
            if (checked) EmeraldLayer.copy(alpha = 0.6f) else StudioBorderSubtle
        ),
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onToggle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) EmeraldLayer else StudioTextMuted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                fontSize = 10.5.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (checked) StudioTextPrimary else StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ScriptHistoryTabContent(
    historyEntries: List<DirectorScriptHistoryEntry>,
    onInspectEntry: (DirectorScriptHistoryEntry) -> Unit,
    onCopyEntry: (DirectorScriptHistoryEntry) -> Unit,
    onReapplyEntry: (DirectorScriptHistoryEntry) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    if (historyEntries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = StudioCharcoal,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StudioBorderSubtle)
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(32.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No Script History Yet",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = StudioTextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The last 10 successfully applied `.fcscript.json` drafts are saved automatically offline so you can re-inspect, copy, or re-apply any earlier take in 1 tap.",
                fontSize = 11.5.sp,
                color = StudioTextSecondary,
                lineHeight = 16.sp
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Offline Script History (${historyEntries.size}/10)",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = StudioTextSecondary
            )
            TextButton(
                onClick = onClearAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.testTag("history_clear_all_btn")
            ) {
                Text("Clear All", fontSize = 11.sp, color = CoralPlayhead)
            }
        }

        historyEntries.forEachIndexed { idx, entry ->
            Surface(
                color = StudioCharcoal,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioBorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_entry_$idx")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = StudioTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${dateFormat.format(Date(entry.appliedAtMs))} • ${entry.sceneCount} scene(s) • ${String.format(Locale.US, "%.1fs", entry.totalDurationSec)} • ${entry.cameraShotCount} cam",
                                fontSize = 10.5.sp,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onDeleteEntry(entry.id) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete history entry",
                                tint = StudioTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onInspectEntry(entry) },
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Icon(Icons.Rounded.Visibility, null, tint = ElectricCyan, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Inspect", fontSize = 11.sp, color = ElectricCyan)
                        }
                        OutlinedButton(
                            onClick = { onCopyEntry(entry) },
                            border = BorderStroke(1.dp, StudioBorderSubtle),
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = StudioTextPrimary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", fontSize = 11.sp, color = StudioTextPrimary)
                        }
                        Button(
                            onClick = { onReapplyEntry(entry) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldLayer,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Icon(Icons.Rounded.Replay, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Re-Apply", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BlueprintStatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioObsidian,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.8.dp, StudioBorderSubtle),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, fontSize = 9.5.sp, color = StudioTextMuted, maxLines = 1)
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
