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
import com.example.data.DirectorScriptHistoryEntry
import com.example.engine.*
import com.example.model.CharacterRigDefinition
import com.example.model.LocalMediaAsset
import com.example.model.MediaAssetType
import com.example.model.ProjectDocument
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 100% Offline Universal Script-Based Film Studio Modal (3-Tab Pro Workflow):
 * - Tab 1: Export Blueprint & 1-Tap AI Prompt Builder (Compact vs Full, Include Current Timeline State,
 *          live Estimated Token Count pill, Odia/Hindi/English Story Instructions, Copy Complete Prompt).
 * - Tab 2: Import Director Script (Resilient LLM JSON auto-sanitizer, Interactive Dry-Run Summary,
 *          1-Tap "Copy Fix Prompt for AI" on errors, 6 Selective Track Import checkboxes, Replace/Append).
 * - Tab 3: Offline Script History (Last 10 applied scripts stored in `filesDir/director_script_history.json`
 *          with 1-tap Load/Inspect, Copy JSON, Re-Apply, and Delete).
 */
@Composable
fun DirectorScriptDialog(
    project: ProjectDocument,
    characters: List<CharacterRigDefinition>,
    mediaAssets: List<LocalMediaAsset>,
    scriptHistory: List<DirectorScriptHistoryEntry> = emptyList(),
    onApplyDirectorScript: (
        scriptJson: String,
        mode: DirectorScriptImportMode,
        trackSelection: DirectorScriptTrackSelection
    ) -> Unit,
    onDeleteHistoryEntry: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onExportBlueprintToSaf: (Uri, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Blueprint, 1 = Import, 2 = History
    var exportMode by remember { mutableStateOf(AIBlueprintExporter.BlueprintExportMode.COMPACT) }
    var includeCurrentTimelineState by remember { mutableStateOf(false) }
    var storyInstructions by remember { mutableStateOf("") }
    var blueprintJson by remember { mutableStateOf("") }
    var blueprintLoading by remember { mutableStateOf(true) }
    var blueprintStatus by remember { mutableStateOf<String?>(null) }
    var showBlueprintPreview by remember { mutableStateOf(false) }

    var scriptInput by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf(DirectorScriptImportMode.REPLACE_TIMELINE) }
    var trackSelection by remember { mutableStateOf(DirectorScriptTrackSelection()) }
    var importFeedback by remember { mutableStateOf<String?>(null) }

    val capabilitySnapshot = remember { StudioCapabilityRegistry.introspect() }
    val totalCapabilities = remember(capabilitySnapshot) {
        StudioCapabilityRegistry.totalCapabilityCount(capabilitySnapshot)
    }

    val exportOptions = remember(exportMode, includeCurrentTimelineState) {
        AIBlueprintExporter.BlueprintExportOptions(
            mode = exportMode,
            includeCurrentTimelineState = includeCurrentTimelineState
        )
    }

    // Build .fcblueprint.json off main thread whenever project or export scope changes
    LaunchedEffect(
        project.id,
        project.updatedAt,
        characters.size,
        mediaAssets.size,
        exportOptions
    ) {
        blueprintLoading = true
        blueprintJson = withContext(Dispatchers.IO) {
            AIBlueprintExporter.exportToJson(
                project = project,
                characters = characters,
                mediaAssets = mediaAssets,
                prettyPrint = exportOptions.mode == AIBlueprintExporter.BlueprintExportMode.FULL,
                options = exportOptions
            )
        }
        blueprintLoading = false
    }

    val blueprintTokens = remember(blueprintJson) {
        AIBlueprintExporter.estimateTokenCount(blueprintJson)
    }
    val completePromptPreview = remember(blueprintJson, storyInstructions, exportOptions) {
        if (blueprintJson.isBlank()) ""
        else AIBlueprintExporter.buildCompleteAiPrompt(
            blueprintJson = blueprintJson,
            storyInstructions = storyInstructions,
            options = exportOptions
        )
    }
    val completePromptTokens = remember(completePromptPreview) {
        AIBlueprintExporter.estimateTokenCount(completePromptPreview)
    }

    // Live resilient auto-sanitized dry-run validation whenever scriptInput changes
    val dryRunReport: DirectorScriptDryRunReport? = remember(scriptInput, characters, mediaAssets) {
        if (scriptInput.isBlank()) null
        else DirectorScriptReceiver.validateAndDryRun(scriptInput, characters, mediaAssets).second
    }

    val blueprintSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && blueprintJson.isNotBlank()) {
            onExportBlueprintToSaf(uri, blueprintJson)
            blueprintStatus = "Saved .fcblueprint.json (${exportOptions.mode.label}) successfully."
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
                .widthIn(max = 600.dp)
                .fillMaxHeight(0.90f)
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
                                text = "100% Offline Blueprint, Prompt Builder & Script Compiler (v3)",
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

                // 3-Tab Selector: Blueprint | Import | History
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(StudioCharcoal)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    ScriptStudioTabPill(
                        label = "Blueprint",
                        icon = Icons.Rounded.AutoAwesome,
                        selected = activeTab == 0,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("director_tab_export"),
                        onClick = { activeTab = 0 }
                    )
                    ScriptStudioTabPill(
                        label = "Import",
                        icon = Icons.Rounded.Download,
                        selected = activeTab == 1,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("director_tab_import"),
                        onClick = { activeTab = 1 }
                    )
                    ScriptStudioTabPill(
                        label = if (scriptHistory.isNotEmpty()) "History (${scriptHistory.size})" else "History",
                        icon = Icons.Rounded.History,
                        selected = activeTab == 2,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("director_tab_history"),
                        onClick = { activeTab = 2 }
                    )
                }

                Spacer(Modifier.height(12.dp))

                when (activeTab) {
                    0 -> ExportBlueprintTabContent(
                        project = project,
                        charactersCount = characters.size,
                        backgroundsCount = mediaAssets.count { it.type == MediaAssetType.BACKGROUND },
                        propsCount = mediaAssets.count {
                            it.type == MediaAssetType.IMAGE ||
                                it.type == MediaAssetType.SVG ||
                                it.type == MediaAssetType.GIF
                        },
                        audioCount = project.scenes.sumOf { s -> s.clips.count { !it.audioPath.isNullOrBlank() } } +
                            mediaAssets.count { it.type == MediaAssetType.AUDIO },
                        totalCapabilities = totalCapabilities,
                        exportMode = exportMode,
                        onExportModeChange = { exportMode = it },
                        includeCurrentTimelineState = includeCurrentTimelineState,
                        onToggleIncludeTimelineState = { includeCurrentTimelineState = it },
                        storyInstructions = storyInstructions,
                        onStoryInstructionsChange = { storyInstructions = it },
                        blueprintJson = blueprintJson,
                        blueprintTokens = blueprintTokens,
                        completePromptTokens = completePromptTokens,
                        isLoading = blueprintLoading,
                        statusMessage = blueprintStatus,
                        showPreview = showBlueprintPreview,
                        onTogglePreview = { showBlueprintPreview = !showBlueprintPreview },
                        onCopyCompletePrompt = {
                            if (completePromptPreview.isNotBlank()) {
                                copyTextToClipboard(context, "FrameCraft Complete AI Prompt", completePromptPreview)
                                val tok = AIBlueprintExporter.formatTokenEstimate(completePromptTokens)
                                blueprintStatus = "Copied Ready-to-Paste AI Prompt ($tok) — paste into any external AI!"
                            }
                        },
                        onCopyBlueprintOnly = {
                            copyTextToClipboard(context, "FrameCraft Blueprint", blueprintJson)
                            val tok = AIBlueprintExporter.formatTokenEstimate(blueprintTokens)
                            blueprintStatus = "Copied Blueprint JSON only ($tok) to clipboard."
                        },
                        onSaveBlueprint = {
                            val safe = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "FrameCraft" }
                            blueprintSaveLauncher.launch("${safe}.fcblueprint.json")
                        }
                    )
                    1 -> ImportDirectorScriptTabContent(
                        scriptInput = scriptInput,
                        onScriptInputChange = {
                            scriptInput = it
                            importFeedback = null
                        },
                        dryRunReport = dryRunReport,
                        importMode = importMode,
                        onImportModeChange = { importMode = it },
                        trackSelection = trackSelection,
                        onTrackSelectionChange = { trackSelection = it },
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
                        onCopyFixPrompt = { report ->
                            val fixPrompt = DirectorScriptReceiver.buildErrorFixPrompt(scriptInput, report)
                            copyTextToClipboard(context, "FrameCraft AI Fix Prompt", fixPrompt)
                            importFeedback = "Copied Fix Prompt with exact error & offending snippet — paste back to your AI!"
                        },
                        onApply = {
                            if (dryRunReport?.isValid == true && trackSelection.anySelected) {
                                onApplyDirectorScript(scriptInput, importMode, trackSelection)
                            }
                        }
                    )
                    else -> ScriptHistoryTabContent(
                        historyEntries = scriptHistory,
                        onInspectEntry = { entry ->
                            scriptInput = entry.scriptJson
                            importFeedback = "Loaded '${entry.title}' from offline history into Import tab."
                            activeTab = 1
                        },
                        onCopyEntry = { entry ->
                            copyTextToClipboard(context, entry.title, entry.scriptJson)
                            importFeedback = "Copied '${entry.title}' JSON to clipboard."
                        },
                        onReapplyEntry = { entry ->
                            val mode = runCatching { DirectorScriptImportMode.valueOf(entry.importMode) }
                                .getOrDefault(DirectorScriptImportMode.REPLACE_TIMELINE)
                            onApplyDirectorScript(entry.scriptJson, mode, DirectorScriptTrackSelection())
                        },
                        onDeleteEntry = onDeleteHistoryEntry,
                        onClearAll = onClearHistory
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
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) StudioObsidian else StudioTextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) StudioObsidian else StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
    exportMode: AIBlueprintExporter.BlueprintExportMode,
    onExportModeChange: (AIBlueprintExporter.BlueprintExportMode) -> Unit,
    includeCurrentTimelineState: Boolean,
    onToggleIncludeTimelineState: (Boolean) -> Unit,
    storyInstructions: String,
    onStoryInstructionsChange: (String) -> Unit,
    blueprintJson: String,
    blueprintTokens: Int,
    completePromptTokens: Int,
    isLoading: Boolean,
    statusMessage: String?,
    showPreview: Boolean,
    onTogglePreview: () -> Unit,
    onCopyCompletePrompt: () -> Unit,
    onCopyBlueprintOnly: () -> Unit,
    onSaveBlueprint: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Smart Scope, Compact/Full Mode & Live Token Estimator Card
        Surface(
            color = StudioCharcoal,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        Icon(Icons.Rounded.AutoAwesome, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Blueprint Scope & Mode",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Live Estimated Token Count Pill
                    Surface(
                        color = ElectricCyanDim,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("blueprint_token_estimate_pill")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Speed, null, tint = ElectricCyan, modifier = Modifier.size(12.dp))
                            Text(
                                text = if (isLoading) "Estimating..." else AIBlueprintExporter.formatTokenEstimate(blueprintTokens),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                        }
                    }
                }

                // Compact vs Full Mode Segmented Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AIBlueprintExporter.BlueprintExportMode.entries.forEach { mode ->
                        val selected = exportMode == mode
                        val subtitle = if (mode == AIBlueprintExporter.BlueprintExportMode.COMPACT) {
                            "Lean tokens"
                        } else {
                            "Full schema + examples"
                        }
                        Surface(
                            color = if (selected) ElectricCyanDim else StudioObsidian,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (selected) ElectricCyan else StudioBorderSubtle),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onExportModeChange(mode) }
                                .testTag("blueprint_mode_${mode.name.lowercase()}")
                        ) {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = mode.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) ElectricCyan else StudioTextPrimary
                                )
                                Text(
                                    text = subtitle,
                                    fontSize = 9.5.sp,
                                    color = if (selected) ElectricCyan.copy(alpha = 0.85f) else StudioTextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Include Current Timeline State Toggle (OFF by default)
                Surface(
                    color = StudioObsidian,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (includeCurrentTimelineState) EmeraldLayer.copy(alpha = 0.55f) else StudioBorderSubtle
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleIncludeTimelineState(!includeCurrentTimelineState) }
                        .testTag("blueprint_include_timeline_toggle")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Include Current Timeline State",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (includeCurrentTimelineState) EmeraldLayer else StudioTextPrimary
                            )
                            Text(
                                text = "Serializes active scene clips, positions, dialogue & camera keys so AI can refine existing animation.",
                                fontSize = 10.sp,
                                color = StudioTextSecondary,
                                lineHeight = 13.5.sp
                            )
                        }
                        Switch(
                            checked = includeCurrentTimelineState,
                            onCheckedChange = onToggleIncludeTimelineState,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = StudioObsidian,
                                checkedTrackColor = EmeraldLayer
                            )
                        )
                    }
                }

                // Compact Live Asset Metrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BlueprintStatChip("Stage", "${project.aspectRatioPreset.label} • ${project.fps}fps", ElectricCyan, Modifier.weight(1f))
                    BlueprintStatChip("Chars / Audio", "$charactersCount / $audioCount", EmeraldLayer, Modifier.weight(1f))
                    BlueprintStatChip("BGs / Caps", "$backgroundsCount+$propsCount • $totalCapabilities", AmberKeyframe, Modifier.weight(1f))
                }
            }
        }

        // Story / Scene Instructions Input (Odia / Hindi / English)
        OutlinedTextField(
            value = storyInstructions,
            onValueChange = onStoryInstructionsChange,
            label = { Text("Story / Scene Instructions (Odia / Hindi / English — Optional)", fontSize = 11.5.sp) },
            placeholder = {
                Text(
                    "e.g. \"ଦୁଇ ସାଙ୍ଗ ବର୍ଷାରେ ଗାଁ ରାସ୍ତାରେ କଥା ହେଉଛନ୍ତି...\" or \"Two friends talk in monsoon rain with close-up reaction cuts\"",
                    fontSize = 11.sp,
                    color = StudioTextMuted
                )
            },
            trailingIcon = {
                if (storyInstructions.isNotEmpty()) {
                    IconButton(
                        onClick = { onStoryInstructionsChange("") },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Clear,
                            contentDescription = "Clear story instructions",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            textStyle = LocalTextStyle.current.copy(
                fontSize = 12.sp,
                color = StudioTextPrimary,
                lineHeight = 17.sp
            ),
            minLines = 3,
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = StudioBorderSubtle,
                focusedContainerColor = StudioObsidian,
                unfocusedContainerColor = StudioObsidian
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("blueprint_story_instructions_input")
        )

        // Primary 1-Tap CTA: Copy Ready-to-Paste AI Prompt
        Button(
            onClick = onCopyCompletePrompt,
            enabled = !isLoading && blueprintJson.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("blueprint_copy_complete_prompt_btn")
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (isLoading) {
                    "Preparing AI Prompt..."
                } else {
                    "Copy Ready-to-Paste AI Prompt (${AIBlueprintExporter.formatTokenEstimate(completePromptTokens)})"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Secondary Actions: Copy Blueprint JSON Only & Save .fcblueprint.json
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCopyBlueprintOnly,
                enabled = !isLoading && blueprintJson.isNotBlank(),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("blueprint_copy_btn")
            ) {
                Icon(Icons.Rounded.ContentCopy, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "Copy Blueprint JSON Only",
                    color = ElectricCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onSaveBlueprint,
                enabled = !isLoading && blueprintJson.isNotBlank(),
                border = BorderStroke(1.dp, EmeraldLayer.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("blueprint_save_file_btn")
            ) {
                Icon(Icons.Rounded.SaveAlt, null, tint = EmeraldLayer, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "Save .fcblueprint.json",
                    color = EmeraldLayer,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                        text = if (isLoading) {
                            "Generating blueprint..."
                        } else {
                            "Blueprint JSON Preview (${exportMode.label} • ${blueprintJson.length} chars)"
                        },
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = StudioTextSecondary
                    )
                    Icon(
                        imageVector = if (showPreview) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle blueprint preview",
                        tint = StudioTextSecondary
                    )
                }
                if (showPreview && !isLoading) {
                    HorizontalDivider(color = StudioBorderSubtle)
                    Text(
                        text = blueprintJson.take(4500) + if (blueprintJson.length > 4500) {
                            "\n... (${blueprintJson.length - 4500} more chars)"
                        } else "",
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

internal fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun readTextFromClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
