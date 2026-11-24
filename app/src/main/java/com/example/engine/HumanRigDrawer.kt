package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.hypot
import kotlin.math.max

/**
 * High-detail anatomical Real-Human 2D Illustrated Rig & PNG-to-Skeleton Cutout Articulated Rig.
 */
internal object HumanRigDrawer {

    fun drawRealHuman(
        canvas: Canvas,
        rig: CharacterRigDefinition,
        orientation: CharacterOrientation,
        pose: ProceduralRigRenderer.PoseAngles,
        s: Float,
        alphaInt: Int,
        skin: Int,
        primary: Int,
        secondary: Int,
        accent: Int,
        hair: Int,
        eyeColor: Int,
        drawAccessory: (Canvas, CharacterAccessory, Float, Int, Int, Paint, Int) -> Unit
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = withAlpha(darken(skin, 0.28f), (alphaInt * 0.85f).toInt())
            strokeWidth = 2.0f * s
        }
        val clothOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = withAlpha(0xFF0E131D.toInt(), (alphaInt * 0.72f).toInt())
            strokeWidth = 2.1f * s
        }

        val isBack = orientation == CharacterOrientation.BACK || orientation == CharacterOrientation.THREE_QUARTER_BACK
        val isSide = orientation == CharacterOrientation.SIDE
        val isThreeQuarter = orientation == CharacterOrientation.THREE_QUARTER_FRONT ||
            orientation == CharacterOrientation.THREE_QUARTER_BACK

        val build = rig.buildType
        val shoulderHalf = (if (isSide) 7f else if (isThreeQuarter) 22f else 26f) * build.shoulderScale * s
        val waistHalf = (if (isSide) 13f else if (isThreeQuarter) 16.5f else 18f) * build.torsoScale * s
        val hipHalf = (if (isSide) 5f else 11.5f) * build.torsoScale * s
        val faceShiftX = when (orientation) {
            CharacterOrientation.FRONT -> 0f
            CharacterOrientation.THREE_QUARTER_FRONT -> 7.5f * s
            CharacterOrientation.SIDE -> 12f * s
            CharacterOrientation.THREE_QUARTER_BACK -> -7f * s
            CharacterOrientation.BACK -> 0f
        }

        // Ground Contact Shadow
        fill.color = withAlpha(0x52000000, alphaInt)
        canvas.drawOval(RectF(-34f * s, 82f * s, 34f * s, 94f * s), fill)

        // Cape behind body if equipped
        if (rig.accessory == CharacterAccessory.CAPE && !isBack) {
            drawHumanCape(canvas, s, accent, alphaInt)
        }

        // Back hair drape for long hairstyles
        if (!isBack && rig.hairStyle == HumanHairStyle.WAVY_LONG) {
            fill.color = withAlpha(darken(hair, 0.85f), alphaInt)
            val backHair = Path().apply {
                moveTo(-22f * s, -72f * s)
                quadTo(-30f * s, -32f * s, -24f * s, -10f * s)
                quadTo(0f, -4f * s, 24f * s, -10f * s)
                quadTo(30f * s, -32f * s, 22f * s, -72f * s)
                close()
            }
            canvas.drawPath(backHair, fill)
        }

        // Far Arm (Upper arm + Elbow + Forearm + Sculpted Hand)
        val sleeveColor = if (rig.costumeStyle == HumanCostumeStyle.SPORT_ATHLETIC) skin else primary
        drawAnatomicalArm(
            canvas, -shoulderHalf, -30f * s, pose.leftArmDeg, pose.leftForearmDeg,
            s, primary, sleeveColor, skin, clothOutline, alphaInt, mirrorThumb = true
        )

        // Far Leg & Near Leg (Thigh + Knee + Calf + Structured Shoe)
        val pantColor = when (rig.costumeStyle) {
            HumanCostumeStyle.VILLAGE_DHOTI -> secondary
            HumanCostumeStyle.SAREE_DRAPE -> darken(primary, 0.82f)
            else -> secondary
        }
        drawAnatomicalLeg(
            canvas, -hipHalf, 20f * s, pose.leftLegDeg, pose.leftKneeDeg,
            s, pantColor, accent, clothOutline, alphaInt
        )
        drawAnatomicalLeg(
            canvas, hipHalf, 20f * s, pose.rightLegDeg, pose.rightKneeDeg,
            s, pantColor, accent, clothOutline, alphaInt
        )

        // Anatomical Torso with Tapered Shoulders, Chest, Waist & Pelvis
        drawHumanTorsoAndCostume(
            canvas, rig.costumeStyle, shoulderHalf, waistHalf, s,
            primary, secondary, accent, skin, clothOutline, alphaInt, isBack
        )

        // Scarf accessory across collarbone
        if (rig.accessory == CharacterAccessory.SCARF) {
            fill.color = withAlpha(accent, alphaInt)
            val scarfNeck = RectF(-shoulderHalf * 0.65f, -38f * s, shoulderHalf * 0.65f, -26f * s)
            canvas.drawRoundRect(scarfNeck, 6f * s, 6f * s, fill)
            canvas.drawRoundRect(scarfNeck, 6f * s, 6f * s, clothOutline)
            val tail = Path().apply {
                moveTo(3f * s, -28f * s)
                lineTo(15f * s, -28f * s)
                lineTo(19f * s, 4f * s)
                lineTo(6f * s, 6f * s)
                close()
            }
            canvas.drawPath(tail, fill)
            canvas.drawPath(tail, clothOutline)
        }

        if (rig.accessory == CharacterAccessory.CAPE && isBack) {
            drawHumanCape(canvas, s, accent, alphaInt)
        }

        // Anatomical Neck with Chin Shadow & Collarbone Hint
        val neckW = 7.8f * s
        fill.color = withAlpha(skin, alphaInt)
        canvas.drawRoundRect(RectF(-neckW, -52f * s, neckW, -31f * s), 4f * s, 4f * s, fill)
        // Chin ambient shadow on neck
        fill.color = withAlpha(darken(skin, 0.78f), alphaInt)
        canvas.drawOval(RectF(-neckW * 0.9f, -50f * s, neckW * 0.9f, -40f * s), fill)

        // Sculpted Real-Human Head Group
        canvas.save()
        canvas.translate(0f, -68f * s)
        canvas.rotate(pose.headNodDeg)

        // Halo behind head
        if (rig.accessory == CharacterAccessory.HALO) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4.5f * s
                color = withAlpha(0xFFFACC15.toInt(), alphaInt)
            }
            canvas.drawOval(RectF(-24f * s, -40f * s, 24f * s, -28f * s), haloPaint)
        }

        // Ears
        fill.color = withAlpha(darken(skin, 0.92f), alphaInt)
        if (!isSide) {
            canvas.drawOval(RectF(-22f * s, -6f * s, -15f * s, 7f * s), fill)
            canvas.drawOval(RectF(15f * s, -6f * s, 22f * s, 7f * s), fill)
        } else {
            canvas.drawOval(RectF(-5f * s, -5f * s, 3f * s, 8f * s), fill)
        }

        // Sculpted Cranium + Tapered Jawline Path
        val headW = if (isSide) 18.5f * s else 20.5f * s
        val headPath = Path().apply {
            moveTo(-headW, -4f * s)
            cubicTo(-headW, -27f * s, headW, -27f * s, headW, -4f * s)
            cubicTo(headW, 9f * s, headW * 0.62f, 20f * s, faceShiftX * 0.25f, 23.5f * s)
            cubicTo(-headW * 0.62f, 20f * s, -headW, 9f * s, -headW, -4f * s)
            close()
        }
        val skinShader = LinearGradient(
            -headW, -20f * s, headW, 20f * s,
            withAlpha(lighten(skin, 1.07f), alphaInt),
            withAlpha(darken(skin, 0.88f), alphaInt),
            Shader.TileMode.CLAMP
        )
        fill.shader = skinShader
        canvas.drawPath(headPath, fill)
        fill.shader = null
        canvas.drawPath(headPath, outline)

        // Realistic Facial Features (visible on Front, 3/4 Front, Side)
        if (!isBack) {
            drawHumanFaceFeatures(
                canvas, orientation, faceShiftX, pose, s, alphaInt, skin, hair, eyeColor
            )
        }

        // Layered Hairstyles
        drawHumanHair(canvas, rig.hairStyle, isBack, isSide, headW, s, hair, alphaInt)

        // Head Accessories
        drawAccessory(canvas, rig.accessory, s, accent, primary, clothOutline, alphaInt)
        canvas.restore()

        // Near Arm (drawn cleanly in front of torso)
        drawAnatomicalArm(
            canvas, shoulderHalf, -30f * s, pose.rightArmDeg, pose.rightForearmDeg,
            s, primary, sleeveColor, skin, clothOutline, alphaInt, mirrorThumb = false
        )
    }

    private fun drawHumanTorsoAndCostume(
        canvas: Canvas,
        style: HumanCostumeStyle,
        shoulderHalf: Float,
        waistHalf: Float,
        s: Float,
        primary: Int,
        secondary: Int,
        accent: Int,
        skin: Int,
        outline: Paint,
        alphaInt: Int,
        isBack: Boolean
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
        val bottomY = when (style) {
            HumanCostumeStyle.KURTA_TRADITIONAL, HumanCostumeStyle.VILLAGE_DHOTI -> 40f * s
            HumanCostumeStyle.FANTASY_ARMOR -> 32f * s
            else -> 24f * s
        }

        val torsoPath = Path().apply {
            moveTo(-shoulderHalf, -32f * s)
            quadTo(0f, -38f * s, shoulderHalf, -32f * s)
            lineTo(waistHalf, 12f * s)
            lineTo(waistHalf * 1.10f, bottomY)
            quadTo(0f, bottomY + 5f * s, -waistHalf * 1.10f, bottomY)
            lineTo(-waistHalf, 12f * s)
            close()
        }

        val gradient = LinearGradient(
            -shoulderHalf, -30f * s, shoulderHalf, bottomY,
            withAlpha(lighten(primary, 1.08f), alphaInt),
            withAlpha(darken(primary, 0.80f), alphaInt),
            Shader.TileMode.CLAMP
        )
        fill.shader = gradient
        canvas.drawPath(torsoPath, fill)
        fill.shader = null
        canvas.drawPath(torsoPath, outline)

        if (isBack) return

        // Costume-specific anatomical garment details
        when (style) {
            HumanCostumeStyle.CASUAL_JACKET -> {
                // Inner t-shirt V-panel + jacket lapels
                fill.color = withAlpha(lighten(secondary, 1.4f), alphaInt)
                val inner = Path().apply {
                    moveTo(-8f * s, -34f * s)
                    lineTo(8f * s, -34f * s)
                    lineTo(5f * s, 24f * s)
                    lineTo(-5f * s, 24f * s)
                    close()
                }
                canvas.drawPath(inner, fill)
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawCircle(-9f * s, -12f * s, 2.2f * s, fill)
                canvas.drawCircle(-9f * s, 2f * s, 2.2f * s, fill)
            }
            HumanCostumeStyle.KURTA_TRADITIONAL -> {
                // Mandarin collar & embroidered vertical button placket
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRoundRect(RectF(-9f * s, -37f * s, 9f * s, -30f * s), 3f * s, 3f * s, fill)
                canvas.drawRoundRect(RectF(-3.2f * s, -31f * s, 3.2f * s, 2f * s), 2f * s, 2f * s, fill)
                fill.color = withAlpha(0xFFFFFBEB.toInt(), alphaInt)
                canvas.drawCircle(0f, -22f * s, 1.6f * s, fill)
                canvas.drawCircle(0f, -13f * s, 1.6f * s, fill)
                canvas.drawCircle(0f, -4f * s, 1.6f * s, fill)
            }
            HumanCostumeStyle.SAREE_DRAPE -> {
                // Diagonal pallu drape & golden zari border
                val drape = Path().apply {
                    moveTo(-shoulderHalf * 0.9f, -33f * s)
                    lineTo(-shoulderHalf * 0.25f, -34f * s)
                    lineTo(waistHalf * 1.05f, 22f * s)
                    lineTo(waistHalf * 0.35f, 25f * s)
                    close()
                }
                fill.color = withAlpha(accent, (alphaInt * 0.92f).toInt())
                canvas.drawPath(drape, fill)
                canvas.drawPath(drape, outline)
                // Waist pleats
                fill.color = withAlpha(secondary, alphaInt)
                canvas.drawRoundRect(RectF(-waistHalf, 12f * s, waistHalf, 21f * s), 3f * s, 3f * s, fill)
            }
            HumanCostumeStyle.FORMAL_SUIT -> {
                // Crisp white dress shirt triangle + necktie
                val shirt = Path().apply {
                    moveTo(-10f * s, -34f * s)
                    lineTo(10f * s, -34f * s)
                    lineTo(0f, -2f * s)
                    close()
                }
                fill.color = withAlpha(0xFFF8FAFC.toInt(), alphaInt)
                canvas.drawPath(shirt, fill)
                val tie = Path().apply {
                    moveTo(-3.2f * s, -32f * s)
                    lineTo(3.2f * s, -32f * s)
                    lineTo(4.5f * s, -7f * s)
                    lineTo(0f, 1f * s)
                    lineTo(-4.5f * s, -7f * s)
                    close()
                }
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawPath(tie, fill)
            }
            HumanCostumeStyle.SPORT_ATHLETIC -> {
                // Athletic chest band & V-neck collar
                fill.color = withAlpha(skin, alphaInt)
                val vNeck = Path().apply {
                    moveTo(-9f * s, -35f * s)
                    lineTo(9f * s, -35f * s)
                    lineTo(0f, -23f * s)
                    close()
                }
                canvas.drawPath(vNeck, fill)
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRect(-waistHalf * 0.96f, -12f * s, waistHalf * 0.96f, -4f * s, fill)
            }
            HumanCostumeStyle.HOODIE_STREET -> {
                // Hood drawstrings & kangaroo front pocket
                val cordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(0xFFF8FAFC.toInt(), alphaInt)
                    strokeWidth = 2.2f * s
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(-5f * s, -31f * s, -6f * s, -12f * s, cordPaint)
                canvas.drawLine(5f * s, -31f * s, 6f * s, -12f * s, cordPaint)
                fill.color = withAlpha(darken(primary, 0.82f), alphaInt)
                canvas.drawRoundRect(RectF(-13f * s, 2f * s, 13f * s, 18f * s), 5f * s, 5f * s, fill)
                canvas.drawRoundRect(RectF(-13f * s, 2f * s, 13f * s, 18f * s), 5f * s, 5f * s, outline)
            }
            HumanCostumeStyle.VILLAGE_DHOTI -> {
                // Waist sash & dhoti center fold
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRoundRect(RectF(-waistHalf * 1.05f, 10f * s, waistHalf * 1.05f, 19f * s), 4f * s, 4f * s, fill)
                val fold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(darken(secondary, 0.72f), alphaInt)
                    strokeWidth = 2f * s
                }
                canvas.drawLine(0f, 19f * s, 0f, bottomY, fold)
            }
            HumanCostumeStyle.FANTASY_ARMOR -> {
                // Sculpted chest emblem + waist belt buckle
                fill.color = withAlpha(accent, alphaInt)
                val crest = Path().apply {
                    moveTo(0f, -24f * s)
                    lineTo(12f * s, -14f * s)
                    lineTo(8f * s, 4f * s)
                    lineTo(0f, 10f * s)
                    lineTo(-8f * s, 4f * s)
                    lineTo(-12f * s, -14f * s)
                    close()
                }
                canvas.drawPath(crest, fill)
                canvas.drawPath(crest, outline)
                canvas.drawRoundRect(RectF(-waistHalf, 12f * s, waistHalf, 20f * s), 3f * s, 3f * s, fill)
            }
        }
    }

    private fun drawHumanFaceFeatures(
        canvas: Canvas,
        orientation: CharacterOrientation,
        faceShiftX: Float,
        pose: ProceduralRigRenderer.PoseAngles,
        s: Float,
        alphaInt: Int,
        skin: Int,
        hair: Int,
        eyeColor: Int
    ) {
        val isSide = orientation == CharacterOrientation.SIDE
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val eyePositions = if (isSide) {
            listOf(9.5f * s)
        } else {
            listOf(-7.5f * s + faceShiftX * 0.55f, 7.5f * s + faceShiftX * 0.55f)
        }

        // Expressive Eyebrows
        stroke.color = withAlpha(darken(hair, 0.9f), alphaInt)
        stroke.strokeWidth = 2.1f * s
        val browLift = if (pose.happySmile) -1.6f * s else 0f
        for (ex in eyePositions) {
            canvas.drawArc(
                RectF(ex - 4.8f * s, -11.5f * s + browLift, ex + 4.8f * s, -5.5f * s + browLift),
                200f, 140f, false, stroke
            )
        }

        // Almond Human Eyes (Sclera + Colored Iris + Pupil + Specular Glint + Lash)
        for (ex in eyePositions) {
            if (pose.blinkClosed) {
                stroke.color = withAlpha(0xFF1F1B18.toInt(), alphaInt)
                stroke.strokeWidth = 2.0f * s
                canvas.drawArc(RectF(ex - 4.2f * s, -4f * s, ex + 4.2f * s, 1f * s), 10f, 160f, false, stroke)
            } else {
                // Sclera
                fill.color = withAlpha(0xFFFAFAFA.toInt(), alphaInt)
                canvas.drawOval(RectF(ex - 4.5f * s, -5.2f * s, ex + 4.5f * s, 1.4f * s), fill)
                // Iris
                fill.color = withAlpha(eyeColor, alphaInt)
                canvas.drawCircle(ex + faceShiftX * 0.08f, -2.0f * s, 2.7f * s, fill)
                // Pupil
                fill.color = withAlpha(0xFF0A0D12.toInt(), alphaInt)
                canvas.drawCircle(ex + faceShiftX * 0.08f, -2.0f * s, 1.45f * s, fill)
                // Eye sparkle highlight
                fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
                canvas.drawCircle(ex + 1.0f * s, -3.1f * s, 0.9f * s, fill)
                // Upper lash contour
                stroke.color = withAlpha(0xFF141210.toInt(), alphaInt)
                stroke.strokeWidth = 1.6f * s
                canvas.drawArc(RectF(ex - 4.6f * s, -5.5f * s, ex + 4.6f * s, 1.2f * s), 195f, 150f, false, stroke)
            }
        }

        // Subtle Anatomical Nose Bridge & Tip Shadow
        val noseX = faceShiftX * 0.65f
        stroke.color = withAlpha(darken(skin, 0.68f), alphaInt)
        stroke.strokeWidth = 1.6f * s
        val nosePath = Path().apply {
            moveTo(noseX - 0.8f * s, -2.5f * s)
            lineTo(noseX + (if (isSide) 4.2f else 1.4f) * s, 4.8f * s)
            lineTo(noseX - 1.2f * s, 5.8f * s)
        }
        canvas.drawPath(nosePath, stroke)

        // Subtle Natural Cheek Warmth
        fill.color = withAlpha(0x2AE11D48, alphaInt)
        if (!isSide) {
            canvas.drawOval(RectF(-14f * s + faceShiftX * 0.35f, 3f * s, -6f * s + faceShiftX * 0.35f, 8.5f * s), fill)
            canvas.drawOval(RectF(6f * s + faceShiftX * 0.35f, 3f * s, 14f * s + faceShiftX * 0.35f, 8.5f * s), fill)
        }

        // Sculpted Human Lips / Talking Mouth
        val mouthX = faceShiftX * 0.55f
        val mouthY = 12.2f * s
        if (pose.mouthOpen > 0.14f) {
            val openH = (2.8f + pose.mouthOpen * 5.6f) * s
            fill.color = withAlpha(0xFF7F1D1D.toInt(), alphaInt)
            canvas.drawOval(RectF(mouthX - 6f * s, mouthY - openH * 0.35f, mouthX + 6f * s, mouthY + openH), fill)
            // Upper teeth bar
            fill.color = withAlpha(0xFFF8FAFC.toInt(), alphaInt)
            canvas.drawRoundRect(RectF(mouthX - 4.2f * s, mouthY - openH * 0.28f, mouthX + 4.2f * s, mouthY + 0.8f * s), 1.5f * s, 1.5f * s, fill)
        } else {
            stroke.color = withAlpha(0xFF8C3D3D.toInt(), alphaInt)
            stroke.strokeWidth = 2.0f * s
            val lipRect = RectF(mouthX - 6.2f * s, mouthY - 3.2f * s, mouthX + 6.2f * s, mouthY + if (pose.happySmile) 5.5f * s else 2.2f * s)
            canvas.drawArc(lipRect, 15f, 150f, false, stroke)
        }
    }

    private fun drawHumanHair(
        canvas: Canvas,
        hairStyle: HumanHairStyle,
        isBack: Boolean,
        isSide: Boolean,
        headW: Float,
        s: Float,
        hairColor: Int,
        alphaInt: Int
    ) {
        if (hairStyle == HumanHairStyle.NONE) return
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(hairColor, alphaInt)
        }
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * s
            strokeCap = Paint.Cap.ROUND
            color = withAlpha(lighten(hairColor, 1.38f), (alphaInt * 0.55f).toInt())
        }

        if (isBack) {
            canvas.drawOval(RectF(-headW - 2f * s, -24f * s, headW + 2f * s, 12f * s), fill)
            if (hairStyle == HumanHairStyle.TRADITIONAL_BUN) {
                canvas.drawCircle(0f, -6f * s, 11f * s, fill)
            }
            return
        }

        when (hairStyle) {
            HumanHairStyle.SHORT_FADE -> {
                canvas.drawArc(RectF(-headW - 1.5f * s, -25f * s, headW + 1.5f * s, 2f * s), 185f, 170f, true, fill)
                canvas.drawArc(RectF(-12f * s, -20f * s, 12f * s, -8f * s), 200f, 140f, false, highlight)
            }
            HumanHairStyle.SIDE_PART -> {
                val path = Path().apply {
                    moveTo(-headW - 1f * s, -4f * s)
                    cubicTo(-headW - 3f * s, -27f * s, headW + 3f * s, -28f * s, headW + 1f * s, -5f * s)
                    quadTo(8f * s, -15f * s, -6f * s, -11f * s)
                    quadTo(-16f * s, -8f * s, -headW - 1f * s, -4f * s)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawArc(RectF(-13f * s, -21f * s, 13f * s, -9f * s), 195f, 145f, false, highlight)
            }
            HumanHairStyle.WAVY_LONG -> {
                canvas.drawArc(RectF(-headW - 3f * s, -26f * s, headW + 3f * s, 4f * s), 180f, 180f, true, fill)
                canvas.drawOval(RectF(-headW - 4f * s, -14f * s, -headW + 7f * s, 22f * s), fill)
                if (!isSide) {
                    canvas.drawOval(RectF(headW - 7f * s, -14f * s, headW + 4f * s, 22f * s), fill)
                }
            }
            HumanHairStyle.TRADITIONAL_BUN -> {
                canvas.drawCircle(0f, -26f * s, 10.5f * s, fill)
                canvas.drawArc(RectF(-headW - 1.5f * s, -24f * s, headW + 1.5f * s, 2f * s), 182f, 176f, true, fill)
            }
            HumanHairStyle.CURLY_AFRO -> {
                for (i in -3..3) {
                    val cx = i * 6.5f * s
                    val cy = -18f * s + kotlin.math.abs(i) * 2.2f * s
                    canvas.drawCircle(cx, cy, 9.5f * s, fill)
                }
            }
            HumanHairStyle.SPIKY -> {
                val spikes = Path().apply {
                    moveTo(-headW, -6f * s)
                    lineTo(-headW - 4f * s, -20f * s)
                    lineTo(-10f * s, -17f * s)
                    lineTo(-5f * s, -30f * s)
                    lineTo(4f * s, -19f * s)
                    lineTo(13f * s, -28f * s)
                    lineTo(headW + 2f * s, -14f * s)
                    lineTo(headW, -5f * s)
                    quadTo(0f, -14f * s, -headW, -6f * s)
                    close()
                }
                canvas.drawPath(spikes, fill)
            }
            HumanHairStyle.NONE -> Unit
        }
    }

    private fun drawAnatomicalArm(
        canvas: Canvas,
        ox: Float,
        oy: Float,
        upperDeg: Float,
        forearmDeg: Float,
        s: Float,
        upperColor: Int,
        sleeveColor: Int,
        skin: Int,
        outline: Paint,
        alphaInt: Int,
        mirrorThumb: Boolean
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        canvas.save()
        canvas.translate(ox, oy)
        canvas.rotate(upperDeg)

        // Upper arm / Sleeve
        val upperLen = 28f * s
        val upperW = 5.4f * s
        fill.color = withAlpha(upperColor, alphaInt)
        val upperRect = RectF(-upperW, -2f * s, upperW, upperLen)
        canvas.drawRoundRect(upperRect, upperW, upperW, fill)
        canvas.drawRoundRect(upperRect, upperW, upperW, outline)

        // Elbow pivot -> Forearm
        canvas.translate(0f, upperLen - 3f * s)
        canvas.rotate(forearmDeg)
        val foreLen = 24f * s
        val foreW = 4.4f * s
        fill.color = withAlpha(if (sleeveColor == skin) skin else skin, alphaInt)
        val foreRect = RectF(-foreW, 0f, foreW, foreLen)
        canvas.drawRoundRect(foreRect, foreW, foreW, fill)
        canvas.drawRoundRect(foreRect, foreW, foreW, outline)

        // Sculpted Human Hand with Thumb
        canvas.translate(0f, foreLen + 2f * s)
        fill.color = withAlpha(skin, alphaInt)
        canvas.drawOval(RectF(-4.8f * s, -3f * s, 4.8f * s, 7.5f * s), fill)
        canvas.drawOval(RectF(-4.8f * s, -3f * s, 4.8f * s, 7.5f * s), outline)
        val thumbSign = if (mirrorThumb) 1f else -1f
        canvas.drawOval(RectF(thumbSign * 2.5f * s - 2.2f * s, -1f * s, thumbSign * 2.5f * s + 2.2f * s, 4.5f * s), fill)

        canvas.restore()
    }

    private fun drawAnatomicalLeg(
        canvas: Canvas,
        ox: Float,
        oy: Float,
        thighDeg: Float,
        kneeDeg: Float,
        s: Float,
        pantColor: Int,
        shoeColor: Int,
        outline: Paint,
        alphaInt: Int
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        canvas.save()
        canvas.translate(ox, oy)
        canvas.rotate(thighDeg)

        val thighLen = 32f * s
        val thighW = 6.8f * s
        fill.color = withAlpha(pantColor, alphaInt)
        val thighRect = RectF(-thighW, -2f * s, thighW, thighLen)
        canvas.drawRoundRect(thighRect, thighW, thighW, fill)
        canvas.drawRoundRect(thighRect, thighW, thighW, outline)

        // Knee pivot -> Calf
        canvas.translate(0f, thighLen - 3f * s)
        canvas.rotate(kneeDeg)
        val calfLen = 31f * s
        val calfW = 5.4f * s
        fill.color = withAlpha(darken(pantColor, 0.92f), alphaInt)
        val calfRect = RectF(-calfW, 0f, calfW, calfLen)
        canvas.drawRoundRect(calfRect, calfW, calfW, fill)
        canvas.drawRoundRect(calfRect, calfW, calfW, outline)

        // Structured Sneaker / Shoe with Sole
        val shoeTop = calfLen - 2f * s
        fill.color = withAlpha(darken(shoeColor, 0.65f), alphaInt)
        val shoeRect = RectF(-calfW * 1.05f, shoeTop, calfW * 2.15f, shoeTop + 9.5f * s)
        canvas.drawRoundRect(shoeRect, 4.5f * s, 4.5f * s, fill)
        canvas.drawRoundRect(shoeRect, 4.5f * s, 4.5f * s, outline)
        fill.color = withAlpha(0xFFE2E8F0.toInt(), alphaInt)
        canvas.drawRoundRect(RectF(-calfW * 1.05f, shoeTop + 6.8f * s, calfW * 2.15f, shoeTop + 9.8f * s), 2f * s, 2f * s, fill)

        canvas.restore()
    }

    private fun drawHumanCape(canvas: Canvas, s: Float, color: Int, alphaInt: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = withAlpha(color, (alphaInt * 0.92f).toInt())
        }
        val path = Path().apply {
            moveTo(-24f * s, -32f * s)
            lineTo(24f * s, -32f * s)
            lineTo(38f * s, 60f * s)
            quadTo(0f, 70f * s, -38f * s, 60f * s)
            close()
        }
        canvas.drawPath(path, fill)
    }

    /**
     * Renders an imported PNG character using 2D Alpha-Aware Linear Blend Skinning (LBS)
     * and Hierarchical Forward Kinematics via [SkeletonMeshDeformer] so joints bend smoothly
     * without seams, black clipPath cutouts, or duplicated limb slices.
     */
    fun drawSkeletonPngRig(
        canvas: Canvas,
        bitmap: Bitmap,
        skeleton: SkeletonRigConfig,
        pose: ProceduralRigRenderer.PoseAngles,
        rigScale: Float,
        alpha: Float,
        tintOverlayColor: Int?
    ) {
        SkeletonMeshDeformer.drawDeformedSkeletonRig(
            canvas = canvas,
            bitmap = bitmap,
            skeleton = skeleton,
            pose = pose,
            rigScale = rigScale,
            alpha = alpha,
            tintOverlayColor = tintOverlayColor
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun lighten(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )
}
