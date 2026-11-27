package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.*

/**
 * ChromaToons-inspired Hand & Head Prop Attachment drawer,
 * 2048x2048 Modular Character Texture Chart cutout renderer & template generator,
 * and Direct Stage IK Limb & Head Handle solver.
 */
object PropAndModularChartRenderer {

    private class PropScratch {
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val srcRect = Rect()
        val dstRect = RectF()
        val path = Path()
    }

    private val scratchLocal = ThreadLocal.withInitial { PropScratch() }

    data class ChartPartZone(
        val id: String,
        val label: String,
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        val pivotU: Float,
        val pivotV: Float,
        val guideColor: Int
    )

    /**
     * 10-part normalized UV layout for a 2048x2048 ChromaToons-style Modular Character Chart.
     * Each body part occupies a dedicated non-overlapping box so limbs can rotate 360° cleanly
     * without stretching adjacent pixels.
     */
    val MODULAR_CHART_ZONES: List<ChartPartZone> = listOf(
        ChartPartZone("HEAD", "HEAD & FACE", 0.34f, 0.02f, 0.66f, 0.25f, 0.50f, 0.23f, 0xFF38BDF8.toInt()),
        ChartPartZone("TORSO", "TORSO / CHEST", 0.33f, 0.27f, 0.67f, 0.58f, 0.50f, 0.54f, 0xFF10B981.toInt()),
        ChartPartZone("L_UPPER_ARM", "L. UPPER ARM", 0.06f, 0.27f, 0.29f, 0.43f, 0.175f, 0.29f, 0xFFF59E0B.toInt()),
        ChartPartZone("L_FOREARM_HAND", "L. FOREARM & HAND", 0.06f, 0.45f, 0.29f, 0.63f, 0.175f, 0.47f, 0xFFFB923C.toInt()),
        ChartPartZone("R_UPPER_ARM", "R. UPPER ARM", 0.71f, 0.27f, 0.94f, 0.43f, 0.825f, 0.29f, 0xFFEC4899.toInt()),
        ChartPartZone("R_FOREARM_HAND", "R. FOREARM & HAND", 0.71f, 0.45f, 0.94f, 0.63f, 0.825f, 0.47f, 0xFFF472B6.toInt()),
        ChartPartZone("L_THIGH", "L. THIGH", 0.24f, 0.61f, 0.48f, 0.78f, 0.36f, 0.63f, 0xFFA78BFA.toInt()),
        ChartPartZone("L_SHIN_FOOT", "L. SHIN & SHOE", 0.24f, 0.80f, 0.48f, 0.98f, 0.36f, 0.82f, 0xFFC084FC.toInt()),
        ChartPartZone("R_THIGH", "R. THIGH", 0.52f, 0.61f, 0.76f, 0.78f, 0.64f, 0.63f, 0xFF34D399.toInt()),
        ChartPartZone("R_SHIN_FOOT", "R. SHIN & SHOE", 0.52f, 0.80f, 0.76f, 0.98f, 0.64f, 0.82f, 0xFF2DD4BF.toInt())
    )

    data class StageIkHandle(
        val id: IkHandleId,
        val nx: Float,
        val ny: Float,
        val color: Int
    )

    /**
     * Generates a downloadable 2048x2048 reference chart PNG with labeled body-part UV zones,
     * pivot markers, and silhouette guides so creators can paint custom ChromaToons-style
     * modular characters in any drawing application.
     */
    fun generateBlank2048ChartTemplate(sizePx: Int = 2048): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = sizePx / 2048f

        // Subtle checkerboard / dark slate grid background
        canvas.drawColor(Color.argb(255, 15, 23, 42))

        val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * s
            pathEffect = DashPathEffect(floatArrayOf(18f * s, 12f * s), 0f)
        }
        val silhouetteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f * s
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 203, 213, 225)
            textSize = 24f * s
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * s
            color = Color.WHITE
        }

        // Title header
        canvas.drawText(
            "FRAMECRAFT 2048×2048 MODULAR CHARACTER CHART TEMPLATE",
            sizePx * 0.5f,
            34f * s,
            subTextPaint
        )

        for (zone in MODULAR_CHART_ZONES) {
            val rect = RectF(
                zone.u0 * sizePx,
                zone.v0 * sizePx,
                zone.u1 * sizePx,
                zone.v1 * sizePx
            )
            boxFill.color = Color.argb(
                36,
                Color.red(zone.guideColor),
                Color.green(zone.guideColor),
                Color.blue(zone.guideColor)
            )
            canvas.drawRoundRect(rect, 22f * s, 22f * s, boxFill)

            boxStroke.color = zone.guideColor
            canvas.drawRoundRect(rect, 22f * s, 22f * s, boxStroke)

            // Draw faint anatomical silhouette guide inside the box
            silhouetteFill.color = Color.argb(
                65,
                Color.red(zone.guideColor),
                Color.green(zone.guideColor),
                Color.blue(zone.guideColor)
            )
            val insetW = rect.width() * 0.24f
            val insetH = rect.height() * 0.14f
            val guideRect = RectF(
                rect.left + insetW,
                rect.top + insetH,
                rect.right - insetW,
                rect.bottom - insetH
            )
            if (zone.id == "HEAD") {
                canvas.drawOval(guideRect, silhouetteFill)
            } else {
                canvas.drawRoundRect(guideRect, guideRect.width() * 0.45f, guideRect.width() * 0.45f, silhouetteFill)
            }

            // Pivot crosshair marker
            val px = zone.pivotU * sizePx
            val py = zone.pivotV * sizePx
            canvas.drawCircle(px, py, 14f * s, pivotPaint)
            canvas.drawLine(px - 22f * s, py, px + 22f * s, py, pivotPaint)
            canvas.drawLine(px, py - 22f * s, px, py + 22f * s, pivotPaint)

            // Zone label
            textPaint.color = zone.guideColor
            canvas.drawText(zone.label, rect.centerX(), rect.top + 44f * s, textPaint)
        }

        return bmp
    }

    /**
     * Renders a ChromaToons-style 2048x2048 Modular Character Texture Chart by slicing each
     * of the 10 UV zones from [bitmap] and rotating them rigidly around hierarchical FK joints.
     */
    internal fun drawModularChartRig(
        canvas: Canvas,
        bitmap: Bitmap,
        skeleton: SkeletonRigConfig,
        pose: ProceduralRigRenderer.PoseAngles,
        rigScale: Float,
        alpha: Float,
        tintOverlayColor: Int?,
        propType: CharacterPropType,
        propAttachment: PropAttachmentPoint,
        customPropBitmap: Bitmap?,
        propScale: Float
    ) {
        val bw = bitmap.width.coerceAtLeast(1)
        val bh = bitmap.height.coerceAtLeast(1)
        val s = rigScale
        val alphaInt = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val scratch = scratchLocal.get()!!

        val paint = scratch.bmpPaint
        paint.alpha = alphaInt
        paint.colorFilter = if (tintOverlayColor != null) {
            PorterDuffColorFilter(tintOverlayColor, PorterDuff.Mode.SRC_ATOP)
        } else {
            null
        }

        // Ground shadow
        scratch.fillPaint.color = Color.argb((alphaInt * 0.32f).toInt(), 0, 0, 0)
        scratch.dstRect.set(-34f * s, 82f * s, 34f * s, 94f * s)
        canvas.drawOval(scratch.dstRect, scratch.fillPaint)

        fun drawZoneSlice(zoneId: String, left: Float, top: Float, right: Float, bottom: Float) {
            val z = MODULAR_CHART_ZONES.firstOrNull { it.id == zoneId } ?: return
            scratch.srcRect.set(
                (z.u0 * bw).toInt().coerceIn(0, bw - 1),
                (z.v0 * bh).toInt().coerceIn(0, bh - 1),
                (z.u1 * bw).toInt().coerceIn(1, bw),
                (z.v1 * bh).toInt().coerceIn(1, bh)
            )
            scratch.dstRect.set(left, top, right, bottom)
            canvas.drawBitmap(bitmap, scratch.srcRect, scratch.dstRect, paint)
        }

        val shoulderHalf = 24f * s
        val hipHalf = 12f * s
        val upperArmLen = 28f * s
        val foreArmLen = 25f * s
        val thighLen = 32f * s
        val calfLen = 32f * s

        // 1. Left Arm (Far arm behind torso)
        canvas.save()
        canvas.translate(-shoulderHalf, -30f * s)
        canvas.rotate(pose.leftArmDeg)
        drawZoneSlice("L_UPPER_ARM", -10f * s, -4f * s, 10f * s, upperArmLen + 4f * s)
        canvas.translate(0f, upperArmLen - 3f * s)
        canvas.rotate(pose.leftForearmDeg)
        drawZoneSlice("L_FOREARM_HAND", -10f * s, -3f * s, 10f * s, foreArmLen + 8f * s)
        if (propType != CharacterPropType.NONE && propAttachment == PropAttachmentPoint.LEFT_HAND) {
            canvas.save()
            canvas.translate(0f, foreArmLen + 2f * s)
            drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, pose.propSwayDeg, alphaInt,
                pose.propSwayOffsetX * s, pose.propSwayOffsetY * s
            )
            canvas.restore()
        }
        canvas.restore()

        // 2. Left Leg
        canvas.save()
        canvas.translate(-hipHalf, 20f * s)
        canvas.rotate(pose.leftLegDeg)
        drawZoneSlice("L_THIGH", -12f * s, -4f * s, 12f * s, thighLen + 4f * s)
        canvas.translate(0f, thighLen - 3f * s)
        canvas.rotate(pose.leftKneeDeg)
        drawZoneSlice("L_SHIN_FOOT", -12f * s, -3f * s, 14f * s, calfLen + 10f * s)
        canvas.restore()

        // 3. Right Leg
        canvas.save()
        canvas.translate(hipHalf, 20f * s)
        canvas.rotate(pose.rightLegDeg)
        drawZoneSlice("R_THIGH", -12f * s, -4f * s, 12f * s, thighLen + 4f * s)
        canvas.translate(0f, thighLen - 3f * s)
        canvas.rotate(pose.rightKneeDeg)
        drawZoneSlice("R_SHIN_FOOT", -12f * s, -3f * s, 14f * s, calfLen + 10f * s)
        canvas.restore()

        // 4. Torso
        drawZoneSlice("TORSO", -28f * s, -38f * s, 28f * s, 26f * s)

        // 5. Head
        canvas.save()
        canvas.translate(0f, -68f * s)
        canvas.rotate(pose.headNodDeg)
        drawZoneSlice("HEAD", -26f * s, -28f * s, 26f * s, 24f * s)
        if (pose.facialExpression != FacialExpression.AUTO) {
            drawModularExpressionBadgeOverlay(canvas, pose, s, alphaInt, scratch)
        }
        if (propType != CharacterPropType.NONE && propAttachment == PropAttachmentPoint.HEAD) {
            canvas.save()
            canvas.translate(0f, -26f * s)
            drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, pose.propSwayDeg, alphaInt,
                pose.propSwayOffsetX * s, pose.propSwayOffsetY * s
            )
            canvas.restore()
        }
        canvas.restore()

        // 6. Right Arm (Near arm in front of torso)
        canvas.save()
        canvas.translate(shoulderHalf, -30f * s)
        canvas.rotate(pose.rightArmDeg)
        drawZoneSlice("R_UPPER_ARM", -10f * s, -4f * s, 10f * s, upperArmLen + 4f * s)
        canvas.translate(0f, upperArmLen - 3f * s)
        canvas.rotate(pose.rightForearmDeg)
        drawZoneSlice("R_FOREARM_HAND", -10f * s, -3f * s, 10f * s, foreArmLen + 8f * s)
        if (propType != CharacterPropType.NONE && propAttachment == PropAttachmentPoint.RIGHT_HAND) {
            canvas.save()
            canvas.translate(0f, foreArmLen + 2f * s)
            drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, pose.propSwayDeg, alphaInt,
                pose.propSwayOffsetX * s, pose.propSwayOffsetY * s
            )
            canvas.restore()
        }
        canvas.restore()

        paint.colorFilter = null

        if (skeleton.showBoneOverlay) {
            val bonePaint = scratch.strokePaint
            bonePaint.strokeWidth = 2.2f * s
            bonePaint.color = Color.argb(190, 56, 189, 248)
            val jointPaint = scratch.fillPaint
            jointPaint.color = Color.argb(230, 250, 204, 21)
            canvas.drawLine(0f, 18f * s, 0f, -34f * s, bonePaint)
            canvas.drawLine(0f, -34f * s, 0f, -68f * s, bonePaint)
            canvas.drawCircle(0f, -68f * s, 3.8f * s, jointPaint)
            canvas.drawCircle(-shoulderHalf, -30f * s, 3.8f * s, jointPaint)
            canvas.drawCircle(shoulderHalf, -30f * s, 3.8f * s, jointPaint)
            canvas.drawCircle(-hipHalf, 20f * s, 3.8f * s, jointPaint)
            canvas.drawCircle(hipHalf, 20f * s, 3.8f * s, jointPaint)
        }
    }

    private fun drawModularExpressionBadgeOverlay(
        canvas: Canvas,
        pose: ProceduralRigRenderer.PoseAngles,
        s: Float,
        alphaInt: Int,
        scratch: PropScratch
    ) {
        val stroke = scratch.strokePaint
        stroke.strokeWidth = 2.2f * s
        stroke.color = Color.argb(alphaInt, 20, 18, 16)
        val fill = scratch.fillPaint
        when (pose.facialExpression) {
            FacialExpression.ANGRY -> {
                canvas.drawLine(-11f * s, -9f * s, -3f * s, -5f * s, stroke)
                canvas.drawLine(11f * s, -9f * s, 3f * s, -5f * s, stroke)
            }
            FacialExpression.SAD -> {
                fill.color = Color.argb(alphaInt, 56, 189, 248)
                scratch.dstRect.set(8f * s, -1f * s, 12f * s, 6f * s)
                canvas.drawOval(scratch.dstRect, fill)
            }
            FacialExpression.SHOCKED -> {
                fill.color = Color.argb(alphaInt, 127, 29, 29)
                scratch.dstRect.set(-4.5f * s, 7f * s, 4.5f * s, 16f * s)
                canvas.drawOval(scratch.dstRect, fill)
            }
            FacialExpression.SLEEPING -> {
                canvas.drawLine(-10f * s, -2f * s, -3f * s, -2f * s, stroke)
                canvas.drawLine(3f * s, -2f * s, 10f * s, -2f * s, stroke)
            }
            else -> Unit
        }
    }

    /**
     * Draws an attached Hand or Head prop in local socket coordinates (0, 0) with
     * spring-pendulum sway rotation and optional inertial lag translation.
     */
    fun drawPropAtSocket(
        canvas: Canvas,
        propType: CharacterPropType,
        customPropBitmap: Bitmap?,
        s: Float,
        swayDeg: Float,
        alphaInt: Int,
        swayOffsetX: Float = 0f,
        swayOffsetY: Float = 0f
    ) {
        if (propType == CharacterPropType.NONE) return
        val scratch = scratchLocal.get()!!
        canvas.save()
        if (swayOffsetX != 0f || swayOffsetY != 0f) {
            canvas.translate(swayOffsetX, swayOffsetY)
        }
        canvas.rotate(swayDeg)

        val fill = scratch.fillPaint
        val stroke = scratch.strokePaint
        stroke.strokeWidth = 2.0f * s
        stroke.color = Color.argb(alphaInt, 15, 23, 42)
        val rect = scratch.dstRect
        val path = scratch.path

        when (propType) {
            CharacterPropType.NONE -> Unit
            CharacterPropType.CUSTOM_PNG -> {
                if (customPropBitmap != null && !customPropBitmap.isRecycled) {
                    val bmpPaint = scratch.bmpPaint
                    bmpPaint.alpha = alphaInt
                    bmpPaint.colorFilter = null
                    val targetH = 38f * s
                    val aspect = customPropBitmap.width.toFloat() / customPropBitmap.height.coerceAtLeast(1).toFloat()
                    val targetW = targetH * aspect
                    rect.set(-targetW * 0.5f, -targetH * 0.75f, targetW * 0.5f, targetH * 0.25f)
                    canvas.drawBitmap(customPropBitmap, null, rect, bmpPaint)
                }
            }
            CharacterPropType.MICROPHONE -> {
                // Handle
                fill.color = Color.argb(alphaInt, 51, 65, 85)
                val body = RectF(-3.2f * s, -16f * s, 3.2f * s, 8f * s)
                canvas.drawRoundRect(body, 2.5f * s, 2.5f * s, fill)
                canvas.drawRoundRect(body, 2.5f * s, 2.5f * s, stroke)
                // Mic grille capsule
                fill.color = Color.argb(alphaInt, 148, 163, 184)
                canvas.drawCircle(0f, -20f * s, 6.2f * s, fill)
                canvas.drawCircle(0f, -20f * s, 6.2f * s, stroke)
                fill.color = Color.argb(alphaInt, 56, 189, 248)
                canvas.drawRect(-3.4f * s, -14.5f * s, 3.4f * s, -12.5f * s, fill)
            }
            CharacterPropType.SWORD -> {
                // Pommel & grip
                fill.color = Color.argb(alphaInt, 120, 53, 15)
                canvas.drawRoundRect(RectF(-2.6f * s, -6f * s, 2.6f * s, 9f * s), 2f * s, 2f * s, fill)
                fill.color = Color.argb(alphaInt, 250, 204, 21)
                canvas.drawCircle(0f, 10f * s, 3.6f * s, fill)
                // Crossguard
                val guard = RectF(-10f * s, -9.5f * s, 10f * s, -5.5f * s)
                canvas.drawRoundRect(guard, 2.5f * s, 2.5f * s, fill)
                canvas.drawRoundRect(guard, 2.5f * s, 2.5f * s, stroke)
                // Steel blade
                val blade = Path().apply {
                    moveTo(-4.2f * s, -9.5f * s)
                    lineTo(4.2f * s, -9.5f * s)
                    lineTo(2.8f * s, -48f * s)
                    lineTo(0f, -56f * s)
                    lineTo(-2.8f * s, -48f * s)
                    close()
                }
                fill.color = Color.argb(alphaInt, 226, 232, 240)
                canvas.drawPath(blade, fill)
                canvas.drawPath(blade, stroke)
            }
            CharacterPropType.MAGIC_WAND -> {
                // Wand shaft
                fill.color = Color.argb(alphaInt, 139, 92, 246)
                val shaft = RectF(-2.2f * s, -32f * s, 2.2f * s, 8f * s)
                canvas.drawRoundRect(shaft, 2f * s, 2f * s, fill)
                canvas.drawRoundRect(shaft, 2f * s, 2f * s, stroke)
                // Glowing star tip
                val star = Path()
                val cx = 0f
                val cy = -36f * s
                val outerR = 10.5f * s
                val innerR = 4.6f * s
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outerR else innerR
                    val a = (i * 36.0 - 90.0) * PI / 180.0
                    val px = cx + (cos(a) * r).toFloat()
                    val py = cy + (sin(a) * r).toFloat()
                    if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
                }
                star.close()
                fill.color = Color.argb(alphaInt, 250, 204, 21)
                canvas.drawPath(star, fill)
                canvas.drawPath(star, stroke)
            }
            CharacterPropType.SMARTPHONE -> {
                val phone = RectF(-6.5f * s, -18f * s, 6.5f * s, 4f * s)
                fill.color = Color.argb(alphaInt, 15, 23, 42)
                canvas.drawRoundRect(phone, 2.8f * s, 2.8f * s, fill)
                canvas.drawRoundRect(phone, 2.8f * s, 2.8f * s, stroke)
                fill.color = Color.argb(alphaInt, 56, 189, 248)
                canvas.drawRoundRect(RectF(-5.1f * s, -15.8f * s, 5.1f * s, 1.8f * s), 1.8f * s, 1.8f * s, fill)
            }
            CharacterPropType.COFFEE_MUG -> {
                // Handle loop
                stroke.color = Color.argb(alphaInt, 248, 250, 252)
                stroke.strokeWidth = 2.8f * s
                canvas.drawArc(RectF(3f * s, -8f * s, 11f * s, 2f * s), -90f, 180f, false, stroke)
                stroke.color = Color.argb(alphaInt, 15, 23, 42)
                stroke.strokeWidth = 2.0f * s
                val cup = RectF(-7f * s, -11f * s, 6f * s, 5f * s)
                fill.color = Color.argb(alphaInt, 249, 115, 22)
                canvas.drawRoundRect(cup, 2.5f * s, 2.5f * s, fill)
                canvas.drawRoundRect(cup, 2.5f * s, 2.5f * s, stroke)
            }
            CharacterPropType.LANTERN -> {
                // Hanging ring & cap
                canvas.drawCircle(0f, 0f, 3.2f * s, stroke)
                fill.color = Color.argb(alphaInt, 180, 83, 9)
                canvas.drawRoundRect(RectF(-7.5f * s, 3f * s, 7.5f * s, 7f * s), 2f * s, 2f * s, fill)
                // Glowing glass chamber
                fill.color = Color.argb((alphaInt * 0.35f).toInt(), 253, 224, 71)
                canvas.drawCircle(0f, 15f * s, 16f * s, fill)
                fill.color = Color.argb(alphaInt, 254, 240, 138)
                val glass = RectF(-6.5f * s, 7f * s, 6.5f * s, 23f * s)
                canvas.drawRoundRect(glass, 3f * s, 3f * s, fill)
                canvas.drawRoundRect(glass, 3f * s, 3f * s, stroke)
                fill.color = Color.argb(alphaInt, 180, 83, 9)
                canvas.drawRoundRect(RectF(-8.5f * s, 22f * s, 8.5f * s, 26f * s), 2f * s, 2f * s, fill)
            }
            CharacterPropType.SHIELD -> {
                fill.color = Color.argb(alphaInt, 30, 64, 175)
                canvas.drawCircle(0f, 0f, 18f * s, fill)
                canvas.drawCircle(0f, 0f, 18f * s, stroke)
                fill.color = Color.argb(alphaInt, 250, 204, 21)
                canvas.drawCircle(0f, 0f, 12f * s, stroke)
                canvas.drawCircle(0f, 0f, 5.5f * s, fill)
                canvas.drawCircle(0f, 0f, 5.5f * s, stroke)
            }
            CharacterPropType.UMBRELLA -> {
                // Shaft
                canvas.drawLine(0f, 8f * s, 0f, -44f * s, stroke)
                // Canopy dome
                fill.color = Color.argb(alphaInt, 239, 68, 68)
                val dome = RectF(-28f * s, -60f * s, 28f * s, -24f * s)
                canvas.drawArc(dome, 180f, 180f, true, fill)
                canvas.drawArc(dome, 180f, 180f, true, stroke)
            }
            CharacterPropType.BOOK -> {
                val cover = RectF(-10f * s, -12f * s, 10f * s, 6f * s)
                fill.color = Color.argb(alphaInt, 16, 185, 129)
                canvas.drawRoundRect(cover, 2.5f * s, 2.5f * s, fill)
                canvas.drawRoundRect(cover, 2.5f * s, 2.5f * s, stroke)
                fill.color = Color.argb(alphaInt, 248, 250, 252)
                canvas.drawRect(-8f * s, -9.5f * s, -1f * s, 3.5f * s, fill)
                canvas.drawRect(1f * s, -9.5f * s, 8f * s, 3.5f * s, fill)
            }
            CharacterPropType.FLOWER -> {
                stroke.color = Color.argb(alphaInt, 22, 163, 74)
                stroke.strokeWidth = 2.6f * s
                canvas.drawLine(0f, 8f * s, 0f, -18f * s, stroke)
                fill.color = Color.argb(alphaInt, 244, 114, 182)
                for (i in 0 until 6) {
                    val a = i * 60.0 * PI / 180.0
                    val px = (cos(a) * 6.5f * s).toFloat()
                    val py = -20f * s + (sin(a) * 6.5f * s).toFloat()
                    canvas.drawCircle(px, py, 4.8f * s, fill)
                }
                fill.color = Color.argb(alphaInt, 250, 204, 21)
                canvas.drawCircle(0f, -20f * s, 4.2f * s, fill)
            }
        }

        canvas.restore()
    }

    /**
     * Computes stage-normalized (0..1) positions for the 10 interactive IK posing handles
     * on the currently selected character clip via [IkRigSolver].
     */
    internal fun computeStageIkHandles(
        clip: TimelineClip,
        rig: CharacterRigDefinition,
        transform: EvaluatedTransform,
        pose: ProceduralRigRenderer.PoseAngles,
        stageWidth: Float,
        stageHeight: Float
    ): List<StageIkHandle> = IkRigSolver.computeStageIkHandles(
        clip = clip,
        rig = rig,
        transform = transform,
        pose = pose,
        stageWidth = stageWidth,
        stageHeight = stageHeight
    )

    /**
     * Converts a user drag of [handleId] to world-normalized stage position ([targetNx], [targetNy])
     * into updated [PoseKeyframeOffsets] using analytical 2-bone IK via [IkRigSolver].
     */
    internal fun solveIkDragToOffsets(
        handleId: IkHandleId,
        targetNx: Float,
        targetNy: Float,
        clip: TimelineClip,
        rig: CharacterRigDefinition,
        transform: EvaluatedTransform,
        basePose: ProceduralRigRenderer.PoseAngles,
        currentOffsets: PoseKeyframeOffsets,
        stageWidth: Float,
        stageHeight: Float,
        bendFlip: Boolean = false
    ): PoseKeyframeOffsets = IkRigSolver.solveIkDragToOffsets(
        handleId = handleId,
        targetNx = targetNx,
        targetNy = targetNy,
        clip = clip,
        rig = rig,
        transform = transform,
        basePose = basePose,
        currentOffsets = currentOffsets,
        stageWidth = stageWidth,
        stageHeight = stageHeight,
        bendFlip = bendFlip
    )
}
