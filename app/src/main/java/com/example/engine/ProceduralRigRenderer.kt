package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object ProceduralRigRenderer {

    internal data class PoseAngles(
        val bodyBobY: Float = 0f,
        val bodyTiltDeg: Float = 0f,
        val squashX: Float = 1f,
        val squashY: Float = 1f,
        val headNodDeg: Float = 0f,
        val leftArmDeg: Float = 10f,
        val rightArmDeg: Float = -10f,
        val leftForearmDeg: Float = 0f,
        val rightForearmDeg: Float = 0f,
        val leftLegDeg: Float = 4f,
        val rightLegDeg: Float = -4f,
        val leftKneeDeg: Float = 0f,
        val rightKneeDeg: Float = 0f,
        val mouthOpen: Float = 0f,
        val happySmile: Boolean = false,
        val blinkClosed: Boolean = false,
        val wingFlapDeg: Float = 0f,
        val wheelSpinDeg: Float = 0f,
        val facialExpression: FacialExpression = FacialExpression.AUTO,
        val propSwayDeg: Float = 0f
    ) {
        fun withOffsets(
            offsets: PoseKeyframeOffsets,
            expression: FacialExpression = FacialExpression.AUTO,
            swayDeg: Float = 0f
        ): PoseAngles {
            val effectiveMouth = when (expression) {
                FacialExpression.TALKING -> if (mouthOpen > 0.1f) mouthOpen else 0.65f
                FacialExpression.SHOCKED -> 0.85f
                FacialExpression.LAUGHING -> 0.72f
                FacialExpression.NEUTRAL, FacialExpression.SAD, FacialExpression.ANGRY, FacialExpression.SLEEPING -> 0f
                FacialExpression.HAPPY, FacialExpression.AUTO -> mouthOpen
            }
            val effectiveSmile = when (expression) {
                FacialExpression.HAPPY, FacialExpression.LAUGHING -> true
                FacialExpression.ANGRY, FacialExpression.SAD, FacialExpression.NEUTRAL, FacialExpression.SHOCKED -> false
                FacialExpression.AUTO, FacialExpression.TALKING, FacialExpression.SLEEPING -> happySmile
            }
            val effectiveBlink = when (expression) {
                FacialExpression.SLEEPING, FacialExpression.LAUGHING -> true
                FacialExpression.SHOCKED, FacialExpression.ANGRY -> false
                else -> blinkClosed
            }
            return copy(
                bodyTiltDeg = bodyTiltDeg + offsets.torsoTiltOffset,
                headNodDeg = headNodDeg + offsets.headNodOffset,
                leftArmDeg = leftArmDeg + offsets.leftArmOffset,
                leftForearmDeg = leftForearmDeg + offsets.leftForearmOffset,
                rightArmDeg = rightArmDeg + offsets.rightArmOffset,
                rightForearmDeg = rightForearmDeg + offsets.rightForearmOffset,
                leftLegDeg = leftLegDeg + offsets.leftLegOffset,
                leftKneeDeg = leftKneeDeg + offsets.leftKneeOffset,
                rightLegDeg = rightLegDeg + offsets.rightLegOffset,
                rightKneeDeg = rightKneeDeg + offsets.rightKneeOffset,
                mouthOpen = effectiveMouth,
                happySmile = effectiveSmile,
                blinkClosed = effectiveBlink,
                facialExpression = expression,
                propSwayDeg = swayDeg
            )
        }
    }

    fun drawCharacter(
        canvas: Canvas,
        rig: CharacterRigDefinition,
        orientation: CharacterOrientation,
        preset: AnimationPresetType,
        phase: Float,
        rigScale: Float,
        alpha: Float = 1f,
        customBitmap: Bitmap? = null,
        tintOverlayColor: Int? = null,
        poseOffsets: PoseKeyframeOffsets = PoseKeyframeOffsets(),
        facialExpression: FacialExpression = FacialExpression.AUTO,
        propType: CharacterPropType = CharacterPropType.NONE,
        propAttachment: PropAttachmentPoint = PropAttachmentPoint.RIGHT_HAND,
        customPropBitmap: Bitmap? = null,
        propSwayEnabled: Boolean = true,
        propScale: Float = 1.0f
    ) {
        val p = ((phase % 1f) + 1f) % 1f
        val swayDeg = if (propSwayEnabled) (sin(p * 2.0 * PI) * 11.0).toFloat() else 0f
        val basePose = computePose(preset, p)
        val pose = basePose.withOffsets(poseOffsets, facialExpression, swayDeg)

        canvas.save()
        canvas.translate(0f, pose.bodyBobY * rigScale)
        canvas.rotate(pose.bodyTiltDeg)
        canvas.scale(pose.squashX, pose.squashY)

        if (customBitmap != null && !customBitmap.isRecycled) {
            val skeleton = rig.skeletonRig
            if (skeleton != null && skeleton.enabled) {
                if (skeleton.rigMode == CustomRigMode.MODULAR_CHART_2048) {
                    PropAndModularChartRenderer.drawModularChartRig(
                        canvas = canvas,
                        bitmap = customBitmap,
                        skeleton = skeleton,
                        pose = pose,
                        rigScale = rigScale,
                        alpha = alpha,
                        tintOverlayColor = tintOverlayColor,
                        propType = propType,
                        propAttachment = propAttachment,
                        customPropBitmap = customPropBitmap,
                        propScale = propScale
                    )
                } else {
                    HumanRigDrawer.drawSkeletonPngRig(
                        canvas = canvas,
                        bitmap = customBitmap,
                        skeleton = skeleton,
                        pose = pose,
                        rigScale = rigScale,
                        alpha = alpha,
                        tintOverlayColor = tintOverlayColor,
                        propType = propType,
                        propAttachment = propAttachment,
                        customPropBitmap = customPropBitmap,
                        propScale = propScale
                    )
                }
            } else {
                drawCustomSpriteRig(canvas, customBitmap, rigScale, alpha, tintOverlayColor)
                if (propType != CharacterPropType.NONE) {
                    val alphaInt = (alpha.coerceIn(0f, 1f) * 255).toInt()
                    canvas.save()
                    when (propAttachment) {
                        PropAttachmentPoint.LEFT_HAND -> canvas.translate(-28f * rigScale, 4f * rigScale)
                        PropAttachmentPoint.RIGHT_HAND -> canvas.translate(28f * rigScale, 4f * rigScale)
                        PropAttachmentPoint.HEAD -> canvas.translate(0f, -86f * rigScale)
                    }
                    PropAndModularChartRenderer.drawPropAtSocket(
                        canvas, propType, customPropBitmap, rigScale * propScale, pose.propSwayDeg, alphaInt
                    )
                    canvas.restore()
                }
            }
            canvas.restore()
            return
        }

        val alphaInt = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val skinColor = applyTint(parseColorSafe(rig.skinColorHex, 0xFFE5B48A.toInt()), tintOverlayColor)
        val primaryColor = applyTint(parseColorSafe(rig.primaryColorHex, 0xFF38BDF8.toInt()), tintOverlayColor)
        val secondaryColor = applyTint(parseColorSafe(rig.secondaryColorHex, 0xFF1E293B.toInt()), tintOverlayColor)
        val accentColor = applyTint(parseColorSafe(rig.accentColorHex, 0xFFF59E0B.toInt()), tintOverlayColor)
        val hairColor = applyTint(parseColorSafe(rig.hairColorHex, 0xFF1F1B18.toInt()), tintOverlayColor)
        val eyeColor = applyTint(parseColorSafe(rig.eyeColorHex, 0xFF2B1E16.toInt()), tintOverlayColor)

        when (rig.bodyType) {
            CharacterBodyType.HUMANOID -> HumanRigDrawer.drawRealHuman(
                canvas = canvas,
                rig = rig,
                orientation = orientation,
                pose = pose,
                s = rigScale,
                alphaInt = alphaInt,
                skin = skinColor,
                primary = primaryColor,
                secondary = secondaryColor,
                accent = accentColor,
                hair = hairColor,
                eyeColor = eyeColor,
                propType = propType,
                propAttachment = propAttachment,
                customPropBitmap = customPropBitmap,
                propScale = propScale,
                drawAccessory = { c, acc, sc, acCol, prCol, st, al ->
                    drawHeadAccessory(c, acc, sc, acCol, prCol, st, al)
                }
            )
            CharacterBodyType.BIRD -> {
                drawBird(
                    canvas, rig, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose.propSwayDeg, alphaInt)
            }
            CharacterBodyType.QUADRUPED -> {
                drawQuadruped(
                    canvas, rig, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose.propSwayDeg, alphaInt)
            }
            CharacterBodyType.VEHICLE -> {
                drawVehicle(
                    canvas, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose.propSwayDeg, alphaInt)
            }
            CharacterBodyType.SPIRIT -> {
                drawSpirit(
                    canvas, rig, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose.propSwayDeg, alphaInt)
            }
        }

        canvas.restore()
    }

    private fun drawNonHumanoidPropIfEquipped(
        canvas: Canvas,
        propType: CharacterPropType,
        propAttachment: PropAttachmentPoint,
        customPropBitmap: Bitmap?,
        rigScale: Float,
        propScale: Float,
        swayDeg: Float,
        alphaInt: Int
    ) {
        if (propType == CharacterPropType.NONE) return
        canvas.save()
        when (propAttachment) {
            PropAttachmentPoint.LEFT_HAND -> canvas.translate(-32f * rigScale, 4f * rigScale)
            PropAttachmentPoint.RIGHT_HAND -> canvas.translate(32f * rigScale, 4f * rigScale)
            PropAttachmentPoint.HEAD -> canvas.translate(0f, -56f * rigScale)
        }
        PropAndModularChartRenderer.drawPropAtSocket(
            canvas, propType, customPropBitmap, rigScale * propScale, swayDeg, alphaInt
        )
        canvas.restore()
    }

    private fun drawCustomSpriteRig(
        canvas: Canvas,
        bitmap: Bitmap,
        rigScale: Float,
        alpha: Float,
        tintOverlayColor: Int?
    ) {
        val targetH = 190f * rigScale
        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val targetW = targetH * aspect
        val rect = RectF(-targetW / 2f, -targetH * 0.72f, targetW / 2f, targetH * 0.28f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            if (tintOverlayColor != null) {
                colorFilter = PorterDuffColorFilter(tintOverlayColor, PorterDuff.Mode.SRC_ATOP)
            }
        }
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    private fun drawHumanoid(
        canvas: Canvas,
        rig: CharacterRigDefinition,
        orientation: CharacterOrientation,
        pose: PoseAngles,
        s: Float,
        alphaInt: Int,
        skin: Int,
        primary: Int,
        secondary: Int,
        accent: Int,
        hair: Int
    ) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = withAlpha(0xFF0E1118.toInt(), alphaInt)
            strokeWidth = 3.2f * s
        }

        val isBack = orientation == CharacterOrientation.BACK || orientation == CharacterOrientation.THREE_QUARTER_BACK
        val isSide = orientation == CharacterOrientation.SIDE
        val faceShiftX = when (orientation) {
            CharacterOrientation.FRONT -> 0f
            CharacterOrientation.THREE_QUARTER_FRONT -> 9f * s
            CharacterOrientation.SIDE -> 15f * s
            CharacterOrientation.THREE_QUARTER_BACK -> -8f * s
            CharacterOrientation.BACK -> 0f
        }
        val torsoWidth = if (isSide) 36f * s else 52f * s
        val shoulderSpread = if (isSide) 6f * s else 26f * s
        val hipSpread = if (isSide) 4f * s else 14f * s

        // Ground shadow
        fillPaint.color = withAlpha(0x55000000, alphaInt)
        canvas.drawOval(RectF(-34f * s, 74f * s, 34f * s, 88f * s), fillPaint)

        // Cape behind body if equipped
        if (rig.accessory == CharacterAccessory.CAPE && !isBack) {
            drawCape(canvas, s, accent, alphaInt)
        }

        // Far arm (drawn behind torso in side/3-4)
        drawLimbSegment(
            canvas, -shoulderSpread, -26f * s, pose.leftArmDeg, pose.leftForearmDeg,
            26f * s, 22f * s, 11f * s, primary, skin, strokePaint, alphaInt, isArm = true
        )

        // Back leg
        drawLimbSegment(
            canvas, -hipSpread, 18f * s, pose.leftLegDeg, pose.leftKneeDeg,
            30f * s, 28f * s, 13f * s, secondary, accent, strokePaint, alphaInt, isArm = false
        )

        // Front leg
        drawLimbSegment(
            canvas, hipSpread, 18f * s, pose.rightLegDeg, pose.rightKneeDeg,
            30f * s, 28f * s, 13f * s, secondary, accent, strokePaint, alphaInt, isArm = false
        )

        // Torso / Outfit
        val torsoRect = RectF(-torsoWidth / 2f, -34f * s, torsoWidth / 2f, 24f * s)
        fillPaint.color = withAlpha(primary, alphaInt)
        canvas.drawRoundRect(torsoRect, 16f * s, 16f * s, fillPaint)
        canvas.drawRoundRect(torsoRect, 16f * s, 16f * s, strokePaint)

        // Waist sash / belt detail
        fillPaint.color = withAlpha(accent, alphaInt)
        canvas.drawRoundRect(
            RectF(-torsoWidth / 2f + 2f * s, 8f * s, torsoWidth / 2f - 2f * s, 18f * s),
            5f * s, 5f * s, fillPaint
        )

        // Scarf accessory across collar
        if (rig.accessory == CharacterAccessory.SCARF) {
            fillPaint.color = withAlpha(accent, alphaInt)
            canvas.drawRoundRect(
                RectF(-torsoWidth / 2f - 2f * s, -36f * s, torsoWidth / 2f + 2f * s, -22f * s),
                8f * s, 8f * s, fillPaint
            )
            canvas.drawRoundRect(
                RectF(4f * s, -24f * s, 18f * s, 4f * s),
                6f * s, 6f * s, fillPaint
            )
        }

        // Cape drawn over back when viewing from back
        if (rig.accessory == CharacterAccessory.CAPE && isBack) {
            drawCape(canvas, s, accent, alphaInt)
        }

        // Neck
        fillPaint.color = withAlpha(skin, alphaInt)
        canvas.drawRoundRect(RectF(-7f * s, -46f * s, 7f * s, -30f * s), 4f * s, 4f * s, fillPaint)

        // Head group
        canvas.save()
        canvas.translate(0f, -64f * s)
        canvas.rotate(pose.headNodDeg)

        // Halo behind head
        if (rig.accessory == CharacterAccessory.HALO) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 5f * s
                color = withAlpha(0xFFFACC15.toInt(), alphaInt)
            }
            canvas.drawOval(RectF(-26f * s, -42f * s, 26f * s, -28f * s), haloPaint)
        }

        val headRadius = 25f * s
        fillPaint.color = withAlpha(skin, alphaInt)
        canvas.drawCircle(0f, 0f, headRadius, fillPaint)
        canvas.drawCircle(0f, 0f, headRadius, strokePaint)

        // Hair cap
        fillPaint.color = withAlpha(hair, alphaInt)
        if (isBack) {
            canvas.drawCircle(0f, -2f * s, headRadius + 1.5f * s, fillPaint)
            canvas.drawCircle(0f, -2f * s, headRadius + 1.5f * s, strokePaint)
        } else {
            val hairRect = RectF(-headRadius - 1f * s, -headRadius - 3f * s, headRadius + 1f * s, 4f * s)
            canvas.drawArc(hairRect, 180f, 180f, true, fillPaint)
            // Side bangs
            if (isSide) {
                canvas.drawOval(RectF(-headRadius - 2f * s, -16f * s, 2f * s, 12f * s), fillPaint)
            }
        }

        // Facial features (only visible on Front, 3/4 Front, Side)
        if (!isBack) {
            val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(0xFF111827.toInt(), alphaInt)
                style = Paint.Style.FILL
            }
            val eyeHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
            }
            val eyePositions = if (isSide) {
                listOf(10f * s)
            } else {
                listOf(-8.5f * s + faceShiftX * 0.55f, 8.5f * s + faceShiftX * 0.55f)
            }
            for (ex in eyePositions) {
                if (pose.blinkClosed) {
                    val blinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = withAlpha(0xFF111827.toInt(), alphaInt)
                        strokeWidth = 2.6f * s
                        strokeCap = Paint.Cap.ROUND
                    }
                    canvas.drawLine(ex - 3.5f * s, -2f * s, ex + 3.5f * s, -2f * s, blinkPaint)
                } else {
                    canvas.drawCircle(ex, -2f * s, 3.6f * s, eyePaint)
                    canvas.drawCircle(ex + 1.1f * s, -3.2f * s, 1.2f * s, eyeHighlight)
                }
            }

            // Cheek blush on happy/dancing
            if (pose.happySmile) {
                fillPaint.color = withAlpha(0x55F43F5E, alphaInt)
                canvas.drawCircle(-13f * s + faceShiftX * 0.4f, 6f * s, 4.5f * s, fillPaint)
                if (!isSide) {
                    canvas.drawCircle(13f * s + faceShiftX * 0.4f, 6f * s, 4.5f * s, fillPaint)
                }
            }

            // Mouth (animated when talking or smiling)
            val mouthX = faceShiftX * 0.5f
            val mouthY = 10.5f * s
            if (pose.mouthOpen > 0.15f) {
                fillPaint.color = withAlpha(0xFF7F1D1D.toInt(), alphaInt)
                val openH = (3f + pose.mouthOpen * 6.5f) * s
                canvas.drawOval(RectF(mouthX - 6f * s, mouthY - openH * 0.4f, mouthX + 6f * s, mouthY + openH), fillPaint)
            } else {
                val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * s
                    strokeCap = Paint.Cap.ROUND
                    color = withAlpha(0xFF1F2937.toInt(), alphaInt)
                }
                val smileRect = RectF(mouthX - 7f * s, mouthY - 4f * s, mouthX + 7f * s, mouthY + if (pose.happySmile) 7f * s else 3f * s)
                canvas.drawArc(smileRect, 15f, 150f, false, mouthPaint)
            }
        }

        // Head Accessories
        drawHeadAccessory(canvas, rig.accessory, s, accent, primary, strokePaint, alphaInt)
        canvas.restore()

        // Near arm (drawn in front of torso)
        drawLimbSegment(
            canvas, shoulderSpread, -26f * s, pose.rightArmDeg, pose.rightForearmDeg,
            26f * s, 22f * s, 11f * s, primary, skin, strokePaint, alphaInt, isArm = true
        )
    }

    private fun drawLimbSegment(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        upperDeg: Float,
        lowerDeg: Float,
        upperLen: Float,
        lowerLen: Float,
        thickness: Float,
        upperColor: Int,
        lowerColor: Int,
        strokePaint: Paint,
        alphaInt: Int,
        isArm: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        canvas.save()
        canvas.translate(originX, originY)
        canvas.rotate(upperDeg)

        paint.color = withAlpha(upperColor, alphaInt)
        val upperRect = RectF(-thickness / 2f, 0f, thickness / 2f, upperLen)
        canvas.drawRoundRect(upperRect, thickness / 2f, thickness / 2f, paint)
        canvas.drawRoundRect(upperRect, thickness / 2f, thickness / 2f, strokePaint)

        canvas.translate(0f, upperLen - thickness * 0.2f)
        canvas.rotate(lowerDeg)
        paint.color = withAlpha(if (isArm) lowerColor else upperColor, alphaInt)
        val lowerRect = RectF(-thickness * 0.45f, 0f, thickness * 0.45f, lowerLen)
        canvas.drawRoundRect(lowerRect, thickness / 2f, thickness / 2f, paint)
        canvas.drawRoundRect(lowerRect, thickness / 2f, thickness / 2f, strokePaint)

        // Hand or Shoe tip
        paint.color = withAlpha(lowerColor, alphaInt)
        if (isArm) {
            canvas.drawCircle(0f, lowerLen, thickness * 0.56f, paint)
            canvas.drawCircle(0f, lowerLen, thickness * 0.56f, strokePaint)
        } else {
            val shoeRect = RectF(-thickness * 0.6f, lowerLen - thickness * 0.2f, thickness * 1.15f, lowerLen + thickness * 0.65f)
            canvas.drawRoundRect(shoeRect, thickness * 0.35f, thickness * 0.35f, paint)
            canvas.drawRoundRect(shoeRect, thickness * 0.35f, thickness * 0.35f, strokePaint)
        }
        canvas.restore()
    }

    private fun drawHeadAccessory(
        canvas: Canvas,
        accessory: CharacterAccessory,
        s: Float,
        accent: Int,
        primary: Int,
        stroke: Paint,
        alphaInt: Int
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        when (accessory) {
            CharacterAccessory.TURBAN -> {
                fill.color = withAlpha(accent, alphaInt)
                val rect = RectF(-28f * s, -34f * s, 28f * s, -8f * s)
                canvas.drawRoundRect(rect, 14f * s, 14f * s, fill)
                canvas.drawRoundRect(rect, 14f * s, 14f * s, stroke)
                fill.color = withAlpha(0xFFFDE047.toInt(), alphaInt)
                canvas.drawCircle(0f, -22f * s, 5f * s, fill)
            }
            CharacterAccessory.HAT -> {
                fill.color = withAlpha(primary, alphaInt)
                canvas.drawRoundRect(RectF(-34f * s, -22f * s, 34f * s, -14f * s), 5f * s, 5f * s, fill)
                canvas.drawRoundRect(RectF(-20f * s, -38f * s, 20f * s, -18f * s), 7f * s, 7f * s, fill)
                canvas.drawRoundRect(RectF(-20f * s, -38f * s, 20f * s, -18f * s), 7f * s, 7f * s, stroke)
            }
            CharacterAccessory.CROWN -> {
                fill.color = withAlpha(0xFFFACC15.toInt(), alphaInt)
                val path = Path().apply {
                    moveTo(-18f * s, -20f * s)
                    lineTo(-22f * s, -38f * s)
                    lineTo(-8f * s, -28f * s)
                    lineTo(0f, -42f * s)
                    lineTo(8f * s, -28f * s)
                    lineTo(22f * s, -38f * s)
                    lineTo(18f * s, -20f * s)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
            CharacterAccessory.HORNS -> {
                fill.color = withAlpha(accent, alphaInt)
                val leftHorn = Path().apply {
                    moveTo(-14f * s, -20f * s)
                    quadTo(-28f * s, -32f * s, -20f * s, -44f * s)
                    quadTo(-12f * s, -32f * s, -6f * s, -22f * s)
                    close()
                }
                val rightHorn = Path().apply {
                    moveTo(14f * s, -20f * s)
                    quadTo(28f * s, -32f * s, 20f * s, -44f * s)
                    quadTo(12f * s, -32f * s, 6f * s, -22f * s)
                    close()
                }
                canvas.drawPath(leftHorn, fill)
                canvas.drawPath(rightHorn, fill)
            }
            CharacterAccessory.HEADPHONES -> {
                val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 5f * s
                    color = withAlpha(accent, alphaInt)
                }
                canvas.drawArc(RectF(-28f * s, -29f * s, 28f * s, 20f * s), 190f, 160f, false, band)
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRoundRect(RectF(-31f * s, -10f * s, -22f * s, 10f * s), 4f * s, 4f * s, fill)
                canvas.drawRoundRect(RectF(22f * s, -10f * s, 31f * s, 10f * s), 4f * s, 4f * s, fill)
            }
            else -> Unit
        }
    }

    private fun drawCape(canvas: Canvas, s: Float, color: Int, alphaInt: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = withAlpha(color, (alphaInt * 0.9f).toInt())
        }
        val path = Path().apply {
            moveTo(-24f * s, -30f * s)
            lineTo(24f * s, -30f * s)
            lineTo(38f * s, 56f * s)
            quadTo(0f, 66f * s, -38f * s, 56f * s)
            close()
        }
        canvas.drawPath(path, fill)
    }

    private fun drawBird(
        canvas: Canvas,
        rig: CharacterRigDefinition,
        orientation: CharacterOrientation,
        pose: PoseAngles,
        s: Float,
        alphaInt: Int,
        beak: Int,
        primary: Int,
        secondary: Int,
        accent: Int
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * s
            color = withAlpha(0xFF0F172A.toInt(), alphaInt)
        }
        val isBack = orientation == CharacterOrientation.BACK || orientation == CharacterOrientation.THREE_QUARTER_BACK

        // Shadow
        fill.color = withAlpha(0x44000000, alphaInt)
        canvas.drawOval(RectF(-28f * s, 52f * s, 28f * s, 64f * s), fill)

        // Tail feathers
        fill.color = withAlpha(secondary, alphaInt)
        val tailPath = Path().apply {
            moveTo(-24f * s, 14f * s)
            lineTo(-56f * s, 34f * s)
            lineTo(-44f * s, 46f * s)
            lineTo(-14f * s, 26f * s)
            close()
        }
        canvas.drawPath(tailPath, fill)

        // Legs
        val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(beak, alphaInt)
            strokeWidth = 4.5f * s
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(-10f * s, 30f * s, -12f * s + pose.leftLegDeg * 0.4f * s, 54f * s, legPaint)
        canvas.drawLine(10f * s, 30f * s, 12f * s + pose.rightLegDeg * 0.4f * s, 54f * s, legPaint)

        // Body oval
        fill.color = withAlpha(primary, alphaInt)
        val bodyRect = RectF(-34f * s, -20f * s, 34f * s, 36f * s)
        canvas.drawOval(bodyRect, fill)
        canvas.drawOval(bodyRect, stroke)

        // Belly highlight
        fill.color = withAlpha(accent, (alphaInt * 0.85f).toInt())
        canvas.drawOval(RectF(-18f * s, -4f * s, 18f * s, 28f * s), fill)

        // Flapping Wings
        canvas.save()
        canvas.translate(-24f * s, -4f * s)
        canvas.rotate(-25f - pose.wingFlapDeg)
        fill.color = withAlpha(secondary, alphaInt)
        canvas.drawOval(RectF(-34f * s, -10f * s, 6f * s, 18f * s), fill)
        canvas.drawOval(RectF(-34f * s, -10f * s, 6f * s, 18f * s), stroke)
        canvas.restore()

        canvas.save()
        canvas.translate(24f * s, -4f * s)
        canvas.rotate(25f + pose.wingFlapDeg)
        fill.color = withAlpha(secondary, alphaInt)
        canvas.drawOval(RectF(-6f * s, -10f * s, 34f * s, 18f * s), fill)
        canvas.drawOval(RectF(-6f * s, -10f * s, 34f * s, 18f * s), stroke)
        canvas.restore()

        // Head
        canvas.save()
        canvas.translate(0f, -34f * s)
        canvas.rotate(pose.headNodDeg)
        fill.color = withAlpha(primary, alphaInt)
        canvas.drawCircle(0f, 0f, 22f * s, fill)
        canvas.drawCircle(0f, 0f, 22f * s, stroke)

        if (!isBack) {
            // Eyes
            val eyeOffset = if (orientation == CharacterOrientation.SIDE) 8f * s else 0f
            fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
            canvas.drawCircle(-7f * s + eyeOffset, -4f * s, 5f * s, fill)
            if (orientation != CharacterOrientation.SIDE) {
                canvas.drawCircle(7f * s, -4f * s, 5f * s, fill)
            }
            fill.color = withAlpha(0xFF0F172A.toInt(), alphaInt)
            canvas.drawCircle(-6f * s + eyeOffset, -4f * s, 2.5f * s, fill)
            if (orientation != CharacterOrientation.SIDE) {
                canvas.drawCircle(8f * s, -4f * s, 2.5f * s, fill)
            }
            // Beak
            fill.color = withAlpha(beak, alphaInt)
            val beakShift = if (orientation == CharacterOrientation.SIDE) 18f * s else 0f
            val beakPath = Path().apply {
                moveTo(-8f * s + beakShift, 2f * s)
                lineTo(8f * s + beakShift, 2f * s)
                lineTo(beakShift, (15f + pose.mouthOpen * 7f) * s)
                close()
            }
            canvas.drawPath(beakPath, fill)
            canvas.drawPath(beakPath, stroke)
        }
        drawHeadAccessory(canvas, rig.accessory, s, accent, primary, stroke, alphaInt)
        canvas.restore()
    }

    private fun drawQuadruped(
        canvas: Canvas,
        rig: CharacterRigDefinition,
        orientation: CharacterOrientation,
        pose: PoseAngles,
        s: Float,
        alphaInt: Int,
        belly: Int,
        primary: Int,
        secondary: Int,
        accent: Int
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * s
            color = withAlpha(0xFF0F172A.toInt(), alphaInt)
        }
        val isBack = orientation == CharacterOrientation.BACK || orientation == CharacterOrientation.THREE_QUARTER_BACK

        // Ground shadow
        fill.color = withAlpha(0x44000000, alphaInt)
        canvas.drawOval(RectF(-46f * s, 46f * s, 46f * s, 60f * s), fill)

        // Wagging tail
        canvas.save()
        canvas.translate(-38f * s, -2f * s)
        canvas.rotate(-30f + pose.rightArmDeg * 0.7f)
        fill.color = withAlpha(primary, alphaInt)
        canvas.drawRoundRect(RectF(-34f * s, -6f * s, 4f * s, 6f * s), 6f * s, 6f * s, fill)
        canvas.restore()

        // Four animated legs
        val legAngles = listOf(pose.leftLegDeg, pose.rightLegDeg, -pose.leftLegDeg * 0.8f, -pose.rightLegDeg * 0.8f)
        val legXs = listOf(-28f * s, -14f * s, 14f * s, 28f * s)
        for (i in legXs.indices) {
            canvas.save()
            canvas.translate(legXs[i], 14f * s)
            canvas.rotate(legAngles[i])
            fill.color = withAlpha(if (i % 2 == 0) secondary else primary, alphaInt)
            val lr = RectF(-7f * s, 0f, 7f * s, 36f * s)
            canvas.drawRoundRect(lr, 6f * s, 6f * s, fill)
            canvas.drawRoundRect(lr, 6f * s, 6f * s, stroke)
            canvas.restore()
        }

        // Body
        fill.color = withAlpha(primary, alphaInt)
        val bodyRect = RectF(-42f * s, -22f * s, 40f * s, 24f * s)
        canvas.drawRoundRect(bodyRect, 20f * s, 20f * s, fill)
        canvas.drawRoundRect(bodyRect, 20f * s, 20f * s, stroke)

        // Belly patch
        fill.color = withAlpha(belly, alphaInt)
        canvas.drawOval(RectF(-24f * s, -2f * s, 24f * s, 20f * s), fill)

        // Head
        canvas.save()
        canvas.translate(if (orientation == CharacterOrientation.FRONT) 0f else 32f * s, -28f * s)
        canvas.rotate(pose.headNodDeg)

        // Ears
        fill.color = withAlpha(primary, alphaInt)
        canvas.drawCircle(-17f * s, -18f * s, 9f * s, fill)
        canvas.drawCircle(17f * s, -18f * s, 9f * s, fill)
        fill.color = withAlpha(belly, alphaInt)
        canvas.drawCircle(-17f * s, -18f * s, 4.5f * s, fill)
        canvas.drawCircle(17f * s, -18f * s, 4.5f * s, fill)

        // Head circle
        fill.color = withAlpha(primary, alphaInt)
        canvas.drawCircle(0f, 0f, 23f * s, fill)
        canvas.drawCircle(0f, 0f, 23f * s, stroke)

        if (!isBack) {
            fill.color = withAlpha(belly, alphaInt)
            canvas.drawOval(RectF(-13f * s, 0f, 13f * s, 17f * s), fill)
            fill.color = withAlpha(accent, alphaInt)
            canvas.drawCircle(-8f * s, -5f * s, 3.3f * s, fill)
            canvas.drawCircle(8f * s, -5f * s, 3.3f * s, fill)
            // Nose
            canvas.drawOval(RectF(-5f * s, 1f * s, 5f * s, 7f * s), fill)
        }
        drawHeadAccessory(canvas, rig.accessory, s, accent, primary, stroke, alphaInt)
        canvas.restore()
    }

    private fun drawVehicle(
        canvas: Canvas,
        orientation: CharacterOrientation,
        pose: PoseAngles,
        s: Float,
        alphaInt: Int,
        windowColor: Int,
        primary: Int,
        secondary: Int,
        accent: Int
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.2f * s
            color = withAlpha(0xFF0F172A.toInt(), alphaInt)
        }

        // Shadow
        fill.color = withAlpha(0x55000000, alphaInt)
        canvas.drawOval(RectF(-66f * s, 38f * s, 66f * s, 54f * s), fill)

        val isFrontOrBack = orientation == CharacterOrientation.FRONT || orientation == CharacterOrientation.BACK
        val bodyW = if (isFrontOrBack) 46f * s else 64f * s

        // Chassis lower body
        fill.color = withAlpha(primary, alphaInt)
        val chassis = RectF(-bodyW, -6f * s, bodyW, 32f * s)
        canvas.drawRoundRect(chassis, 14f * s, 14f * s, fill)
        canvas.drawRoundRect(chassis, 14f * s, 14f * s, stroke)

        // Stripe
        fill.color = withAlpha(secondary, alphaInt)
        canvas.drawRect(-bodyW + 3f * s, 10f * s, bodyW - 3f * s, 20f * s, fill)

        // Cabin top
        fill.color = withAlpha(secondary, alphaInt)
        val cabin = RectF(-bodyW * 0.72f, -42f * s, bodyW * 0.65f, -2f * s)
        canvas.drawRoundRect(cabin, 16f * s, 16f * s, fill)
        canvas.drawRoundRect(cabin, 16f * s, 16f * s, stroke)

        // Windshield / Windows
        fill.color = withAlpha(windowColor, alphaInt)
        canvas.drawRoundRect(
            RectF(-bodyW * 0.56f, -34f * s, bodyW * 0.50f, -8f * s),
            8f * s, 8f * s, fill
        )

        // Headlights / Taillights
        fill.color = withAlpha(
            if (orientation == CharacterOrientation.BACK) 0xFFEF4444.toInt() else 0xFFFEF08A.toInt(),
            alphaInt
        )
        canvas.drawCircle(bodyW - 8f * s, 12f * s, 6f * s, fill)
        if (isFrontOrBack) {
            canvas.drawCircle(-bodyW + 8f * s, 12f * s, 6f * s, fill)
        }

        // Spinning Wheels
        val wheelPositions = if (isFrontOrBack) listOf(-bodyW * 0.65f, bodyW * 0.65f) else listOf(-36f * s, 36f * s)
        for (wx in wheelPositions) {
            canvas.save()
            canvas.translate(wx, 34f * s)
            canvas.rotate(pose.wheelSpinDeg)
            fill.color = withAlpha(accent, alphaInt)
            canvas.drawCircle(0f, 0f, 16f * s, fill)
            canvas.drawCircle(0f, 0f, 16f * s, stroke)
            fill.color = withAlpha(0xFFE2E8F0.toInt(), alphaInt)
            canvas.drawCircle(0f, 0f, 7f * s, fill)
            canvas.drawLine(-7f * s, 0f, 7f * s, 0f, stroke)
            canvas.drawLine(0f, -7f * s, 0f, 7f * s, stroke)
            canvas.restore()
        }
    }

    private fun drawSpirit(
        canvas: Canvas,
        rig: CharacterRigDefinition,
        orientation: CharacterOrientation,
        pose: PoseAngles,
        s: Float,
        alphaInt: Int,
        faceGlow: Int,
        primary: Int,
        secondary: Int,
        accent: Int
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.8f * s
            color = withAlpha(accent, alphaInt)
        }
        val isBack = orientation == CharacterOrientation.BACK || orientation == CharacterOrientation.THREE_QUARTER_BACK

        // Spectral aura
        fill.color = withAlpha(accent, (alphaInt * 0.18f).toInt())
        canvas.drawCircle(0f, -12f * s, 56f * s, fill)

        // Flowing spectral robe
        val wave = sin(pose.rightArmDeg * PI / 180.0).toFloat() * 8f * s
        val bodyPath = Path().apply {
            moveTo(-30f * s, -42f * s)
            quadTo(0f, -78f * s, 30f * s, -42f * s)
            lineTo(38f * s, 44f * s)
            quadTo(20f * s, 58f * s + wave, 8f * s, 40f * s)
            quadTo(0f, 62f * s - wave, -10f * s, 40f * s)
            quadTo(-24f * s, 58f * s + wave, -38f * s, 44f * s)
            close()
        }
        fill.color = withAlpha(primary, (alphaInt * 0.92f).toInt())
        canvas.drawPath(bodyPath, fill)
        canvas.drawPath(bodyPath, stroke)

        // Inner hood shade
        fill.color = withAlpha(secondary, alphaInt)
        canvas.drawOval(RectF(-22f * s, -48f * s, 22f * s, -8f * s), fill)

        if (!isBack) {
            // Glowing eyes
            fill.color = withAlpha(accent, alphaInt)
            canvas.drawCircle(-8f * s, -28f * s, 4.5f * s, fill)
            canvas.drawCircle(8f * s, -28f * s, 4.5f * s, fill)
            fill.color = withAlpha(faceGlow, alphaInt)
            canvas.drawCircle(-8f * s, -28f * s, 2f * s, fill)
            canvas.drawCircle(8f * s, -28f * s, 2f * s, fill)
        }

        canvas.save()
        canvas.translate(0f, -38f * s)
        drawHeadAccessory(canvas, rig.accessory, s, accent, primary, stroke, alphaInt)
        canvas.restore()
    }

    internal fun computePose(preset: AnimationPresetType, phase: Float): PoseAngles {
        val rad = (phase * 2.0 * PI).toFloat()
        val s1 = sin(rad)
        val c1 = cos(rad)
        val s2 = sin(rad * 2f)
        val blink = phase in 0.88f..0.94f

        return when (preset) {
            AnimationPresetType.IDLE -> PoseAngles(
                bodyBobY = s1 * 2.5f,
                headNodDeg = s1 * 2.5f,
                leftArmDeg = 12f + s1 * 4f,
                rightArmDeg = -12f - s1 * 4f,
                blinkClosed = blink,
                wingFlapDeg = s1 * 8f,
                wheelSpinDeg = phase * 60f
            )
            AnimationPresetType.IDLE_HAPPY -> PoseAngles(
                bodyBobY = -abs(s1) * 7f,
                bodyTiltDeg = s1 * 4f,
                headNodDeg = s2 * 5f,
                leftArmDeg = 22f + s1 * 12f,
                rightArmDeg = -22f - s1 * 12f,
                happySmile = true,
                blinkClosed = blink,
                wingFlapDeg = abs(s1) * 24f,
                wheelSpinDeg = phase * 180f
            )
            AnimationPresetType.WALK -> PoseAngles(
                bodyBobY = -abs(s1) * 6f,
                bodyTiltDeg = 4f,
                leftArmDeg = s1 * 32f,
                rightArmDeg = -s1 * 32f,
                leftForearmDeg = -14f,
                rightForearmDeg = -14f,
                leftLegDeg = -s1 * 34f,
                rightLegDeg = s1 * 34f,
                leftKneeDeg = (s1.coerceAtLeast(0f)) * 22f,
                rightKneeDeg = ((-s1).coerceAtLeast(0f)) * 22f,
                wingFlapDeg = s1 * 28f,
                wheelSpinDeg = phase * 360f
            )
            AnimationPresetType.JOGGING -> PoseAngles(
                bodyBobY = -abs(s1) * 11f,
                bodyTiltDeg = 9f,
                leftArmDeg = s1 * 52f,
                rightArmDeg = -s1 * 52f,
                leftForearmDeg = -45f,
                rightForearmDeg = -45f,
                leftLegDeg = -s1 * 48f,
                rightLegDeg = s1 * 48f,
                leftKneeDeg = 26f + (s1.coerceAtLeast(0f)) * 28f,
                rightKneeDeg = 26f + ((-s1).coerceAtLeast(0f)) * 28f,
                wingFlapDeg = s1 * 42f,
                wheelSpinDeg = phase * 720f
            )
            AnimationPresetType.RUNNING -> PoseAngles(
                bodyBobY = -abs(s1) * 15f,
                bodyTiltDeg = 16f,
                leftArmDeg = s1 * 72f,
                rightArmDeg = -s1 * 72f,
                leftForearmDeg = -62f,
                rightForearmDeg = -62f,
                leftLegDeg = -s1 * 64f,
                rightLegDeg = s1 * 64f,
                leftKneeDeg = 36f + (s1.coerceAtLeast(0f)) * 38f,
                rightKneeDeg = 36f + ((-s1).coerceAtLeast(0f)) * 38f,
                wingFlapDeg = s1 * 55f,
                wheelSpinDeg = phase * 1080f
            )
            AnimationPresetType.SITTING -> PoseAngles(
                bodyBobY = 20f + s1 * 1.8f,
                leftArmDeg = 18f,
                rightArmDeg = -18f,
                leftForearmDeg = -32f,
                rightForearmDeg = 32f,
                leftLegDeg = -72f,
                rightLegDeg = -66f,
                leftKneeDeg = 76f,
                rightKneeDeg = 72f,
                blinkClosed = blink,
                wingFlapDeg = s1 * 5f
            )
            AnimationPresetType.STANDING -> PoseAngles(
                bodyBobY = s1 * 1.2f,
                leftArmDeg = 7f,
                rightArmDeg = -7f,
                leftLegDeg = 3f,
                rightLegDeg = -3f,
                blinkClosed = blink
            )
            AnimationPresetType.TALKING -> PoseAngles(
                bodyBobY = s1 * 2.5f,
                headNodDeg = s2 * 6f,
                leftArmDeg = 20f + s1 * 18f,
                rightArmDeg = -36f - c1 * 22f,
                leftForearmDeg = -28f,
                rightForearmDeg = -40f + s2 * 16f,
                mouthOpen = abs(sin(rad * 3f)),
                happySmile = true,
                wingFlapDeg = abs(s2) * 18f
            )
            AnimationPresetType.WAVING -> PoseAngles(
                bodyBobY = s1 * 3f,
                headNodDeg = -6f + s1 * 4f,
                leftArmDeg = 14f,
                rightArmDeg = -142f + s2 * 18f,
                rightForearmDeg = -28f + s2 * 24f,
                happySmile = true,
                blinkClosed = blink,
                wingFlapDeg = 35f + s2 * 20f
            )
            AnimationPresetType.JUMPING -> {
                val air = sin(phase * PI).toFloat()
                val crouch = if (phase < 0.2f || phase > 0.85f) 1f else 0f
                PoseAngles(
                    bodyBobY = if (crouch > 0f) 12f else -air * 44f,
                    squashX = if (crouch > 0f) 1.12f else 0.94f,
                    squashY = if (crouch > 0f) 0.88f else 1.08f,
                    leftArmDeg = if (crouch > 0f) 30f else 135f,
                    rightArmDeg = if (crouch > 0f) -30f else -135f,
                    leftLegDeg = if (crouch > 0f) -26f else 16f,
                    rightLegDeg = if (crouch > 0f) 26f else -16f,
                    leftKneeDeg = if (crouch > 0f) 46f else 18f,
                    rightKneeDeg = if (crouch > 0f) 46f else 18f,
                    happySmile = true,
                    wingFlapDeg = air * 52f
                )
            }
            AnimationPresetType.DANCING -> PoseAngles(
                bodyBobY = -abs(s2) * 12f,
                bodyTiltDeg = s1 * 11f,
                headNodDeg = -s1 * 10f,
                leftArmDeg = 95f + s1 * 48f,
                rightArmDeg = -95f + s1 * 48f,
                leftForearmDeg = -34f + c1 * 24f,
                rightForearmDeg = 34f - c1 * 24f,
                leftLegDeg = -18f + s1 * 22f,
                rightLegDeg = 18f + s1 * 22f,
                happySmile = true,
                wingFlapDeg = 30f + s2 * 30f,
                wheelSpinDeg = phase * 360f
            )
        }
    }

    fun parseColorSafe(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun applyTint(baseColor: Int, tint: Int?): Int {
        if (tint == null) return baseColor
        val r = (Color.red(baseColor) * 0.45f + Color.red(tint) * 0.55f).toInt().coerceIn(0, 255)
        val g = (Color.green(baseColor) * 0.45f + Color.green(tint) * 0.55f).toInt().coerceIn(0, 255)
        val b = (Color.blue(baseColor) * 0.45f + Color.blue(tint) * 0.55f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
