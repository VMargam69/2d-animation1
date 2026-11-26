package com.example.engine

import android.graphics.*
import com.example.model.*

/**
 * Hierarchical 2D bone rig renderer for Custom Character Maker Sprite Atlases.
 *
 * Bone Hierarchy:
 *   body root -> head -> eyes / eyebrows / lips / hair_front / hair_back / accessory
 *   body root -> left/right shoulders -> elbows -> wrists -> hand poses / prop hands
 *   body root -> left/right hips -> knees -> ankles
 *
 * Strict Z-Order:
 *   1. Ground contact shadow
 *   2. hair_back
 *   3. Back limbs (far arm + back leg)
 *   4. chest_body (body root)
 *   5. Front leg
 *   6. head -> eye_white -> eyeball -> eyelid -> eyebrow -> lip/mouth
 *   7. hair_front & accessory
 *   8. Front arm + hand pose + attached prop
 *
 * Zero bitmap decoding or slicing happens inside this renderer; all bitmaps are pre-cached O(1) lookups.
 */
object SpriteAtlasRigRenderer {

    private class DrawScratch {
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val guideFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val dst = RectF()
    }

    private val scratchLocal = ThreadLocal.withInitial { DrawScratch() }

    internal fun drawSpriteAtlasRig(
        canvas: Canvas,
        slices: Map<AtlasPartSlot, Bitmap>,
        poseTemplate: AtlasPoseTemplate,
        pose: ProceduralRigRenderer.PoseAngles,
        rigScale: Float,
        alpha: Float = 1f,
        tintOverlayColor: Int? = null,
        handPose: HandPoseType = HandPoseType.OPEN,
        blinkOverride: Boolean? = null,
        mouthSlotOverride: AtlasPartSlot? = null,
        propType: CharacterPropType = CharacterPropType.NONE,
        propAttachment: PropAttachmentPoint = PropAttachmentPoint.RIGHT_HAND,
        customPropBitmap: Bitmap? = null,
        propScale: Float = 1.0f,
        drawGhostFallbackWhenEmpty: Boolean = false,
        skipFaceOverlays: Boolean = false
    ) {
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

        val isBackSide = poseTemplate == AtlasPoseTemplate.BACK_SIDE
        val isThreeQuarter = poseTemplate == AtlasPoseTemplate.THREE_QUARTER_FRONT
        val shoulderSpread = when (poseTemplate) {
            AtlasPoseTemplate.FRONT -> 29f * s
            AtlasPoseTemplate.THREE_QUARTER_FRONT -> 24f * s
            AtlasPoseTemplate.BACK_SIDE -> 11f * s
        }
        val hipSpread = when (poseTemplate) {
            AtlasPoseTemplate.FRONT -> 15f * s
            AtlasPoseTemplate.THREE_QUARTER_FRONT -> 13f * s
            AtlasPoseTemplate.BACK_SIDE -> 7f * s
        }
        val faceShiftX = when (poseTemplate) {
            AtlasPoseTemplate.FRONT -> 0f
            AtlasPoseTemplate.THREE_QUARTER_FRONT -> 4.5f * s
            AtlasPoseTemplate.BACK_SIDE -> 9.0f * s
        }

        val isBlinking = blinkOverride ?: pose.blinkClosed
        val hasAnyPart = slices.isNotEmpty()
        val showGuide = drawGhostFallbackWhenEmpty && !hasAnyPart

        // 1. Ground contact shadow
        scratch.shadowPaint.color = Color.argb((alphaInt * 0.28f).toInt(), 0, 0, 0)
        canvas.drawOval(RectF(-34f * s, 77f * s, 34f * s, 90f * s), scratch.shadowPaint)

        // 2. HAIR_BACK (attached to head neck pivot so hair rotates cleanly with head)
        val hairBackBmp = slices[AtlasPartSlot.HAIR_BACK]
        if (hairBackBmp != null && !hairBackBmp.isRecycled) {
            canvas.save()
            canvas.translate(0f, -36f * s)
            canvas.rotate(pose.headNodDeg)
            drawBitmapAnchored(canvas, hairBackBmp, 74f * s, 74f * s, 0.50f, 0.78f, paint, scratch.dst)
            canvas.restore()
        }

        // 3. Back Limbs: Far Arm (Left) + Back Leg (Left)
        drawArmChain(
            canvas = canvas,
            originX = -shoulderSpread,
            originY = -28f * s,
            upperDeg = pose.leftArmDeg,
            lowerDeg = pose.leftForearmDeg,
            upperBmp = slices[AtlasPartSlot.L_UPPER_ARM],
            lowerBmp = slices[AtlasPartSlot.L_LOWER_ARM],
            handBmp = resolveHandBitmap(slices, handPose, isLeft = true),
            s = s,
            paint = paint,
            scratch = scratch,
            alphaInt = alphaInt,
            showGuide = showGuide,
            propType = if (propAttachment == PropAttachmentPoint.LEFT_HAND) propType else CharacterPropType.NONE,
            customPropBitmap = customPropBitmap,
            propScale = propScale,
            swayDeg = pose.propSwayDeg
        )

        drawLegChain(
            canvas = canvas,
            originX = -hipSpread,
            originY = 18f * s,
            upperDeg = pose.leftLegDeg,
            lowerDeg = pose.leftKneeDeg,
            upperBmp = slices[AtlasPartSlot.L_UPPER_LEG],
            lowerBmp = slices[AtlasPartSlot.L_LOWER_LEG],
            s = s,
            paint = paint,
            scratch = scratch,
            alphaInt = alphaInt,
            showGuide = showGuide
        )

        // 4. Front Leg (Right) - drawn behind lower hem of torso or layered smoothly
        drawLegChain(
            canvas = canvas,
            originX = hipSpread,
            originY = 18f * s,
            upperDeg = pose.rightLegDeg,
            lowerDeg = pose.rightKneeDeg,
            upperBmp = slices[AtlasPartSlot.R_UPPER_LEG],
            lowerBmp = slices[AtlasPartSlot.R_LOWER_LEG],
            s = s,
            paint = paint,
            scratch = scratch,
            alphaInt = alphaInt,
            showGuide = showGuide
        )

        // 5. CHEST_BODY (body root)
        val bodyBmp = slices[AtlasPartSlot.CHEST_BODY]
        if (bodyBmp != null && !bodyBmp.isRecycled) {
            val bodyW = if (isBackSide) 52f * s else 66f * s
            drawBitmapAnchored(canvas, bodyBmp, bodyW, 66f * s, 0.50f, 0.56f, paint, scratch.dst)
        } else if (showGuide) {
            drawGhostCapsule(canvas, 0f, -6f * s, if (isBackSide) 36f * s else 50f * s, 58f * s, 0x4438BDF8, alphaInt, scratch)
        }

        // 6. HEAD + Facial Feature Stack + HAIR_FRONT + ACCESSORY
        // Anchored directly to CHEST_BODY's top-neck pivot (-36f * s) so chin/neck never detaches from torso
        canvas.save()
        canvas.translate(0f, -36f * s)
        canvas.rotate(pose.headNodDeg)

        val headBmp = slices[AtlasPartSlot.HEAD]
        if (headBmp != null && !headBmp.isRecycled) {
            drawBitmapAnchored(canvas, headBmp, 64f * s, 68f * s, 0.50f, 0.86f, paint, scratch.dst)
        } else if (showGuide) {
            drawGhostHead(canvas, s, alphaInt, scratch)
        }

        if (!skipFaceOverlays) {
            // Eyes (Eye White -> Eyeball -> Eyelid / Blink) — only draw if at least one eye slice exists
            val eyeWhite = slices[AtlasPartSlot.EYE_WHITE]?.takeIf { !it.isRecycled }
            val eyeball = slices[AtlasPartSlot.EYEBALL]?.takeIf { !it.isRecycled }
            val eyelid = slices[AtlasPartSlot.EYELID]?.takeIf { !it.isRecycled }
            val hasAnyEyeSlice = eyeWhite != null || eyeball != null || eyelid != null
            val eyeY = -27f * s
            val eyeOffsets = if (isBackSide) {
                listOf(faceShiftX)
            } else if (isThreeQuarter) {
                listOf(-8f * s + faceShiftX, 11f * s + faceShiftX)
            } else {
                listOf(-11f * s, 11f * s)
            }

            if (hasAnyEyeSlice) {
                for (ex in eyeOffsets) {
                    canvas.save()
                    canvas.translate(ex, eyeY)
                    if (isBlinking) {
                        if (eyelid != null) {
                            drawBitmapAnchored(canvas, eyelid, 16f * s, 9f * s, 0.50f, 0.50f, paint, scratch.dst)
                        } else if (eyeWhite != null || eyeball != null) {
                            // Clean blink line when eyes exist but eyelid slice wasn't provided
                            scratch.guideStroke.color = Color.argb(alphaInt, 24, 24, 36)
                            scratch.guideStroke.strokeWidth = 2.4f * s
                            canvas.drawLine(-6f * s, 0f, 6f * s, 0f, scratch.guideStroke)
                        }
                    } else {
                        if (eyeWhite != null) {
                            drawBitmapAnchored(canvas, eyeWhite, 15f * s, 12f * s, 0.50f, 0.50f, paint, scratch.dst)
                        }
                        if (eyeball != null) {
                            val lookX = if (isThreeQuarter || isBackSide) 1.5f * s else 0f
                            canvas.save()
                            canvas.translate(lookX, 0f)
                            drawBitmapAnchored(canvas, eyeball, 9.5f * s, 9.5f * s, 0.50f, 0.50f, paint, scratch.dst)
                            canvas.restore()
                        }
                        if (eyelid != null) {
                            canvas.save()
                            canvas.translate(0f, -4.5f * s)
                            drawBitmapAnchored(canvas, eyelid, 16f * s, 7f * s, 0.50f, 0.50f, paint, scratch.dst)
                            canvas.restore()
                        }
                    }
                    canvas.restore()
                }
            }

            // Eyebrow Expression Swap (Happy / Angry / Idle / Scared)
            val browBmp = resolveEyebrowBitmap(slices, pose.facialExpression, pose.happySmile)
            if (browBmp != null && !browBmp.isRecycled) {
                val browY = -37f * s
                if (isBackSide) {
                    canvas.save()
                    canvas.translate(faceShiftX, browY)
                    drawBitmapAnchored(canvas, browBmp, 16f * s, 8f * s, 0.50f, 0.50f, paint, scratch.dst)
                    canvas.restore()
                } else {
                    canvas.save()
                    canvas.translate(eyeOffsets.first(), browY)
                    drawBitmapAnchored(canvas, browBmp, 16f * s, 8f * s, 0.50f, 0.50f, paint, scratch.dst)
                    canvas.restore()

                    canvas.save()
                    canvas.translate(eyeOffsets.last(), browY)
                    canvas.scale(-1f, 1f)
                    drawBitmapAnchored(canvas, browBmp, 16f * s, 8f * s, 0.50f, 0.50f, paint, scratch.dst)
                    canvas.restore()
                }
            }

            // Mouth / Lips Expression & Talking Lip-Sync (MTH1..MTH4 + Shocked)
            val mouthBmp = resolveMouthBitmap(slices, pose.facialExpression, pose.mouthOpen, pose.happySmile, mouthSlotOverride)
            if (mouthBmp != null && !mouthBmp.isRecycled) {
                canvas.save()
                canvas.translate(faceShiftX * 0.85f, -11.5f * s)
                val talkScaleY = if (mouthSlotOverride == null && pose.mouthOpen > 0.15f) {
                    1f + pose.mouthOpen * 0.35f
                } else 1f
                canvas.scale(1f, talkScaleY)
                drawBitmapAnchored(canvas, mouthBmp, 20f * s, 13f * s, 0.50f, 0.50f, paint, scratch.dst)
                canvas.restore()
            }
        }

        // 7. HAIR_FRONT & ACCESSORY (on top of head & face)
        val hairFrontBmp = slices[AtlasPartSlot.HAIR_FRONT]
        if (hairFrontBmp != null && !hairFrontBmp.isRecycled) {
            canvas.save()
            canvas.translate(0f, -37f * s)
            drawBitmapAnchored(canvas, hairFrontBmp, 72f * s, 54f * s, 0.50f, 0.50f, paint, scratch.dst)
            canvas.restore()
        }

        val accBmp = slices[AtlasPartSlot.ACCESSORY]
        if (accBmp != null && !accBmp.isRecycled) {
            canvas.save()
            canvas.translate(0f, -49f * s)
            drawBitmapAnchored(canvas, accBmp, 64f * s, 44f * s, 0.50f, 0.72f, paint, scratch.dst)
            canvas.restore()
        }

        // Head-socket Stage Prop
        if (propType != CharacterPropType.NONE && propAttachment == PropAttachmentPoint.HEAD) {
            canvas.save()
            canvas.translate(0f, -51f * s)
            PropAndModularChartRenderer.drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, pose.propSwayDeg * 0.5f, alphaInt
            )
            canvas.restore()
        }

        canvas.restore() // restore head bone

        // 8. Front Arm (Right) -> Elbow -> Wrist -> Hand Pose / Prop Hand
        drawArmChain(
            canvas = canvas,
            originX = shoulderSpread,
            originY = -28f * s,
            upperDeg = pose.rightArmDeg,
            lowerDeg = pose.rightForearmDeg,
            upperBmp = slices[AtlasPartSlot.R_UPPER_ARM],
            lowerBmp = slices[AtlasPartSlot.R_LOWER_ARM],
            handBmp = resolveHandBitmap(slices, handPose, isLeft = false),
            s = s,
            paint = paint,
            scratch = scratch,
            alphaInt = alphaInt,
            showGuide = showGuide,
            propType = if (propAttachment == PropAttachmentPoint.RIGHT_HAND) propType else CharacterPropType.NONE,
            customPropBitmap = customPropBitmap,
            propScale = propScale,
            swayDeg = pose.propSwayDeg
        )

        paint.colorFilter = null
    }

    private fun drawArmChain(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        upperDeg: Float,
        lowerDeg: Float,
        upperBmp: Bitmap?,
        lowerBmp: Bitmap?,
        handBmp: Bitmap?,
        s: Float,
        paint: Paint,
        scratch: DrawScratch,
        alphaInt: Int,
        showGuide: Boolean,
        propType: CharacterPropType,
        customPropBitmap: Bitmap?,
        propScale: Float,
        swayDeg: Float
    ) {
        val upperLen = 28f * s
        val lowerLen = 25f * s

        canvas.save()
        canvas.translate(originX, originY)
        canvas.rotate(upperDeg)

        if (upperBmp != null && !upperBmp.isRecycled) {
            drawBitmapAnchored(canvas, upperBmp, 22f * s, upperLen * 1.25f, 0.50f, 0.14f, paint, scratch.dst)
        } else if (showGuide) {
            drawGhostCapsule(canvas, 0f, upperLen * 0.5f, 12f * s, upperLen, 0x35F59E0B, alphaInt, scratch)
        }

        canvas.translate(0f, upperLen)
        canvas.rotate(lowerDeg)

        if (lowerBmp != null && !lowerBmp.isRecycled) {
            drawBitmapAnchored(canvas, lowerBmp, 20f * s, lowerLen * 1.22f, 0.50f, 0.14f, paint, scratch.dst)
        } else if (showGuide) {
            drawGhostCapsule(canvas, 0f, lowerLen * 0.5f, 10f * s, lowerLen, 0x35FBBF24, alphaInt, scratch)
        }

        canvas.translate(0f, lowerLen)
        if (handBmp != null && !handBmp.isRecycled) {
            drawBitmapAnchored(canvas, handBmp, 22f * s, 22f * s, 0.50f, 0.18f, paint, scratch.dst)
        } else if (showGuide) {
            scratch.guideFill.color = Color.argb((alphaInt * 0.35f).toInt(), 45, 212, 191)
            canvas.drawCircle(0f, 4f * s, 6.5f * s, scratch.guideFill)
        }

        if (propType != CharacterPropType.NONE) {
            PropAndModularChartRenderer.drawPropAtSocket(
                canvas, propType, customPropBitmap, s * propScale, swayDeg, alphaInt
            )
        }
        canvas.restore()
    }

    private fun drawLegChain(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        upperDeg: Float,
        lowerDeg: Float,
        upperBmp: Bitmap?,
        lowerBmp: Bitmap?,
        s: Float,
        paint: Paint,
        scratch: DrawScratch,
        alphaInt: Int,
        showGuide: Boolean
    ) {
        val upperLen = 33f * s
        val lowerLen = 34f * s

        canvas.save()
        canvas.translate(originX, originY)
        canvas.rotate(upperDeg)

        if (upperBmp != null && !upperBmp.isRecycled) {
            drawBitmapAnchored(canvas, upperBmp, 26f * s, upperLen * 1.25f, 0.50f, 0.14f, paint, scratch.dst)
        } else if (showGuide) {
            drawGhostCapsule(canvas, 0f, upperLen * 0.5f, 14f * s, upperLen, 0x358B5CF6, alphaInt, scratch)
        }

        canvas.translate(0f, upperLen)
        canvas.rotate(lowerDeg)

        if (lowerBmp != null && !lowerBmp.isRecycled) {
            drawBitmapAnchored(canvas, lowerBmp, 24f * s, lowerLen * 1.25f, 0.50f, 0.14f, paint, scratch.dst)
        } else if (showGuide) {
            drawGhostCapsule(canvas, 0f, lowerLen * 0.5f, 13f * s, lowerLen, 0x35A78BFA, alphaInt, scratch)
        }
        canvas.restore()
    }

    private fun resolveHandBitmap(
        slices: Map<AtlasPartSlot, Bitmap>,
        handPose: HandPoseType,
        isLeft: Boolean
    ): Bitmap? {
        val primary = when (handPose) {
            HandPoseType.OPEN -> AtlasPartSlot.HAND_OPEN
            HandPoseType.FIST -> AtlasPartSlot.HAND_FIST
            HandPoseType.POINT -> AtlasPartSlot.HAND_POINT
            HandPoseType.PROP_GRIP -> if (isLeft) AtlasPartSlot.PROP_HAND_L else AtlasPartSlot.PROP_HAND_R
        }
        return slices[primary]
            ?: slices[AtlasPartSlot.HAND_OPEN]
            ?: slices[AtlasPartSlot.HAND_FIST]
            ?: slices[AtlasPartSlot.PROP_HAND_R]
            ?: slices[AtlasPartSlot.PROP_HAND_L]
    }

    private fun resolveEyebrowBitmap(
        slices: Map<AtlasPartSlot, Bitmap>,
        expression: FacialExpression,
        happySmile: Boolean
    ): Bitmap? {
        val target = when (expression) {
            FacialExpression.HAPPY, FacialExpression.LAUGHING -> AtlasPartSlot.BROW_HAPPY
            FacialExpression.ANGRY -> AtlasPartSlot.BROW_ANGRY
            FacialExpression.SAD, FacialExpression.SHOCKED -> AtlasPartSlot.BROW_SCARED
            FacialExpression.AUTO -> if (happySmile) AtlasPartSlot.BROW_HAPPY else AtlasPartSlot.BROW_IDLE
            else -> AtlasPartSlot.BROW_IDLE
        }
        return slices[target] ?: slices[AtlasPartSlot.BROW_IDLE] ?: slices[AtlasPartSlot.BROW_HAPPY]
    }

    private fun resolveMouthBitmap(
        slices: Map<AtlasPartSlot, Bitmap>,
        expression: FacialExpression,
        mouthOpen: Float,
        happySmile: Boolean,
        mouthSlotOverride: AtlasPartSlot?
    ): Bitmap? {
        if (mouthSlotOverride != null) {
            return slices[mouthSlotOverride] ?: slices[AtlasPartSlot.LIP_IDLE]
        }
        val target = when {
            expression == FacialExpression.SHOCKED -> AtlasPartSlot.LIP_SHOCKED
            expression == FacialExpression.ANGRY -> AtlasPartSlot.LIP_ANGRY
            expression == FacialExpression.SAD -> AtlasPartSlot.LIP_SCARED
            expression == FacialExpression.HAPPY || expression == FacialExpression.LAUGHING -> AtlasPartSlot.LIP_HAPPY
            expression == FacialExpression.TALKING || mouthOpen > 0.25f -> {
                when {
                    mouthOpen > 0.72f -> AtlasPartSlot.LIP_SHOCKED
                    mouthOpen > 0.45f -> AtlasPartSlot.LIP_HAPPY
                    else -> AtlasPartSlot.LIP_SCARED
                }
            }
            happySmile -> AtlasPartSlot.LIP_HAPPY
            else -> AtlasPartSlot.LIP_IDLE
        }
        return slices[target]
            ?: slices[AtlasPartSlot.LIP_IDLE]
            ?: slices[AtlasPartSlot.LIP_HAPPY]
            ?: slices[AtlasPartSlot.LIP_SHOCKED]
    }

    private fun drawBitmapAnchored(
        canvas: Canvas,
        bmp: Bitmap,
        boxW: Float,
        boxH: Float,
        pivotU: Float,
        pivotV: Float,
        paint: Paint,
        dst: RectF
    ) {
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
        val boxAspect = boxW / boxH.coerceAtLeast(1f)
        val drawW: Float
        val drawH: Float
        if (aspect > boxAspect) {
            drawW = boxW
            drawH = boxW / aspect
        } else {
            drawH = boxH
            drawW = boxH * aspect
        }
        val left = -drawW * pivotU
        val top = -drawH * pivotV
        dst.set(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun drawGhostCapsule(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        color: Int,
        alphaInt: Int,
        scratch: DrawScratch
    ) {
        scratch.guideFill.color = Color.argb((Color.alpha(color) * alphaInt) / 255, Color.red(color), Color.green(color), Color.blue(color))
        scratch.guideStroke.color = Color.argb((alphaInt * 0.45f).toInt(), 148, 163, 184)
        scratch.guideStroke.strokeWidth = 1.6f
        scratch.dst.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
        val rad = minOf(w, h) * 0.42f
        canvas.drawRoundRect(scratch.dst, rad, rad, scratch.guideFill)
        canvas.drawRoundRect(scratch.dst, rad, rad, scratch.guideStroke)
    }

    private fun drawGhostHead(
        canvas: Canvas,
        s: Float,
        alphaInt: Int,
        scratch: DrawScratch
    ) {
        scratch.guideFill.color = Color.argb((alphaInt * 0.25f).toInt(), 56, 189, 248)
        scratch.guideStroke.color = Color.argb((alphaInt * 0.55f).toInt(), 148, 163, 184)
        scratch.guideStroke.strokeWidth = 1.8f * s
        canvas.drawCircle(0f, -25f * s, 23f * s, scratch.guideFill)
        canvas.drawCircle(0f, -25f * s, 23f * s, scratch.guideStroke)
    }
}
