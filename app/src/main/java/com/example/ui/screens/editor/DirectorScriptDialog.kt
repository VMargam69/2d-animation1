package com.example.ui.screens.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.*
import com.example.model.CharacterRigDefinition
import com.example.model.LocalMediaAsset
import com.example.model.ProjectDocument
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 100% Offline Universal Script-Based Film Studio Modal:
 * - Tab 1: Export AI Blueprint (`.fcblueprint.json` v3) with dynamic capability introspection & real audio durations.
 * - Tab 2: Import Director Script (`.fcscript.json`) with live dry-run validation, Replace/Append mode,
 *          SAF file import, clipboard paste, built-in offline demo script, and automatic Undo snapshot.
 */
@Composable
fun DirectorScriptDialog(
    project: ProjectDocument,
    characters: List<CharacterRigDefinition>,
    mediaAssets: List<LocalMediaAsset>,
    onApplyDirectorScript: (scriptJson: String, mode: DirectorScriptImportMode) -> Unit,
    onExportBlueprintToSaf: (Uri, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Export Blueprint, 1 = Import Script
    var blueprintJson by remember { mutableStateOf("") }
    var blueprintLoading by remember { mutableStateOf(true) }
    var blueprintStatus by remember { mutableStateOf<String?>(null) }
    var showBlueprintPreview by remember { mutableStateOf(false) }

    var scriptInput by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf(DirectorScriptImportMode.REPLACE_TIMELINE) }
    var importFeedback by remember { mutableStateOf<String?>(null) }

    val capabilitySnapshot = remember { StudioCapabilityRegistry.introspect() }
    val totalCapabilities = remember(capabilitySnapshot) {
        StudioCapabilityRegistry.totalCapabilityCount(capabilitySnapshot)
    }

    // Build .fcblueprint.json off main thread so MediaMetadataRetriever audio probing never stalls UI
    LaunchedEffect(project.id, project.updatedAt, characters.size, mediaAssets.size) {
        blueprintLoading = true
        blueprintJson = withContext(Dispatchers.IO) {
            AIBlueprintExporter.exportToJson(
                project = project,
                characters = characters,
                mediaAssets = mediaAssets,
                prettyPrint = true
            )
        }
        blueprintLoading = false
    }

    // Live dry-run validation whenever scriptInput changes
    val dryRunReport: DirectorScriptDryRunReport? = remember(scriptInput, characters, mediaAssets) {
        if (scriptInput.isBlank()) null
        else DirectorScriptReceiver.validateAndDryRun(scriptInput, characters, mediaAssets).second
    }

    val blueprintSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && blueprintJson.isNotBlank()) {
            onExportBlueprintToSaf(uri, blueprintJson)
            blueprintStatus = "Saved .fcblueprint.json successfully."
        }
    }

    val scriptOpenLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (!loaded.isNullOrBlank()) {
                    scriptInput = loaded
                    importFeedback = "Loaded director script (${loaded.length} chars)."
                } else {
                    importFeedback = "Could not read selected file."
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = StudioPanel,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 580.dp)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            color = ElectricCyanDim,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MovieFilter,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Director Script Studio",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = StudioTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "100% Offline Blueprint & Universal Script Pipeline (v3)",
                                fontSize = 11.sp,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("director_script_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close Director Script Studio",
                            tint = StudioTextSecondary
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 2-Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(StudioCharcoal)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ScriptStudioTabPill(
                        label = "Export Blueprint",
                        icon = Icons.Rounded.UploadFile,
                        selected = activeTab == 0,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("director_tab_export"),
                        onClick = { activeTab = 0 }
                    )
                    ScriptStudioTabPill(
                        label = "Import Script",
                        icon = Icons.Rounded.Download,
                        selected = activeTab == 1,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("director_tab_import"),
                        onClick = { activeTab = 1 }
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (activeTab == 0) {
                    ExportBlueprintTabContent(
                        project = project,
                        charactersCount = characters.size,
                        backgroundsCount = mediaAssets.count { it.type == com.example.model.MediaAssetType.BACKGROUND },
                        propsCount = mediaAssets.count {
                            it.type == com.example.model.MediaAssetType.IMAGE ||
                                it.type == com.example.model.MediaAssetType.SVG ||
                                it.type == com.example.model.MediaAssetType.GIF
                        },
                        audioCount = project.scenes.sumOf { s -> s.clips.count { !it.audioPath.isNullOrBlank() } } +
                            mediaAssets.count { it.type == com.example.model.MediaAssetType.AUDIO },
                        totalCapabilities = totalCapabilities,
                        blueprintJson = blueprintJson,
                        isLoading = blueprintLoading,
                        statusMessage = blueprintStatus,
                        showPreview = showBlueprintPreview,
                        onTogglePreview = { showBlueprintPreview = !showBlueprintPreview },
                        onSaveBlueprint = {
                            val safe = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "FrameCraft" }
                            blueprintSaveLauncher.launch("${safe}.fcblueprint.json")
                        },
                        onCopyBlueprint = {
                            copyTextToClipboard(context, "FrameCraft Blueprint", blueprintJson)
                            blueprintStatus = "Copied .fcblueprint.json (${blueprintJson.length} chars) to clipboard."
                        }
                    )
                } else {
                    ImportDirectorScriptTabContent(
                        scriptInput = scriptInput,
                        onScriptInputChange = {
                            scriptInput = it
                            importFeedback = null
                        },
                        dryRunReport = dryRunReport,
                        importMode = importMode,
                        onImportModeChange = { importMode = it },
                        feedbackMessage = importFeedback,
                        onPickFile = {
                            scriptOpenLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        onPasteClipboard = {
                            val pasted = readTextFromClipboard(context)
                            if (!pasted.isNullOrBlank()) {
                                scriptInput = pasted
                                importFeedback = "Pasted ${pasted.length} characters from clipboard."
                            } else {
                                importFeedback = "Clipboard is empty."
                            }
                        },
                        onLoadDemoScript = {
                            scriptInput = DirectorScriptReceiver.sampleDemoDirectorScriptJson(project, characters)
                            importFeedback = "Loaded built-in offline demo script (Inline Tree SVG + Custom Anim + Iris Transition + Auto-Focus Camera)."
                        },
                        onApply = {
                            if (dryRunReport?.isValid == true) {
                                onApplyDirectorScript(scriptInput, importMode)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptStudioTabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) ElectricCyan else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) StudioObsidian else StudioTextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) StudioObsidian else StudioTextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ExportBlueprintTabContent(
    project: ProjectDocument,
    charactersCount: Int,
    backgroundsCount: Int,
    propsCount: Int,
    audioCount: Int,
    totalCapabilities: Int,
    blueprintJson: String,
    isLoading: Boolean,
    statusMessage: String?,
    showPreview: Boolean,
    onTogglePreview: () -> Unit,
    onSaveBlueprint: () -> Unit,
    onCopyBlueprint: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Intro & Live Registry Card
        Surface(
            color = StudioCharcoal,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Live Project Snapshot (.fcblueprint.json v3)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioTextPrimary
                    )
                }
                Text(
                    text = "Dynamically packages your live project assets, exact audio durations, $totalCapabilities live studio capabilities, and Universal Extension schemas (Inline SVGs, Custom Animations & Transitions) for any external LLM or script editor.",
                    fontSize = 11.5.sp,
                    color = StudioTextSecondary,
                    lineHeight = 16.sp
                )

                // Compact metrics grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BlueprintStatChip("Stage", "${project.aspectRatioPreset.label} • ${project.fps}fps", ElectricCyan, Modifier.weight(1f))
                    BlueprintStatChip("Chars", "$charactersCount", EmeraldLayer, Modifier.weight(1f))
                    BlueprintStatChip("Audio", "$audioCount", AmberKeyframe, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BlueprintStatChip("BGs / Props", "$backgroundsCount / $propsCount", StudioTextPrimary, Modifier.weight(1f))
                    BlueprintStatChip("Capabilities", "$totalCapabilities live", ElectricCyan, Modifier.weight(1f))
                    BlueprintStatChip(
                        "Custom Ext",
                        "${project.inlineSvgAssets.size}/${project.customAnimations.size}/${project.customTransitions.size}",
                        EmeraldLayer,
                        Modifier.weight(1f)
                    )
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSaveBlueprint,
                enabled = !isLoading && blueprintJson.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("blueprint_save_file_btn")
            ) {
                Icon(Icons.Rounded.SaveAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save .fcblueprint.json", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }

            OutlinedButton(
                onClick = onCopyBlueprint,
                enabled = !isLoading && blueprintJson.isNotBlank(),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("blueprint_copy_btn")
            ) {
                Icon(Icons.Rounded.ContentCopy, null, tint = ElectricCyan, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Copy", color = ElectricCyan, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Surface(
                color = EmeraldLayer.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, EmeraldLayer.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = EmeraldLayer, modifier = Modifier.size(16.dp))
                    Text(statusMessage, fontSize = 11.5.sp, color = StudioTextPrimary)
                }
            }
        }

        // Expandable Blueprint JSON Preview
        Surface(
            color = StudioObsidian,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTogglePreview)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isLoading) "Generating blueprint..." else "Blueprint JSON Preview (${blueprintJson.length} chars)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = StudioTextSecondary
                    )
                    Icon(
                        imageVector = if (showPreview) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle preview",
                        tint = StudioTextSecondary
                    )
                }
                if (showPreview && !isLoading) {
                    HorizontalDivider(color = StudioBorderSubtle)
                    Text(
                        text = blueprintJson.take(4500) + if (blueprintJson.length > 4500) "\n... (${blueprintJson.length - 4500} more chars)" else "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = ElectricCyan.copy(alpha = 0.9f),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportDirectorScriptTabContent(
    scriptInput: String,
    onScriptInputChange: (String) -> Unit,
    dryRunReport: DirectorScriptDryRunReport?,
    importMode: DirectorScriptImportMode,
    onImportModeChange: (DirectorScriptImportMode) -> Unit,
    feedbackMessage: String?,
    onPickFile: () -> Unit,
    onPasteClipboard: () -> Unit,
    onLoadDemoScript: () -> Unit,
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
                    .height(44.dp)
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
                    .height(44.dp)
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
                    .height(44.dp)
                    .testTag("script_load_demo_btn")
            ) {
                Icon(Icons.Rounded.Bolt, null, tint = AmberKeyframe, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Demo", fontSize = 11.5.sp, color = AmberKeyframe, maxLines = 1)
            }
        }

        // Script Editor Box
        OutlinedTextField(
            value = scriptInput,
            onValueChange = onScriptInputChange,
            label = { Text("Paste .fcscript.json here", fontSize = 12.sp) },
            placeholder = {
                Text(
                    """{ "format": "framecraft.director_script", "scenes": [...] }""",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = StudioTextPrimary
            ),
            minLines = 5,
            maxLines = 8,
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

        // Dry-Run Validation Summary Card
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
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (dryRunReport.isValid) Icons.Rounded.Verified else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = borderColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (dryRunReport.isValid) "Dry-Run Validated — Ready to Compile" else "Validation Failed",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = borderColor
                        )
                    }

                    if (dryRunReport.isValid) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BlueprintStatChip("Scenes", "${dryRunReport.sceneCount}", StudioTextPrimary, Modifier.weight(1f))
                            BlueprintStatChip("Inline SVGs", "${dryRunReport.inlineSvgCount}", ElectricCyan, Modifier.weight(1f))
                            BlueprintStatChip("Custom Anims", "${dryRunReport.customAnimationCount}", EmeraldLayer, Modifier.weight(1f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BlueprintStatChip("Transitions", "${dryRunReport.customTransitionCount}", AmberKeyframe, Modifier.weight(1f))
                            BlueprintStatChip("Clips / Keys", "${dryRunReport.clipCount} / ${dryRunReport.keyframeCount}", StudioTextPrimary, Modifier.weight(1f))
                            BlueprintStatChip("Cam Shots", "${dryRunReport.cameraShotCount}", ElectricCyan, Modifier.weight(1f))
                        }
                        if (dryRunReport.universalKeyframeTrackCount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BlueprintStatChip(
                                    "Universal Tracks",
                                    "${dryRunReport.universalKeyframeTrackCount}",
                                    EmeraldLayer,
                                    Modifier.weight(1f)
                                )
                                BlueprintStatChip(
                                    "Universal Keys",
                                    "${dryRunReport.universalKeyframeCount}",
                                    AmberKeyframe,
                                    Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    dryRunReport.errors.forEach { err ->
                        Text("• $err", fontSize = 11.sp, color = CoralPlayhead)
                    }
                    dryRunReport.warnings.take(4).forEach { warn ->
                        Text("⚠ $warn", fontSize = 10.5.sp, color = AmberKeyframe)
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
                        .height(42.dp)
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

        // Undo & Editability safety notice
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.History, null, tint = EmeraldLayer, modifier = Modifier.size(14.dp))
            Text(
                text = "Auto-saves Undo snapshot before apply. All layers, SVGs, keyframes & camera cuts remain 100% manually editable.",
                fontSize = 10.5.sp,
                color = StudioTextSecondary,
                lineHeight = 14.sp
            )
        }

        // Primary Apply Button
        Button(
            onClick = onApply,
            enabled = dryRunReport?.isValid == true,
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
                text = "Compile & Apply Director Script",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BlueprintStatChip(
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
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun readTextFromClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
