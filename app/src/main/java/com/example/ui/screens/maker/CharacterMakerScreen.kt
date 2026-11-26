package com.example.ui.screens.maker

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.LocalAssetStorage
import com.example.engine.ProceduralRigRenderer
import com.example.engine.SpriteAtlasEngine
import com.example.engine.SvgAtlasRenderer
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Complete Offline-First Pixel-Perfect Custom Character Maker & Sprite-Sheet Studio:
 * - 2-Mode Toggle: Workspace Mode (Drag OFF: pan/pinch-zoom) vs Part Edit Mode (Drag ON: move/scale/rotate + Lock Part).
 * - Supports BOTH transparent PNG and vector SVG per part or as a full 2048x2048 / 4096x4096 atlas.
 * - Live Pose/Expression tester (Idle, Walk, Run, Eye Close, Talk 1-10, Hand Poses, MTH1-MTH4).
 * - Clean ExportTex (auto-hides BG, Chart, borders, and Preview) + Import/Export Code (.fcchar.json / .svg).
 */
@Composable
fun CharacterMakerScreen(
    initialPose: AtlasPoseTemplate,
    existingCharacter: CharacterRigDefinition?,
    assetStorage: LocalAssetStorage,
    onSaveCharacter: (CharacterRigDefinition) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val scope = rememberCoroutineScope()

    var config by remember(existingCharacter?.id, initialPose) {
        val existingAtlas = existingCharacter?.spriteAtlasRig
        mutableStateOf(
            existingAtlas ?: SpriteAtlasRigConfig(
                poseTemplate = initialPose,
                resolutionPx = 2048,
                textureName = existingCharacter?.name?.ifBlank { "CustomHero" } ?: "CustomHero",
                importedFullSheetPath = existingCharacter?.customImagePath
            )
        )
    }

    var selectedResolution by remember(config.resolutionPx) {
        mutableStateOf(AtlasResolution.fromPx(config.resolutionPx))
    }
    var testAnimation by remember { mutableStateOf(MakerTestAnimation.IDLE) }
    var testAnimMenuExpanded by remember { mutableStateOf(false) }

    // Left workspace toggles
    var isDragPartMode by remember { mutableStateOf(false) }
    var showBgGuide by remember { mutableStateOf(true) }
    var showChartGrid by remember { mutableStateOf(true) }
    var showImportedSheet by remember { mutableStateOf(true) }

    // Right panel toggles
    var previewEnabled by remember { mutableStateOf(true) }
    var bodyPartsExpanded by remember { mutableStateOf(true) }
    var showCodeDialog by remember { mutableStateOf(false) }

    var selectedSlot by remember { mutableStateOf<AtlasPartSlot?>(AtlasPartSlot.HEAD) }
    var pendingImportSlot by remember { mutableStateOf<AtlasPartSlot?>(null) }

    var statusBanner by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    // Decoded bitmaps (strictly populated on Dispatchers.IO — never decoded inside DrawScope)
    var importedSheetBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var importedSheetSlices by remember { mutableStateOf<Map<AtlasPartSlot, Bitmap>>(emptyMap()) }
    var individualPartBitmaps by remember { mutableStateOf<Map<AtlasPartSlot, Bitmap>>(emptyMap()) }

    // Async load imported full sprite sheet + slice cache on Dispatchers.IO (high-DPI for SVG & PNG)
    LaunchedEffect(config.importedFullSheetPath, selectedResolution) {
        val path = config.importedFullSheetPath
        if (path.isNullOrBlank()) {
            importedSheetBitmap = null
            importedSheetSlices = emptyMap()
        } else {
            val targetPx = selectedResolution.px.coerceIn(2048, 4096)
            val loaded = withContext(Dispatchers.IO) {
                val bmp = assetStorage.loadBitmapFromFile(path, maxDimension = targetPx)
                val slices = bmp?.let { SpriteAtlasEngine.sliceAtlasIntoParts(it) } ?: emptyMap()
                bmp to slices
            }
            importedSheetBitmap = loaded.first
            importedSheetSlices = loaded.second
        }
    }

    // Async load individual part bitmaps on Dispatchers.IO whenever part paths change
    val partPathsSignature = remember(config.placements) {
        config.placements.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value.partImagePath}" }
    }
    LaunchedEffect(partPathsSignature) {
        val placementsCopy = config.placements
        val decodedMap = withContext(Dispatchers.IO) {
            val out = LinkedHashMap<AtlasPartSlot, Bitmap>()
            for (slot in AtlasPartSlot.entries) {
                val path = placementsCopy[slot.id]?.partImagePath ?: continue
                val bmp = assetStorage.loadBitmapFromFile(path, maxDimension = 768)
                if (bmp != null && !bmp.isRecycled) {
                    out[slot] = bmp
                }
            }
            out
        }
        individualPartBitmaps = decodedMap
    }

    // Assemble O(1) live rig preview slices respecting every part's visibility checkbox
    val livePreviewSlices = remember(individualPartBitmaps, importedSheetSlices, showImportedSheet, config.placements) {
        val merged = LinkedHashMap<AtlasPartSlot, Bitmap>()
        for (slot in AtlasPartSlot.entries) {
            if (!config.isSlotVisible(slot)) continue
            val individual = individualPartBitmaps[slot]
            if (individual != null && !individual.isRecycled) {
                merged[slot] = individual
            } else if (showImportedSheet) {
                val fromSheet = importedSheetSlices[slot]
                if (fromSheet != null && !fromSheet.isRecycled) {
                    merged[slot] = fromSheet
                }
            }
        }
        merged
    }

    // Zero-permission SAF OpenDocument picker supporting BOTH transparent PNG/WebP and vector SVG parts
    val partAssetPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetSlot = pendingImportSlot ?: return@rememberLauncherForActivityResult
        pendingImportSlot = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            val imported = assetStorage.importUriToOfflineStorage(uri, preferredType = null)
            if (imported != null) {
                val isSvg = SvgAtlasRenderer.isSvgPath(imported.filePath) ||
                    imported.mimeType.contains("svg", ignoreCase = true)
                val inlineSvg = if (isSvg) {
                    withContext(Dispatchers.IO) { assetStorage.readSvgTextFromPath(imported.filePath) }
                } else null
                val cur = config.placementFor(targetSlot)
                config = config.copy(
                    renderMode = if (isSvg) "SVG" else config.renderMode,
                    placements = config.placements + (targetSlot.id to cur.copy(
                        partImagePath = imported.filePath,
                        svgInline = inlineSvg,
                        visible = true,
                        locked = false,
                        offsetX = 0f,
                        offsetY = 0f,
                        scale = 1f,
                        rotationDeg = 0f
                    ))
                )
                selectedSlot = targetSlot
                statusBanner = "Imported ${targetSlot.label} (${if (isSvg) "HD SVG" else "PNG"})"
            } else {
                statusBanner = "Could not import PNG/SVG for ${targetSlot.label}"
            }
            isBusy = false
        }
    }

    // Zero-permission SAF OpenDocument picker for ImportTex (full 2048/4096 PNG/WebP or crisp SVG atlas)
    val fullSheetPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            val imported = assetStorage.importUriToOfflineStorage(uri, preferredType = null)
            if (imported != null) {
                val isSvg = SvgAtlasRenderer.isSvgPath(imported.filePath) ||
                    imported.mimeType.contains("svg", ignoreCase = true)
                val cleanedImportedName = imported.displayName
                    .replace(Regex("[_\\-]+"), " ")
                    .trim()
                val shouldAdoptImportedName = config.textureName.isBlank() ||
                    config.textureName.equals("CustomHero", ignoreCase = true) ||
                    (existingCharacter != null && !existingCharacter.isCustom &&
                        config.textureName.equals(existingCharacter.name, ignoreCase = true))
                val resolvedName = if (shouldAdoptImportedName && cleanedImportedName.isNotBlank()) {
                    cleanedImportedName
                } else {
                    config.textureName.ifBlank { cleanedImportedName.ifBlank { "CustomHero" } }
                }
                if (isSvg) {
                    val inlineSvg = withContext(Dispatchers.IO) {
                        assetStorage.readSvgTextFromPath(imported.filePath)
                    }
                    showImportedSheet = true
                    config = config.copy(
                        importedFullSheetPath = imported.filePath,
                        inlineAtlasSvg = inlineSvg,
                        renderMode = "SVG",
                        textureName = resolvedName
                    )
                    statusBanner = "Imported crisp HD SVG sprite sheet \"$resolvedName\" (${selectedResolution.label})"
                } else {
                    val (w, h) = assetStorage.inspectImageDimensions(imported.filePath)
                    val validation = SpriteAtlasEngine.validateAtlasDimensions(w, h)
                    selectedResolution = validation.recommendedResolution
                    showImportedSheet = true
                    config = config.copy(
                        importedFullSheetPath = imported.filePath,
                        inlineAtlasSvg = null,
                        renderMode = "PNG",
                        resolutionPx = validation.recommendedResolution.px,
                        textureName = resolvedName
                    )
                    statusBanner = validation.message
                }
            } else {
                statusBanner = "Could not import PNG/SVG sprite sheet"
            }
            isBusy = false
        }
    }

    fun runExportAtlas(saveToLibraryAfter: Boolean) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            try {
                val resPx = selectedResolution.px
                val cleanConfig = config.copy(
                    resolutionPx = resPx,
                    textureName = config.textureName.trim().ifBlank { "CustomHero" }
                )
                val result = withContext(Dispatchers.IO) {
                    val fullSheetForExport = if (showImportedSheet && !cleanConfig.importedFullSheetPath.isNullOrBlank()) {
                        assetStorage.loadBitmapFromFile(cleanConfig.importedFullSheetPath, maxDimension = resPx)
                    } else null
                    val exportParts = LinkedHashMap<AtlasPartSlot, Bitmap>()
                    for (slot in AtlasPartSlot.entries) {
                        val p = cleanConfig.placementFor(slot)
                        if (!p.visible || p.partImagePath.isNullOrBlank()) continue
                        val bmp = assetStorage.loadBitmapFromFile(p.partImagePath, maxDimension = 1536)
                        if (bmp != null && !bmp.isRecycled) {
                            exportParts[slot] = bmp
                        }
                    }
                    // Clean transparent PNG — Preview, BG, Chart, and borders are automatically excluded
                    val atlasBitmap = SpriteAtlasEngine.composeCleanAtlasBitmap(
                        config = cleanConfig,
                        resolutionPx = resPx,
                        importedSheetBitmap = fullSheetForExport,
                        partBitmapsBySlot = exportParts
                    )
                    val exported = assetStorage.exportTransparentAtlasPng(
                        bitmap = atlasBitmap,
                        textureName = cleanConfig.textureName,
                        saveToGallery = !saveToLibraryAfter
                    )
                    atlasBitmap.recycle()
                    exported
                }

                if (result == null) {
                    statusBanner = "Export failed — check available device storage."
                    return@launch
                }

                if (saveToLibraryAfter) {
                    val editingBundled = existingCharacter != null && !existingCharacter.isCustom
                    val baseId = if (existingCharacter != null && existingCharacter.isCustom) {
                        existingCharacter.id
                    } else {
                        "custom_${UUID.randomUUID().toString().take(12)}"
                    }
                    val finalCharName = if (editingBundled &&
                        cleanConfig.textureName.equals(existingCharacter?.name, ignoreCase = true)
                    ) {
                        "${cleanConfig.textureName} Custom"
                    } else {
                        cleanConfig.textureName
                    }
                    val updatedAtlasCfg = cleanConfig.copy(
                        textureName = finalCharName,
                        importedFullSheetPath = result.internalPath
                    )
                    val draftChar = (if (editingBundled) null else existingCharacter ?: CharacterRigDefinition(
                        id = baseId,
                        name = finalCharName,
                        category = CharacterCategory.LATEST,
                        isCustom = true,
                        isLatest = true
                    ) ?: CharacterRigDefinition(
                        id = baseId,
                        name = finalCharName,
                        category = CharacterCategory.LATEST,
                        isCustom = true,
                        isLatest = true
                    )).let { base ->
                        (base ?: CharacterRigDefinition(
                            id = baseId,
                            name = finalCharName,
                            category = CharacterCategory.LATEST,
                            isCustom = true,
                            isLatest = true
                        )).copy(
                            id = baseId,
                            name = finalCharName,
                            isCustom = true,
                            isLatest = true,
                            customImagePath = result.internalPath,
                            supportedOrientations = listOf(
                                cleanConfig.poseTemplate.defaultOrientation,
                                CharacterOrientation.FRONT,
                                CharacterOrientation.THREE_QUARTER_FRONT,
                                CharacterOrientation.SIDE
                            ).distinct(),
                            skeletonRig = (existingCharacter?.skeletonRig ?: SkeletonRigConfig()).copy(
                                enabled = true,
                                rigMode = CustomRigMode.ATLAS_RIG_PRO
                            ),
                            spriteAtlasRig = updatedAtlasCfg
                        )
                    }
                    val jsonPath = assetStorage.saveAtlasRigMetadataJson(draftChar)
                    val finalChar = draftChar.copy(
                        spriteAtlasRig = updatedAtlasCfg.copy(metadataJsonPath = jsonPath)
                    )
                    onSaveCharacter(finalChar)
                    statusBanner = "Saved \"${finalChar.name}\" (${resPx}px atlas + JSON rig)"
                } else {
                    statusBanner = "ExportTex saved clean transparent ${resPx}×${resPx} PNG to ${result.publicDisplayPath}"
                }
            } finally {
                isBusy = false
            }
        }
    }

    Scaffold(
        containerColor = StudioObsidian,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MakerTopControlBar(
                textureName = config.textureName,
                onTextureNameChange = { name -> config = config.copy(textureName = name) },
                resolution = selectedResolution,
                onSelectResolution = { res ->
                    selectedResolution = res
                    config = config.copy(resolutionPx = res.px)
                },
                testAnimation = testAnimation,
                testAnimMenuExpanded = testAnimMenuExpanded,
                onToggleTestAnimMenu = { testAnimMenuExpanded = it },
                onSelectTestAnim = { anim ->
                    testAnimation = anim
                    testAnimMenuExpanded = false
                },
                isBusy = isBusy,
                onImportTex = {
                    fullSheetPicker.launch(arrayOf("image/png", "image/webp", "image/svg+xml"))
                },
                onExportTex = { runExportAtlas(saveToLibraryAfter = false) },
                onOpenCodeDialog = { showCodeDialog = true },
                onSaveRig = { runExportAtlas(saveToLibraryAfter = true) },
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Optional status banner (import validation / export feedback)
            statusBanner?.let { banner ->
                Surface(
                    color = ElectricCyanDim,
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { statusBanner = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.Close, "Dismiss status", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (isBusy) {
                LinearProgressIndicator(
                    color = ElectricCyan,
                    trackColor = StudioPanel,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(6.dp)
            ) {
                val isWide = maxWidth >= 740.dp
                if (isWide) {
                    // Wide / Tablet / Landscape 3-Pane Studio Layout
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val selectedIsLocked = selectedSlot?.let { config.isSlotLocked(it) } == true
                        LeftToggleRail(
                            isDragPartMode = isDragPartMode,
                            showBgGuide = showBgGuide,
                            showChartGrid = showChartGrid,
                            showImportedSheet = showImportedSheet,
                            faceBakedInHead = config.faceBakedInHead,
                            selectedSlotLocked = selectedIsLocked,
                            hasSelectedSlot = selectedSlot != null,
                            canResetSelectedPart = isDragPartMode && selectedSlot != null && !selectedIsLocked,
                            onToggleDrag = { isDragPartMode = !isDragPartMode },
                            onToggleBg = { showBgGuide = !showBgGuide },
                            onToggleChart = { showChartGrid = !showChartGrid },
                            onToggleImg = { showImportedSheet = !showImportedSheet },
                            onToggleFaceBaked = {
                                val next = !config.faceBakedInHead
                                config = config.copy(faceBakedInHead = next)
                                statusBanner = if (next) {
                                    "Face Baked in Head ON — separate eye/brow/mouth overlays hidden"
                                } else {
                                    "Face Baked in Head OFF — separate eye/brow/mouth slots active"
                                }
                            },
                            onToggleLockSelectedPart = {
                                selectedSlot?.let { slot ->
                                    val cur = config.placementFor(slot)
                                    config = config.copy(
                                        placements = config.placements + (slot.id to cur.copy(locked = !cur.locked))
                                    )
                                }
                            },
                            onResetSelectedPart = {
                                selectedSlot?.let { slot ->
                                    val cur = config.placementFor(slot)
                                    if (!cur.locked) {
                                        config = config.copy(
                                            placements = config.placements + (slot.id to cur.copy(
                                                offsetX = 0f, offsetY = 0f, scale = 1f, rotationDeg = 0f
                                            ))
                                        )
                                    }
                                }
                            }
                        )

                        if (previewEnabled) {
                            LiveRigPreviewPanel(
                                poseTemplate = config.poseTemplate,
                                testAnimation = testAnimation,
                                liveSlices = livePreviewSlices,
                                skipFaceOverlays = config.faceBakedInHead,
                                modifier = Modifier
                                    .width(220.dp)
                                    .fillMaxHeight()
                            )
                        }

                        AtlasWorkspaceCanvas(
                            config = config,
                            partBitmapsBySlot = individualPartBitmaps,
                            importedSheetBitmap = importedSheetBitmap,
                            selectedSlot = selectedSlot,
                            isDragPartMode = isDragPartMode,
                            showBgGuide = showBgGuide,
                            showChartGrid = showChartGrid,
                            showImportedSheet = showImportedSheet,
                            onSelectSlot = { selectedSlot = it },
                            onTransformSelectedSlot = { dx, dy, scaleFactor, rotDelta ->
                                val slot = selectedSlot ?: return@AtlasWorkspaceCanvas
                                val cur = config.placementFor(slot)
                                if (cur.locked) return@AtlasWorkspaceCanvas
                                config = config.copy(
                                    placements = config.placements + (slot.id to cur.copy(
                                        offsetX = (cur.offsetX + dx).coerceIn(-0.6f, 0.6f),
                                        offsetY = (cur.offsetY + dy).coerceIn(-0.6f, 0.6f),
                                        scale = (cur.scale * scaleFactor).coerceIn(0.25f, 2.8f),
                                        rotationDeg = cur.rotationDeg + rotDelta
                                    ))
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )

                        RightPartsInspectorPanel(
                            config = config,
                            selectedSlot = selectedSlot,
                            previewEnabled = previewEnabled,
                            bodyPartsExpanded = bodyPartsExpanded,
                            loadedSheetSlots = if (showImportedSheet) importedSheetSlices.keys else emptySet(),
                            onTogglePreview = { previewEnabled = it },
                            onToggleBodyParts = { bodyPartsExpanded = it },
                            onSelectSlot = { selectedSlot = it },
                            onToggleSlotVisible = { slot, visible ->
                                val cur = config.placementFor(slot)
                                config = config.copy(
                                    placements = config.placements + (slot.id to cur.copy(visible = visible))
                                )
                            },
                            onToggleSlotLocked = { slot ->
                                val cur = config.placementFor(slot)
                                config = config.copy(
                                    placements = config.placements + (slot.id to cur.copy(locked = !cur.locked))
                                )
                            },
                            onImportSlotPng = { slot ->
                                pendingImportSlot = slot
                                partAssetPicker.launch(arrayOf("image/png", "image/webp", "image/svg+xml"))
                            },
                            onClearSlotPng = { slot ->
                                val cur = config.placementFor(slot)
                                config = config.copy(
                                    placements = config.placements + (slot.id to cur.copy(
                                        partImagePath = null, offsetX = 0f, offsetY = 0f, scale = 1f, rotationDeg = 0f
                                    ))
                                )
                            },
                            modifier = Modifier
                                .width(if (bodyPartsExpanded) 256.dp else 132.dp)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Compact Portrait Phone Layout: Top Live Preview + Atlas with Left Toggles, Bottom Right-Panel Dock
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val selectedIsLocked = selectedSlot?.let { config.isSlotLocked(it) } == true
                        Row(
                            modifier = Modifier
                                .weight(if (bodyPartsExpanded) 0.56f else 0.86f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LeftToggleRail(
                                isDragPartMode = isDragPartMode,
                                showBgGuide = showBgGuide,
                                showChartGrid = showChartGrid,
                                showImportedSheet = showImportedSheet,
                                faceBakedInHead = config.faceBakedInHead,
                                selectedSlotLocked = selectedIsLocked,
                                hasSelectedSlot = selectedSlot != null,
                                canResetSelectedPart = isDragPartMode && selectedSlot != null && !selectedIsLocked,
                                onToggleDrag = { isDragPartMode = !isDragPartMode },
                                onToggleBg = { showBgGuide = !showBgGuide },
                                onToggleChart = { showChartGrid = !showChartGrid },
                                onToggleImg = { showImportedSheet = !showImportedSheet },
                                onToggleFaceBaked = {
                                    val next = !config.faceBakedInHead
                                    config = config.copy(faceBakedInHead = next)
                                    statusBanner = if (next) {
                                        "Face Baked in Head ON — separate eye/brow/mouth overlays hidden"
                                    } else {
                                        "Face Baked in Head OFF — separate eye/brow/mouth slots active"
                                    }
                                },
                                onToggleLockSelectedPart = {
                                    selectedSlot?.let { slot ->
                                        val cur = config.placementFor(slot)
                                        config = config.copy(
                                            placements = config.placements + (slot.id to cur.copy(locked = !cur.locked))
                                        )
                                    }
                                },
                                onResetSelectedPart = {
                                    selectedSlot?.let { slot ->
                                        val cur = config.placementFor(slot)
                                        if (!cur.locked) {
                                            config = config.copy(
                                                placements = config.placements + (slot.id to cur.copy(
                                                    offsetX = 0f, offsetY = 0f, scale = 1f, rotationDeg = 0f
                                                ))
                                            )
                                        }
                                    }
                                }
                            )

                            if (previewEnabled) {
                                LiveRigPreviewPanel(
                                    poseTemplate = config.poseTemplate,
                                    testAnimation = testAnimation,
                                    liveSlices = livePreviewSlices,
                                    skipFaceOverlays = config.faceBakedInHead,
                                    modifier = Modifier
                                        .weight(0.42f)
                                        .fillMaxHeight()
                                )
                            }

                            AtlasWorkspaceCanvas(
                                config = config,
                                partBitmapsBySlot = individualPartBitmaps,
                                importedSheetBitmap = importedSheetBitmap,
                                selectedSlot = selectedSlot,
                                isDragPartMode = isDragPartMode,
                                showBgGuide = showBgGuide,
                                showChartGrid = showChartGrid,
                                showImportedSheet = showImportedSheet,
                                onSelectSlot = { selectedSlot = it },
                                onTransformSelectedSlot = { dx, dy, scaleFactor, rotDelta ->
                                    val slot = selectedSlot ?: return@AtlasWorkspaceCanvas
                                    val cur = config.placementFor(slot)
                                    if (cur.locked) return@AtlasWorkspaceCanvas
                                    config = config.copy(
                                        placements = config.placements + (slot.id to cur.copy(
                                            offsetX = (cur.offsetX + dx).coerceIn(-0.6f, 0.6f),
                                            offsetY = (cur.offsetY + dy).coerceIn(-0.6f, 0.6f),
                                            scale = (cur.scale * scaleFactor).coerceIn(0.25f, 2.8f),
                                            rotationDeg = cur.rotationDeg + rotDelta
                                        ))
                                    )
                                },
                                modifier = Modifier
                                    .weight(if (previewEnabled) 0.58f else 1f)
                                    .fillMaxHeight()
                            )
                        }

                        RightPartsInspectorPanel(
                            config = config,
                            selectedSlot = selectedSlot,
                            previewEnabled = previewEnabled,
                            bodyPartsExpanded = bodyPartsExpanded,
                            loadedSheetSlots = if (showImportedSheet) importedSheetSlices.keys else emptySet(),
                            onTogglePreview = { previewEnabled = it },
                            onToggleBodyParts = { bodyPartsExpanded = it },
                            onSelectSlot = { selectedSlot = it },
                            onToggleSlotVisible = { slot, visible ->
                                val cur = config.placementFor(slot)
                                config = config.copy(
                                    placements = config.placements + (slot.id to cur.copy(visible = visible))
                                )
                            },
                            onToggleSlotLocked = { slot ->
                                val cur = config.placementFor(slot)
                                config = config.copy(
                                    placements = config.placements + (slot.id to cur.copy(locked = !cur.locked))
                                )
                            },
                            onImportSlotPng = { slot ->
                                pendingImportSlot = slot
                                partAssetPicker.launch(arrayOf("image/png", "image/webp", "image/svg+xml"))
                            },
                            onClearSlotPng = { slot ->
                                val cur = config.placementFor(slot)
                                config = config.copy(
                                    placements = config.placements + (slot.id to cur.copy(
                                        partImagePath = null, offsetX = 0f, offsetY = 0f, scale = 1f, rotationDeg = 0f
                                    ))
                                )
                            },
                            modifier = Modifier
                                .then(
                                    if (bodyPartsExpanded) Modifier.weight(0.44f) else Modifier.wrapContentHeight()
                                )
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showCodeDialog) {
        CharacterCodeDialog(
            config = config,
            assetStorage = assetStorage,
            onApplyImportedConfig = { importedCfg, msg ->
                config = importedCfg
                selectedResolution = AtlasResolution.fromPx(importedCfg.resolutionPx)
                showImportedSheet = true
                statusBanner = msg
            },
            onDismiss = { showCodeDialog = false }
        )
    }
}

@Composable
private fun MakerTopControlBar(
    textureName: String,
    onTextureNameChange: (String) -> Unit,
    resolution: AtlasResolution,
    onSelectResolution: (AtlasResolution) -> Unit,
    testAnimation: MakerTestAnimation,
    testAnimMenuExpanded: Boolean,
    onToggleTestAnimMenu: (Boolean) -> Unit,
    onSelectTestAnim: (MakerTestAnimation) -> Unit,
    isBusy: Boolean,
    onImportTex: () -> Unit,
    onExportTex: () -> Unit,
    onOpenCodeDialog: () -> Unit,
    onSaveRig: () -> Unit,
    onBack: () -> Unit
) {
    Surface(color = StudioCharcoal, border = BorderStroke(1.dp, StudioBorderSubtle)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Back + Texture Name + Save Rig
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("maker_back_btn")
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        tint = StudioTextPrimary
                    )
                }

                OutlinedTextField(
                    value = textureName,
                    onValueChange = onTextureNameChange,
                    placeholder = { Text("Texture Name", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioTextPrimary),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StudioPanel,
                        unfocusedContainerColor = StudioPanel,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = StudioBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("maker_texture_name_input")
                )

                Button(
                    onClick = onSaveRig,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldLayer,
                        contentColor = StudioObsidian
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(42.dp)
                        .testTag("maker_save_character_btn")
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save Rig", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Row 2: Resolution Selector + Test Animation Dropdown + ImportTex + ExportTex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Resolution Selector (2048x2048 / 4096x4096)
                AtlasResolution.entries.forEach { res ->
                    val active = res == resolution
                    FilterChip(
                        selected = active,
                        onClick = { onSelectResolution(res) },
                        label = { Text(res.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = StudioPanel,
                            labelColor = StudioTextSecondary,
                            selectedContainerColor = ElectricCyanDim,
                            selectedLabelColor = ElectricCyan
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = active,
                            borderColor = StudioBorderSubtle,
                            selectedBorderColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("maker_res_${res.px}")
                    )
                }

                // Test Animation Dropdown (Idle, Walk, Hand Poses, MTH1-MTH4)
                Box {
                    OutlinedButton(
                        onClick = { onToggleTestAnimMenu(true) },
                        border = BorderStroke(1.dp, AmberKeyframe.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("maker_test_anim_dropdown")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = AmberKeyframe, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(testAnimation.displayName, style = MaterialTheme.typography.labelSmall, color = AmberKeyframe)
                        Icon(Icons.Rounded.ArrowDropDown, null, tint = AmberKeyframe, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = testAnimMenuExpanded,
                        onDismissRequest = { onToggleTestAnimMenu(false) },
                        containerColor = StudioPanel
                    ) {
                        MakerTestAnimation.entries.forEach { anim ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            anim.displayName,
                                            color = if (anim == testAnimation) ElectricCyan else StudioTextPrimary,
                                            fontWeight = if (anim == testAnimation) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                },
                                onClick = { onSelectTestAnim(anim) },
                                modifier = Modifier.testTag("maker_test_anim_${anim.name.lowercase()}")
                            )
                        }
                    }
                }

                // ImportTex
                OutlinedButton(
                    onClick = onImportTex,
                    enabled = !isBusy,
                    border = BorderStroke(1.dp, ElectricCyan),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("maker_import_tex_btn")
                ) {
                    Icon(Icons.Rounded.UploadFile, null, tint = ElectricCyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ImportTex", style = MaterialTheme.typography.labelSmall, color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }

                // ExportTex
                Button(
                    onClick = onExportTex,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = StudioObsidian
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("maker_export_tex_btn")
                ) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ExportTex", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                // Import / Export Character Code (.fcchar.json / .svg)
                OutlinedButton(
                    onClick = onOpenCodeDialog,
                    enabled = !isBusy,
                    border = BorderStroke(1.dp, EmeraldLayer),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("maker_code_dialog_btn")
                ) {
                    Icon(Icons.Rounded.Code, null, tint = EmeraldLayer, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Code", style = MaterialTheme.typography.labelSmall, color = EmeraldLayer, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun LeftToggleRail(
    isDragPartMode: Boolean,
    showBgGuide: Boolean,
    showChartGrid: Boolean,
    showImportedSheet: Boolean,
    faceBakedInHead: Boolean,
    selectedSlotLocked: Boolean,
    hasSelectedSlot: Boolean,
    canResetSelectedPart: Boolean,
    onToggleDrag: () -> Unit,
    onToggleBg: () -> Unit,
    onToggleChart: () -> Unit,
    onToggleImg: () -> Unit,
    onToggleFaceBaked: () -> Unit,
    onToggleLockSelectedPart: () -> Unit,
    onResetSelectedPart: () -> Unit
) {
    Surface(
        color = StudioCharcoal,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, StudioBorderSubtle),
        modifier = Modifier.width(56.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RailToggleButton(
                label = if (isDragPartMode) "Part" else "Pan",
                icon = if (isDragPartMode) Icons.Rounded.OpenWith else Icons.Rounded.PanTool,
                active = isDragPartMode,
                accentColor = AmberKeyframe,
                testTag = "maker_toggle_drag",
                onClick = onToggleDrag
            )
            RailToggleButton(
                label = "BG",
                icon = Icons.Rounded.AccessibilityNew,
                active = showBgGuide,
                accentColor = ElectricCyan,
                testTag = "maker_toggle_bg",
                onClick = onToggleBg
            )
            RailToggleButton(
                label = "Chart",
                icon = Icons.Rounded.GridOn,
                active = showChartGrid,
                accentColor = EmeraldLayer,
                testTag = "maker_toggle_chart",
                onClick = onToggleChart
            )
            RailToggleButton(
                label = "IMG",
                icon = Icons.Rounded.Image,
                active = showImportedSheet,
                accentColor = Color(0xFFA78BFA),
                testTag = "maker_toggle_img",
                onClick = onToggleImg
            )
            RailToggleButton(
                label = if (faceBakedInHead) "Baked" else "Face",
                icon = if (faceBakedInHead) Icons.Rounded.FaceRetouchingOff else Icons.Rounded.Face,
                active = faceBakedInHead,
                accentColor = Color(0xFFF472B6),
                testTag = "maker_toggle_face_baked",
                onClick = onToggleFaceBaked
            )
            if (hasSelectedSlot) {
                HorizontalDivider(color = StudioBorderSubtle, modifier = Modifier.padding(vertical = 1.dp))
                RailToggleButton(
                    label = if (selectedSlotLocked) "Locked" else "Lock",
                    icon = if (selectedSlotLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    active = selectedSlotLocked,
                    accentColor = CoralPlayhead,
                    testTag = "maker_toggle_lock_part",
                    onClick = onToggleLockSelectedPart
                )
            }
            if (canResetSelectedPart) {
                RailToggleButton(
                    label = "Reset",
                    icon = Icons.Rounded.RestartAlt,
                    active = false,
                    accentColor = CoralPlayhead,
                    testTag = "maker_reset_part_btn",
                    onClick = onResetSelectedPart
                )
            }
        }
    }
}

@Composable
private fun RailToggleButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (active) accentColor.copy(alpha = 0.22f) else StudioPanel,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (active) accentColor else StudioBorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) accentColor else StudioTextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) accentColor else StudioTextSecondary,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RightPartsInspectorPanel(
    config: SpriteAtlasRigConfig,
    selectedSlot: AtlasPartSlot?,
    previewEnabled: Boolean,
    bodyPartsExpanded: Boolean,
    loadedSheetSlots: Set<AtlasPartSlot>,
    onTogglePreview: (Boolean) -> Unit,
    onToggleBodyParts: (Boolean) -> Unit,
    onSelectSlot: (AtlasPartSlot) -> Unit,
    onToggleSlotVisible: (AtlasPartSlot, Boolean) -> Unit,
    onToggleSlotLocked: (AtlasPartSlot) -> Unit,
    onImportSlotPng: (AtlasPartSlot) -> Unit,
    onClearSlotPng: (AtlasPartSlot) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioCharcoal,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, StudioBorderSubtle),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Preview & Body Parts toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Switch(
                        checked = previewEnabled,
                        onCheckedChange = onTogglePreview,
                        modifier = Modifier.testTag("maker_toggle_preview")
                    )
                    Text("Preview", style = MaterialTheme.typography.labelMedium, color = StudioTextPrimary)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Body Parts", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                    Switch(
                        checked = bodyPartsExpanded,
                        onCheckedChange = onToggleBodyParts,
                        modifier = Modifier.testTag("maker_toggle_body_parts")
                    )
                }
            }

            if (bodyPartsExpanded) {
                HorizontalDivider(color = StudioBorderSubtle)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("maker_body_parts_list"),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(AtlasPartSlot.entries, key = { it.id }) { slot ->
                        val placement = config.placementFor(slot)
                        val hasCustomPart = !placement.partImagePath.isNullOrBlank()
                        val isSvgPart = SvgAtlasRenderer.isSvgPath(placement.partImagePath)
                        val hasSheetSlice = slot in loadedSheetSlots
                        val isSelected = slot == selectedSlot
                        val slotColor = Color(ProceduralRigRenderer.parseColorSafe(slot.guideColorHex, 0xFF38BDF8.toInt()))

                        Surface(
                            color = if (isSelected) ElectricCyanDim else StudioPanel,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) ElectricCyan else StudioBorderSubtle
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSlot(slot) }
                                .testTag("maker_part_row_${slot.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Checkbox(
                                    checked = placement.visible,
                                    onCheckedChange = { checked -> onToggleSlotVisible(slot, checked) },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("maker_part_vis_${slot.id}")
                                )

                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(slotColor)
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = slot.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StudioTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when {
                                            hasCustomPart && isSvgPart -> "SVG Loaded"
                                            hasCustomPart -> "PNG Loaded"
                                            hasSheetSlice -> "From Sheet"
                                            else -> slot.category.displayName
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            hasCustomPart -> EmeraldLayer
                                            hasSheetSlice -> ElectricCyan
                                            else -> StudioTextMuted
                                        },
                                        maxLines = 1
                                    )
                                }

                                IconButton(
                                    onClick = { onToggleSlotLocked(slot) },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("maker_part_lock_${slot.id}")
                                ) {
                                    Icon(
                                        imageVector = if (placement.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                        contentDescription = if (placement.locked) "Unlock ${slot.label}" else "Lock ${slot.label}",
                                        tint = if (placement.locked) AmberKeyframe else StudioTextMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                if (hasCustomPart) {
                                    IconButton(
                                        onClick = { onClearSlotPng(slot) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Clear,
                                            contentDescription = "Clear ${slot.label}",
                                            tint = CoralPlayhead,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { onImportSlotPng(slot) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (hasCustomPart) EmeraldLayer.copy(alpha = 0.2f) else StudioCard,
                                        contentColor = if (hasCustomPart) EmeraldLayer else ElectricCyan
                                    ),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("maker_part_import_${slot.id}")
                                ) {
                                    Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(if (hasCustomPart) "Swap" else "PNG/SVG", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
