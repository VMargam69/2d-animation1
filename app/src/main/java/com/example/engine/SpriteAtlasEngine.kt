package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class AtlasValidationResult(
    val isValid: Boolean,
    val isExactStandardSize: Boolean,
    val width: Int,
    val height: Int,
    val recommendedResolution: AtlasResolution,
    val message: String
)

/**
 * Pure offline Sprite Atlas validator, normalized (0..1) slicer, clean transparent PNG compositor,
 * and Atlas Editor guide-sheet / chart overlay drawer.
 *
 * IMPORTANT: All bitmap slicing and compositing functions MUST be called on Dispatchers.IO or
 * Dispatchers.Default — never inside Compose DrawScope.
 */
object SpriteAtlasEngine {

    fun validateAtlasDimensions(width: Int, height: Int): AtlasValidationResult {
        if (width <= 0 || height <= 0) {
            return AtlasValidationResult(
                isValid = false,
                isExactStandardSize = false,
                width = width,
                height = height,
                recommendedResolution = AtlasResolution.RES_2048,
                message = "Invalid image dimensions (${width}×${height})."
            )
        }
        val aspect = width.toFloat() / height.toFloat()
        val isSquare = abs(aspect - 1f) <= 0.04f
        val exact2k = width == 2048 && height == 2048
        val exact4k = width == 4096 && height == 4096
        val recommended = if (width >= 3072 || height >= 3072) AtlasResolution.RES_4096 else AtlasResolution.RES_2048

        return when {
            exact2k -> AtlasValidationResult(
                isValid = true,
                isExactStandardSize = true,
                width = width,
                height = height,
                recommendedResolution = AtlasResolution.RES_2048,
                message = "Valid 2048×2048 sprite atlas."
            )
            exact4k -> AtlasValidationResult(
                isValid = true,
                isExactStandardSize = true,
                width = width,
                height = height,
                recommendedResolution = AtlasResolution.RES_4096,
                message = "Valid 4096×4096 UHD sprite atlas."
            )
            isSquare && width >= 512 -> AtlasValidationResult(
                isValid = true,
                isExactStandardSize = false,
                width = width,
                height = height,
                recommendedResolution = recommended,
                message = "Loaded ${width}×${height} square sheet (normalized 0..1 slicing active; recommended 2048×2048 or 4096×4096)."
            )
            else -> AtlasValidationResult(
                isValid = false,
                isExactStandardSize = false,
                width = width,
                height = height,
                recommendedResolution = recommended,
                message = "Non-standard ${width}×${height} sheet — expected square 2048×2048 or 4096×4096 PNG. Scaled to fit atlas slots."
            )
        }
    }

    /**
     * Slices a loaded sprite-sheet bitmap into per-slot bitmaps using normalized (0..1) UVs.
     * Completely transparent empty slots are omitted so downstream rendering stays O(1) & memory-light.
     * Call ONLY on Dispatchers.IO / Default.
     */
    fun sliceAtlasIntoParts(
        atlasBitmap: Bitmap,
        slots: List<AtlasPartSlot> = AtlasPartSlot.entries
    ): Map<AtlasPartSlot, Bitmap> {
        if (atlasBitmap.isRecycled || atlasBitmap.width < 16 || atlasBitmap.height < 16) {
            return emptyMap()
        }
        val w = atlasBitmap.width
        val h = atlasBitmap.height
        val result = LinkedHashMap<AtlasPartSlot, Bitmap>(slots.size)
        for (slot in slots) {
            val sx = (slot.u0 * w).roundToInt().coerceIn(0, w - 1)
            val sy = (slot.v0 * h).roundToInt().coerceIn(0, h - 1)
            val ex = (slot.u1 * w).roundToInt().coerceIn(sx + 1, w)
            val ey = (slot.v1 * h).roundToInt().coerceIn(sy + 1, h)
            val sw = (ex - sx).coerceAtLeast(1)
            val sh = (ey - sy).coerceAtLeast(1)
            val cropped = runCatching { Bitmap.createBitmap(atlasBitmap, sx, sy, sw, sh) }.getOrNull() ?: continue
            if (hasVisiblePixels(cropped)) {
                result[slot] = cropped
            } else {
                cropped.recycle()
            }
        }
        return result
    }

    /**
     * Composes a clean, transparent ARGB_8888 PNG sprite atlas (2048×2048 or 4096×4096) with
     * ZERO preview borders, ZERO chart grid lines, ZERO pivot dots, and ZERO reference background.
     * Call ONLY on Dispatchers.IO / Default.
     */
    fun composeCleanAtlasBitmap(
        config: SpriteAtlasRigConfig,
        resolutionPx: Int,
        importedSheetBitmap: Bitmap?,
        partBitmapsBySlot: Map<AtlasPartSlot, Bitmap>
    ): Bitmap {
        val targetSize = if (resolutionPx >= 4096) 4096 else 2048
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val sizeF = targetSize.toFloat()

        for (slot in AtlasPartSlot.entries) {
            val placement = config.placementFor(slot)
            if (!placement.visible) continue

            val slotLeft = slot.u0 * sizeF
            val slotTop = slot.v0 * sizeF
            val slotRight = slot.u1 * sizeF
            val slotBottom = slot.v1 * sizeF
            val slotW = slotRight - slotLeft
            val slotH = slotBottom - slotTop

            val individualBmp = partBitmapsBySlot[slot]?.takeIf { !it.isRecycled }
            if (individualBmp != null) {
                drawPlacedPartInSlot(
                    canvas = canvas,
                    bitmap = individualBmp,
                    slotLeft = slotLeft,
                    slotTop = slotTop,
                    slotW = slotW,
                    slotH = slotH,
                    placement = placement,
                    paint = paint
                )
            } else if (importedSheetBitmap != null && !importedSheetBitmap.isRecycled) {
                // Copy matching normalized region from imported full sheet, respecting user offset/scale/rotation
                val srcRect = Rect(
                    (slot.u0 * importedSheetBitmap.width).roundToInt().coerceIn(0, importedSheetBitmap.width - 1),
                    (slot.v0 * importedSheetBitmap.height).roundToInt().coerceIn(0, importedSheetBitmap.height - 1),
                    (slot.u1 * importedSheetBitmap.width).roundToInt().coerceIn(1, importedSheetBitmap.width),
                    (slot.v1 * importedSheetBitmap.height).roundToInt().coerceIn(1, importedSheetBitmap.height)
                )
                if (srcRect.width() > 0 && srcRect.height() > 0) {
                    canvas.save()
                    canvas.clipRect(slotLeft, slotTop, slotRight, slotBottom)
                    val cx = slotLeft + slotW * 0.5f + placement.offsetX * slotW
                    val cy = slotTop + slotH * 0.5f + placement.offsetY * slotH
                    canvas.translate(cx, cy)
                    canvas.rotate(placement.rotationDeg)
                    val s = placement.scale.coerceIn(0.2f, 3.0f)
                    canvas.scale(s, s)
                    val dst = RectF(-slotW * 0.5f, -slotH * 0.5f, slotW * 0.5f, slotH * 0.5f)
                    canvas.drawBitmap(importedSheetBitmap, srcRect, dst, paint)
                    canvas.restore()
                }
            }
        }
        return out
    }

    /**
     * Draws a single part bitmap inside its assigned slot with aspect-fit + user drag/scale/rotation,
     * clipped cleanly so adjacent slots can never bleed into each other.
     */
    fun drawPlacedPartInSlot(
        canvas: Canvas,
        bitmap: Bitmap,
        slotLeft: Float,
        slotTop: Float,
        slotW: Float,
        slotH: Float,
        placement: AtlasPartPlacement,
        paint: Paint
    ) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        canvas.save()
        canvas.clipRect(slotLeft, slotTop, slotLeft + slotW, slotTop + slotH)
        val cx = slotLeft + slotW * 0.5f + placement.offsetX * slotW
        val cy = slotTop + slotH * 0.5f + placement.offsetY * slotH
        canvas.translate(cx, cy)
        canvas.rotate(placement.rotationDeg)
        val userScale = placement.scale.coerceIn(0.2f, 3.0f)
        canvas.scale(userScale, userScale)

        val padW = slotW * 0.90f
        val padH = slotH * 0.90f
        val fit = min(padW / bitmap.width.toFloat(), padH / bitmap.height.toFloat())
        val dw = bitmap.width * fit
        val dh = bitmap.height * fit
        canvas.drawBitmap(bitmap, null, RectF(-dw * 0.5f, -dh * 0.5f, dw * 0.5f, dh * 0.5f), paint)
        canvas.restore()
    }

    /**
     * Draws the procedural BG reference character guide sheet inside each slot so artists can align
     * heads, facial parts, limbs, and hands accurately inside the Atlas Editor.
     */
    fun drawReferenceGuideSheet(
        canvas: Canvas,
        width: Float,
        height: Float,
        pose: AtlasPoseTemplate
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (min(width, height) * 0.0028f).coerceAtLeast(1.4f)
            color = 0x5594A3B8
        }

        val skinGuide = 0x35F2C6A0
        val suitGuide = 0x3538BDF8
        val hairGuide = 0x35A78BFA
        val accentGuide = 0x38F472B6
        val legGuide = 0x3564748B
        val xBias = when (pose) {
            AtlasPoseTemplate.FRONT -> 0f
            AtlasPoseTemplate.THREE_QUARTER_FRONT -> 0.08f
            AtlasPoseTemplate.BACK_SIDE -> 0.14f
        }

        for (slot in AtlasPartSlot.entries) {
            val l = slot.u0 * width
            val t = slot.v0 * height
            val r = slot.u1 * width
            val b = slot.v1 * height
            val sw = r - l
            val sh = b - t
            val cx = l + sw * 0.5f
            val cy = t + sh * 0.5f

            when (slot) {
                AtlasPartSlot.HEAD -> {
                    fill.color = skinGuide
                    val rad = min(sw, sh) * 0.34f
                    val headRect = RectF(cx - rad * 0.88f, cy - rad, cx + rad * 0.88f, cy + rad)
                    canvas.drawOval(headRect, fill)
                    canvas.drawOval(headRect, stroke)
                    // Subtle ear & centerline
                    canvas.drawLine(cx + sw * xBias, cy - rad * 0.9f, cx + sw * xBias, cy + rad * 0.9f, stroke)
                }
                AtlasPartSlot.HAIR_FRONT -> {
                    fill.color = hairGuide
                    val rad = min(sw, sh) * 0.36f
                    canvas.drawArc(RectF(cx - rad, cy - rad, cx + rad, cy + rad * 0.45f), 180f, 180f, true, fill)
                    canvas.drawArc(RectF(cx - rad, cy - rad, cx + rad, cy + rad * 0.45f), 180f, 180f, false, stroke)
                }
                AtlasPartSlot.HAIR_BACK -> {
                    fill.color = hairGuide
                    val rad = min(sw, sh) * 0.37f
                    canvas.drawRoundRect(RectF(cx - rad, cy - rad * 0.85f, cx + rad, cy + rad * 0.95f), rad * 0.6f, rad * 0.6f, fill)
                    canvas.drawRoundRect(RectF(cx - rad, cy - rad * 0.85f, cx + rad, cy + rad * 0.95f), rad * 0.6f, rad * 0.6f, stroke)
                }
                AtlasPartSlot.CHEST_BODY -> {
                    fill.color = suitGuide
                    val bodyW = if (pose == AtlasPoseTemplate.BACK_SIDE) sw * 0.42f else sw * 0.58f
                    val bodyRect = RectF(cx - bodyW * 0.5f, t + sh * 0.16f, cx + bodyW * 0.5f, b - sh * 0.14f)
                    canvas.drawRoundRect(bodyRect, sw * 0.12f, sw * 0.12f, fill)
                    canvas.drawRoundRect(bodyRect, sw * 0.12f, sw * 0.12f, stroke)
                }
                AtlasPartSlot.EYE_WHITE -> {
                    fill.color = 0x35FFFFFF
                    canvas.drawOval(RectF(cx - sw * 0.32f, cy - sh * 0.24f, cx + sw * 0.32f, cy + sh * 0.24f), fill)
                    canvas.drawOval(RectF(cx - sw * 0.32f, cy - sh * 0.24f, cx + sw * 0.32f, cy + sh * 0.24f), stroke)
                }
                AtlasPartSlot.EYEBALL -> {
                    fill.color = 0x5522D3EE
                    canvas.drawCircle(cx, cy, min(sw, sh) * 0.25f, fill)
                    canvas.drawCircle(cx, cy, min(sw, sh) * 0.25f, stroke)
                }
                AtlasPartSlot.EYELID -> {
                    canvas.drawArc(RectF(cx - sw * 0.32f, cy - sh * 0.22f, cx + sw * 0.32f, cy + sh * 0.22f), 190f, 160f, false, stroke)
                }
                AtlasPartSlot.BROW_IDLE, AtlasPartSlot.BROW_HAPPY, AtlasPartSlot.BROW_ANGRY, AtlasPartSlot.BROW_SCARED -> {
                    val slope = when (slot) {
                        AtlasPartSlot.BROW_ANGRY -> sh * 0.12f
                        AtlasPartSlot.BROW_SCARED -> -sh * 0.12f
                        else -> 0f
                    }
                    canvas.drawLine(cx - sw * 0.32f, cy - slope, cx + sw * 0.32f, cy + slope, stroke)
                }
                AtlasPartSlot.LIP_IDLE, AtlasPartSlot.LIP_HAPPY, AtlasPartSlot.LIP_SCARED, AtlasPartSlot.LIP_ANGRY, AtlasPartSlot.LIP_SHOCKED -> {
                    fill.color = accentGuide
                    val mh = if (slot == AtlasPartSlot.LIP_SHOCKED || slot == AtlasPartSlot.LIP_HAPPY) sh * 0.26f else sh * 0.14f
                    canvas.drawOval(RectF(cx - sw * 0.28f, cy - mh, cx + sw * 0.28f, cy + mh), fill)
                    canvas.drawOval(RectF(cx - sw * 0.28f, cy - mh, cx + sw * 0.28f, cy + mh), stroke)
                }
                AtlasPartSlot.ACCESSORY -> {
                    fill.color = 0x35FACC15
                    canvas.drawRoundRect(RectF(cx - sw * 0.34f, cy - sh * 0.26f, cx + sw * 0.34f, cy + sh * 0.26f), 10f, 10f, fill)
                    canvas.drawRoundRect(RectF(cx - sw * 0.34f, cy - sh * 0.26f, cx + sw * 0.34f, cy + sh * 0.26f), 10f, 10f, stroke)
                }
                AtlasPartSlot.L_UPPER_ARM, AtlasPartSlot.L_LOWER_ARM,
                AtlasPartSlot.R_UPPER_ARM, AtlasPartSlot.R_LOWER_ARM -> {
                    fill.color = if (slot.name.contains("UPPER")) suitGuide else skinGuide
                    val rect = RectF(cx - sw * 0.28f, t + sh * 0.10f, cx + sw * 0.28f, b - sh * 0.10f)
                    canvas.drawRoundRect(rect, sw * 0.25f, sw * 0.25f, fill)
                    canvas.drawRoundRect(rect, sw * 0.25f, sw * 0.25f, stroke)
                }
                AtlasPartSlot.L_UPPER_LEG, AtlasPartSlot.L_LOWER_LEG,
                AtlasPartSlot.R_UPPER_LEG, AtlasPartSlot.R_LOWER_LEG -> {
                    fill.color = legGuide
                    val rect = RectF(cx - sw * 0.30f, t + sh * 0.10f, cx + sw * 0.30f, b - sh * 0.10f)
                    canvas.drawRoundRect(rect, sw * 0.24f, sw * 0.24f, fill)
                    canvas.drawRoundRect(rect, sw * 0.24f, sw * 0.24f, stroke)
                }
                AtlasPartSlot.HAND_OPEN, AtlasPartSlot.HAND_FIST, AtlasPartSlot.HAND_POINT,
                AtlasPartSlot.PROP_HAND_L, AtlasPartSlot.PROP_HAND_R -> {
                    fill.color = skinGuide
                    val rHand = min(sw, sh) * 0.30f
                    canvas.drawCircle(cx, cy + rHand * 0.1f, rHand, fill)
                    canvas.drawCircle(cx, cy + rHand * 0.1f, rHand, stroke)
                }
            }
        }
    }

    /**
     * Draws color-coded slot boxes, short slot codes, and joint/pivot alignment dots when Chart is ON.
     */
    fun drawAtlasChartOverlay(
        canvas: Canvas,
        width: Float,
        height: Float,
        selectedSlot: AtlasPartSlot?,
        loadedSlots: Set<AtlasPartSlot> = emptySet()
    ) {
        val minDim = min(width, height).coerceAtLeast(1f)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = (minDim * 0.017f).coerceIn(9f, 22f)
        }
        val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val pivotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (minDim * 0.0022f).coerceAtLeast(1.2f)
            color = Color.WHITE
        }

        for (slot in AtlasPartSlot.entries) {
            val isSelected = slot == selectedSlot
            val isLoaded = slot in loadedSlots
            val color = ProceduralRigRenderer.parseColorSafe(slot.guideColorHex, 0xFF38BDF8.toInt())
            val rect = RectF(slot.u0 * width, slot.v0 * height, slot.u1 * width, slot.v1 * height)

            // Subtle slot tint
            fillPaint.color = Color.argb(if (isSelected) 48 else if (isLoaded) 18 else 24, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(rect, fillPaint)

            // Color-coded slot border
            borderPaint.color = if (isSelected) Color.WHITE else Color.argb(215, Color.red(color), Color.green(color), Color.blue(color))
            borderPaint.strokeWidth = if (isSelected) (minDim * 0.0045f).coerceAtLeast(2.4f) else (minDim * 0.0020f).coerceAtLeast(1.1f)
            canvas.drawRect(rect, borderPaint)

            // Slot code label
            textPaint.color = if (isSelected) Color.WHITE else color
            canvas.drawText(slot.shortCode, rect.left + 4f, rect.top + textPaint.textSize + 2f, textPaint)

            // Joint / Pivot alignment crosshair & dot
            val px = rect.left + rect.width() * slot.pivotU
            val py = rect.top + rect.height() * slot.pivotV
            val dotR = (minDim * 0.0055f).coerceIn(3f, 7f)
            val cross = dotR * 1.9f
            pivotPaint.color = if (isSelected) 0xFFFACC15.toInt() else color
            canvas.drawLine(px - cross, py, px + cross, py, pivotRing)
            canvas.drawLine(px, py - cross, px, py + cross, pivotRing)
            canvas.drawCircle(px, py, dotR, pivotPaint)
            canvas.drawCircle(px, py, dotR, pivotRing)
        }
    }

    private fun hasVisiblePixels(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val stepX = (w / 14).coerceAtLeast(1)
        val stepY = (h / 14).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (Color.alpha(bmp.getPixel(x, y)) > 12) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }
}
