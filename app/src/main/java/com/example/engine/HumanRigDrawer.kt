package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.abs
import kotlin.math.max

/**
 * High-detail anatomical Real-Human 2D Illustrated Rig & PNG-to-Skeleton Cutout Articulated Rig.
 * Powered by [CharacterAngleSystem] for true 5-angle turnaround accuracy (`FRONT`, `THREE_QUARTER_FRONT`,
 * `SIDE`, `THREE_QUARTER_BACK`, `BACK`), articulated hands (`OPEN`, `FIST`, `POINT`, `PROP_GRIP`),
 * floor-pinned dynamic contact shadows, tailored sleeves/cuffs, and 2-tone studio cel-shading.
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
        handPose: HandPoseType = HandPoseType.OPEN,
        organicFace: OrganicFacialState? = null,
        tearsConfig: TearsFxConfig = TearsFxConfig(),
        globalFrame: Int = 0,
        fps: Int = 24,
        clipSeed: Int = 0,
        drawAccessory: (Canvas, CharacterAccessory, Float, Int, Int, Paint, Int) -> Unit
    ) {
        val scratch = scratchLocal.get()!!
        val fill = scratch.fill
        val clothOutline = scratch.clothOutline
        clothOutline.color = withAlpha(0xFF1F1916.toInt(), (alphaInt * 0.84f).toInt())
        clothOutline.strokeWidth = 1.65f * s

        val profile = CharacterAngleSystem.profileFor(orientation, rig.buildType)
        val isBack = !profile.showsFace
        val isSide = orientation == CharacterOrientation.SIDE
        val leftShoulderX = profile.leftShoulderX * s
        val rightShoulderX = profile.rightShoulderX * s
        val leftHipX = profile.leftHipX * s
        val rightHipX = profile.rightHipX * s
        val waistHalf = profile.waistHalfWidth * s
        val faceShiftX = profile.faceShiftX * s

        // 1. Floor-Pinned Dynamic Ground Contact Shadow (stays anchored on ground even during jumps/sits!)
        val jumpLift = (-pose.bodyBobY).coerceAtLeast(0f)
        val shadowScale = (1f - (jumpLift / 115f)).coerceIn(0.45f, 1.12f)
        val shadowAlpha = ((0x50 * shadowScale) * (alphaInt / 255f)).toInt().coerceIn(0, 255)
        val floorY = (88f - pose.bodyBobY) * s
        fill.color = Color.argb(shadowAlpha, 0, 0, 0)
        canvas.drawOval(
            RectF(-34f * s * shadowScale, floorY - 6f * s * shadowScale, 34f * s * shadowScale, floorY + 6f * s * shadowScale),
            fill
        )

        // 2. Cape behind body if equipped (with physical sway flutter)
        if (rig.accessory == CharacterAccessory.CAPE && !isBack) {
            drawHumanCape(canvas, s, accent, alphaInt, pose.accessoryFlutterDeg, scratch)
        }

        // Back hair drape for long hairstyles (visible behind shoulders in front/3Q/side)
        if (!isBack && rig.hairStyle == HumanHairStyle.WAVY_LONG) {
            fill.color = withAlpha(darken(hair, 0.84f), alphaInt)
            val backHair = scratch.path
            backHair.reset()
            val hairShift = -faceShiftX * 0.35f
            backHair.moveTo(-21f * s + hairShift, -72f * s)
            backHair.quadTo(-29f * s + hairShift, -32f * s, -22f * s + hairShift, -8f * s)
            backHair.quadTo(hairShift, -2f * s, 22f * s + hairShift, -8f * s)
            backHair.quadTo(29f * s + hairShift, -32f * s, 21f * s + hairShift, -72f * s)
            backHair.close()
            canvas.drawPath(backHair, fill)
        }

        val hasLongSleeves = when (rig.costumeStyle) {
            HumanCostumeStyle.FORMAL_SUIT,
            HumanCostumeStyle.HOODIE_STREET,
            HumanCostumeStyle.CASUAL_JACKET,
            HumanCostumeStyle.KURTA_TRADITIONAL,
            HumanCostumeStyle.DESI_KURTA_DHOTI,
            HumanCostumeStyle.FANTASY_ARMOR -> true
            else -> false
        }
        val showAccentCuff = hasLongSleeves && rig.costumeStyle != HumanCostumeStyle.DESI_KURTA_DHOTI
        val isSleeveless = rig.costumeStyle == HumanCostumeStyle.SPORT_ATHLETIC

        val pantColor = when (rig.costumeStyle) {
            HumanCostumeStyle.VILLAGE_DHOTI, HumanCostumeStyle.DESI_KURTA_DHOTI -> secondary
            HumanCostumeStyle.SAREE_DRAPE -> darken(primary, 0.82f)
            else -> secondary
        }

        fun drawLeftArm() {
            val shade = profile.leftArmDepthShade
            drawAnatomicalArm(
                canvas = canvas,
                ox = leftShoulderX,
                oy = -30f * s,
                upperDeg = pose.leftArmDeg,
                forearmDeg = pose.leftForearmDeg,
                s = s,
                upperColor = darken(if (isSleeveless) skin else primary, shade),
                forearmColor = darken(if (hasLongSleeves) primary else skin, shade),
                cuffColor = if (showAccentCuff) darken(accent, shade) else null,
                skin = darken(skin, shade),
                outline = clothOutline,
                alphaInt = alphaInt,
                mirrorThumb = true,
                handPose = handPose,
                propType = if (propAttachment == PropAttachmentPoint.LEFT_HAND) propType else CharacterPropType.NONE,
                customPropBitmap = customPropBitmap,
                propScale = propScale,
                propSwayDeg = pose.propSwayDeg,
                propSwayOffsetX = pose.propSwayOffsetX * s,
                propSwayOffsetY = pose.propSwayOffsetY * s
            )
        }

        fun drawRightArm() {
            val shade = profile.rightArmDepthShade
            drawAnatomicalArm(
                canvas = canvas,
                ox = rightShoulderX,
                oy = -30f * s,
                upperDeg = pose.rightArmDeg,
                forearmDeg = pose.rightForearmDeg,
                s = s,
                upperColor = darken(if (isSleeveless) skin else primary, shade),
                forearmColor = darken(if (hasLongSleeves) primary else skin, shade),
                cuffColor = if (showAccentCuff) darken(accent, shade) else null,
                skin = darken(skin, shade),
                outline = clothOutline,
                alphaInt = alphaInt,
                mirrorThumb = false,
                handPose = handPose,
                propType = if (propAttachment == PropAttachmentPoint.RIGHT_HAND) propType else CharacterPropType.NONE,
                customPropBitmap = customPropBitmap,
                propScale = propScale,
                propSwayDeg = pose.propSwayDeg,
                propSwayOffsetX = pose.propSwayOffsetX * s,
                propSwayOffsetY = pose.propSwayOffsetY * s
            )
        }

        // 3. Far Arm strictly behind torso based on 5-Angle profile
        if (profile.leftArmBehindTorso) drawLeftArm()
        if (profile.rightArmBehindTorso) drawRightArm()

        // 4. Far Leg -> Near Leg with 5-Angle depth shading & directional shoes / chappals
        val drawLeftLegFirst = profile.leftLegDepthShade <= profile.rightLegDepthShade
        if (drawLeftLegFirst) {
            drawAnatomicalLeg(
                canvas, leftHipX, 20f * s, pose.leftLegDeg, pose.leftKneeDeg,
                s, darken(pantColor, profile.leftLegDepthShade), darken(accent, profile.leftLegDepthShade),
                darken(skin, profile.leftLegDepthShade), rig.costumeStyle,
                profile.leftShoeView, clothOutline, alphaInt
            )
            drawAnatomicalLeg(
                canvas, rightHipX, 20f * s, pose.rightLegDeg, pose.rightKneeDeg,
                s, darken(pantColor, profile.rightLegDepthShade), darken(accent, profile.rightLegDepthShade),
                darken(skin, profile.rightLegDepthShade), rig.costumeStyle,
                profile.rightShoeView, clothOutline, alphaInt
            )
        } else {
            drawAnatomicalLeg(
                canvas, rightHipX, 20f * s, pose.rightLegDeg, pose.rightKneeDeg,
                s, darken(pantColor, profile.rightLegDepthShade), darken(accent, profile.rightLegDepthShade),
                darken(skin, profile.rightLegDepthShade), rig.costumeStyle,
                profile.rightShoeView, clothOutline, alphaInt
            )
            drawAnatomicalLeg(
                canvas, leftHipX, 20f * s, pose.leftLegDeg, pose.leftKneeDeg,
                s, darken(pantColor, profile.leftLegDepthShade), darken(accent, profile.leftLegDepthShade),
                darken(skin, profile.leftLegDepthShade), rig.costumeStyle,
                profile.leftShoeView, clothOutline, alphaInt
            )
        }

        // 5. Anatomical Torso with Asymmetric 5-Angle Contour, Trapezius Collar & Costume Details
        drawHumanTorsoAndCostume(
            canvas = canvas,
            style = rig.costumeStyle,
            profile = profile,
            leftShoulderX = leftShoulderX,
            rightShoulderX = rightShoulderX,
            waistHalf = waistHalf,
            s = s,
            primary = primary,
            secondary = secondary,
            accent = accent,
            skin = skin,
            outline = clothOutline,
            alphaInt = alphaInt
        )

        // Scarf accessory across collarbone (tail flutters naturally with body motion)
        if (rig.accessory == CharacterAccessory.SCARF) {
            fill.color = withAlpha(accent, alphaInt)
            scratch.rect.set(leftShoulderX * 0.65f, -38f * s, rightShoulderX * 0.65f, -26f * s)
            canvas.drawRoundRect(scratch.rect, 6f * s, 6f * s, fill)
            canvas.drawRoundRect(scratch.rect, 6f * s, 6f * s, clothOutline)
            canvas.save()
            canvas.translate(if (isBack) -6f * s else 9f * s, -28f * s)
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

        // 6. Sculpted Anatomical Neck with Smooth Trapezius Flare & Chin Shadow
        val neckW = (if (isSide) 6.8f else 7.5f) * s
        val neckPath = scratch.path
        neckPath.reset()
        neckPath.moveTo(-neckW * 0.86f, -52f * s)
        neckPath.lineTo(neckW * 0.86f, -52f * s)
        neckPath.quadTo(neckW * 0.95f, -38f * s, neckW * 1.48f, -31f * s)
        neckPath.quadTo(0f, -27.5f * s, -neckW * 1.48f, -31f * s)
        neckPath.quadTo(-neckW * 0.95f, -38f * s, -neckW * 0.86f, -52f * s)
        neckPath.close()
        fill.color = withAlpha(skin, alphaInt)
        canvas.drawPath(neckPath, fill)
        // Chin ambient shadow on upper neck
        fill.color = withAlpha(darken(skin, 0.78f), alphaInt)
        canvas.drawOval(RectF(-neckW * 0.88f, -51f * s, neckW * 0.88f, -40f * s), fill)
        canvas.drawPath(neckPath, clothOutline)

        // 7. Sculpted 5-Angle Real-Human Head Group
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

        // 5-Angle Ears (behind cranium for Front/3Q/Back, placed over jaw rear for Side)
        if (!isSide) {
            drawHumanEars(canvas, profile, s, skin, clothOutline, alphaInt)
        }

        // Sculpted 5-Angle Cranium + Jawline Path (true side profile silhouette on SIDE!)
        val headW = profile.headHalfWidth * s
        val headPath = buildHeadContourPath(profile.headProfileMode, headW, faceShiftX, s)
        fill.color = withAlpha(skin, alphaInt)
        canvas.drawPath(headPath, fill)

        // Clipped 2-Tone Cel-Shadow Crescent + Soft Rim Highlight
        canvas.save()
        canvas.clipPath(headPath)
        val shadowCrescent = Path().apply {
            if (isBack) {
                moveTo(headW * 0.35f, -26f * s)
                cubicTo(headW * 0.88f, -4f * s, headW * 0.78f, 16f * s, -headW * 0.15f, 24f * s)
                lineTo(headW + 5f * s, 26f * s)
                lineTo(headW + 5f * s, -28f * s)
                close()
            } else {
                moveTo(headW * 0.44f, -24f * s)
                cubicTo(headW * 0.88f, -6f * s, headW * 0.72f, 14f * s, -headW * 0.22f, 23f * s)
                lineTo(headW + 6f * s, 26f * s)
                lineTo(headW + 6f * s, -28f * s)
                close()
            }
        }
        fill.color = withAlpha(darken(skin, 0.85f), alphaInt)
        canvas.drawPath(shadowCrescent, fill)
        if (!isBack) {
            fill.color = withAlpha(lighten(skin, 1.15f), (alphaInt * 0.50f).toInt())
            canvas.drawOval(RectF(-headW * 0.74f, -15f * s, -headW * 0.22f, 1f * s), fill)
        }
        canvas.restore()
        canvas.drawPath(headPath, clothOutline)

        // Facial Features (Front, 3/4 Front, Side — completely hidden on Back & 3/4 Back!)
        if (profile.showsFace) {
            drawHumanFaceFeatures(
                canvas = canvas,
                rig = rig,
                profile = profile,
                pose = pose,
                s = s,
                alphaInt = alphaInt,
                skin = skin,
                hair = hair,
                eyeColor = eyeColor,
                organicFace = organicFace,
                tearsConfig = tearsConfig,
                globalFrame = globalFrame,
                fps = fps,
                clipSeed = clipSeed
            )
        }

        // Layered 5-Angle Hairstyles (wraps nape cleanly on BACK & THREE_QUARTER_BACK)
        drawHumanHair(canvas, rig.hairStyle, profile, headW, s, hair, clothOutline, alphaInt)

        // Side-profile ear drawn over jaw/sideburn boundary
        if (isSide) {
            drawHumanEars(canvas, profile, s, skin, clothOutline, alphaInt)
        }

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

        // 8. Near Arm drawn strictly IN FRONT of torso based on 5-Angle profile
        if (!profile.leftArmBehindTorso) drawLeftArm()
        if (!profile.rightArmBehindTorso) drawRightArm()
    }

    private fun buildHeadContourPath(
        mode: HeadAngleProfileMode,
        headW: Float,
        faceShiftX: Float,
        s: Float
    ): Path = Path().apply {
        when (mode) {
            HeadAngleProfileMode.SIDE_PROFILE -> {
                // True anatomical side profile: rounded cranium, brow ridge, nose contour, lips & chin!
                moveTo(-headW * 0.96f, -4f * s)
                cubicTo(-headW * 0.96f, -27f * s, headW * 0.72f, -27f * s, headW * 0.82f, -8f * s)
                // Brow -> Nose tip -> Upper/lower lips -> Chin -> Jawline
                lineTo(headW * 0.86f, -2f * s)
                lineTo(headW * 1.14f, 4.5f * s) // sculpted nose tip
                lineTo(headW * 0.85f, 6.2f * s) // under nose
                quadTo(headW * 0.96f, 10.5f * s, headW * 0.82f, 13.5f * s) // lip contour
                quadTo(headW * 0.88f, 18.8f * s, headW * 0.56f, 22.5f * s) // chin
                cubicTo(headW * 0.08f, 23.5f * s, -headW * 0.62f, 19f * s, -headW * 0.96f, -4f * s)
                close()
            }
            HeadAngleProfileMode.THREE_QUARTER_FRONT -> {
                moveTo(-headW, -4f * s)
                cubicTo(-headW, -27f * s, headW, -27f * s, headW * 0.96f, -4f * s)
                // Slight cheek curve on the turned side
                cubicTo(headW * 1.04f, 6f * s, headW * 0.70f, 19.5f * s, faceShiftX * 0.36f, 23.5f * s)
                cubicTo(-headW * 0.62f, 20.5f * s, -headW, 9f * s, -headW, -4f * s)
                close()
            }
            HeadAngleProfileMode.THREE_QUARTER_BACK -> {
                moveTo(-headW * 0.96f, -4f * s)
                cubicTo(-headW, -27f * s, headW, -27f * s, headW, -4f * s)
                cubicTo(headW, 10f * s, headW * 0.60f, 20.5f * s, faceShiftX * 0.30f, 22.8f * s)
                cubicTo(-headW * 0.68f, 19.5f * s, -headW * 0.98f, 8f * s, -headW * 0.96f, -4f * s)
                close()
            }
            else -> {
                // FRONT & BACK symmetric sculpted cranium + tapered jaw
                moveTo(-headW, -4f * s)
                cubicTo(-headW, -27f * s, headW, -27f * s, headW, -4f * s)
                cubicTo(headW, 9f * s, headW * 0.60f, 20.5f * s, 0f, 23.5f * s)
                cubicTo(-headW * 0.60f, 20.5f * s, -headW, 9f * s, -headW, -4f * s)
                close()
            }
        }
    }

    private fun drawHumanEars(
        canvas: Canvas,
        profile: CharacterAngleProfile,
        s: Float,
        skin: Int,
        outline: Paint,
        alphaInt: Int
    ) {
        val fill = scratchLocal.get()!!.fill
        val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.25f * s
            strokeCap = Paint.Cap.ROUND
            color = withAlpha(darken(skin, 0.64f), alphaInt)
        }
        val earSkin = withAlpha(darken(skin, 0.94f), alphaInt)
        val earW = 4.1f * s
        val earH = 6.8f * s
        if (profile.showLeftEar) {
            val ex = profile.leftEarX * s
            val rect = RectF(ex - earW, -5.5f * s, ex + earW, -5.5f * s + earH * 2f)
            fill.color = earSkin
            canvas.drawOval(rect, fill)
            canvas.drawOval(rect, outline)
            canvas.drawArc(RectF(ex - earW * 0.52f, -2.8f * s, ex + earW * 0.56f, 4.6f * s), 135f, 185f, false, innerStroke)
        }
        if (profile.showRightEar) {
            val ex = profile.rightEarX * s
            val rect = RectF(ex - earW, -5.5f * s, ex + earW, -5.5f * s + earH * 2f)
            fill.color = earSkin
            canvas.drawOval(rect, fill)
            canvas.drawOval(rect, outline)
            canvas.drawArc(RectF(ex - earW * 0.56f, -2.8f * s, ex + earW * 0.52f, 4.6f * s), -140f, 185f, false, innerStroke)
        }
    }

    private fun drawHumanTorsoAndCostume(
        canvas: Canvas,
        style: HumanCostumeStyle,
        profile: CharacterAngleProfile,
        leftShoulderX: Float,
        rightShoulderX: Float,
        waistHalf: Float,
        s: Float,
        primary: Int,
        secondary: Int,
        accent: Int,
        skin: Int,
        outline: Paint,
        alphaInt: Int
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
        val isBack = !profile.showsFace
        val isSide = profile.orientation == CharacterOrientation.SIDE
        val chestShift = profile.chestForwardShift * s
        val bottomY = when (style) {
            HumanCostumeStyle.DESI_KURTA_DHOTI -> 44f * s
            HumanCostumeStyle.KURTA_TRADITIONAL, HumanCostumeStyle.VILLAGE_DHOTI -> 40f * s
            HumanCostumeStyle.FANTASY_ARMOR -> 32f * s
            else -> 24f * s
        }

        val leftTopX = if (isSide) -waistHalf * 0.96f else leftShoulderX
        val rightTopX = if (isSide) waistHalf * 1.12f else rightShoulderX
        val leftWaistX = -waistHalf + chestShift * 0.25f
        val rightWaistX = waistHalf + chestShift * 0.35f

        val torsoPath = Path().apply {
            moveTo(leftTopX, -32f * s)
            quadTo(chestShift * 0.4f, -38.5f * s, rightTopX, -32f * s)
            cubicTo(
                rightTopX + chestShift * 0.35f, -10f * s,
                rightWaistX * 1.04f, 4f * s,
                rightWaistX, 12f * s
            )
            lineTo(rightWaistX * 1.08f, bottomY)
            quadTo(chestShift * 0.25f, bottomY + 5.5f * s, leftWaistX * 1.08f, bottomY)
            lineTo(leftWaistX, 12f * s)
            cubicTo(
                leftWaistX * 1.04f, 4f * s,
                leftTopX - chestShift * 0.15f, -10f * s,
                leftTopX, -32f * s
            )
            close()
        }

        fill.color = withAlpha(primary, alphaInt)
        canvas.drawPath(torsoPath, fill)

        // Clipped 2-tone cel-shading shadow side + soft rim highlight
        canvas.save()
        canvas.clipPath(torsoPath)
        val torsoShadow = Path().apply {
            moveTo(rightTopX * 0.36f, -39f * s)
            quadTo(rightWaistX * 0.48f, 4f * s, rightWaistX * 0.28f, bottomY + 8f * s)
            lineTo(rightTopX + 16f * s, bottomY + 8f * s)
            lineTo(rightTopX + 16f * s, -39f * s)
            close()
        }
        fill.color = withAlpha(darken(primary, 0.78f), alphaInt)
        canvas.drawPath(torsoShadow, fill)
        val torsoHighlight = Path().apply {
            moveTo(leftTopX - 4f * s, -35f * s)
            lineTo(leftTopX * 0.46f, -35f * s)
            quadTo(leftWaistX * 0.60f, -4f * s, leftWaistX * 0.74f, bottomY)
            lineTo(leftTopX - 6f * s, bottomY)
            close()
        }
        fill.color = withAlpha(lighten(primary, 1.18f), (alphaInt * 0.42f).toInt())
        canvas.drawPath(torsoHighlight, fill)
        canvas.restore()
        canvas.drawPath(torsoPath, outline)

        // Back-view anatomical seams (rear collar, shoulder-blade contour, waist sash)
        if (isBack) {
            val backSeam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = 1.6f * s
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(darken(primary, 0.66f), (alphaInt * 0.75f).toInt())
            }
            val centerBackX = profile.faceShiftX * 0.32f * s
            // Rear collar curve
            canvas.drawArc(
                RectF(centerBackX - 10f * s, -37f * s, centerBackX + 10f * s, -29f * s),
                12f, 156f, false, backSeam
            )
            // Subtle vertical spine/center-back tailoring seam
            canvas.drawLine(centerBackX, -29f * s, centerBackX, bottomY - 4f * s, backSeam)
            // Rear waist sash / belt for traditional & armor costumes
            if (style == HumanCostumeStyle.VILLAGE_DHOTI || style == HumanCostumeStyle.FANTASY_ARMOR || style == HumanCostumeStyle.SAREE_DRAPE) {
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRoundRect(RectF(leftWaistX * 1.04f, 11f * s, rightWaistX * 1.04f, 19f * s), 3.5f * s, 3.5f * s, fill)
                canvas.drawRoundRect(RectF(leftWaistX * 1.04f, 11f * s, rightWaistX * 1.04f, 19f * s), 3.5f * s, 3.5f * s, outline)
            }
            return
        }

        // Front / 3/4 Front / Side costume details shifted smoothly with angle perspective
        val detailShiftX = profile.faceShiftX * 0.42f * s
        val detailScaleX = if (isSide) 0.55f else 1f
        canvas.save()
        canvas.translate(detailShiftX, 0f)
        canvas.scale(detailScaleX, 1f)

        when (style) {
            HumanCostumeStyle.CASUAL_JACKET -> {
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
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRoundRect(RectF(-9f * s, -37f * s, 9f * s, -30f * s), 3f * s, 3f * s, fill)
                canvas.drawRoundRect(RectF(-3.2f * s, -31f * s, 3.2f * s, 2f * s), 2f * s, 2f * s, fill)
                fill.color = withAlpha(0xFFFFFBEB.toInt(), alphaInt)
                canvas.drawCircle(0f, -22f * s, 1.6f * s, fill)
                canvas.drawCircle(0f, -13f * s, 1.6f * s, fill)
                canvas.drawCircle(0f, -4f * s, 1.6f * s, fill)
            }
            HumanCostumeStyle.SAREE_DRAPE -> {
                val drape = Path().apply {
                    moveTo(-waistHalf * 0.95f, -33f * s)
                    lineTo(-waistHalf * 0.25f, -34f * s)
                    lineTo(waistHalf * 1.02f, 22f * s)
                    lineTo(waistHalf * 0.35f, 25f * s)
                    close()
                }
                fill.color = withAlpha(accent, (alphaInt * 0.92f).toInt())
                canvas.drawPath(drape, fill)
                canvas.drawPath(drape, outline)
                fill.color = withAlpha(secondary, alphaInt)
                canvas.drawRoundRect(RectF(-waistHalf, 12f * s, waistHalf, 21f * s), 3f * s, 3f * s, fill)
            }
            HumanCostumeStyle.FORMAL_SUIT -> {
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
                fill.color = withAlpha(skin, alphaInt)
                val vNeck = Path().apply {
                    moveTo(-9f * s, -35f * s)
                    lineTo(9f * s, -35f * s)
                    lineTo(0f, -23f * s)
                    close()
                }
                canvas.drawPath(vNeck, fill)
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRect(-waistHalf * 0.94f, -12f * s, waistHalf * 0.94f, -4f * s, fill)
            }
            HumanCostumeStyle.HOODIE_STREET -> {
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
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawRoundRect(RectF(-waistHalf * 1.05f, 10f * s, waistHalf * 1.05f, 19f * s), 4f * s, 4f * s, fill)
                val fold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(darken(secondary, 0.72f), alphaInt)
                    strokeWidth = 2f * s
                }
                canvas.drawLine(0f, 19f * s, 0f, bottomY, fold)
            }
            HumanCostumeStyle.DESI_KURTA_DHOTI -> {
                // Authentic Desi Collared Kurta: V-neck skin opening, folded pointed shirt collar lapels,
                // central 2-button placket, and crisp shadowed lower side slits (chaak)!
                val detailStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 1.25f * s
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = withAlpha(darken(primary, 0.42f), alphaInt)
                }
                // Warm skin V-neck throat opening
                val vNeck = Path().apply {
                    moveTo(-8.2f * s, -36.5f * s)
                    lineTo(8.2f * s, -36.5f * s)
                    lineTo(0f, -19.5f * s)
                    close()
                }
                fill.color = withAlpha(skin, alphaInt)
                canvas.drawPath(vNeck, fill)
                canvas.drawPath(vNeck, detailStroke)

                // Left & Right Folded Shirt Collar Lapels
                val leftCollar = Path().apply {
                    moveTo(-8.2f * s, -36.5f * s)
                    lineTo(-14.2f * s, -24.5f * s)
                    lineTo(-5.8f * s, -28.0f * s)
                    lineTo(0f, -19.5f * s)
                    close()
                }
                val rightCollar = Path().apply {
                    moveTo(8.2f * s, -36.5f * s)
                    lineTo(14.2f * s, -24.5f * s)
                    lineTo(5.8f * s, -28.0f * s)
                    lineTo(0f, -19.5f * s)
                    close()
                }
                fill.color = withAlpha(darken(primary, 0.90f), alphaInt)
                canvas.drawPath(leftCollar, fill)
                canvas.drawPath(leftCollar, detailStroke)
                canvas.drawPath(rightCollar, fill)
                canvas.drawPath(rightCollar, detailStroke)

                // Central Buttoned Placket Strip + 2 Crisp Outlined Buttons
                val placketRect = RectF(-2.5f * s, -20.5f * s, 2.5f * s, -3.5f * s)
                fill.color = withAlpha(darken(primary, 0.93f), alphaInt)
                canvas.drawRect(placketRect, fill)
                canvas.drawRect(placketRect, detailStroke)
                listOf(-15.5f * s, -9.2f * s).forEach { by ->
                    fill.color = withAlpha(darken(primary, 0.78f), alphaInt)
                    canvas.drawCircle(0f, by, 1.35f * s, fill)
                    canvas.drawCircle(0f, by, 1.35f * s, detailStroke)
                }

                // Authentic Lower Kurta Side Slits (Chaak) on Left & Right Hem Edges
                val slitDark = withAlpha(darken(primary, 0.44f), alphaInt)
                val leftSlit = Path().apply {
                    moveTo(-waistHalf * 1.02f, 25f * s)
                    lineTo(-waistHalf * 1.09f, bottomY + 1.2f * s)
                    lineTo(-waistHalf * 0.88f, bottomY + 1.2f * s)
                    close()
                }
                val rightSlit = Path().apply {
                    moveTo(waistHalf * 1.02f, 25f * s)
                    lineTo(waistHalf * 1.09f, bottomY + 1.2f * s)
                    lineTo(waistHalf * 0.88f, bottomY + 1.2f * s)
                    close()
                }
                fill.color = slitDark
                canvas.drawPath(leftSlit, fill)
                canvas.drawPath(leftSlit, outline)
                canvas.drawPath(rightSlit, fill)
                canvas.drawPath(rightSlit, outline)
            }
            HumanCostumeStyle.FANTASY_ARMOR -> {
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
        canvas.restore()
    }

    private fun drawHumanFaceFeatures(
        canvas: Canvas,
        rig: CharacterRigDefinition,
        profile: CharacterAngleProfile,
        pose: ProceduralRigRenderer.PoseAngles,
        s: Float,
        alphaInt: Int,
        skin: Int,
        hair: Int,
        eyeColor: Int,
        organicFace: OrganicFacialState? = null,
        tearsConfig: TearsFxConfig = TearsFxConfig(),
        globalFrame: Int = 0,
        fps: Int = 24,
        clipSeed: Int = 0
    ) {
        val isSide = profile.orientation == CharacterOrientation.SIDE
        val faceShiftX = profile.faceShiftX * s
        val isFeminineStyle = rig.costumeStyle == HumanCostumeStyle.SAREE_DRAPE ||
            rig.accessory == CharacterAccessory.DESI_BINDI_JEWEL
        val hasDesiPagdiTilak = rig.accessory == CharacterAccessory.DESI_PAGDI_TILAK
        val hasBindi = rig.accessory == CharacterAccessory.DESI_BINDI_JEWEL
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Perspective-foreshortened eye placement & scale per angle
        data class EyeSpec(val x: Float, val scaleX: Float, val isRight: Boolean)
        val eyeSpecs = if (isSide) {
            listOf(EyeSpec(9.2f * s, profile.rightEyeScaleX, isRight = true))
        } else {
            listOf(
                EyeSpec(-7.7f * s + faceShiftX * 0.54f, profile.leftEyeScaleX, isRight = false),
                EyeSpec(7.7f * s + faceShiftX * 0.54f, profile.rightEyeScaleX, isRight = true)
            )
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

        // 0. Forehead Red Vertical Tilak (Raju/Ramesh/Dada Ji/Pandit) or Round Red Bindi (Meera Devi)
        val foreheadX = if (isSide) faceShiftX * 1.15f else faceShiftX * 0.54f
        if (hasDesiPagdiTilak) {
            fill.color = withAlpha(0xFFDC2626.toInt(), alphaInt)
            val tilakRx = (if (isSide) 1.0f else 1.55f) * s
            canvas.drawOval(
                RectF(foreheadX - tilakRx, -15.8f * s, foreheadX + tilakRx, -8.8f * s),
                fill
            )
            fill.color = withAlpha(0xFFFB923C.toInt(), (alphaInt * 0.55f).toInt())
            canvas.drawOval(
                RectF(foreheadX - tilakRx * 0.42f, -14.8f * s, foreheadX + tilakRx * 0.42f, -10.5f * s),
                fill
            )
        } else if (hasBindi) {
            fill.color = withAlpha(0xFFDC2626.toInt(), alphaInt)
            canvas.drawCircle(foreheadX, -11.4f * s, 2.0f * s, fill)
        }

        // 1. Bold Sculpted Organic Eyebrows (crisp cartoon clarity matching Desi studio style)
        stroke.color = withAlpha(darken(hair, 0.90f), alphaInt)
        stroke.strokeWidth = (if (isFeminineStyle) 1.95f else 2.85f) * s
        eyeSpecs.forEach { spec ->
            val isLeft = !spec.isRight && !isSide
            val lift = (if (isLeft) face.leftBrowLift else face.rightBrowLift) * 3.1f * s
            val tiltDeg = if (isLeft) face.leftBrowTiltDeg else face.rightBrowTiltDeg
            val tiltRad = tiltDeg * (Math.PI.toFloat() / 180f)
            val halfW = 5.1f * s * spec.scaleX
            val tiltDy = kotlin.math.sin(tiltRad) * halfW
            val baseY = -9.2f * s - lift
            val outerY = baseY - tiltDy + 0.5f * s
            val innerY = baseY + tiltDy
            val archControlY = (outerY + innerY) * 0.5f - (1.85f + (if (isLeft) face.leftBrowLift else face.rightBrowLift).coerceAtLeast(0f) * 1.4f) * s

            val browPath = scratchLocal.get()!!.path
            browPath.reset()
            if (isLeft) {
                browPath.moveTo(spec.x - halfW, outerY)
                browPath.quadTo(spec.x, archControlY, spec.x + halfW * 0.92f, innerY)
            } else {
                browPath.moveTo(spec.x - halfW * 0.92f, innerY)
                browPath.quadTo(spec.x, archControlY, spec.x + halfW, outerY)
            }
            canvas.drawPath(browPath, stroke)
        }

        // 2. Pixel-Perfect Expressive Almond Cartoon Eyes with Dual Catchlights & Clean Lashes
        eyeSpecs.forEach { spec ->
            val ex = spec.x
            val eyeHalfW = 4.85f * s * spec.scaleX
            val eyeClosed = if (spec.isRight) face.isBlinkingRight else face.isBlinkingLeft
            val lidClose = if (spec.isRight) face.rightEyelidClose else face.leftEyelidClose
            val eyeOpen = (if (spec.isRight) face.rightEyeOpenness else face.leftEyeOpenness).coerceIn(0.22f, 1.38f)

            if (eyeClosed && face.happyEyeArc >= 0.45f) {
                stroke.color = withAlpha(0xFF1A1512.toInt(), alphaInt)
                stroke.strokeWidth = 2.3f * s
                canvas.drawArc(RectF(ex - eyeHalfW, -5.0f * s, ex + eyeHalfW, 1.4f * s), 195f, 150f, false, stroke)
            } else if (eyeClosed) {
                stroke.color = withAlpha(0xFF1A1512.toInt(), alphaInt)
                stroke.strokeWidth = 2.0f * s
                canvas.drawArc(RectF(ex - eyeHalfW, -4.1f * s, ex + eyeHalfW, 1.0f * s), 12f, 156f, false, stroke)
            } else {
                val topY = -5.4f * s * eyeOpen.coerceIn(0.65f, 1.32f)
                val botY = (1.9f - face.cheekSquint * 0.9f) * s
                val scleraRect = RectF(ex - eyeHalfW, topY, ex + eyeHalfW, botY)

                fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
                canvas.drawOval(scleraRect, fill)

                canvas.save()
                val eyeClip = Path().apply { addOval(scleraRect, Path.Direction.CW) }
                canvas.clipPath(eyeClip)

                val gazeOffsetX = faceShiftX * 0.08f + face.eyeLookX * 1.9f * s * spec.scaleX
                val gazeOffsetY = face.eyeLookY * 1.35f * s
                val irisR = (3.05f * face.pupilScale.coerceIn(0.76f, 1.18f)) * s
                val cy = (topY + botY) * 0.5f + gazeOffsetY

                // Rich espresso/colored iris ring
                fill.color = withAlpha(eyeColor, alphaInt)
                canvas.drawOval(RectF(ex + gazeOffsetX - irisR * spec.scaleX, cy - irisR, ex + gazeOffsetX + irisR * spec.scaleX, cy + irisR), fill)
                // Deep velvety pupil
                fill.color = withAlpha(0xFF0A0A0D.toInt(), alphaInt)
                canvas.drawOval(RectF(ex + gazeOffsetX - irisR * 0.58f * spec.scaleX, cy - irisR * 0.58f, ex + gazeOffsetX + irisR * 0.58f * spec.scaleX, cy + irisR * 0.58f), fill)
                // Crisp primary white catchlight + delicate secondary glint
                fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
                canvas.drawCircle(ex + gazeOffsetX + 1.0f * s * spec.scaleX, cy - 1.15f * s, 0.98f * s, fill)
                canvas.drawCircle(ex + gazeOffsetX - 0.85f * s * spec.scaleX, cy + 0.95f * s, 0.42f * s, fill)

                if (lidClose > 0.06f) {
                    val lidBottom = topY + (botY - topY) * (lidClose * 0.92f)
                    fill.color = withAlpha(darken(skin, 0.93f), alphaInt)
                    canvas.drawRect(ex - eyeHalfW * 1.15f, topY - 2f * s, ex + eyeHalfW * 1.15f, lidBottom, fill)
                    stroke.color = withAlpha(0xFF141210.toInt(), alphaInt)
                    stroke.strokeWidth = 1.6f * s
                    canvas.drawLine(ex - eyeHalfW, lidBottom, ex + eyeHalfW, lidBottom, stroke)
                }
                canvas.restore()

                // Crisp upper lash line + subtle lower rim
                stroke.color = withAlpha(0xFF14100E.toInt(), alphaInt)
                stroke.strokeWidth = (if (isFeminineStyle) 2.0f else 1.75f) * s
                canvas.drawArc(RectF(ex - eyeHalfW, topY - 0.25f * s, ex + eyeHalfW, botY + 0.15f * s), 190f, 160f, false, stroke)
                stroke.strokeWidth = 0.85f * s
                stroke.color = withAlpha(0xFF3E2B22.toInt(), (alphaInt * 0.65f).toInt())
                canvas.drawArc(RectF(ex - eyeHalfW, topY, ex + eyeHalfW, botY), 18f, 144f, false, stroke)

                if (isFeminineStyle) {
                    // Subtle winged outer eyelash flick
                    val outerSign = if (!spec.isRight && !isSide) -1f else 1f
                    stroke.color = withAlpha(0xFF14100E.toInt(), alphaInt)
                    stroke.strokeWidth = 1.4f * s
                    canvas.drawLine(
                        ex + outerSign * eyeHalfW * 0.92f,
                        topY + (botY - topY) * 0.34f,
                        ex + outerSign * (eyeHalfW + 1.9f * s),
                        topY + (botY - topY) * 0.12f,
                        stroke
                    )
                }

                if (face.showTear && !tearsConfig.isActive) {
                    fill.color = withAlpha(0xD938BDF8.toInt(), alphaInt)
                    canvas.drawOval(RectF(ex + 1.8f * s, botY - 0.5f * s, ex + 4.2f * s, botY + 3.8f * s), fill)
                }
            }
        }

        // 2b. Animated Tears FX anchored smoothly under eyes
        if (tearsConfig.isActive) {
            val tearAnchors = eyeSpecs.map { spec ->
                val sideSign = if (isSide) 1f else if (!spec.isRight) -1f else 1f
                ClipVisualFxEngine.TearEyeAnchor(
                    x = spec.x + sideSign * 0.8f * s,
                    y = 1.6f * s,
                    sideSign = sideSign
                )
            }
            ClipVisualFxEngine.drawTearsAtEyes(
                canvas = canvas,
                config = tearsConfig,
                eyeAnchors = tearAnchors,
                scale = s,
                globalFrame = globalFrame,
                fps = fps,
                alphaInt = alphaInt,
                seed = clipSeed
            )
        }

        // 3. Clean Subtle Sculpted Nose (soft curved nostril arc + warm shadow, matching Desi cartoon style)
        if (!isSide) {
            val noseX = faceShiftX * 0.62f
            fill.color = withAlpha(darken(skin, 0.84f), (alphaInt * 0.65f).toInt())
            canvas.drawOval(RectF(noseX - 2.3f * s, 4.2f * s, noseX + 2.3f * s, 6.7f * s), fill)
            stroke.color = withAlpha(darken(skin, 0.58f), alphaInt)
            stroke.strokeWidth = 1.35f * s
            canvas.drawArc(
                RectF(noseX - 2.4f * s, 2.2f * s, noseX + 2.4f * s, 6.4f * s),
                20f, 140f, false, stroke
            )
        }

        // 4. Dynamic Duchenne Cheek Warmth
        val cheekAlpha = ((0x1A + (face.cheekSquint * 0x2C).toInt()) * alphaInt) / 255
        fill.color = Color.argb(cheekAlpha.coerceIn(0, 255), 225, 29, 72)
        val cheekLift = face.cheekSquint * 1.4f * s
        if (!isSide) {
            canvas.drawOval(RectF(-14f * s + faceShiftX * 0.35f, 3f * s - cheekLift, -6f * s + faceShiftX * 0.35f, 8.2f * s - cheekLift), fill)
            canvas.drawOval(RectF(6f * s + faceShiftX * 0.35f, 3f * s - cheekLift, 14f * s + faceShiftX * 0.35f, 8.2f * s - cheekLift), fill)
        } else {
            canvas.drawOval(RectF(5f * s, 3f * s - cheekLift, 11.5f * s, 8.2f * s - cheekLift), fill)
        }

        // 5. Fluid Organic Sculpted Lips & Mouth (foreshortened smoothly for Side & 3/4!)
        val mouthX = if (isSide) faceShiftX * 0.92f else faceShiftX * 0.55f
        val mouthY = (if (hasDesiPagdiTilak) 13.0f else 12.2f) * s - face.cheekSquint * 0.7f * s
        val angleWidthScale = if (isSide) 0.58f else if (profile.orientation == CharacterOrientation.THREE_QUARTER_FRONT) 0.90f else 1f
        val halfW = (5.9f * face.mouthScaleX.coerceIn(0.68f, 1.35f) * angleWidthScale) * s
        val curveY = face.smileCurve * 4.2f * s
        val openAmount = face.mouthOpen.coerceIn(0f, 1f)

        if (face.showGrittedTeeth && openAmount > 0.10f) {
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
            fill.color = withAlpha(0xFFE11D48.toInt(), alphaInt)
            canvas.drawOval(
                RectF(mouthX - halfW * 0.62f, mouthY + openH * 0.25f, mouthX + halfW * 0.62f, mouthY + openH * 1.15f),
                fill
            )
            if (face.showTeeth) {
                fill.color = withAlpha(0xFFF8FAFC.toInt(), alphaInt)
                canvas.drawRoundRect(
                    RectF(mouthX - halfW * 0.78f, upperMidY - 0.5f * s, mouthX + halfW * 0.78f, upperMidY + 2.3f * s),
                    1.4f * s, 1.4f * s, fill
                )
            }
            canvas.restore()

            stroke.color = withAlpha(0xFF7A3832.toInt(), alphaInt)
            stroke.strokeWidth = 1.5f * s
            canvas.drawPath(mouthPath, stroke)
        } else {
            // Soft sculpted lower lip tint + clean mouth curve
            fill.color = withAlpha(if (isFeminineStyle) 0xFFC04258.toInt() else 0xFFA25C4A.toInt(), (alphaInt * 0.78f).toInt())
            canvas.drawOval(
                RectF(mouthX - halfW * 0.76f, mouthY - 0.8f * s, mouthX + halfW * 0.76f, mouthY + 2.5f * s + curveY.coerceAtLeast(0f) * 0.25f),
                fill
            )
            stroke.color = withAlpha(0xFF6B2D26.toInt(), alphaInt)
            stroke.strokeWidth = 1.8f * s
            val cornerY = mouthY - curveY * 0.52f
            val midY = mouthY + curveY * 0.62f
            val lipPath = Path().apply {
                moveTo(mouthX - halfW, cornerY)
                quadTo(mouthX, midY, mouthX + halfW, cornerY)
            }
            canvas.drawPath(lipPath, stroke)
        }

        // 6. Iconic Curled Desi Handlebar Mustache (for DESI_PAGDI_TILAK characters; lifts slightly when talking!)
        if (hasDesiPagdiTilak) {
            val stacheY = mouthY - 3.6f * s - openAmount * 1.2f * s
            val stacheW = 11.5f * s * angleWidthScale
            val stachePath = Path().apply {
                moveTo(mouthX, stacheY - 2.1f * s)
                // Right handlebar wing curling upward at tip
                cubicTo(
                    mouthX + stacheW * 0.36f, stacheY - 3.0f * s,
                    mouthX + stacheW * 0.82f, stacheY + 0.6f * s,
                    mouthX + stacheW, stacheY - 1.8f * s
                )
                cubicTo(
                    mouthX + stacheW * 1.08f, stacheY + 1.8f * s,
                    mouthX + stacheW * 0.45f, stacheY + 3.2f * s,
                    mouthX, stacheY + 1.0f * s
                )
                // Left handlebar wing curling upward at tip
                cubicTo(
                    mouthX - stacheW * 0.45f, stacheY + 3.2f * s,
                    mouthX - stacheW * 1.08f, stacheY + 1.8f * s,
                    mouthX - stacheW, stacheY - 1.8f * s
                )
                cubicTo(
                    mouthX - stacheW * 0.82f, stacheY + 0.6f * s,
                    mouthX - stacheW * 0.36f, stacheY - 3.0f * s,
                    mouthX, stacheY - 2.1f * s
                )
                close()
            }
            fill.color = withAlpha(hair, alphaInt)
            canvas.drawPath(stachePath, fill)
            stroke.color = withAlpha(darken(hair, 0.62f), alphaInt)
            stroke.strokeWidth = 0.9f * s
            canvas.drawPath(stachePath, stroke)
        }
    }

    private fun drawHumanHair(
        canvas: Canvas,
        hairStyle: HumanHairStyle,
        profile: CharacterAngleProfile,
        headW: Float,
        s: Float,
        hairColor: Int,
        outline: Paint,
        alphaInt: Int
    ) {
        if (hairStyle == HumanHairStyle.NONE) return
        val isBack = profile.showsBackHairFull
        val isSide = profile.orientation == CharacterOrientation.SIDE
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
            // Full rear hair crown wrapping down to nape of neck (distinct for BACK vs 3/4 BACK!)
            val is3QBack = profile.orientation == CharacterOrientation.THREE_QUARTER_BACK
            val backPath = Path().apply {
                moveTo(-headW - 2f * s, -4f * s)
                cubicTo(-headW - 3f * s, -27f * s, headW + 3f * s, -27f * s, headW + 2f * s, -4f * s)
                if (is3QBack) {
                    // Expose tiny slice of far cheek/jaw on 3/4 Back
                    cubicTo(headW * 0.85f, 11f * s, headW * 0.25f, 16f * s, -headW * 0.35f, 15f * s)
                    quadTo(-headW * 0.92f, 14f * s, -headW - 2f * s, -4f * s)
                } else {
                    cubicTo(headW + 1f * s, 12f * s, headW * 0.65f, 17f * s, 0f, 15f * s)
                    cubicTo(-headW * 0.65f, 17f * s, -headW - 1f * s, 12f * s, -headW - 2f * s, -4f * s)
                }
                close()
            }
            canvas.drawPath(backPath, fill)
            canvas.drawPath(backPath, outline)
            canvas.drawArc(RectF(-headW * 0.62f, -21f * s, headW * 0.62f, -9f * s), 200f, 140f, false, highlight)
            if (hairStyle == HumanHairStyle.TRADITIONAL_BUN) {
                val bunX = if (is3QBack) -headW * 0.35f else 0f
                canvas.drawCircle(bunX, -4f * s, 11f * s, fill)
                canvas.drawCircle(bunX, -4f * s, 11f * s, outline)
            } else if (hairStyle == HumanHairStyle.WAVY_LONG) {
                val longDrape = Path().apply {
                    moveTo(-headW - 1f * s, 4f * s)
                    quadTo(-headW - 4f * s, 28f * s, -headW * 0.65f, 42f * s)
                    quadTo(0f, 47f * s, headW * 0.65f, 42f * s)
                    quadTo(headW + 4f * s, 28f * s, headW + 1f * s, 4f * s)
                    close()
                }
                canvas.drawPath(longDrape, fill)
                canvas.drawPath(longDrape, outline)
            }
            return
        }

        when (hairStyle) {
            HumanHairStyle.SHORT_FADE -> {
                if (isSide) {
                    val sideHair = Path().apply {
                        moveTo(-headW - 1f * s, 8f * s)
                        cubicTo(-headW - 3f * s, -26f * s, headW * 0.85f, -28f * s, headW * 0.90f, -8f * s)
                        quadTo(2f * s, -14f * s, -4f * s, -3f * s)
                        lineTo(-6f * s, 8f * s)
                        quadTo(-headW * 0.65f, 11f * s, -headW - 1f * s, 8f * s)
                        close()
                    }
                    canvas.drawPath(sideHair, fill)
                    canvas.drawArc(RectF(-12f * s, -20f * s, 8f * s, -8f * s), 200f, 140f, false, highlight)
                } else {
                    canvas.drawArc(RectF(-headW - 1.5f * s, -25f * s, headW + 1.5f * s, 2f * s), 185f, 170f, true, fill)
                    canvas.drawArc(RectF(-12f * s, -20f * s, 12f * s, -8f * s), 200f, 140f, false, highlight)
                }
            }
            HumanHairStyle.SIDE_PART -> {
                val path = Path().apply {
                    moveTo(-headW - 1f * s, if (isSide) 7f * s else -4f * s)
                    cubicTo(-headW - 3f * s, -27f * s, headW + 2f * s, -28f * s, headW * (if (isSide) 0.94f else 1.05f), -6f * s)
                    quadTo(6f * s, -15f * s, -6f * s, -10f * s)
                    quadTo(-15f * s, -6f * s, -headW - 1f * s, if (isSide) 7f * s else -4f * s)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawArc(RectF(-13f * s, -21f * s, 11f * s, -9f * s), 195f, 145f, false, highlight)
            }
            HumanHairStyle.WAVY_LONG -> {
                canvas.drawArc(RectF(-headW - 3f * s, -26f * s, headW + 2.5f * s, 4f * s), 180f, 180f, true, fill)
                canvas.drawOval(RectF(-headW - 4f * s, -14f * s, -headW + 8f * s, 24f * s), fill)
                if (!isSide) {
                    canvas.drawOval(RectF(headW - 7f * s, -14f * s, headW + 4f * s, 22f * s), fill)
                }
            }
            HumanHairStyle.TRADITIONAL_BUN -> {
                val bunX = if (isSide) -headW * 0.85f else 0f
                val bunY = if (isSide) -16f * s else -26f * s
                canvas.drawCircle(bunX, bunY, 10.5f * s, fill)
                canvas.drawArc(RectF(-headW - 1.5f * s, -24f * s, headW + 1.2f * s, 2f * s), 182f, 176f, true, fill)
            }
            HumanHairStyle.CURLY_AFRO -> {
                for (i in -3..3) {
                    val cx = i * 6.5f * s - (if (isSide) 2f * s else 0f)
                    val cy = -18f * s + abs(i) * 2.2f * s
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
        forearmColor: Int,
        cuffColor: Int?,
        skin: Int,
        outline: Paint,
        alphaInt: Int,
        mirrorThumb: Boolean,
        handPose: HandPoseType,
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

        // Tapered Organic Upper Arm / Sleeve Path (wider deltoid tapering smoothly to elbow cap)
        val upperLen = 28f * s
        val topW = 6.2f * s
        val elbowW = 4.6f * s
        val upperPath = Path().apply {
            moveTo(-topW, 2f * s)
            quadTo(0f, -5.8f * s, topW, 2f * s)
            cubicTo(topW * 1.05f, upperLen * 0.42f, elbowW * 1.08f, upperLen * 0.78f, elbowW, upperLen)
            quadTo(0f, upperLen + 3.8f * s, -elbowW, upperLen)
            cubicTo(-elbowW * 1.08f, upperLen * 0.78f, -topW * 1.05f, upperLen * 0.42f, -topW, 2f * s)
            close()
        }
        fill.color = withAlpha(upperColor, alphaInt)
        canvas.drawPath(upperPath, fill)
        canvas.save()
        canvas.clipPath(upperPath)
        fill.color = withAlpha(darken(upperColor, 0.78f), alphaInt)
        canvas.drawRect(topW * 0.2f, -6f * s, topW + 4f * s, upperLen + 6f * s, fill)
        fill.color = withAlpha(lighten(upperColor, 1.18f), (alphaInt * 0.45f).toInt())
        canvas.drawRect(-topW - 2f * s, -4f * s, -topW * 0.42f, upperLen + 4f * s, fill)
        canvas.restore()
        canvas.drawPath(upperPath, outline)

        // Elbow pivot -> Rounded Joint Cap + Tapered Organic Forearm (tailored long sleeve or bare skin!)
        canvas.translate(0f, upperLen - 2.5f * s)
        canvas.rotate(forearmDeg)
        val foreLen = 24f * s
        val foreTopW = 4.7f * s
        val wristW = 3.2f * s
        val forePath = Path().apply {
            moveTo(-foreTopW, 1f * s)
            quadTo(0f, -3.8f * s, foreTopW, 1f * s)
            cubicTo(foreTopW * 1.05f, foreLen * 0.35f, wristW * 1.15f, foreLen * 0.75f, wristW, foreLen)
            quadTo(0f, foreLen + 2.5f * s, -wristW, foreLen)
            cubicTo(-wristW * 1.15f, foreLen * 0.75f, -foreTopW * 1.05f, foreLen * 0.35f, -foreTopW, 1f * s)
            close()
        }
        fill.color = withAlpha(forearmColor, alphaInt)
        canvas.drawPath(forePath, fill)
        canvas.save()
        canvas.clipPath(forePath)
        fill.color = withAlpha(darken(forearmColor, 0.82f), alphaInt)
        canvas.drawRect(foreTopW * 0.22f, -4f * s, foreTopW + 3f * s, foreLen + 4f * s, fill)
        fill.color = withAlpha(lighten(forearmColor, 1.14f), (alphaInt * 0.42f).toInt())
        canvas.drawRect(-foreTopW - 2f * s, -3f * s, -foreTopW * 0.38f, foreLen + 3f * s, fill)
        if (cuffColor != null) {
            fill.color = withAlpha(cuffColor, alphaInt)
            canvas.drawRect(-wristW * 1.4f, foreLen - 4.2f * s, wristW * 1.4f, foreLen + 2f * s, fill)
        }
        canvas.restore()
        canvas.drawPath(forePath, outline)

        // Articulated Sculpted Human Hand honoring HandPoseType (OPEN / FIST / POINT / PROP_GRIP)
        canvas.translate(0f, foreLen + 1.4f * s)
        drawArticulatedHand(canvas, handPose, mirrorThumb, s, skin, outline, alphaInt)

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

    private fun drawArticulatedHand(
        canvas: Canvas,
        handPose: HandPoseType,
        mirrorThumb: Boolean,
        s: Float,
        skin: Int,
        outline: Paint,
        alphaInt: Int
    ) {
        val fill = scratchLocal.get()!!.fill
        val thumbSign = if (mirrorThumb) 1f else -1f
        val shadowSkin = withAlpha(darken(skin, 0.84f), alphaInt)
        val mainSkin = withAlpha(skin, alphaInt)

        when (handPose) {
            HandPoseType.FIST -> {
                // Compact clenched fist with knuckle ridges & folded thumb
                val fistRect = RectF(-4.6f * s, -2.0f * s, 4.6f * s, 5.8f * s)
                fill.color = mainSkin
                canvas.drawRoundRect(fistRect, 3.2f * s, 3.2f * s, fill)
                fill.color = shadowSkin
                canvas.drawRoundRect(RectF(-3.8f * s, 2.2f * s, 3.8f * s, 5.4f * s), 1.8f * s, 1.8f * s, fill)
                canvas.drawRoundRect(fistRect, 3.2f * s, 3.2f * s, outline)
                // Tucked thumb across knuckles
                val thumbRect = RectF(thumbSign * 1.2f * s - 2.6f * s, 0.4f * s, thumbSign * 1.2f * s + 2.6f * s, 3.8f * s)
                fill.color = mainSkin
                canvas.drawRoundRect(thumbRect, 1.6f * s, 1.6f * s, fill)
                canvas.drawRoundRect(thumbRect, 1.6f * s, 1.6f * s, outline)
            }
            HandPoseType.POINT -> {
                // Curled palm + extended tapered index finger
                val palmRect = RectF(-4.4f * s, -2.2f * s, 4.4f * s, 5.2f * s)
                fill.color = mainSkin
                canvas.drawOval(palmRect, fill)
                canvas.drawOval(palmRect, outline)
                // Extended index finger
                val fingerX = thumbSign * 1.6f * s
                val fingerRect = RectF(fingerX - 1.65f * s, 3.2f * s, fingerX + 1.65f * s, 11.4f * s)
                fill.color = mainSkin
                canvas.drawRoundRect(fingerRect, 1.6f * s, 1.6f * s, fill)
                canvas.drawRoundRect(fingerRect, 1.6f * s, 1.6f * s, outline)
                // Thumb
                val thumbRect = RectF(thumbSign * 3.0f * s - 1.8f * s, -0.5f * s, thumbSign * 3.0f * s + 1.8f * s, 4.0f * s)
                canvas.drawOval(thumbRect, fill)
                canvas.drawOval(thumbRect, outline)
            }
            HandPoseType.PROP_GRIP -> {
                // Wrapped grip hand cupping prop handle
                val gripRect = RectF(-4.8f * s, -2.2f * s, 4.8f * s, 6.4f * s)
                fill.color = mainSkin
                canvas.drawRoundRect(gripRect, 3.0f * s, 3.0f * s, fill)
                fill.color = shadowSkin
                canvas.drawRoundRect(RectF(-3.4f * s, 0.6f * s, 3.4f * s, 5.2f * s), 2.0f * s, 2.0f * s, fill)
                canvas.drawRoundRect(gripRect, 3.0f * s, 3.0f * s, outline)
                val thumbRect = RectF(thumbSign * 2.2f * s - 2.3f * s, -0.6f * s, thumbSign * 2.2f * s + 2.3f * s, 3.6f * s)
                fill.color = mainSkin
                canvas.drawRoundRect(thumbRect, 1.8f * s, 1.8f * s, fill)
                canvas.drawRoundRect(thumbRect, 1.8f * s, 1.8f * s, outline)
            }
            HandPoseType.OPEN -> {
                // Pixel-Perfect 5-Finger Articulated Cartoon Hand (sculpted palm + curved thumb + 4 tapered fingers)
                val fineOutline = Paint(outline).apply { strokeWidth = (outline.strokeWidth * 0.78f).coerceAtLeast(1.0f * s) }
                val palmPath = Path().apply {
                    moveTo(-3.4f * s, -1.6f * s)
                    lineTo(3.4f * s, -1.6f * s)
                    cubicTo(4.6f * s, 0.8f * s, 4.4f * s, 4.6f * s, 3.3f * s, 6.0f * s)
                    lineTo(-3.3f * s, 6.0f * s)
                    cubicTo(-4.4f * s, 4.6f * s, -4.6f * s, 0.8f * s, -3.4f * s, -1.6f * s)
                    close()
                }
                // Draw 4 articulated fingers (Index -> Middle -> Ring -> Pinky) with natural length cascade
                val fingerSpecs = listOf(
                    Triple(thumbSign * 2.35f, 10.6f, 1.15f),  // Index
                    Triple(thumbSign * 0.75f, 11.7f, 1.18f),  // Middle
                    Triple(-thumbSign * 0.85f, 10.9f, 1.12f), // Ring
                    Triple(-thumbSign * 2.35f, 9.2f, 1.02f)   // Pinky
                )
                fingerSpecs.forEach { (fx, fyEnd, fw) ->
                    val fRect = RectF((fx - fw) * s, 4.2f * s, (fx + fw) * s, fyEnd * s)
                    fill.color = mainSkin
                    canvas.drawRoundRect(fRect, fw * s, fw * s, fill)
                    canvas.drawRoundRect(fRect, fw * s, fw * s, fineOutline)
                }
                // Sculpted Palm over finger bases
                fill.color = mainSkin
                canvas.drawPath(palmPath, fill)
                fill.color = shadowSkin
                canvas.drawArc(RectF(-3.8f * s, -1.0f * s, 3.8f * s, 5.8f * s), -20f, 130f, false, fill)
                canvas.drawPath(palmPath, fineOutline)
                // Curved outward Thumb
                canvas.save()
                canvas.translate(thumbSign * 3.4f * s, 1.6f * s)
                canvas.rotate(thumbSign * -28f)
                val thumbRect = RectF(-1.35f * s, -1.0f * s, 1.35f * s, 5.6f * s)
                fill.color = mainSkin
                canvas.drawRoundRect(thumbRect, 1.3f * s, 1.3f * s, fill)
                canvas.drawRoundRect(thumbRect, 1.3f * s, 1.3f * s, fineOutline)
                canvas.restore()
            }
        }
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
        skin: Int,
        costumeStyle: HumanCostumeStyle,
        shoeView: ShoeAngleView,
        outline: Paint,
        alphaInt: Int
    ) {
        val fill = scratchLocal.get()!!.fill
        val isDhotiStyle = costumeStyle == HumanCostumeStyle.DESI_KURTA_DHOTI ||
            costumeStyle == HumanCostumeStyle.VILLAGE_DHOTI
        canvas.save()
        canvas.translate(ox, oy)
        canvas.rotate(thighDeg)

        // Tapered Organic Thigh Path (fuller pleated drape for Desi Dhoti!)
        val thighLen = 32f * s
        val hipW = (if (isDhotiStyle) 9.8f else 7.6f) * s
        val kneeW = (if (isDhotiStyle) 6.6f else 5.4f) * s
        val thighPath = Path().apply {
            moveTo(-hipW, 2f * s)
            quadTo(0f, -5.2f * s, hipW, 2f * s)
            cubicTo(hipW * 1.08f, thighLen * 0.45f, kneeW * 1.12f, thighLen * 0.80f, kneeW, thighLen)
            quadTo(0f, thighLen + 3.8f * s, -kneeW, thighLen)
            cubicTo(-kneeW * 1.12f, thighLen * 0.80f, -hipW * 1.08f, thighLen * 0.45f, -hipW, 2f * s)
            close()
        }
        fill.color = withAlpha(pantColor, alphaInt)
        canvas.drawPath(thighPath, fill)
        canvas.save()
        canvas.clipPath(thighPath)
        fill.color = withAlpha(darken(pantColor, if (isDhotiStyle) 0.90f else 0.76f), alphaInt)
        canvas.drawRect(hipW * 0.28f, -6f * s, hipW + 4f * s, thighLen + 5f * s, fill)
        if (isDhotiStyle) {
            // Soft curved dhoti pleat drape lines
            val pleatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.25f * s
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(darken(pantColor, 0.82f), alphaInt)
            }
            val pleat = Path().apply {
                moveTo(-hipW * 0.55f, 4f * s)
                quadTo(0f, thighLen * 0.55f, kneeW * 0.65f, thighLen * 0.92f)
            }
            canvas.drawPath(pleat, pleatPaint)
        } else {
            fill.color = withAlpha(lighten(pantColor, 1.16f), (alphaInt * 0.35f).toInt())
            canvas.drawRect(-hipW - 2f * s, -4f * s, -hipW * 0.40f, thighLen + 4f * s, fill)
        }
        canvas.restore()
        canvas.drawPath(thighPath, outline)

        // Knee pivot -> Tapered Organic Calf Path
        canvas.translate(0f, thighLen - 2.5f * s)
        canvas.rotate(kneeDeg)
        val calfLen = 31f * s
        val calfTopW = (if (isDhotiStyle) 6.8f else 5.7f) * s
        val ankleW = (if (isDhotiStyle) 4.4f else 4.0f) * s
        val calfClothBottom = if (isDhotiStyle) calfLen - 4.5f * s else calfLen
        val calfBaseColor = darken(pantColor, if (isDhotiStyle) 0.96f else 0.93f)
        val calfPath = Path().apply {
            moveTo(-calfTopW, 1f * s)
            quadTo(0f, -3.8f * s, calfTopW, 1f * s)
            cubicTo(calfTopW * 1.12f, calfClothBottom * 0.35f, ankleW * 1.18f, calfClothBottom * 0.78f, ankleW, calfClothBottom)
            quadTo(0f, calfClothBottom + 2.6f * s, -ankleW, calfClothBottom)
            cubicTo(-ankleW * 1.12f, calfClothBottom * 0.78f, -calfTopW * 1.06f, calfClothBottom * 0.35f, -calfTopW, 1f * s)
            close()
        }
        fill.color = withAlpha(calfBaseColor, alphaInt)
        canvas.drawPath(calfPath, fill)
        canvas.save()
        canvas.clipPath(calfPath)
        fill.color = withAlpha(darken(calfBaseColor, if (isDhotiStyle) 0.89f else 0.78f), alphaInt)
        canvas.drawRect(calfTopW * 0.25f, -4f * s, calfTopW + 3f * s, calfClothBottom + 4f * s, fill)
        canvas.restore()
        canvas.drawPath(calfPath, outline)

        // 5-Angle Directional Footwear: Traditional Brown Leather Chappals for Desi Dhoti, or Sculpted Sneakers
        val shoeTop = calfLen - 2f * s
        if (isDhotiStyle) {
            drawChappalFootwear(canvas, ankleW, calfClothBottom, shoeTop, shoeView, s, skin, outline, alphaInt)
        } else {
            when (shoeView) {
                ShoeAngleView.FRONT_OUTWARD_LEFT -> {
                    val shoePath = Path().apply {
                        moveTo(ankleW * 1.18f, shoeTop)
                        lineTo(-ankleW * 1.25f, shoeTop)
                        quadTo(-ankleW * 2.15f, shoeTop + 2.0f * s, -ankleW * 2.05f, shoeTop + 7.2f * s)
                        lineTo(-ankleW * 2.05f, shoeTop + 9.6f * s)
                        lineTo(ankleW * 1.25f, shoeTop + 9.6f * s)
                        close()
                    }
                    fill.color = withAlpha(darken(shoeColor, 0.68f), alphaInt)
                    canvas.drawPath(shoePath, fill)
                    fill.color = withAlpha(0xFFF1F5F9.toInt(), alphaInt)
                    canvas.drawRoundRect(RectF(-ankleW * 2.08f, shoeTop + 7.0f * s, ankleW * 1.28f, shoeTop + 10.0f * s), 2f * s, 2f * s, fill)
                    canvas.drawPath(shoePath, outline)
                }
                ShoeAngleView.FRONT_OUTWARD_RIGHT -> {
                    val shoePath = Path().apply {
                        moveTo(-ankleW * 1.18f, shoeTop)
                        lineTo(ankleW * 1.25f, shoeTop)
                        quadTo(ankleW * 2.15f, shoeTop + 2.0f * s, ankleW * 2.05f, shoeTop + 7.2f * s)
                        lineTo(ankleW * 2.05f, shoeTop + 9.6f * s)
                        lineTo(-ankleW * 1.25f, shoeTop + 9.6f * s)
                        close()
                    }
                    fill.color = withAlpha(darken(shoeColor, 0.68f), alphaInt)
                    canvas.drawPath(shoePath, fill)
                    fill.color = withAlpha(0xFFF1F5F9.toInt(), alphaInt)
                    canvas.drawRoundRect(RectF(-ankleW * 1.28f, shoeTop + 7.0f * s, ankleW * 2.08f, shoeTop + 10.0f * s), 2f * s, 2f * s, fill)
                    canvas.drawPath(shoePath, outline)
                }
                ShoeAngleView.REAR_HEEL -> {
                    val heelRect = RectF(-ankleW * 1.32f, shoeTop, ankleW * 1.32f, shoeTop + 9.4f * s)
                    fill.color = withAlpha(darken(shoeColor, 0.62f), alphaInt)
                    canvas.drawRoundRect(heelRect, 3.2f * s, 3.2f * s, fill)
                    fill.color = withAlpha(0xFFE2E8F0.toInt(), alphaInt)
                    canvas.drawRoundRect(RectF(-ankleW * 1.36f, shoeTop + 6.8f * s, ankleW * 1.36f, shoeTop + 9.8f * s), 1.8f * s, 1.8f * s, fill)
                    canvas.drawRoundRect(heelRect, 3.2f * s, 3.2f * s, outline)
                }
                ShoeAngleView.PROFILE_RIGHT -> {
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
                }
            }
        }

        canvas.restore()
    }

    /**
     * Traditional Indian Brown Leather Chappals (Kolhapuri Sandals) with bare ankle/instep skin,
     * angled brown leather strap, and contoured flat dark-brown sole across all 5 turnaround angles.
     */
    private fun drawChappalFootwear(
        canvas: Canvas,
        ankleW: Float,
        dhotiBottomY: Float,
        shoeTop: Float,
        shoeView: ShoeAngleView,
        s: Float,
        skin: Int,
        outline: Paint,
        alphaInt: Int
    ) {
        val fill = scratchLocal.get()!!.fill
        val strapColor = withAlpha(0xFF6E472B.toInt(), alphaInt)
        val soleColor = withAlpha(0xFF4A2E1A.toInt(), alphaInt)
        val skinCol = withAlpha(skin, alphaInt)

        // Bare ankle bridge peeking cleanly beneath gathered dhoti cuff
        fill.color = skinCol
        val ankleRect = RectF(-ankleW * 0.72f, dhotiBottomY - 1.5f * s, ankleW * 0.72f, shoeTop + 3.2f * s)
        canvas.drawRect(ankleRect, fill)

        when (shoeView) {
            ShoeAngleView.FRONT_OUTWARD_LEFT -> {
                val footPath = Path().apply {
                    moveTo(ankleW * 0.95f, shoeTop)
                    lineTo(-ankleW * 0.75f, shoeTop)
                    quadTo(-ankleW * 2.35f, shoeTop + 2.5f * s, -ankleW * 2.25f, shoeTop + 7.2f * s)
                    lineTo(ankleW * 1.15f, shoeTop + 7.2f * s)
                    close()
                }
                fill.color = skinCol
                canvas.drawPath(footPath, fill)
                canvas.drawPath(footPath, outline)
                // Brown leather diagonal sandal strap
                val strap = Path().apply {
                    moveTo(-ankleW * 1.65f, shoeTop + 2.4f * s)
                    quadTo(-ankleW * 0.65f, shoeTop + 0.4f * s, ankleW * 0.25f, shoeTop + 6.8f * s)
                    lineTo(-ankleW * 0.55f, shoeTop + 7.2f * s)
                    quadTo(-ankleW * 1.15f, shoeTop + 3.2f * s, -ankleW * 1.85f, shoeTop + 5.2f * s)
                    close()
                }
                fill.color = strapColor
                canvas.drawPath(strap, fill)
                canvas.drawPath(strap, outline)
                // Flat brown leather sole
                val sole = RectF(-ankleW * 2.45f, shoeTop + 6.6f * s, ankleW * 1.32f, shoeTop + 10.0f * s)
                fill.color = soleColor
                canvas.drawRoundRect(sole, 2.2f * s, 2.2f * s, fill)
                canvas.drawRoundRect(sole, 2.2f * s, 2.2f * s, outline)
            }
            ShoeAngleView.FRONT_OUTWARD_RIGHT -> {
                val footPath = Path().apply {
                    moveTo(-ankleW * 0.95f, shoeTop)
                    lineTo(ankleW * 0.75f, shoeTop)
                    quadTo(ankleW * 2.35f, shoeTop + 2.5f * s, ankleW * 2.25f, shoeTop + 7.2f * s)
                    lineTo(-ankleW * 1.15f, shoeTop + 7.2f * s)
                    close()
                }
                fill.color = skinCol
                canvas.drawPath(footPath, fill)
                canvas.drawPath(footPath, outline)
                val strap = Path().apply {
                    moveTo(ankleW * 1.65f, shoeTop + 2.4f * s)
                    quadTo(ankleW * 0.65f, shoeTop + 0.4f * s, -ankleW * 0.25f, shoeTop + 6.8f * s)
                    lineTo(ankleW * 0.55f, shoeTop + 7.2f * s)
                    quadTo(ankleW * 1.15f, shoeTop + 3.2f * s, ankleW * 1.85f, shoeTop + 5.2f * s)
                    close()
                }
                fill.color = strapColor
                canvas.drawPath(strap, fill)
                canvas.drawPath(strap, outline)
                val sole = RectF(-ankleW * 1.32f, shoeTop + 6.6f * s, ankleW * 2.45f, shoeTop + 10.0f * s)
                fill.color = soleColor
                canvas.drawRoundRect(sole, 2.2f * s, 2.2f * s, fill)
                canvas.drawRoundRect(sole, 2.2f * s, 2.2f * s, outline)
            }
            ShoeAngleView.REAR_HEEL -> {
                val heel = RectF(-ankleW * 1.15f, shoeTop + 0.8f * s, ankleW * 1.15f, shoeTop + 7.2f * s)
                fill.color = skinCol
                canvas.drawRoundRect(heel, 2.6f * s, 2.6f * s, fill)
                canvas.drawRoundRect(heel, 2.6f * s, 2.6f * s, outline)
                val sole = RectF(-ankleW * 1.32f, shoeTop + 6.6f * s, ankleW * 1.32f, shoeTop + 10.0f * s)
                fill.color = soleColor
                canvas.drawRoundRect(sole, 2.0f * s, 2.0f * s, fill)
                canvas.drawRoundRect(sole, 2.0f * s, 2.0f * s, outline)
            }
            ShoeAngleView.PROFILE_RIGHT -> {
                val footPath = Path().apply {
                    moveTo(-ankleW * 1.15f, shoeTop)
                    lineTo(ankleW * 0.95f, shoeTop)
                    quadTo(ankleW * 2.85f, shoeTop + 2.2f * s, ankleW * 2.75f, shoeTop + 7.2f * s)
                    lineTo(-ankleW * 1.35f, shoeTop + 7.2f * s)
                    close()
                }
                fill.color = skinCol
                canvas.drawPath(footPath, fill)
                canvas.drawPath(footPath, outline)
                val strap = Path().apply {
                    moveTo(ankleW * 1.85f, shoeTop + 2.4f * s)
                    quadTo(ankleW * 0.90f, shoeTop + 0.5f * s, ankleW * 0.05f, shoeTop + 7.0f * s)
                    lineTo(ankleW * 0.85f, shoeTop + 7.2f * s)
                    quadTo(ankleW * 1.35f, shoeTop + 3.2f * s, ankleW * 2.05f, shoeTop + 5.0f * s)
                    close()
                }
                fill.color = strapColor
                canvas.drawPath(strap, fill)
                canvas.drawPath(strap, outline)
                val sole = RectF(-ankleW * 1.48f, shoeTop + 6.6f * s, ankleW * 2.90f, shoeTop + 10.0f * s)
                fill.color = soleColor
                canvas.drawRoundRect(sole, 2.2f * s, 2.2f * s, fill)
                canvas.drawRoundRect(sole, 2.2f * s, 2.2f * s, outline)
            }
        }
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
