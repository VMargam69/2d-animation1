package com.example.ui.screens.maker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LocalAssetStorage
import com.example.engine.SvgAtlasRenderer
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clean offline Import/Export Code dialog for FrameCraft Character Maker:
 * - Export tab: generates .fcchar.json or clean .svg atlas code with Copy to Clipboard and SAF CreateDocument save.
 * - Import tab: paste .fcchar.json or raw <svg>...</svg> code (or load from file via SAF OpenDocument) to apply live.
 */
@Composable
fun CharacterCodeDialog(
    config: SpriteAtlasRigConfig,
    assetStorage: LocalAssetStorage,
    onApplyImportedConfig: (SpriteAtlasRigConfig, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Export Code, 1 = Import Code
    var exportAsSvg by remember { mutableStateOf(false) }
    var generatedCode by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    // Build exported .fcchar.json or clean .svg off the main thread
    LaunchedEffect(config, exportAsSvg) {
        generatedCode = withContext(Dispatchers.IO) {
            if (exportAsSvg) {
                val existingSvg = assetStorage.readSvgTextFromPath(config.importedFullSheetPath)
                if (!existingSvg.isNullOrBlank()) {
                    existingSvg
                } else {
                    val slotSvgs = LinkedHashMap<AtlasPartSlot, String>()
                    for (slot in AtlasPartSlot.entries) {
                        val p = config.placementFor(slot)
                        if (!p.visible) continue
                        val inline = p.svgInline ?: assetStorage.readSvgTextFromPath(p.partImagePath)
                        if (!inline.isNullOrBlank()) slotSvgs[slot] = inline
                    }
                    SvgAtlasRenderer.composeCleanAtlasSvg(config, slotSvgs)
                }
            } else {
                val inlineSvg = assetStorage.readSvgTextFromPath(config.importedFullSheetPath)
                val codeObj = CharacterCodeCodec.fromRigConfig(
                    config = config,
                    atlasFileName = if (inlineSvg != null) "atlas.svg" else config.importedFullSheetPath?.substringAfterLast('/'),
                    inlineSvgAtlas = inlineSvg
                )
                CharacterCodeCodec.toJson(codeObj)
            }
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(if (exportAsSvg) "image/svg+xml" else "application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = assetStorage.writeTextToSafUri(uri, generatedCode)
            feedback = if (ok) {
                "Saved ${if (exportAsSvg) ".svg" else ".fcchar.json"} file"
            } else {
                "Could not write file"
            }
        }
    }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = assetStorage.readTextFromSafUri(uri)
            if (text != null) {
                importText = text
                feedback = "Loaded ${text.length} chars — tap Apply Code"
            } else {
                feedback = "File unreadable or exceeds 4 MB limit"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = {
            Column {
                Text("Character Code (.fcchar.json / .svg)", color = StudioTextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "100% offline copy/paste & file sharing for 30-slot character rigs",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; feedback = null },
                        label = { Text("Export Code") },
                        leadingIcon = { Icon(Icons.Rounded.Code, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("code_tab_export")
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; feedback = null },
                        label = { Text("Import Code") },
                        leadingIcon = { Icon(Icons.Rounded.DataObject, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("code_tab_import")
                    )
                }

                if (selectedTab == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !exportAsSvg,
                            onClick = { exportAsSvg = false },
                            label = { Text(".fcchar.json") },
                            modifier = Modifier.testTag("export_format_json")
                        )
                        FilterChip(
                            selected = exportAsSvg,
                            onClick = { exportAsSvg = true },
                            label = { Text("Clean .svg") },
                            modifier = Modifier.testTag("export_format_svg")
                        )
                    }

                    OutlinedTextField(
                        value = generatedCode.take(12_000),
                        onValueChange = {},
                        readOnly = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = StudioTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .testTag("export_code_preview")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(generatedCode))
                                feedback = "Copied ${generatedCode.length} chars to clipboard"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("copy_code_btn")
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                val safeName = config.textureName.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "CustomHero" }
                                val fileName = if (exportAsSvg) "${safeName}_atlas.svg" else "${safeName}.fcchar.json"
                                saveFileLauncher.launch(fileName)
                            },
                            border = BorderStroke(1.dp, EmeraldLayer),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_code_file_btn")
                        ) {
                            Icon(Icons.Rounded.SaveAlt, null, tint = EmeraldLayer, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save File", color = EmeraldLayer)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipText = clipboard.getText()?.text
                                if (!clipText.isNullOrBlank()) {
                                    importText = clipText
                                    feedback = "Pasted ${clipText.length} chars"
                                } else {
                                    feedback = "Clipboard is empty"
                                }
                            },
                            border = BorderStroke(1.dp, ElectricCyan),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("paste_code_btn")
                        ) {
                            Icon(Icons.Rounded.ContentPaste, null, tint = ElectricCyan, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste", color = ElectricCyan)
                        }
                        OutlinedButton(
                            onClick = {
                                openFileLauncher.launch(arrayOf("application/json", "image/svg+xml", "text/plain", "*/*"))
                            },
                            border = BorderStroke(1.dp, AmberKeyframe),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("load_code_file_btn")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = AmberKeyframe, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open File", color = AmberKeyframe)
                        }
                    }

                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = {
                            Text(
                                "Paste .fcchar.json or <svg viewBox=\"0 0 2048 2048\">...</svg>",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextMuted
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = StudioTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .testTag("import_code_input")
                    )

                    Button(
                        onClick = {
                            val trimmed = importText.trim()
                            if (trimmed.isEmpty()) {
                                feedback = "Paste or open a .fcchar.json or .svg file first"
                                return@Button
                            }
                            scope.launch {
                                if (trimmed.startsWith("<")) {
                                    if (!SvgAtlasRenderer.isSafeSvgContent(trimmed)) {
                                        feedback = "Blocked unsafe or malformed SVG markup"
                                        return@launch
                                    }
                                    val svgPath = assetStorage.exportCleanAtlasSvg(trimmed, config.textureName)
                                    if (svgPath != null) {
                                        val updated = config.copy(
                                            renderMode = "SVG",
                                            importedFullSheetPath = svgPath,
                                            inlineAtlasSvg = trimmed
                                        )
                                        onApplyImportedConfig(updated, "Loaded crisp vector SVG atlas into workspace")
                                        onDismiss()
                                    } else {
                                        feedback = "Could not save imported SVG"
                                    }
                                } else {
                                    val res = assetStorage.importCharacterCodeText(trimmed)
                                    val importedChar = res.character
                                    val rigCfg = importedChar?.spriteAtlasRig
                                    if (importedChar != null && rigCfg != null) {
                                        onApplyImportedConfig(rigCfg, res.message)
                                        onDismiss()
                                    } else {
                                        feedback = res.message
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldLayer, contentColor = StudioObsidian),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("apply_import_code_btn")
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply Code to Maker", fontWeight = FontWeight.Bold)
                    }
                }

                feedback?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberKeyframe
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = StudioTextSecondary)
            }
        }
    )
}
