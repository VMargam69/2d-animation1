package com.example.ui.screens.maker

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.ProceduralRigRenderer
import com.example.engine.SpriteAtlasEngine
import com.example.engine.SpriteAtlasRigRenderer
import com.example.model.*
import com.example.ui.theme.*
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive Center Atlas Editor Canvas:
 * - Drag OFF: Pinch-zoom and pan the atlas workspace smoothly; tap a slot to select, tap outside to deselect.
 * - Drag ON: Drag, pinch-scale, and rotate the currently selected body part live; tap outside to deselect.
 * - Respects BG (reference character sheet), Chart (color-coded slot grid + pivot dots), and IMG (imported sheet).
 * - ZERO bitmap decoding inside DrawScope: all bitmaps are passed in pre-cached from Dispatchers.IO.
 */
@Composable
fun AtlasWorkspaceCanvas(
    config: SpriteAtlasRigConfig,
    partBitmapsBySlot: Map<AtlasPartSlot, Bitmap>,
    importedSheetBitmap: Bitmap?,
    selectedSlot: AtlasPartSlot?,
    isDragPartMode: Boolean,
    showBgGuide: Boolean,
    showChartGrid: Boolean,
    showImportedSheet: Boolean,
    onSelectSlot: (AtlasPartSlot?) -> Unit,
    onTransformSelectedSlot: (dxNorm: Float, dyNorm: Float, scaleFactor: Float, rotationDeltaDeg: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasZoom by remember { mutableFloatStateOf(1.0f) }
    var canvasPan by remember { mutableStateOf(Offset.Zero) }

    val currentDragMode by rememberUpdatedState(isDragPartMode)
    val currentSelectedSlot by rememberUpdatedState(selectedSlot)
    val currentConfig by rememberUpdatedState(config)
    val currentOnSelect by rememberUpdatedState(onSelectSlot)
    val currentOnTransform by rememberUpdatedState(onTransformSelectedSlot)

    val bitmapPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val loadedSlots = remember(partBitmapsBySlot, importedSheetBitmap, showImportedSheet, config.placements) {
        AtlasPartSlot.entries.filter { slot ->
            val p = config.placementFor(slot)
            p.visible && (partBitmapsBySlot[slot] != null || (showImportedSheet && importedSheetBitmap != null))
        }.toSet()
    }
    val selectedIsLocked = selectedSlot?.let { config.isSlotLocked(it) } == true

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E131D))
            .testTag("atlas_editor_canvas")
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val minSide = min(size.width.toFloat(), size.height.toFloat())
                        val sheetSize = minSide * 0.94f * canvasZoom
                        val left = (size.width - sheetSize) * 0.5f + canvasPan.x
                        val top = (size.height - sheetSize) * 0.5f + canvasPan.y
                        val u = (tap.x - left) / sheetSize
                        val v = (tap.y - top) / sheetSize
                        if (u in 0f..1f && v in 0f..1f) {
                            currentOnSelect(AtlasPartSlot.hitTest(u, v))
                        } else {
                            // Clear selection on empty-space tap so placed parts cannot shift accidentally
                            currentOnSelect(null)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val minSide = min(size.width.toFloat(), size.height.toFloat()).coerceAtLeast(1f)
                        val sheetSize = minSide * 0.94f * canvasZoom
                        val activeSlot = currentSelectedSlot
                        if (currentDragMode && activeSlot != null) {
                            // Respect per-part lock toggle so locked slots never shift accidentally
                            if (!currentConfig.isSlotLocked(activeSlot)) {
                                val slotW = (activeSlot.widthU * sheetSize).coerceAtLeast(12f)
                                val slotH = (activeSlot.heightV * sheetSize).coerceAtLeast(12f)
                                currentOnTransform(
                                    pan.x / slotW,
                                    pan.y / slotH,
                                    zoom,
                                    rotation
                                )
                            }
                        } else {
                            canvasZoom = (canvasZoom * zoom).coerceIn(0.65f, 4.5f)
                            val maxPan = minSide * canvasZoom * 0.75f
                            canvasPan = Offset(
                                x = (canvasPan.x + pan.x).coerceIn(-maxPan, maxPan),
                                y = (canvasPan.y + pan.y).coerceIn(-maxPan, maxPan)
                            )
                        }
                    }
                }
        ) {
            val minSide = min(size.width, size.height)
            val sheetSize = minSide * 0.94f * canvasZoom
            val left = (size.width - sheetSize) * 0.5f + canvasPan.x
            val top = (size.height - sheetSize) * 0.5f + canvasPan.y

            drawIntoCanvas { composeCanvas ->
                val nc = composeCanvas.nativeCanvas
                nc.save()
                nc.translate(left, top)
                nc.clipRect(0f, 0f, sheetSize, sheetSize)

                // 1. Transparency checkerboard backdrop
                drawCheckerboard(nc, sheetSize, sheetSize)

                // 2. BG Reference character sheet guide (never included in ExportTex)
                if (showBgGuide) {
                    SpriteAtlasEngine.drawReferenceGuideSheet(nc, sheetSize, sheetSize, config.poseTemplate)
                }

                // 3. IMG Full imported sprite sheet preview (drawn per-slot respecting visibility & placement)
                if (showImportedSheet && importedSheetBitmap != null && !importedSheetBitmap.isRecycled) {
                    for (slot in AtlasPartSlot.entries) {
                        val placement = config.placementFor(slot)
                        if (!placement.visible) continue
                        if (partBitmapsBySlot[slot] != null) continue // Individual part overrides slot
                        val sl = slot.u0 * sheetSize
                        val st = slot.v0 * sheetSize
                        val sw = slot.widthU * sheetSize
                        val sh = slot.heightV * sheetSize
                        val src = Rect(
                            (slot.u0 * importedSheetBitmap.width).roundToInt().coerceIn(0, importedSheetBitmap.width - 1),
                            (slot.v0 * importedSheetBitmap.height).roundToInt().coerceIn(0, importedSheetBitmap.height - 1),
                            (slot.u1 * importedSheetBitmap.width).roundToInt().coerceIn(1, importedSheetBitmap.width),
                            (slot.v1 * importedSheetBitmap.height).roundToInt().coerceIn(1, importedSheetBitmap.height)
                        )
                        if (src.width() > 0 && src.height() > 0) {
                            nc.save()
                            nc.clipRect(sl, st, sl + sw, st + sh)
                            nc.translate(sl + sw * 0.5f + placement.offsetX * sw, st + sh * 0.5f + placement.offsetY * sh)
                            nc.rotate(placement.rotationDeg)
                            val sc = placement.scale.coerceIn(0.2f, 3.0f)
                            nc.scale(sc, sc)
                            nc.drawBitmap(importedSheetBitmap, src, RectF(-sw * 0.5f, -sh * 0.5f, sw * 0.5f, sh * 0.5f), bitmapPaint)
                            nc.restore()
                        }
                    }
                }

                // 4. Individual imported body part PNGs / SVGs
                for (slot in AtlasPartSlot.entries) {
                    val placement = config.placementFor(slot)
                    if (!placement.visible) continue
                    val bmp = partBitmapsBySlot[slot] ?: continue
                    if (bmp.isRecycled) continue
                    SpriteAtlasEngine.drawPlacedPartInSlot(
                        canvas = nc,
                        bitmap = bmp,
                        slotLeft = slot.u0 * sheetSize,
                        slotTop = slot.v0 * sheetSize,
                        slotW = slot.widthU * sheetSize,
                        slotH = slot.heightV * sheetSize,
                        placement = placement,
                        paint = bitmapPaint
                    )
                }

                // 5. Chart overlay (color-coded slots + joint/pivot dots)
                if (showChartGrid) {
                    SpriteAtlasEngine.drawAtlasChartOverlay(
                        canvas = nc,
                        width = sheetSize,
                        height = sheetSize,
                        selectedSlot = selectedSlot,
                        loadedSlots = loadedSlots
                    )
                } else if (selectedSlot != null) {
                    val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 2.6f
                        color = if (selectedIsLocked) 0xFFF59E0B.toInt() else android.graphics.Color.WHITE
                    }
                    nc.drawRect(
                        selectedSlot.u0 * sheetSize,
                        selectedSlot.v0 * sheetSize,
                        selectedSlot.u1 * sheetSize,
                        selectedSlot.v1 * sheetSize,
                        selPaint
                    )
                }

                // 5B. Lock badges on locked slots (UI only, never exported)
                val lockBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF59E0B.toInt() }
                val lockTxtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF0A0D12.toInt()
                    textSize = (sheetSize * 0.015f).coerceIn(10f, 18f)
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }
                for (slot in AtlasPartSlot.entries) {
                    if (config.isSlotLocked(slot)) {
                        val rx = slot.u1 * sheetSize - 11f
                        val ry = slot.v0 * sheetSize + 11f
                        nc.drawCircle(rx, ry, 8.5f, lockBgPaint)
                        nc.drawText("L", rx, ry + lockTxtPaint.textSize * 0.36f, lockTxtPaint)
                    }
                }

                // Outer sheet frame border (UI only, never exported)
                val outerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = 0x5538BDF8
                }
                nc.drawRect(0f, 0f, sheetSize, sheetSize, outerBorder)
                nc.restore()
            }
        }

        // Floating HUD chip showing current mode & zoom + reset button
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = StudioObsidian.copy(alpha = 0.88f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = when {
                        isDragPartMode && selectedSlot != null && selectedIsLocked ->
                            "Locked: ${selectedSlot.label} (unlock to move)"
                        isDragPartMode && selectedSlot != null ->
                            "Part Edit: ${selectedSlot.label} • Drag/Pinch/Rotate"
                        isDragPartMode ->
                            "Part Edit • Tap slot (empty space clears)"
                        else ->
                            "Workspace ${(canvasZoom * 100).toInt()}% • Pan/Pinch"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        selectedIsLocked && isDragPartMode -> CoralPlayhead
                        isDragPartMode -> AmberKeyframe
                        else -> ElectricCyan
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (canvasZoom != 1f || canvasPan != Offset.Zero) {
                SmallFloatingActionButton(
                    onClick = {
                        canvasZoom = 1f
                        canvasPan = Offset.Zero
                    },
                    containerColor = StudioPanel,
                    contentColor = StudioTextPrimary,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Rounded.CenterFocusStrong, "Reset atlas view", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Left Side Live Rig Preview Canvas:
 * Renders the assembled, animated sprite-atlas character in real time as parts are imported or toggled.
 * Supports dragging & pinch-zooming the preview character so the user can inspect head, hands, or feet up close.
 */
@Composable
fun LiveRigPreviewPanel(
    poseTemplate: AtlasPoseTemplate,
    testAnimation: MakerTestAnimation,
    liveSlices: Map<AtlasPartSlot, Bitmap>,
    skipFaceOverlays: Boolean = false,
    modifier: Modifier = Modifier
) {
    var previewOffset by remember { mutableStateOf(Offset.Zero) }
    var previewZoom by remember { mutableFloatStateOf(1.0f) }

    val transition = rememberInfiniteTransition(label = "maker_live_rig_${testAnimation.name}")
    val durationMs = ((testAnimation.preset.defaultDurationFrames * 1000) / 24).coerceIn(750, 2200)
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rig_phase"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101724))
            .testTag("live_rig_preview_panel")
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        previewZoom = (previewZoom * zoom).coerceIn(0.6f, 3.2f)
                        val limitX = size.width * 0.75f
                        val limitY = size.height * 0.75f
                        previewOffset = Offset(
                            x = (previewOffset.x + pan.x).coerceIn(-limitX, limitX),
                            y = (previewOffset.y + pan.y).coerceIn(-limitY, limitY)
                        )
                    }
                }
        ) {
            drawIntoCanvas { composeCanvas ->
                val nc = composeCanvas.nativeCanvas
                val minDim = min(size.width, size.height)
                val rigScale = (minDim / 210f) * previewZoom
                val basePose = ProceduralRigRenderer.computePose(testAnimation.preset, phase)
                val posed = basePose.withOffsets(PoseKeyframeOffsets(), testAnimation.expression, swayDeg = 0f)

                nc.save()
                nc.translate(size.width * 0.5f + previewOffset.x, size.height * 0.57f + previewOffset.y)
                nc.translate(0f, posed.bodyBobY * rigScale)
                nc.rotate(posed.bodyTiltDeg)
                nc.scale(posed.squashX, posed.squashY)

                SpriteAtlasRigRenderer.drawSpriteAtlasRig(
                    canvas = nc,
                    slices = liveSlices,
                    poseTemplate = poseTemplate,
                    pose = posed,
                    rigScale = rigScale,
                    alpha = 1f,
                    handPose = testAnimation.handPose,
                    blinkOverride = testAnimation.blinkOverride,
                    mouthSlotOverride = testAnimation.mouthSlotOverride,
                    drawGhostFallbackWhenEmpty = true,
                    skipFaceOverlays = skipFaceOverlays
                )
                nc.restore()
            }
        }

        // Top badge
        Surface(
            color = StudioObsidian.copy(alpha = 0.82f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
        ) {
            Text(
                text = "Live Rig • ${testAnimation.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldLayer,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }

        // Drag hint / reset button
        if (previewOffset != Offset.Zero || previewZoom != 1f) {
            SmallFloatingActionButton(
                onClick = {
                    previewOffset = Offset.Zero
                    previewZoom = 1f
                },
                containerColor = StudioPanel,
                contentColor = StudioTextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(30.dp)
            ) {
                Icon(Icons.Rounded.CenterFocusStrong, "Reset preview position", modifier = Modifier.size(16.dp))
            }
        } else {
            Surface(
                color = StudioObsidian.copy(alpha = 0.72f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp)
            ) {
                Text(
                    text = "Drag/pinch to inspect head, hands, feet",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun drawCheckerboard(canvas: android.graphics.Canvas, width: Float, height: Float) {
    val tile = (min(width, height) / 24f).coerceIn(10f, 32f)
    val dark = Paint().apply { color = 0xFF141A24.toInt() }
    val light = Paint().apply { color = 0xFF1C2432.toInt() }
    var row = 0
    var y = 0f
    while (y < height) {
        var col = 0
        var x = 0f
        while (x < width) {
            val paint = if ((row + col) % 2 == 0) dark else light
            canvas.drawRect(x, y, min(x + tile, width), min(y + tile, height), paint)
            x += tile
            col++
        }
        y += tile
        row++
    }
}
