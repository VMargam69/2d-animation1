package com.example.ui.screens.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.AudioWaveformData
import com.example.engine.AudioWaveformExtractor
import com.example.engine.AudioWaveformJsonCodec
import com.example.engine.WaveformLipSyncExport
import com.example.model.ProjectDocument
import com.example.model.TimelineClip
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Lightweight Waveform & Lip-Sync JSON Exporter dialog.
 * Allows animators to inspect, copy, save into the project folder, or export via
 * zero-permission Android SAF CreateDocument a compact `.waveform.json` file containing
 * trimmed waveform peaks, frame-aligned MTH1–MTH4 lip-sync cues, and synced character tracks.
 */
@Composable
fun WaveformExportDialog(
    project: ProjectDocument,
    audioClip: TimelineClip,
    waveformSensitivity: Float = 1.0f,
    onSaveToProject: (clipName: String, json: String, onResult: (String) -> Unit) -> Unit,
    onSaveToSafUri: (uri: Uri, json: String, onResult: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var prettyFormat by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val waveform by produceState<AudioWaveformData?>(
        initialValue = AudioWaveformExtractor.getCached(audioClip.audioPath),
        key1 = audioClip.audioPath
    ) {
        val path = audioClip.audioPath
        value = if (!path.isNullOrBlank()) {
            AudioWaveformExtractor.extractWaveform(path)
        } else {
            null
        }
    }

    var exportModel by remember { mutableStateOf<WaveformLipSyncExport?>(null) }
    var jsonText by remember { mutableStateOf("") }

    LaunchedEffect(project, audioClip, waveform, waveformSensitivity, prettyFormat) {
        val (model, serialized) = withContext(Dispatchers.Default) {
            val built = AudioWaveformJsonCodec.buildExport(
                project = project,
                scene = project.activeScene,
                audioClip = audioClip,
                waveform = waveform,
                sensitivity = waveformSensitivity
            )
            built to AudioWaveformJsonCodec.toJson(built, pretty = prettyFormat)
        }
        exportModel = model
        jsonText = serialized
    }

    val saveSafLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && jsonText.isNotBlank()) {
            onSaveToSafUri(uri, jsonText) { msg -> statusMessage = msg }
        }
    }

    val safeBaseName = remember(audioClip.name) {
        audioClip.name.trim()
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .ifBlank { "audio_lipsync" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = StudioPanel,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .testTag("waveform_export_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(EmeraldLayer.copy(alpha = 0.16f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = EmeraldLayer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Export Waveform & Lip-Sync JSON",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioTextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${audioClip.name} • ${project.fps} FPS",
                                style = MaterialTheme.typography.labelSmall,
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
                            .testTag("waveform_export_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = StudioTextSecondary
                        )
                    }
                }

                // Summary Metric Chips
                val model = exportModel
                if (model != null) {
                    val startSec = model.trimStartMs / 1000f
                    val endSec = model.trimEndMs / 1000f
                    val sizeKb = (jsonText.toByteArray(Charsets.UTF_8).size / 1024f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WaveformMetaBadge(
                            label = "Trim",
                            value = String.format(Locale.US, "%.1fs–%.1fs", startSec, endSec),
                            accent = EmeraldLayer,
                            modifier = Modifier.weight(1f)
                        )
                        WaveformMetaBadge(
                            label = "Peaks",
                            value = "${model.peakCount}",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(0.85f)
                        )
                        WaveformMetaBadge(
                            label = "Mouth Cues",
                            value = "${model.lipSyncCues.size}",
                            accent = AmberKeyframe,
                            modifier = Modifier.weight(1f)
                        )
                        WaveformMetaBadge(
                            label = "Size",
                            value = String.format(Locale.US, "%.1f KB", sizeKb.coerceAtLeast(0.1f)),
                            accent = VioletLayer,
                            modifier = Modifier.weight(0.85f)
                        )
                    }
                }

                // Compact vs Pretty toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "JSON Payload Preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !prettyFormat,
                            onClick = { prettyFormat = false },
                            label = { Text("Compact", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyanDim,
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier.testTag("waveform_json_compact_chip")
                        )
                        FilterChip(
                            selected = prettyFormat,
                            onClick = { prettyFormat = true },
                            label = { Text("Pretty", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyanDim,
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier.testTag("waveform_json_pretty_chip")
                        )
                    }
                }

                // Monospace JSON preview box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(172.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(StudioObsidian)
                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = jsonText.ifBlank { "Generating lightweight waveform & lip-sync JSON..." },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = StudioTextSecondary
                    )
                }

                if (statusMessage != null) {
                    Surface(
                        color = EmeraldLayer.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, EmeraldLayer.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusMessage!!,
                            fontSize = 11.5.sp,
                            color = StudioTextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // Export Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (jsonText.isNotBlank()) {
                                clipboard.setText(AnnotatedString(jsonText))
                                statusMessage = "Copied waveform lip-sync JSON to clipboard"
                            }
                        },
                        enabled = jsonText.isNotBlank(),
                        border = BorderStroke(1.dp, StudioBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("waveform_json_copy_btn")
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontSize = 12.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            if (jsonText.isNotBlank()) {
                                onSaveToProject(audioClip.name, jsonText) { msg -> statusMessage = msg }
                            }
                        },
                        enabled = jsonText.isNotBlank(),
                        border = BorderStroke(1.dp, EmeraldLayer.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldLayer),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .heightIn(min = 48.dp)
                            .testTag("waveform_json_save_project_btn")
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Project", fontSize = 12.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            saveSafLauncher.launch("${safeBaseName}.waveform.json")
                        },
                        enabled = jsonText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = StudioObsidian
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1.25f)
                            .heightIn(min = 48.dp)
                            .testTag("waveform_json_export_file_btn")
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save .json", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveformMetaBadge(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(StudioCard)
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = StudioTextMuted,
            maxLines = 1
        )
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
