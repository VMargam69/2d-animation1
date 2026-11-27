package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.BuiltInCatalog
import com.example.engine.ProceduralRigRenderer
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.ExportUiState

@Composable
fun AddShapeDialog(
    onAddShape: (ShapeKind, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedKind by remember { mutableStateOf(ShapeKind.SPEECH_BUBBLE) }
    var fillHex by remember { mutableStateOf("#38BDF8") }
    val swatches = listOf("#38BDF8", "#F59E0B", "#10B981", "#F43F5E", "#A78BFA", "#FFFFFF", "#1E293B")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Add Vector Shape", color = StudioTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ShapeKind.entries) { kind ->
                        FilterChip(
                            selected = selectedKind == kind,
                            onClick = { selectedKind = kind },
                            label = { Text(kind.displayName) }
                        )
                    }
                }
                Text("Fill Color", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(swatches) { hex ->
                        val col = Color(ProceduralRigRenderer.parseColorSafe(hex, 0xFF38BDF8.toInt()))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    if (hex == fillHex) 3.dp else 1.dp,
                                    if (hex == fillHex) ElectricCyan else StudioBorder,
                                    CircleShape
                                )
                                .clickable { fillHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddShape(selectedKind, fillHex, "#F8FAFC") },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                modifier = Modifier.testTag("confirm_add_shape_btn")
            ) {
                Text("Add Shape", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) }
        }
    )
}

@Composable
fun AddTextDialog(
    onAddText: (String, String, Float, TextBubbleStyle, String) -> Unit,
    onDismiss: () -> Unit
) {
    var content by remember { mutableStateOf("Scene Title") }
    var colorHex by remember { mutableStateOf("#FFFFFF") }
    var sizeSp by remember { mutableFloatStateOf(32f) }
    var bubbleStyle by remember { mutableStateOf(TextBubbleStyle.NONE) }
    var bubbleFillHex by remember { mutableStateOf("#FFFFFF") }
    val textSwatches = listOf("#FFFFFF", "#0F172A", "#FEF08A", "#38BDF8", "#F43F5E", "#34D399", "#F59E0B")
    val bubbleSwatches = listOf("#FFFFFF", "#FDE047", "#E0F2FE", "#FCE7F3", "#DCFCE7", "#1E293B")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Add Text & Comic Bubble", color = StudioTextPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Caption / Dialogue") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_text_input")
                )

                Text("Comic Bubble Style", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TextBubbleStyle.entries) { style ->
                        val selected = bubbleStyle == style
                        FilterChip(
                            selected = selected,
                            onClick = {
                                bubbleStyle = style
                                // Smart contrast default when switching to light comic bubbles
                                if (style == TextBubbleStyle.SPEECH || style == TextBubbleStyle.THOUGHT || style == TextBubbleStyle.SHOUT) {
                                    if (colorHex == "#FFFFFF") colorHex = "#0F172A"
                                    if (style == TextBubbleStyle.SHOUT && bubbleFillHex == "#FFFFFF") bubbleFillHex = "#FDE047"
                                } else if (colorHex == "#0F172A") {
                                    colorHex = "#FFFFFF"
                                }
                            },
                            label = { Text(style.displayName) },
                            modifier = Modifier.testTag("text_bubble_style_${style.name.lowercase()}")
                        )
                    }
                }

                Text("Text Size: ${sizeSp.toInt()}sp", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                Slider(value = sizeSp, onValueChange = { sizeSp = it }, valueRange = 18f..68f)

                Text("Text Color", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(textSwatches) { hex ->
                        val col = Color(ProceduralRigRenderer.parseColorSafe(hex, 0xFFFFFFFF.toInt()))
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    if (hex == colorHex) 3.dp else 1.dp,
                                    if (hex == colorHex) ElectricCyan else StudioBorder,
                                    CircleShape
                                )
                                .clickable { colorHex = hex }
                        )
                    }
                }

                if (bubbleStyle == TextBubbleStyle.SPEECH || bubbleStyle == TextBubbleStyle.THOUGHT || bubbleStyle == TextBubbleStyle.SHOUT) {
                    Text("Bubble Fill Color", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bubbleSwatches) { hex ->
                            val col = Color(ProceduralRigRenderer.parseColorSafe(hex, 0xFFFFFFFF.toInt()))
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(
                                        if (hex == bubbleFillHex) 3.dp else 1.dp,
                                        if (hex == bubbleFillHex) AmberKeyframe else StudioBorder,
                                        CircleShape
                                    )
                                    .clickable { bubbleFillHex = hex }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddText(content, colorHex, sizeSp, bubbleStyle, bubbleFillHex) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                modifier = Modifier.testTag("confirm_add_text_btn")
            ) {
                Text(if (bubbleStyle == TextBubbleStyle.NONE) "Add Text" else "Add ${bubbleStyle.displayName}", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) }
        }
    )
}

@Composable
fun BackgroundPickerDialog(
    onSelectBundled: (String) -> Unit,
    onSelectSolidColor: (String) -> Unit,
    onOpenMediaLibrary: () -> Unit,
    onDismiss: () -> Unit
) {
    val bgColors = listOf("#151B28", "#0F172A", "#1E293B", "#14532D", "#451A03", "#312E81", "#F8FAFC", "#00B140")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Scene Background", color = StudioTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bundled Offline Scenery", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BuiltInCatalog.bundledMediaAssets) { asset ->
                        val resId = BuiltInCatalog.bundledBackgroundDrawableRes(asset.bundledResKey)
                        if (resId != null && asset.bundledResKey != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StudioBorder),
                                modifier = Modifier
                                    .size(width = 112.dp, height = 68.dp)
                                    .clickable {
                                        onSelectBundled(asset.bundledResKey)
                                        onDismiss()
                                    }
                            ) {
                                Image(
                                    painter = painterResource(resId),
                                    contentDescription = asset.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                Text("Solid Studio Colors (includes Chroma Green)", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bgColors) { hex ->
                        val c = Color(ProceduralRigRenderer.parseColorSafe(hex, 0xFF151B28.toInt()))
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(1.dp, StudioBorder, CircleShape)
                                .clickable {
                                    onSelectSolidColor(hex)
                                    onDismiss()
                                }
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenMediaLibrary()
                    },
                    border = BorderStroke(1.dp, ElectricCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PermMedia, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pick From Offline Media Library", color = StudioTextPrimary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = StudioTextSecondary) }
        }
    )
}

@Composable
fun TemplatesPickerDialog(
    onApplyTemplate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Apply Offline Scene Template", color = StudioTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BuiltInCatalog.sceneTemplates.forEach { tpl ->
                    Surface(
                        color = StudioCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onApplyTemplate(tpl.id) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tpl.title, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                            Text(tpl.subtitle, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = StudioTextSecondary) }
        }
    )
}

@Composable
fun OfflineExportDialog(
    project: ProjectDocument,
    exportState: ExportUiState,
    onStartExport: (ExportFormat, ExportResolution, Int, Int) -> Unit,
    onCancelExport: () -> Unit,
    onDismiss: () -> Unit
) {
    var format by remember { mutableStateOf(ExportFormat.MP4) }
    var resolution by remember { mutableStateOf(ExportResolution.HD_720) }
    var fps by remember { mutableIntStateOf(project.fps) }
    var durationFrames by remember { mutableIntStateOf(project.activeScene.durationFrames) }

    AlertDialog(
        onDismissRequest = {
            if (exportState !is ExportUiState.Exporting) onDismiss()
        },
        containerColor = StudioPanel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.MovieFilter, null, tint = ElectricCyan)
                Text("Offline Animation Export", color = StudioTextPrimary)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (exportState) {
                    is ExportUiState.Exporting -> {
                        val frac = (exportState.currentFrame.toFloat() / exportState.totalFrames.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val pct = (frac * 100).toInt()
                        Text(
                            "Rendering ${exportState.format.displayName} locally ($pct%)...",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary
                        )
                        LinearProgressIndicator(
                            progress = { frac },
                            color = ElectricCyan,
                            trackColor = StudioCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            "Frame ${exportState.currentFrame} of ${exportState.totalFrames}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }

                    is ExportUiState.Success -> {
                        val kb = (exportState.output.sizeBytes / 1024L).coerceAtLeast(1L)
                        Surface(
                            color = EmeraldLayer.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, EmeraldLayer)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Rounded.CheckCircle, null, tint = EmeraldLayer)
                                    Text("Export Verified & Saved", style = MaterialTheme.typography.titleSmall, color = EmeraldLayer)
                                }
                                Text("Format: ${exportState.output.format.displayName}", style = MaterialTheme.typography.bodySmall, color = StudioTextPrimary)
                                Text("Frames: ${exportState.output.totalFrames} • Size: ${kb} KB", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                Text("Saved to: ${exportState.output.displayPath}", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                            }
                        }
                    }

                    is ExportUiState.Error -> {
                        Surface(
                            color = CoralPlayhead.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CoralPlayhead)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Export Failed", style = MaterialTheme.typography.titleSmall, color = CoralPlayhead)
                                Text(exportState.message, style = MaterialTheme.typography.bodySmall, color = StudioTextPrimary)
                            }
                        }
                    }

                    ExportUiState.Idle -> {
                        Text("Output Format", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExportFormat.entries.forEach { fmt ->
                                val selected = fmt == format
                                Surface(
                                    color = if (selected) ElectricCyanDim else StudioCard,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (selected) ElectricCyan else StudioBorderSubtle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { format = fmt }
                                        .testTag("export_format_${fmt.name.lowercase()}")
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(fmt.displayName, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                                        Text(fmt.description, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    }
                                }
                            }
                        }

                        Text("Resolution", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExportResolution.entries.forEach { res ->
                                FilterChip(
                                    selected = resolution == res,
                                    onClick = { resolution = res },
                                    label = { Text(res.label) }
                                )
                            }
                        }

                        Text("Frame Rate", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(12, 24, 30).forEach { r ->
                                FilterChip(
                                    selected = fps == r,
                                    onClick = { fps = r },
                                    label = { Text("$r FPS") }
                                )
                            }
                        }

                        Text("Export Frames: $durationFrames / ${project.activeScene.durationFrames}", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                        Slider(
                            value = durationFrames.toFloat(),
                            onValueChange = { durationFrames = it.toInt() },
                            valueRange = 12f..project.activeScene.durationFrames.coerceAtLeast(24).toFloat()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (exportState is ExportUiState.Idle || exportState is ExportUiState.Error) {
                Button(
                    onClick = { onStartExport(format, resolution, fps, durationFrames) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                    modifier = Modifier.testTag("start_export_btn")
                ) {
                    Text("Render & Export", fontWeight = FontWeight.Bold)
                }
            } else if (exportState is ExportUiState.Success) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldLayer, contentColor = StudioObsidian)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (exportState is ExportUiState.Exporting) {
                TextButton(
                    onClick = onCancelExport,
                    modifier = Modifier.testTag("cancel_export_btn")
                ) {
                    Text("Cancel Export", color = CoralPlayhead)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close", color = StudioTextSecondary) }
            }
        }
    )
}

@Composable
fun StudioGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Offline Studio Quick Guide", color = StudioTextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GuideStep("1. Pick a Character", "Tap Character in the bottom bar to browse all categories, pin favorites, or build/import custom offline characters.")
                GuideStep("2. Choose an Animation", "Select Front, 3/4 Front, 3/4 Back, Back, or Side orientation and pick a live motion preset (Walk, Run, Talk, Wave, Jump, Dance).")
                GuideStep("3. Animate with Keyframes", "Scrub the timeline frame ruler, drag your character across the canvas to auto-keyframe motion, or tap + Key to lock poses with Linear, Hold, or Ease In-Out interpolation.")
                GuideStep("4. Onion Skin & Layers", "Toggle the Layers icon in the top bar for translucent ghost frames. Hide, lock, rename, or reorder layers in the timeline.")
                GuideStep("5. Export 100% Offline", "Tap Export to render MP4 (H.264), Animated GIF, or lossless PNG frame sequences directly to device storage—no internet required.")
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Got It", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun GuideStep(title: String, body: String) {
    Surface(
        color = StudioCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, StudioBorderSubtle)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ElectricCyan)
            Text(body, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
        }
    }
}
