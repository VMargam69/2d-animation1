package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.hypot
import kotlin.math.max

/**
 * High-detail anatomical Real-Human 2D Illustrated Rig & PNG-to-Skeleton Cutout Articulated Rig.
 */
internal object HumanRigDrawer {

    private class HumanScratch {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val clothOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val rect = RectF()
        val path = Path()
    }

    private val scratchLocal = ThreadLocal.withInitial { HumanScratch() }

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
        propType: CharacterPropType = CharacterPropType.NONE,
        propAttachment: PropAttachmentPoint = PropAttachmentPoint.RIGHT_HAND,
        customPropBitmap: Bitmap? = null,
        propScale: Float = 1.0f,
        organicFace: OrganicFacialState? = null,
        drawAccessory: (Canvas, CharacterAccessory, Float, Int, Int, Paint, Int) -> Unit
    ) {
        val scratch = scratchLocal.get()!!
        val fill = scratch.fill
        val clothOutline = scratch.clothOutline
        clothOutline.color = withAlpha(0xFF0E131D.toInt(), (alphaInt * 0.72f).toInt())
        clothOutline.strokeWidth = 2.1f * s

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

        // Cape behind body if equipped (with physical sway flutter)
        if (rig.accessory == CharacterAccessory.CAPE && !isBack) {
            drawHumanCape(canvas, s, accent, alphaInt, pose.accessoryFlutterDeg, scratch)
        }

        // Back hair drape for long hairstyles
        if (!isBack && rig.hairStyle == HumanHairStyle.WAVY_LONG) {
            fill.color = withAlpha(darken(hair, 0.85f), alphaInt)
            val backHair = scratch.path
            backHair.reset()
            backHair.moveTo(-22f * s, -72f * s)
            backHair.quadTo(-30f * s, -32f * s, -24f * s, -10f * s)
            backHair.quadTo(0f, -4f * s, 24f * s, -10f * s)
            backHair.quadTo(30f * s, -32f * s, 22f * s, -72f * s)
            backHair.close()
            canvas.drawPath(backHair, fill)
        }

        // Far Arm (Upper arm + Elbow + Forearm + Sculpted Hand)
        val sleeveColor = if (rig.costumeStyle == HumanCostumeStyle.SPORT_ATHLETIC) skin else primary
        drawAnatomicalArm(
            canvas = canvas,
            ox = -shoulderHalf,
            oy = -30f * s,
            upperDeg = pose.leftArmDeg,
            forearmDeg = pose.leftForearmDeg,
            s = s,
            upperColor = primary,
            sleeveColor = sleeveColor,
            skin = skin,
            outline = clothOutline,
            alphaInt = alphaInt,
            mirrorThumb = true,
            propType = if (propAttachment == PropAttachmentPoint.LEFT_HAND) propType else CharacterPropType.NONE,
            customPropBitmap = customPropBitmap,
            propScale = propScale,
            propSwayDeg = pose.propSwayDeg,
            propSwayOffsetX = pose.propSwayOffsetX * s,
            propSwayOffsetY = pose.propSwayOffsetY * s
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

        // Scarf accessory across collarbone (tail flutters naturally with body motion)
        if (rig.accessory == CharacterAccessory.SCARF) {
            fill.color = withAlpha(accent, alphaInt)
            scratch.rect.set(-shoulderHalf * 0.65f, -38f * s, shoulderHalf * 0.65f, -26f * s)
            canvas.drawRoundRect(scratch.rect, 6f * s, 6f * s, fill)
            canvas.drawRoundRect(scratch.rect, 6f * s, 6f * s, clothOutline)
            canvas.save()
            canvas.translate(9f * s, -28f * s)
            canvas.rotate(pose.accessoryFlutterDeg * 0.85f)
            val tail = scratch.path
            tail.reset()
            tail.moveTo(-6f * s, 0f)
            tail.lineTo(6f * s, 0f)
            tail.lineTo(10f * s, 32f * s)
            tail.lineTo(-3f * s, 34f * s)
            tail.close()
            canvas.drawPath(tail, fill)
            canvas.drawPath(tail, clothOutline)
            canvas.restore()
        }

        if (rig.accessory == CharacterAccessory.CAPE && isBack) {
            drawHumanCape(canvas, s, accent, alphaInt, pose.accessoryFlutterDeg, scratch)
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
        canvas.rotate(pose.headNodDeg + (organicFace?.headMicroNodDeg ?: 0f))

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

        // Sculpted Cranium + Tapered Jawline Path with 2-Tone Cel-Shading
        val headW = if (isSide) 18.5f * s else 20.5f * s
        val headPath = Path().apply {
            moveTo(-headW, -4f * s)
            cubicTo(-headW, -27f * s, headW, -27f * s, headW, -4f * s)
            cubicTo(headW, 9f * s, headW * 0.62f, 20f * s, faceShiftX * 0.25f, 23.5f * s)
            cubicTo(-headW * 0.62f, 20f * s, -headW, 9f * s, -headW, -4f * s)
            close()
        }
        fill.color = withAlpha(skin, alphaInt)
        canvas.drawPath(headPath, fill)
        // Clipped cel-shadow crescent on far cheek/jaw + soft rim light
        canvas.save()
        canvas.clipPath(headPath)
        val shadowCrescent = Path().apply {
            moveTo(headW * 0.42f, -24f * s)
            cubicTo(headW * 0.85f, -6f * s, headW * 0.72f, 14f * s, -headW * 0.25f, 23f * s)
            lineTo(headW + 4f * s, 26f * s)
            lineTo(headW + 4f * s, -28f * s)
            close()
        }
        fill.color = withAlpha(darken(skin, 0.84f), alphaInt)
        canvas.drawPath(shadowCrescent, fill)
        // Specular forehead/cheek highlight
        fill.color = withAlpha(lighten(skin, 1.16f), (alphaInt * 0.55f).toInt())
        canvas.drawOval(RectF(-headW * 0.78f, -15f * s, -headW * 0.25f, 1f * s), fill)
        canvas.restore()
        canvas.drawPath(headPath, clothOutline)

        // Realistic Facial Features (visible on Front, 3/4 Front, Side)
        if (!isBack) {
            drawHumanFaceFeatures(
                canvas, orientation, faceShiftX, pose, s, alphaInt, skin, hair, eyeColor, organicFace
            )
        }

        // Layered Hairstyles
        drawHumanHair(canvas, rig.hairStyle, isBack, isSide, headW, s, hair, alphaInt)

        // Head Accessories (subtle physical inertia tilt for hats/crowns/turbans)
        if (rig.accessory != CharacterAccessory.NONE) {
            canvas.save()
            canvas.rotate(pose.accessoryFlutterDeg * 0.25f)
            drawAccessory(canvas, rig.accessory, s, accent, primary, clothOutline, alphaInt)
            canvas.restore()
        }

        // Head-mounted prop if attached to HEAD
        if (propType != CharacterPropType.NONE && propAttachment == PropAttachmentPoint.HEAD) {
            canvas.save()
            canvas.translate(0f, -25f * s)
            PropAndModularChartRenderer.drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, pose.propSwayDeg, alphaInt,
                pose.propSwayOffsetX * s, pose.propSwayOffsetY * s
            )
            canvas.restore()
        }
        canvas.restore()

        // Near Arm (drawn cleanly in front of torso)
        drawAnatomicalArm(
            canvas = canvas,
            ox = shoulderHalf,
            oy = -30f * s,
            upperDeg = pose.rightArmDeg,
            forearmDeg = pose.rightForearmDeg,
            s = s,
            upperColor = primary,
            sleeveColor = sleeveColor,
            skin = skin,
            outline = clothOutline,
            alphaInt = alphaInt,
            mirrorThumb = false,
            propType = if (propAttachment == PropAttachmentPoint.RIGHT_HAND) propType else CharacterPropType.NONE,
            customPropBitmap = customPropBitmap,
            propScale = propScale,
            propSwayDeg = pose.propSwayDeg,
            propSwayOffsetX = pose.propSwayOffsetX * s,
            propSwayOffsetY = pose.propSwayOffsetY * s
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
            cubicTo(shoulderHalf * 0.92f, -10f * s, waistHalf * 1.05f, 4f * s, waistHalf, 12f * s)
            lineTo(waistHalf * 1.12f, bottomY)
            quadTo(0f, bottomY + 6f * s, -waistHalf * 1.12f, bottomY)
            lineTo(-waistHalf, 12f * s)
            cubicTo(-waistHalf * 1.05f, 4f * s, -shoulderHalf * 0.92f, -10f * s, -shoulderHalf, -32f * s)
            close()
        }

        fill.color = withAlpha(primary, alphaInt)
        canvas.drawPath(torsoPath, fill)
        // Clipped 2-tone cel-shading shadow side + rim highlight
        canvas.save()
        canvas.clipPath(torsoPath)
        val torsoShadow = Path().apply {
            moveTo(shoulderHalf * 0.34f, -38f * s)
            quadTo(waistHalf * 0.45f, 4f * s, waistHalf * 0.25f, bottomY + 8f * s)
            lineTo(shoulderHalf + 10f * s, bottomY + 8f * s)
            lineTo(shoulderHalf + 10f * s, -38f * s)
            close()
        }
        fill.color = withAlpha(darken(primary, 0.78f), alphaInt)
        canvas.drawPath(torsoShadow, fill)
        val torsoHighlight = Path().apply {
            moveTo(-shoulderHalf, -34f * s)
            lineTo(-shoulderHalf * 0.48f, -34f * s)
            quadTo(-waistHalf * 0.62f, -4f * s, -waistHalf * 0.75f, bottomY)
            lineTo(-shoulderHalf - 4f * s, bottomY)
            close()
        }
        fill.color = withAlpha(lighten(primary, 1.18f), (alphaInt * 0.42f).toInt())
        canvas.drawPath(torsoHighlight, fill)
        canvas.restore()
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
        eyeColor: Int,
        organicFace: OrganicFacialState? = null
    ) {
        val isSide = orientation == CharacterOrientation.SIDE
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val eyePositions = if (isSide) {
            listOf(9.5f * s)
        } else {
            listOf(-7.5f * s + faceShiftX * 0.55f, 7.5f * s + faceShiftX * 0.55f)
        }

        val face = organicFace ?: FacialExpressionEngine.evaluateOrganicFace(
            activePresetId = null,
            previousPresetId = null,
            fallbackExpression = pose.facialExpression,
            framesSinceKey = 999,
            globalFrame = 0,
            fps = 24,
            baseMouthOpen = pose.mouthOpen,
            blinkOverride = if (pose.blinkClosed) true else null
        )

        // 1. Expressive Organic Eyebrows (asymmetric lift + tilt + natural Bézier arch)
        stroke.color = withAlpha(darken(hair, 0.88f), alphaInt)
        stroke.strokeWidth = 2.2f * s
        eyePositions.forEachIndexed { idx, ex ->
            val isLeft = idx == 0 && !isSide
            val lift = (if (isLeft) face.leftBrowLift else face.rightBrowLift) * 3.1f * s
            val tiltDeg = if (isLeft) face.leftBrowTiltDeg else face.rightBrowTiltDeg
            val tiltRad = tiltDeg * (Math.PI.toFloat() / 180f)
            val halfW = 4.9f * s
            val tiltDy = kotlin.math.sin(tiltRad) * halfW
            val baseY = -9.0f * s - lift
            // Left brow positive tilt lowers inner edge (angry V), negative raises inner edge (sad);
            // Right brow mirrors tilt symmetrically around the nose bridge.
            val outerY = baseY - tiltDy
            val innerY = baseY + tiltDy
            val archControlY = (outerY + innerY) * 0.5f - (1.8f + (if (isLeft) face.leftBrowLift else face.rightBrowLift).coerceAtLeast(0f) * 1.4f) * s

            val browPath = scratchLocal.get()!!.path
            browPath.reset()
            if (isLeft) {
                browPath.moveTo(ex - halfW, outerY)
                browPath.quadTo(ex, archControlY, ex + halfW * 0.92f, innerY)
            } else {
                browPath.moveTo(ex - halfW * 0.92f, innerY)
                browPath.quadTo(ex, archControlY, ex + halfW, outerY)
            }
            canvas.drawPath(browPath, stroke)
        }

        // 2. Almond Human Eyes (Sclera + Dynamic Pupil Micro-Saccades + Smooth Eyelid Curtain + Asymmetric Wink/Laugh Arc)
        eyePositions.forEachIndexed { idx, ex ->
            val isRight = idx == 1 || (isSide && idx == 0)
            val eyeClosed = if (isRight) face.isBlinkingRight else face.isBlinkingLeft
            val lidClose = if (isRight) face.rightEyelidClose else face.leftEyelidClose
            val eyeOpen = (if (isRight) face.rightEyeOpenness else face.leftEyeOpenness).coerceIn(0.22f, 1.38f)

            if (eyeClosed && face.happyEyeArc >= 0.45f) {
                // Joyful Duchenne laughing crescent / playful wink squeeze
                stroke.color = withAlpha(0xFF1F1B18.toInt(), alphaInt)
                stroke.strokeWidth = 2.3f * s
                canvas.drawArc(RectF(ex - 4.5f * s, -4.8f * s, ex + 4.5f * s, 1.2f * s), 195f, 150f, false, stroke)
            } else if (eyeClosed) {
                // Smooth relaxed blink curve
                stroke.color = withAlpha(0xFF1F1B18.toInt(), alphaInt)
                stroke.strokeWidth = 2.0f * s
                canvas.drawArc(RectF(ex - 4.3f * s, -4.1f * s, ex + 4.3f * s, 1.0f * s), 12f, 156f, false, stroke)
            } else {
                val topY = -5.1f * s * eyeOpen.coerceIn(0.65f, 1.32f)
                val botY = (1.5f - face.cheekSquint * 0.9f) * s
                val scleraRect = RectF(ex - 4.5f * s, topY, ex + 4.5f * s, botY)

                // Sclera
                fill.color = withAlpha(0xFFFAFAFA.toInt(), alphaInt)
                canvas.drawOval(scleraRect, fill)

                // Clip iris & pupil smoothly inside sclera so lids & gaze never pop outside
                canvas.save()
                val eyeClip = Path().apply { addOval(scleraRect, Path.Direction.CW) }
                canvas.clipPath(eyeClip)

                val gazeOffsetX = faceShiftX * 0.08f + face.eyeLookX * 1.9f * s
                val gazeOffsetY = face.eyeLookY * 1.4f * s
                val irisR = (2.65f * face.pupilScale.coerceIn(0.76f, 1.18f)) * s
                val cy = (topY + botY) * 0.5f + gazeOffsetY

                // Iris
                fill.color = withAlpha(eyeColor, alphaInt)
                canvas.drawCircle(ex + gazeOffsetX, cy, irisR, fill)
                // Pupil
                fill.color = withAlpha(0xFF0A0D12.toInt(), alphaInt)
                canvas.drawCircle(ex + gazeOffsetX, cy, irisR * 0.54f, fill)
                // Eye specular sparkle highlight
                fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
                canvas.drawCircle(ex + gazeOffsetX + 0.95f * s, cy - 1.05f * s, 0.88f * s, fill)

                // Smooth upper eyelid skin curtain for organic squint / anger / sleepy droop / mid-blink
                if (lidClose > 0.06f) {
                    val lidBottom = topY + (botY - topY) * (lidClose * 0.92f)
                    fill.color = withAlpha(darken(skin, 0.93f), alphaInt)
                    canvas.drawRect(ex - 5.2f * s, topY - 2f * s, ex + 5.2f * s, lidBottom, fill)
                    stroke.color = withAlpha(0xFF141210.toInt(), alphaInt)
                    stroke.strokeWidth = 1.6f * s
                    canvas.drawLine(ex - 4.6f * s, lidBottom, ex + 4.6f * s, lidBottom, stroke)
                }
                canvas.restore()

                // Upper lash contour
                stroke.color = withAlpha(0xFF141210.toInt(), alphaInt)
                stroke.strokeWidth = 1.65f * s
                canvas.drawArc(RectF(ex - 4.6f * s, topY - 0.3f * s, ex + 4.6f * s, botY + 0.2f * s), 192f, 156f, false, stroke)

                // Sad glistening tear droplet
                if (face.showTear) {
                    fill.color = withAlpha(0xD938BDF8.toInt(), alphaInt)
                    canvas.drawOval(RectF(ex + 2.0f * s, botY - 0.5f * s, ex + 4.5f * s, botY + 3.8f * s), fill)
                }
            }
        }

        // 3. Subtle Anatomical Nose Bridge & Tip Shadow
        val noseX = faceShiftX * 0.65f
        stroke.color = withAlpha(darken(skin, 0.68f), alphaInt)
        stroke.strokeWidth = 1.6f * s
        val nosePath = Path().apply {
            moveTo(noseX - 0.8f * s, -2.5f * s)
            lineTo(noseX + (if (isSide) 4.2f else 1.4f) * s, 4.8f * s)
            lineTo(noseX - 1.2f * s, 5.8f * s)
        }
        canvas.drawPath(nosePath, stroke)

        // 4. Dynamic Duchenne Cheek Warmth (brightens and lifts gently during Happy/Laugh/Wink)
        val cheekAlpha = ((0x22 + (face.cheekSquint * 0x2E).toInt()) * alphaInt) / 255
        fill.color = Color.argb(cheekAlpha.coerceIn(0, 255), 225, 29, 72)
        val cheekLift = face.cheekSquint * 1.4f * s
        if (!isSide) {
            canvas.drawOval(RectF(-14f * s + faceShiftX * 0.35f, 3f * s - cheekLift, -6f * s + faceShiftX * 0.35f, 8.5f * s - cheekLift), fill)
            canvas.drawOval(RectF(6f * s + faceShiftX * 0.35f, 3f * s - cheekLift, 14f * s + faceShiftX * 0.35f, 8.5f * s - cheekLift), fill)
        }

        // 5. Fluid Organic Sculpted Lips & Mouth (continuous morphing with volume-preserving squash & stretch)
        val mouthX = faceShiftX * 0.55f
        val mouthY = 12.2f * s - face.cheekSquint * 0.7f * s
        val halfW = (5.9f * face.mouthScaleX.coerceIn(0.68f, 1.35f)) * s
        val curveY = face.smileCurve * 4.2f * s
        val openAmount = face.mouthOpen.coerceIn(0f, 1f)

        if (face.showGrittedTeeth && openAmount > 0.10f) {
            // Fierce gritted-teeth expression
            val h = (2.1f + openAmount * 2.8f) * s * face.mouthScaleY.coerceIn(0.8f, 1.3f)
            fill.color = withAlpha(0xFF7F1D1D.toInt(), alphaInt)
            val rect = RectF(mouthX - halfW, mouthY - h * 0.55f, mouthX + halfW, mouthY + h * 0.75f)
            canvas.drawRoundRect(rect, 2.2f * s, 2.2f * s, fill)
            fill.color = withAlpha(0xFFF8FAFC.toInt(), alphaInt)
            canvas.drawRoundRect(
                RectF(mouthX - halfW * 0.84f, mouthY - h * 0.36f, mouthX + halfW * 0.84f, mouthY + h * 0.45f),
                1.4f * s, 1.4f * s, fill
            )
            stroke.color = withAlpha(0xFF641818.toInt(), alphaInt)
            stroke.strokeWidth = 1.2f * s
            canvas.drawLine(mouthX - halfW * 0.82f, mouthY + 0.1f * s, mouthX + halfW * 0.82f, mouthY + 0.1f * s, stroke)
        } else if (openAmount > 0.14f) {
            // Continuous open mouth with curved upper/lower lips, soft tongue hint & upper teeth
            val openH = (2.2f + openAmount * 6.8f) * s * face.mouthScaleY.coerceIn(0.8f, 1.35f)
            val cornerY = mouthY - curveY * 0.42f
            val upperMidY = mouthY - openH * 0.36f + curveY * 0.12f
            val lowerMidY = mouthY + openH * 0.92f + curveY * 0.35f

            val mouthPath = Path().apply {
                moveTo(mouthX - halfW, cornerY)
                quadTo(mouthX, upperMidY, mouthX + halfW, cornerY)
                quadTo(mouthX, lowerMidY, mouthX - halfW, cornerY)
                close()
            }
            fill.color = withAlpha(0xFF7F1D1D.toInt(), alphaInt)
            canvas.drawPath(mouthPath, fill)

            canvas.save()
            canvas.clipPath(mouthPath)
            // Warm tongue highlight at bottom of open mouth
            fill.color = withAlpha(0xFFE11D48.toInt(), alphaInt)
            canvas.drawOval(
                RectF(mouthX - halfW * 0.62f, mouthY + openH * 0.25f, mouthX + halfW * 0.62f, mouthY + openH * 1.15f),
                fill
            )
            // Upper teeth bar when smiling/talking/laughing
            if (face.showTeeth) {
                fill.color = withAlpha(0xFFF8FAFC.toInt(), alphaInt)
                canvas.drawRoundRect(
                    RectF(mouthX - halfW * 0.78f, upperMidY - 0.5f * s, mouthX + halfW * 0.78f, upperMidY + 2.3f * s),
                    1.4f * s, 1.4f * s, fill
                )
            }
            canvas.restore()

            stroke.color = withAlpha(0xFF8C3D3D.toInt(), alphaInt)
            stroke.strokeWidth = 1.6f * s
            canvas.drawPath(mouthPath, stroke)
        } else {
            // Closed expressive curved lip line (smoothly morphs between frown, neutral, smirk, and warm smile)
            stroke.color = withAlpha(0xFF8C3D3D.toInt(), alphaInt)
            stroke.strokeWidth = 2.1f * s
            val cornerY = mouthY - curveY * 0.55f
            val midY = mouthY + curveY * 0.68f
            val lipPath = Path().apply {
                moveTo(mouthX - halfW, cornerY)
                quadTo(mouthX, midY, mouthX + halfW, cornerY)
            }
            canvas.drawPath(lipPath, stroke)
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
        mirrorThumb: Boolean,
        propType: CharacterPropType = CharacterPropType.NONE,
        customPropBitmap: Bitmap? = null,
        propScale: Float = 1.0f,
        propSwayDeg: Float = 0f,
        propSwayOffsetX: Float = 0f,
        propSwayOffsetY: Float = 0f
    ) {
        val fill = scratchLocal.get()!!.fill
        canvas.save()
        canvas.translate(ox, oy)
        canvas.rotate(upperDeg)

        // Tapered Organic Upper Arm / Sleeve Path (wider deltoid tapering to elbow)
        val upperLen = 28f * s
        val topW = 6.2f * s
        val elbowW = 4.6f * s
        val upperPath = Path().apply {
            moveTo(-topW, 2f * s)
            quadTo(0f, -5.5f * s, topW, 2f * s)
            cubicTo(topW * 1.05f, upperLen * 0.42f, elbowW * 1.08f, upperLen * 0.78f, elbowW, upperLen)
            quadTo(0f, upperLen + 3.2f * s, -elbowW, upperLen)
            cubicTo(-elbowW * 1.08f, upperLen * 0.78f, -topW * 1.05f, upperLen * 0.42f, -topW, 2f * s)
            close()
        }
        fill.color = withAlpha(upperColor, alphaInt)
        canvas.drawPath(upperPath, fill)
        // Cel-shading shadow crescent & rim highlight on upper arm
        canvas.save()
        canvas.clipPath(upperPath)
        fill.color = withAlpha(darken(upperColor, 0.78f), alphaInt)
        canvas.drawRect(topW * 0.2f, -6f * s, topW + 4f * s, upperLen + 6f * s, fill)
        fill.color = withAlpha(lighten(upperColor, 1.18f), (alphaInt * 0.45f).toInt())
        canvas.drawRect(-topW - 2f * s, -4f * s, -topW * 0.42f, upperLen + 4f * s, fill)
        canvas.restore()
        canvas.drawPath(upperPath, outline)

        // Elbow pivot -> Tapered Organic Forearm (sculpted muscle contour tapering to wrist)
        canvas.translate(0f, upperLen - 2.5f * s)
        canvas.rotate(forearmDeg)
        val foreLen = 24f * s
        val foreTopW = 4.7f * s
        val wristW = 3.2f * s
        val foreColor = if (sleeveColor == skin) skin else skin
        val forePath = Path().apply {
            moveTo(-foreTopW, 1f * s)
            quadTo(0f, -3.5f * s, foreTopW, 1f * s)
            cubicTo(foreTopW * 1.05f, foreLen * 0.35f, wristW * 1.15f, foreLen * 0.75f, wristW, foreLen)
            quadTo(0f, foreLen + 2.5f * s, -wristW, foreLen)
            cubicTo(-wristW * 1.15f, foreLen * 0.75f, -foreTopW * 1.05f, foreLen * 0.35f, -foreTopW, 1f * s)
            close()
        }
        fill.color = withAlpha(foreColor, alphaInt)
        canvas.drawPath(forePath, fill)
        canvas.save()
        canvas.clipPath(forePath)
        fill.color = withAlpha(darken(foreColor, 0.82f), alphaInt)
        canvas.drawRect(foreTopW * 0.22f, -4f * s, foreTopW + 3f * s, foreLen + 4f * s, fill)
        fill.color = withAlpha(lighten(foreColor, 1.14f), (alphaInt * 0.42f).toInt())
        canvas.drawRect(-foreTopW - 2f * s, -3f * s, -foreTopW * 0.38f, foreLen + 3f * s, fill)
        canvas.restore()
        canvas.drawPath(forePath, outline)

        // Sculpted Human Hand with Palm & Thumb + Cel Shadow
        canvas.translate(0f, foreLen + 1.5f * s)
        val handRect = RectF(-4.8f * s, -2.6f * s, 4.8f * s, 7.6f * s)
        fill.color = withAlpha(skin, alphaInt)
        canvas.drawOval(handRect, fill)
        fill.color = withAlpha(darken(skin, 0.84f), alphaInt)
        canvas.drawArc(handRect, -40f, 150f, false, fill)
        canvas.drawOval(handRect, outline)
        val thumbSign = if (mirrorThumb) 1f else -1f
        val thumbRect = RectF(thumbSign * 2.8f * s - 2.2f * s, -1.2f * s, thumbSign * 2.8f * s + 2.2f * s, 4.4f * s)
        fill.color = withAlpha(skin, alphaInt)
        canvas.drawOval(thumbRect, fill)
        canvas.drawOval(thumbRect, outline)

        if (propType != CharacterPropType.NONE) {
            canvas.save()
            canvas.translate(0f, 3f * s)
            PropAndModularChartRenderer.drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, propSwayDeg, alphaInt,
                propSwayOffsetX, propSwayOffsetY
            )
            canvas.restore()
        }

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
        val fill = scratchLocal.get()!!.fill
        canvas.save()
        canvas.translate(ox, oy)
        canvas.rotate(thighDeg)

        // Tapered Organic Thigh Path (wider hip tapering cleanly to knee)
        val thighLen = 32f * s
        val hipW = 7.6f * s
        val kneeW = 5.4f * s
        val thighPath = Path().apply {
            moveTo(-hipW, 2f * s)
            quadTo(0f, -5f * s, hipW, 2f * s)
            cubicTo(hipW * 1.04f, thighLen * 0.45f, kneeW * 1.08f, thighLen * 0.80f, kneeW, thighLen)
            quadTo(0f, thighLen + 3.5f * s, -kneeW, thighLen)
            cubicTo(-kneeW * 1.08f, thighLen * 0.80f, -hipW * 1.04f, thighLen * 0.45f, -hipW, 2f * s)
            close()
        }
        fill.color = withAlpha(pantColor, alphaInt)
        canvas.drawPath(thighPath, fill)
        canvas.save()
        canvas.clipPath(thighPath)
        fill.color = withAlpha(darken(pantColor, 0.76f), alphaInt)
        canvas.drawRect(hipW * 0.22f, -6f * s, hipW + 4f * s, thighLen + 5f * s, fill)
        fill.color = withAlpha(lighten(pantColor, 1.16f), (alphaInt * 0.35f).toInt())
        canvas.drawRect(-hipW - 2f * s, -4f * s, -hipW * 0.40f, thighLen + 4f * s, fill)
        canvas.restore()
        canvas.drawPath(thighPath, outline)

        // Knee pivot -> Tapered Organic Calf Path (sculpted calf contour tapering to ankle)
        canvas.translate(0f, thighLen - 2.5f * s)
        canvas.rotate(kneeDeg)
        val calfLen = 31f * s
        val calfTopW = 5.7f * s
        val ankleW = 4.0f * s
        val calfBaseColor = darken(pantColor, 0.93f)
        val calfPath = Path().apply {
            moveTo(-calfTopW, 1f * s)
            quadTo(0f, -3.5f * s, calfTopW, 1f * s)
            cubicTo(calfTopW * 1.12f, calfLen * 0.32f, ankleW * 1.18f, calfLen * 0.74f, ankleW, calfLen)
            quadTo(0f, calfLen + 2.5f * s, -ankleW, calfLen)
            cubicTo(-ankleW * 1.12f, calfLen * 0.74f, -calfTopW * 1.06f, calfLen * 0.32f, -calfTopW, 1f * s)
            close()
        }
        fill.color = withAlpha(calfBaseColor, alphaInt)
        canvas.drawPath(calfPath, fill)
        canvas.save()
        canvas.clipPath(calfPath)
        fill.color = withAlpha(darken(calfBaseColor, 0.78f), alphaInt)
        canvas.drawRect(calfTopW * 0.22f, -4f * s, calfTopW + 3f * s, calfLen + 4f * s, fill)
        canvas.restore()
        canvas.drawPath(calfPath, outline)

        // Sculpted Sneaker / Shoe with Curved Toe Cap, Cel Shadow & Crisp Sole
        val shoeTop = calfLen - 2f * s
        val shoePath = Path().apply {
            moveTo(-ankleW * 1.35f, shoeTop)
            lineTo(ankleW * 1.25f, shoeTop)
            quadTo(ankleW * 2.85f, shoeTop + 1.5f * s, ankleW * 2.75f, shoeTop + 7.2f * s)
            lineTo(ankleW * 2.75f, shoeTop + 9.6f * s)
            lineTo(-ankleW * 1.45f, shoeTop + 9.6f * s)
            close()
        }
        fill.color = withAlpha(darken(shoeColor, 0.68f), alphaInt)
        canvas.drawPath(shoePath, fill)
        fill.color = withAlpha(0xFFF1F5F9.toInt(), alphaInt)
        canvas.drawRoundRect(RectF(-ankleW * 1.45f, shoeTop + 7.0f * s, ankleW * 2.78f, shoeTop + 10.0f * s), 2f * s, 2f * s, fill)
        canvas.drawPath(shoePath, outline)

        canvas.restore()
    }

    private fun drawHumanCape(
        canvas: Canvas,
        s: Float,
        color: Int,
        alphaInt: Int,
        flutterDeg: Float = 0f,
        scratch: HumanScratch = scratchLocal.get()!!
    ) {
        val fill = scratch.fill
        fill.color = withAlpha(color, (alphaInt * 0.92f).toInt())
        val swayShift = (flutterDeg * 0.65f).coerceIn(-18f, 18f) * s
        val path = scratch.path
        path.reset()
        path.moveTo(-24f * s, -32f * s)
        path.lineTo(24f * s, -32f * s)
        path.quadTo(32f * s + swayShift * 0.5f, 14f * s, 38f * s + swayShift, 60f * s)
        path.quadTo(swayShift * 0.7f, 70f * s, -38f * s + swayShift, 60f * s)
        path.quadTo(-32f * s + swayShift * 0.5f, 14f * s, -24f * s, -32f * s)
        path.close()
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
        tintOverlayColor: Int?,
        propType: CharacterPropType = CharacterPropType.NONE,
        propAttachment: PropAttachmentPoint = PropAttachmentPoint.RIGHT_HAND,
        customPropBitmap: Bitmap? = null,
        propScale: Float = 1.0f
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
        if (propType != CharacterPropType.NONE) {
            val alphaInt = (alpha.coerceIn(0f, 1f) * 255).toInt()
            val s = rigScale
            canvas.save()
            when (propAttachment) {
                PropAttachmentPoint.LEFT_HAND -> {
                    canvas.translate(-24f * s, -30f * s)
                    canvas.rotate(pose.leftArmDeg)
                    canvas.translate(0f, 25f * s)
                    canvas.rotate(pose.leftForearmDeg)
                    canvas.translate(0f, 26f * s)
                }
                PropAttachmentPoint.RIGHT_HAND -> {
                    canvas.translate(24f * s, -30f * s)
                    canvas.rotate(pose.rightArmDeg)
                    canvas.translate(0f, 25f * s)
                    canvas.rotate(pose.rightForearmDeg)
                    canvas.translate(0f, 26f * s)
                }
                PropAttachmentPoint.HEAD -> {
                    canvas.translate(0f, -68f * s)
                    canvas.rotate(pose.headNodDeg)
                    canvas.translate(0f, -26f * s)
                }
            }
            PropAndModularChartRenderer.drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, pose.propSwayDeg, alphaInt,
                pose.propSwayOffsetX * s, pose.propSwayOffsetY * s
            )
            canvas.restore()
        }
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
