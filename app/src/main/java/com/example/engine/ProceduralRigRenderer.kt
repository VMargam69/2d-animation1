package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
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
        val propSwayDeg: Float = 0f,
        val propSwayOffsetX: Float = 0f,
        val propSwayOffsetY: Float = 0f,
        val accessoryFlutterDeg: Float = 0f
    ) {
        fun withOffsets(
            offsets: PoseKeyframeOffsets,
            expression: FacialExpression = FacialExpression.AUTO,
            swayDeg: Float = 0f,
            swayOffsetX: Float = 0f,
            swayOffsetY: Float = 0f,
            accessoryFlutterDeg: Float = 0f
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
                propSwayDeg = swayDeg,
                propSwayOffsetX = swayOffsetX,
                propSwayOffsetY = swayOffsetY,
                accessoryFlutterDeg = accessoryFlutterDeg
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
        propScale: Float = 1.0f,
        handPose: HandPoseType = HandPoseType.OPEN,
        blinkOverride: Boolean? = null,
        mouthSlotOverride: AtlasPartSlot? = null,
        atlasSlices: Map<AtlasPartSlot, Bitmap>? = null,
        organicFace: OrganicFacialState? = null,
        propSwayState: PropSwayState? = null,
        tearsConfig: TearsFxConfig = TearsFxConfig(),
        globalFrame: Int = 0,
        fps: Int = 24,
        clipSeed: Int = 0,
        bodyBobDelta: Float = 0f,
        squashOverrideX: Float = 1f,
        squashOverrideY: Float = 1f,
        overrideHandTrack: Boolean = false,
        overrideLegTrack: Boolean = false
    ) {
        val p = ((phase % 1f) + 1f) % 1f
        val swayDeg = when {
            !propSwayEnabled -> 0f
            propSwayState != null -> propSwayState.angleDeg
            else -> (sin(p * 2.0 * PI) * 10.0).toFloat()
        }
        val swayOffX = if (propSwayEnabled && propSwayState != null) propSwayState.offsetX else 0f
        val swayOffY = if (propSwayEnabled && propSwayState != null) propSwayState.offsetY else 0f
        val accFlutter = if (propSwayEnabled && propSwayState != null) propSwayState.accessoryFlutterDeg else 0f
        val rawBasePose = computePose(preset, p)
        // Track isolation: when modular Hand/Leg tracks are active, clear base preset's conflicting
        // limb channels so modular actions never double-add or fight against the base AnimationPresetType!
        val isolatedBasePose = rawBasePose.copy(
            leftArmDeg = if (overrideHandTrack) 0f else rawBasePose.leftArmDeg,
            leftForearmDeg = if (overrideHandTrack) 0f else rawBasePose.leftForearmDeg,
            rightArmDeg = if (overrideHandTrack) 0f else rawBasePose.rightArmDeg,
            rightForearmDeg = if (overrideHandTrack) 0f else rawBasePose.rightForearmDeg,
            leftLegDeg = if (overrideLegTrack) 0f else rawBasePose.leftLegDeg,
            leftKneeDeg = if (overrideLegTrack) 0f else rawBasePose.leftKneeDeg,
            rightLegDeg = if (overrideLegTrack) 0f else rawBasePose.rightLegDeg,
            rightKneeDeg = if (overrideLegTrack) 0f else rawBasePose.rightKneeDeg,
            bodyTiltDeg = if (overrideLegTrack) 0f else rawBasePose.bodyTiltDeg,
            bodyBobY = if (overrideLegTrack) 0f else rawBasePose.bodyBobY
        )
        val pose = isolatedBasePose.withOffsets(
            offsets = poseOffsets,
            expression = facialExpression,
            swayDeg = swayDeg,
            swayOffsetX = swayOffX,
            swayOffsetY = swayOffY,
            accessoryFlutterDeg = accFlutter
        ).let {
            it.copy(
                bodyBobY = it.bodyBobY + bodyBobDelta,
                squashX = (it.squashX * squashOverrideX).coerceIn(0.75f, 1.30f),
                squashY = (it.squashY * squashOverrideY).coerceIn(0.75f, 1.30f),
                blinkClosed = blinkOverride ?: it.blinkClosed
            )
        }

        canvas.save()
        canvas.translate(0f, pose.bodyBobY * rigScale)
        canvas.rotate(pose.bodyTiltDeg)
        canvas.scale(pose.squashX, pose.squashY)

        if (rig.spriteAtlasRig != null || rig.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO) {
            val resolvedSlices = atlasSlices ?: emptyMap()
            val poseTemplate = rig.spriteAtlasRig?.poseTemplate ?: when (orientation) {
                CharacterOrientation.FRONT -> AtlasPoseTemplate.FRONT
                CharacterOrientation.SIDE, CharacterOrientation.BACK, CharacterOrientation.THREE_QUARTER_BACK -> AtlasPoseTemplate.BACK_SIDE
                CharacterOrientation.THREE_QUARTER_FRONT -> AtlasPoseTemplate.THREE_QUARTER_FRONT
            }
            SpriteAtlasRigRenderer.drawSpriteAtlasRig(
                canvas = canvas,
                slices = resolvedSlices,
                poseTemplate = poseTemplate,
                pose = pose,
                rigScale = rigScale,
                alpha = alpha,
                tintOverlayColor = tintOverlayColor,
                handPose = handPose,
                blinkOverride = blinkOverride,
                mouthSlotOverride = mouthSlotOverride,
                propType = propType,
                propAttachment = propAttachment,
                customPropBitmap = customPropBitmap,
                propScale = propScale,
                drawGhostFallbackWhenEmpty = resolvedSlices.isEmpty(),
                skipFaceOverlays = rig.spriteAtlasRig?.faceBakedInHead == true,
                organicFace = organicFace,
                tearsConfig = tearsConfig,
                globalFrame = globalFrame,
                fps = fps,
                clipSeed = clipSeed,
                orientation = orientation
            )
            canvas.restore()
            return
        }

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
                        canvas, propType, customPropBitmap, rigScale * propScale, pose.propSwayDeg, alphaInt,
                        pose.propSwayOffsetX * rigScale, pose.propSwayOffsetY * rigScale
                    )
                    canvas.restore()
                }
            }
            if (tearsConfig.isActive) {
                val alphaIntCustom = (alpha.coerceIn(0f, 1f) * 255).toInt()
                ClipVisualFxEngine.drawTearsAtEyes(
                    canvas = canvas,
                    config = tearsConfig,
                    eyeAnchors = listOf(
                        ClipVisualFxEngine.TearEyeAnchor(-9f * rigScale, -56f * rigScale, -1f),
                        ClipVisualFxEngine.TearEyeAnchor(9f * rigScale, -56f * rigScale, 1f)
                    ),
                    scale = rigScale,
                    globalFrame = globalFrame,
                    fps = fps,
                    alphaInt = alphaIntCustom,
                    seed = clipSeed
                )
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
                handPose = handPose,
                organicFace = organicFace,
                tearsConfig = tearsConfig,
                globalFrame = globalFrame,
                fps = fps,
                clipSeed = clipSeed,
                drawAccessory = { c, acc, sc, acCol, prCol, st, al ->
                    drawHeadAccessory(c, acc, sc, acCol, prCol, st, al)
                }
            )
            CharacterBodyType.BIRD -> {
                drawBird(
                    canvas, rig, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose, alphaInt)
            }
            CharacterBodyType.QUADRUPED -> {
                drawQuadruped(
                    canvas, rig, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose, alphaInt)
            }
            CharacterBodyType.VEHICLE -> {
                drawVehicle(
                    canvas, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose, alphaInt)
            }
            CharacterBodyType.SPIRIT -> {
                drawSpirit(
                    canvas, rig, orientation, pose, rigScale, alphaInt,
                    skinColor, primaryColor, secondaryColor, accentColor
                )
                drawNonHumanoidPropIfEquipped(canvas, propType, propAttachment, customPropBitmap, rigScale, propScale, pose, alphaInt)
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
        pose: PoseAngles,
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
            canvas, propType, customPropBitmap, rigScale * propScale, pose.propSwayDeg, alphaInt,
            pose.propSwayOffsetX * rigScale, pose.propSwayOffsetY * rigScale
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
            CharacterAccessory.DESI_PAGDI_TILAK, CharacterAccessory.TURBAN -> {
                // Iconic layered striped Desi Pagdi (coral-orange, saffron-gold, warm rose wrap bands + V-arch)
                val pagdiPath = Path().apply {
                    moveTo(-24.5f * s, -11.5f * s)
                    cubicTo(-28.5f * s, -26f * s, -16f * s, -39.5f * s, 0f, -39.5f * s)
                    cubicTo(16f * s, -39.5f * s, 28.5f * s, -26f * s, 24.5f * s, -11.5f * s)
                    quadTo(12f * s, -18.5f * s, 0f, -21.5f * s)
                    quadTo(-12f * s, -18.5f * s, -24.5f * s, -11.5f * s)
                    close()
                }
                fill.color = withAlpha(accent, alphaInt)
                canvas.drawPath(pagdiPath, fill)

                // Clipped diagonal woven Rajasthani/Desi stripe bands & fabric wrap creases
                canvas.save()
                canvas.clipPath(pagdiPath)
                val bandColors = intArrayOf(
                    0xFFF59E23.toInt(), // warm saffron gold
                    0xFFD9536F.toInt(), // soft coral-crimson
                    accent,
                    0xFFF59E23.toInt(),
                    0xFFD9536F.toInt()
                )
                val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                // Left-wrapped diagonal bands
                for (i in 0..4) {
                    stripePaint.color = withAlpha(bandColors[i % bandColors.size], alphaInt)
                    val offset = (-34f + i * 11.5f) * s
                    val band = Path().apply {
                        moveTo(offset, -42f * s)
                        lineTo(offset + 6.4f * s, -42f * s)
                        lineTo(offset + 22f * s, -8f * s)
                        lineTo(offset + 15.6f * s, -8f * s)
                        close()
                    }
                    canvas.drawPath(band, stripePaint)
                }
                // Cross-wrapped right crown folds for authentic overlapping turban geometry
                val rightCrown = Path().apply {
                    moveTo(-2f * s, -22f * s)
                    cubicTo(6f * s, -34f * s, 19f * s, -37f * s, 29f * s, -24f * s)
                    lineTo(29f * s, -10f * s)
                    quadTo(14f * s, -18f * s, -2f * s, -22f * s)
                    close()
                }
                stripePaint.color = withAlpha(accent, alphaInt)
                canvas.drawPath(rightCrown, stripePaint)
                canvas.save()
                canvas.clipPath(rightCrown)
                for (i in 0..3) {
                    stripePaint.color = withAlpha(bandColors[(i + 1) % bandColors.size], alphaInt)
                    val ox = (2f + i * 9.5f) * s
                    val rBand = Path().apply {
                        moveTo(ox + 10f * s, -40f * s)
                        lineTo(ox + 15.2f * s, -40f * s)
                        lineTo(ox - 2f * s, -8f * s)
                        lineTo(ox - 7.2f * s, -8f * s)
                        close()
                    }
                    canvas.drawPath(rBand, stripePaint)
                }
                canvas.restore()

                // Delicate hand-drawn fabric wrap fold crease lines
                val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.25f * s
                    strokeCap = Paint.Cap.ROUND
                    color = withAlpha(0xFF7C2D12.toInt(), (alphaInt * 0.58f).toInt())
                }
                // Left wrap folds
                canvas.drawArc(RectF(-28f * s, -37f * s, 12f * s, -8f * s), 192f, 118f, false, creasePaint)
                canvas.drawArc(RectF(-26f * s, -31f * s, 6f * s, -6f * s), 188f, 110f, false, creasePaint)
                // Right wrap folds crossing over center V
                canvas.drawArc(RectF(-10f * s, -37f * s, 28f * s, -8f * s), 232f, 118f, false, creasePaint)
                canvas.drawArc(RectF(-4f * s, -30f * s, 26f * s, -5f * s), 240f, 112f, false, creasePaint)

                // Soft upper highlight + lower forehead rim shadow
                stripePaint.color = withAlpha(0xFFFFFFFF.toInt(), (alphaInt * 0.14f).toInt())
                canvas.drawOval(RectF(-16f * s, -38f * s, 16f * s, -27f * s), stripePaint)
                stripePaint.color = withAlpha(0xFF451A03.toInt(), (alphaInt * 0.16f).toInt())
                canvas.drawArc(RectF(-26f * s, -26f * s, 26f * s, -9f * s), 10f, 160f, false, stripePaint)
                canvas.restore()

                // Crisp outer silhouette border
                canvas.drawPath(pagdiPath, stroke)
            }
            CharacterAccessory.DESI_BINDI_JEWEL -> {
                // Delicate golden jhumka drop earrings flanking the jawline
                val gold = withAlpha(0xFFFACC15.toInt(), alphaInt)
                val goldDark = withAlpha(0xFFB45309.toInt(), alphaInt)
                val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.3f * s
                    color = goldDark
                }
                listOf(-19.5f * s, 19.5f * s).forEach { ex ->
                    fill.color = gold
                    canvas.drawCircle(ex, 6.2f * s, 2.2f * s, fill)
                    canvas.drawCircle(ex, 6.2f * s, 2.2f * s, ringStroke)
                    val bell = Path().apply {
                        moveTo(ex - 2.4f * s, 8.2f * s)
                        lineTo(ex + 2.4f * s, 8.2f * s)
                        lineTo(ex + 3.4f * s, 12.6f * s)
                        quadTo(ex, 14.0f * s, ex - 3.4f * s, 12.6f * s)
                        close()
                    }
                    canvas.drawPath(bell, fill)
                    canvas.drawPath(bell, ringStroke)
                }
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

        // Cel-Shaded Body Oval with Shadow Crescent & Rim Highlight
        fill.color = withAlpha(primary, alphaInt)
        val bodyRect = RectF(-34f * s, -20f * s, 34f * s, 36f * s)
        canvas.drawOval(bodyRect, fill)
        fill.color = withAlpha(0x2E000000, alphaInt)
        canvas.drawArc(bodyRect, 20f, 155f, false, fill)
        fill.color = withAlpha(0x33FFFFFF, alphaInt)
        canvas.drawOval(RectF(-26f * s, -15f * s, -4f * s, -3f * s), fill)
        canvas.drawOval(bodyRect, stroke)

        // Belly highlight
        fill.color = withAlpha(accent, (alphaInt * 0.88f).toInt())
        canvas.drawOval(RectF(-18f * s, -4f * s, 18f * s, 28f * s), fill)

        // Sculpted Feather Wings with Cel-Shading
        canvas.save()
        canvas.translate(-24f * s, -4f * s)
        canvas.rotate(-25f - pose.wingFlapDeg)
        val leftWing = Path().apply {
            moveTo(6f * s, -6f * s)
            quadTo(-16f * s, -16f * s, -36f * s, 4f * s)
            quadTo(-18f * s, 22f * s, 6f * s, 12f * s)
            close()
        }
        fill.color = withAlpha(secondary, alphaInt)
        canvas.drawPath(leftWing, fill)
        canvas.drawPath(leftWing, stroke)
        canvas.restore()

        canvas.save()
        canvas.translate(24f * s, -4f * s)
        canvas.rotate(25f + pose.wingFlapDeg)
        val rightWing = Path().apply {
            moveTo(-6f * s, -6f * s)
            quadTo(16f * s, -16f * s, 36f * s, 4f * s)
            quadTo(18f * s, 22f * s, -6f * s, 12f * s)
            close()
        }
        fill.color = withAlpha(secondary, alphaInt)
        canvas.drawPath(rightWing, fill)
        canvas.drawPath(rightWing, stroke)
        canvas.restore()

        // Head with Cel Highlight
        canvas.save()
        canvas.translate(0f, -34f * s)
        canvas.rotate(pose.headNodDeg)
        fill.color = withAlpha(primary, alphaInt)
        canvas.drawCircle(0f, 0f, 22f * s, fill)
        fill.color = withAlpha(0x28000000, alphaInt)
        canvas.drawArc(RectF(-22f * s, -22f * s, 22f * s, 22f * s), 15f, 150f, false, fill)
        fill.color = withAlpha(0x38FFFFFF, alphaInt)
        canvas.drawOval(RectF(-15f * s, -17f * s, -2f * s, -8f * s), fill)
        canvas.drawCircle(0f, 0f, 22f * s, stroke)

        if (!isBack) {
            // Expressive Eyes with Glint
            val eyeOffset = if (orientation == CharacterOrientation.SIDE) 8f * s else 0f
            fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
            canvas.drawCircle(-7f * s + eyeOffset, -4f * s, 5.2f * s, fill)
            if (orientation != CharacterOrientation.SIDE) {
                canvas.drawCircle(7f * s, -4f * s, 5.2f * s, fill)
            }
            fill.color = withAlpha(0xFF0F172A.toInt(), alphaInt)
            canvas.drawCircle(-6f * s + eyeOffset, -4f * s, 2.7f * s, fill)
            if (orientation != CharacterOrientation.SIDE) {
                canvas.drawCircle(8f * s, -4f * s, 2.7f * s, fill)
            }
            fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
            canvas.drawCircle(-5.2f * s + eyeOffset, -5.2f * s, 1.0f * s, fill)
            if (orientation != CharacterOrientation.SIDE) {
                canvas.drawCircle(8.8f * s, -5.2f * s, 1.0f * s, fill)
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

        // Four Tapered Organic Legs
        val legAngles = listOf(pose.leftLegDeg, pose.rightLegDeg, -pose.leftLegDeg * 0.8f, -pose.rightLegDeg * 0.8f)
        val legXs = listOf(-28f * s, -14f * s, 14f * s, 28f * s)
        for (i in legXs.indices) {
            canvas.save()
            canvas.translate(legXs[i], 14f * s)
            canvas.rotate(legAngles[i])
            val legPath = Path().apply {
                moveTo(-7.5f * s, 0f)
                lineTo(7.5f * s, 0f)
                lineTo(5.2f * s, 35f * s)
                quadTo(0f, 38f * s, -5.2f * s, 35f * s)
                close()
            }
            fill.color = withAlpha(if (i % 2 == 0) secondary else primary, alphaInt)
            canvas.drawPath(legPath, fill)
            canvas.drawPath(legPath, stroke)
            canvas.restore()
        }

        // Cel-Shaded Body
        fill.color = withAlpha(primary, alphaInt)
        val bodyRect = RectF(-42f * s, -22f * s, 40f * s, 24f * s)
        canvas.drawRoundRect(bodyRect, 20f * s, 20f * s, fill)
        fill.color = withAlpha(0x2AFFFFFF, alphaInt)
        canvas.drawOval(RectF(-30f * s, -18f * s, 14f * s, -6f * s), fill)
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
        canvas.drawCircle(-17f * s, -18f * s, 9f * s, stroke)
        canvas.drawCircle(17f * s, -18f * s, 9f * s, fill)
        canvas.drawCircle(17f * s, -18f * s, 9f * s, stroke)
        fill.color = withAlpha(belly, alphaInt)
        canvas.drawCircle(-17f * s, -18f * s, 4.5f * s, fill)
        canvas.drawCircle(17f * s, -18f * s, 4.5f * s, fill)

        // Cel-Shaded Head Circle
        fill.color = withAlpha(primary, alphaInt)
        canvas.drawCircle(0f, 0f, 23f * s, fill)
        fill.color = withAlpha(0x2A000000, alphaInt)
        canvas.drawArc(RectF(-23f * s, -23f * s, 23f * s, 23f * s), 20f, 145f, false, fill)
        canvas.drawCircle(0f, 0f, 23f * s, stroke)

        if (!isBack) {
            fill.color = withAlpha(belly, alphaInt)
            canvas.drawOval(RectF(-13f * s, 0f, 13f * s, 17f * s), fill)
            fill.color = withAlpha(accent, alphaInt)
            canvas.drawCircle(-8f * s, -5f * s, 3.5f * s, fill)
            canvas.drawCircle(8f * s, -5f * s, 3.5f * s, fill)
            fill.color = withAlpha(0xFFFFFFFF.toInt(), alphaInt)
            canvas.drawCircle(-7.2f * s, -6.1f * s, 1.1f * s, fill)
            canvas.drawCircle(8.8f * s, -6.1f * s, 1.1f * s, fill)
            // Nose
            fill.color = withAlpha(accent, alphaInt)
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
            AnimationPresetType.WALK -> {
                val stride = sin(rad)
                val foreLag = sin(rad - 0.55f)
                val doubleBounce = (1f - cos(rad * 2f)) * 0.5f // 0 at contact, 1 at passing peak
                PoseAngles(
                    bodyBobY = -doubleBounce * 6.5f,
                    bodyTiltDeg = 3.5f + stride * 1.4f,
                    squashX = 1.015f - doubleBounce * 0.025f,
                    squashY = 0.985f + doubleBounce * 0.025f,
                    headNodDeg = -doubleBounce * 2.2f,
                    leftArmDeg = stride * 30f,
                    rightArmDeg = -stride * 30f,
                    leftForearmDeg = -14f - foreLag * 10f,
                    rightForearmDeg = 14f - foreLag * 10f,
                    leftLegDeg = -stride * 34f,
                    rightLegDeg = stride * 34f,
                    leftKneeDeg = max(0f, sin(rad - 0.35f)) * 28f + 4f,
                    rightKneeDeg = max(0f, -sin(rad - 0.35f)) * 28f + 4f,
                    wingFlapDeg = stride * 28f,
                    wheelSpinDeg = phase * 360f
                )
            }
            AnimationPresetType.JOGGING -> {
                val stride = sin(rad)
                val foreLag = sin(rad - 0.45f)
                val doubleBounce = (1f - cos(rad * 2f)) * 0.5f
                PoseAngles(
                    bodyBobY = -doubleBounce * 11.5f,
                    bodyTiltDeg = 9f + stride * 2f,
                    squashX = 1.03f - doubleBounce * 0.05f,
                    squashY = 0.97f + doubleBounce * 0.05f,
                    headNodDeg = -4f - doubleBounce * 3f,
                    leftArmDeg = stride * 50f,
                    rightArmDeg = -stride * 50f,
                    leftForearmDeg = -48f - foreLag * 14f,
                    rightForearmDeg = 48f - foreLag * 14f,
                    leftLegDeg = -stride * 48f,
                    rightLegDeg = stride * 48f,
                    leftKneeDeg = 22f + max(0f, sin(rad - 0.3f)) * 38f,
                    rightKneeDeg = 22f + max(0f, -sin(rad - 0.3f)) * 38f,
                    wingFlapDeg = stride * 42f,
                    wheelSpinDeg = phase * 720f
                )
            }
            AnimationPresetType.RUNNING -> {
                val stride = sin(rad)
                val foreLag = sin(rad - 0.40f)
                val doubleBounce = (1f - cos(rad * 2f)) * 0.5f
                PoseAngles(
                    bodyBobY = -doubleBounce * 16f,
                    bodyTiltDeg = 16f + stride * 2.5f,
                    squashX = 1.045f - doubleBounce * 0.08f,
                    squashY = 0.955f + doubleBounce * 0.08f,
                    headNodDeg = -7f - doubleBounce * 3.5f,
                    leftArmDeg = stride * 70f,
                    rightArmDeg = -stride * 70f,
                    leftForearmDeg = -66f - foreLag * 18f,
                    rightForearmDeg = 66f - foreLag * 18f,
                    leftLegDeg = -stride * 64f,
                    rightLegDeg = stride * 64f,
                    leftKneeDeg = 32f + max(0f, sin(rad - 0.25f)) * 46f,
                    rightKneeDeg = 32f + max(0f, -sin(rad - 0.25f)) * 46f,
                    wingFlapDeg = stride * 55f,
                    wheelSpinDeg = phase * 1080f
                )
            }
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
            AnimationPresetType.TALKING -> {
                val speechEnv = (0.5f + 0.5f * sin(rad * 3f)) * (0.65f + 0.35f * sin(rad * 5f + 0.7f))
                PoseAngles(
                    bodyBobY = s1 * 2.2f,
                    headNodDeg = s2 * 5.5f + s1 * 2f,
                    leftArmDeg = 22f + s1 * 16f,
                    rightArmDeg = -34f - c1 * 20f,
                    leftForearmDeg = -32f + s1 * 12f,
                    rightForearmDeg = 42f - s2 * 16f,
                    mouthOpen = speechEnv.coerceIn(0f, 1f),
                    happySmile = true,
                    wingFlapDeg = abs(s2) * 18f
                )
            }
            AnimationPresetType.WAVING -> PoseAngles(
                bodyBobY = s1 * 3f,
                headNodDeg = -6f + s1 * 4f,
                leftArmDeg = 14f,
                rightArmDeg = -142f + s2 * 18f,
                rightForearmDeg = 26f + s2 * 24f,
                happySmile = true,
                blinkClosed = blink,
                wingFlapDeg = 35f + s2 * 20f
            )
            AnimationPresetType.JUMPING -> {
                // Smooth C1-continuous 3-phase jump arc (zero 1-frame snap/pop!):
                // 0.00..0.22 anticipation crouch -> 0.22..0.80 airborne parabolic arc -> 0.80..1.00 cushioned landing recovery
                val pClamped = ((phase % 1f) + 1f) % 1f
                val crouchEnv = when {
                    pClamped < 0.22f -> (0.5f - 0.5f * cos((pClamped / 0.22f) * 2.0 * PI)).toFloat()
                    pClamped > 0.80f -> (0.5f - 0.5f * cos(((pClamped - 0.80f) / 0.20f) * 2.0 * PI)).toFloat()
                    else -> 0f
                }
                val airEnv = if (pClamped in 0.22f..0.80f) {
                    val uAir = (pClamped - 0.22f) / 0.58f
                    sin(uAir * PI).toFloat()
                } else 0f
                val stretchEnv = if (pClamped in 0.20f..0.82f) {
                    val uAir = ((pClamped - 0.20f) / 0.62f).coerceIn(0f, 1f)
                    sin(uAir * PI).toFloat()
                } else 0f

                PoseAngles(
                    bodyBobY = crouchEnv * 14f - airEnv * 46f,
                    squashX = 1f + crouchEnv * 0.12f - stretchEnv * 0.07f,
                    squashY = 1f - crouchEnv * 0.12f + stretchEnv * 0.08f,
                    headNodDeg = crouchEnv * 6f - airEnv * 8f,
                    leftArmDeg = 18f + crouchEnv * 14f + airEnv * 120f,
                    rightArmDeg = -18f - crouchEnv * 14f - airEnv * 120f,
                    leftForearmDeg = -18f - crouchEnv * 20f + airEnv * 8f,
                    rightForearmDeg = 18f + crouchEnv * 20f - airEnv * 8f,
                    leftLegDeg = -crouchEnv * 28f + airEnv * 16f,
                    rightLegDeg = -crouchEnv * 24f - airEnv * 14f,
                    leftKneeDeg = 8f + crouchEnv * 46f + airEnv * 26f,
                    rightKneeDeg = 8f + crouchEnv * 46f + airEnv * 22f,
                    happySmile = true,
                    wingFlapDeg = airEnv * 54f
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
            AnimationPresetType.FIGHTING -> {
                // Alternating punch-jab combo with athletic guard bounce
                val jabL = max(0f, s1)
                val jabR = max(0f, -s1)
                PoseAngles(
                    bodyBobY = -abs(s2) * 6f + 6f,
                    bodyTiltDeg = 10f + (jabR - jabL) * 10f,
                    headNodDeg = -6f + s2 * 3f,
                    leftArmDeg = 46f + jabL * 62f,
                    leftForearmDeg = -78f + jabL * 68f,
                    rightArmDeg = -52f - jabR * 64f,
                    rightForearmDeg = 82f - jabR * 70f,
                    leftLegDeg = -22f - jabR * 8f,
                    rightLegDeg = 26f + jabL * 8f,
                    leftKneeDeg = 22f + abs(s1) * 12f,
                    rightKneeDeg = 24f + abs(c1) * 12f,
                    mouthOpen = if (jabL > 0.65f || jabR > 0.65f) 0.45f else 0.08f,
                    wingFlapDeg = 24f + abs(s1) * 32f
                )
            }
            AnimationPresetType.FLYING -> PoseAngles(
                bodyBobY = -28f + s1 * 12f,
                bodyTiltDeg = 22f + c1 * 5f,
                headNodDeg = -14f + s1 * 4f,
                squashX = 0.96f,
                squashY = 1.05f,
                leftArmDeg = 148f + s1 * 10f,
                leftForearmDeg = -14f + c1 * 8f,
                rightArmDeg = -42f + c1 * 12f,
                rightForearmDeg = 18f - s1 * 8f,
                leftLegDeg = -16f + s1 * 10f,
                rightLegDeg = -6f - s1 * 8f,
                leftKneeDeg = 14f + c1 * 8f,
                rightKneeDeg = 28f - c1 * 8f,
                happySmile = true,
                wingFlapDeg = 18f + s2 * 48f
            )
            AnimationPresetType.SLEEPING -> PoseAngles(
                bodyBobY = 16f + s1 * 3.5f,
                bodyTiltDeg = 7f + s1 * 2.5f,
                headNodDeg = 22f + s1 * 5f,
                squashX = 1.03f + s1 * 0.025f,
                squashY = 0.96f - s1 * 0.025f,
                leftArmDeg = 12f + s1 * 3f,
                leftForearmDeg = -28f,
                rightArmDeg = -12f - s1 * 3f,
                rightForearmDeg = 28f,
                leftLegDeg = -12f,
                rightLegDeg = 12f,
                leftKneeDeg = 22f,
                rightKneeDeg = 22f,
                mouthOpen = (0.14f + max(0f, s1) * 0.22f),
                blinkClosed = true,
                wingFlapDeg = -12f + s1 * 3f
            )
            AnimationPresetType.CLAPPING -> {
                val clap = abs(s2)
                PoseAngles(
                    bodyBobY = -clap * 7f,
                    bodyTiltDeg = s1 * 4f,
                    headNodDeg = -clap * 6f,
                    leftArmDeg = 46f + (1f - clap) * 18f,
                    leftForearmDeg = -34f - clap * 44f,
                    rightArmDeg = -46f - (1f - clap) * 18f,
                    rightForearmDeg = 34f + clap * 44f,
                    leftLegDeg = -8f + s1 * 5f,
                    rightLegDeg = 8f - s1 * 5f,
                    happySmile = true,
                    wingFlapDeg = 12f + clap * 26f
                )
            }
            AnimationPresetType.THINKING -> PoseAngles(
                bodyBobY = s1 * 2.5f,
                bodyTiltDeg = -5f + s1 * 2.5f,
                headNodDeg = -9f + c1 * 6f,
                leftArmDeg = 18f,
                leftForearmDeg = -48f,
                rightArmDeg = -64f + s1 * 5f,
                rightForearmDeg = 118f + c1 * 6f,
                leftLegDeg = -9f,
                rightLegDeg = 11f,
                leftKneeDeg = 4f,
                rightKneeDeg = 8f,
                blinkClosed = phase in 0.72f..0.77f,
                wingFlapDeg = 4f + s1 * 4f
            )
        }
    }

    private val colorParseCache = java.util.concurrent.ConcurrentHashMap<String, Int>(64)

    fun parseColorSafe(hex: String, fallback: Int): Int {
        colorParseCache[hex]?.let { return it }
        return try {
            val parsed = Color.parseColor(hex)
            if (colorParseCache.size > 512) {
                colorParseCache.clear()
            }
            colorParseCache[hex] = parsed
            parsed
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
