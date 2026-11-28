package com.example.engine

import com.example.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Physical secondary-motion state produced by [PropSwayPhysicsEngine] for a character's
 * attached hand/head prop and built-in dynamic accessories (cape, scarf, plumes).
 */
data class PropSwayState(
    val angleDeg: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val accessoryFlutterDeg: Float = 0f
) {
    companion object {
        val ZERO = PropSwayState()
    }
}

/**
 * Deterministic, zero-allocation Spring-Pendulum Sway Physics Engine for Hand & Head Props
 * and built-in character accessories.
 *
 * Uses a semi-implicit Euler damped harmonic oscillator integrated over a trailing frame window
 * up to [frame]. Because every input comes purely from keyframe transforms, rig pose angles,
 * and optional live drag deltas, Live Preview and MP4/GIF/PNG Exports match frame-for-frame.
 */
object PropSwayPhysicsEngine {

    private data class PropPhysicsProfile(
        val stiffness: Float,
        val damping: Float,
        val inertiaScale: Float,
        val gravityPendulum: Float,
        val maxAngleDeg: Float,
        val lagOffsetScale: Float,
        val idleBreathAmp: Float
    )

    private fun profileFor(propType: CharacterPropType, attachment: PropAttachmentPoint): PropPhysicsProfile {
        val base = when (propType) {
            CharacterPropType.LANTERN -> PropPhysicsProfile(
                stiffness = 28f,
                damping = 4.2f,
                inertiaScale = 1.45f,
                gravityPendulum = 0.78f,
                maxAngleDeg = 54f,
                lagOffsetScale = 3.2f,
                idleBreathAmp = 2.6f
            )
            CharacterPropType.FLOWER -> PropPhysicsProfile(
                stiffness = 74f,
                damping = 5.4f,
                inertiaScale = 1.25f,
                gravityPendulum = 0.08f,
                maxAngleDeg = 40f,
                lagOffsetScale = 2.4f,
                idleBreathAmp = 2.2f
            )
            CharacterPropType.MAGIC_WAND -> PropPhysicsProfile(
                stiffness = 68f,
                damping = 5.8f,
                inertiaScale = 1.22f,
                gravityPendulum = 0.05f,
                maxAngleDeg = 38f,
                lagOffsetScale = 2.2f,
                idleBreathAmp = 1.8f
            )
            CharacterPropType.UMBRELLA -> PropPhysicsProfile(
                stiffness = 38f,
                damping = 5.5f,
                inertiaScale = 1.38f,
                gravityPendulum = 0.16f,
                maxAngleDeg = 36f,
                lagOffsetScale = 2.6f,
                idleBreathAmp = 1.6f
            )
            CharacterPropType.SWORD -> PropPhysicsProfile(
                stiffness = 46f,
                damping = 6.8f,
                inertiaScale = 0.92f,
                gravityPendulum = 0.04f,
                maxAngleDeg = 32f,
                lagOffsetScale = 1.6f,
                idleBreathAmp = 1.1f
            )
            CharacterPropType.SHIELD -> PropPhysicsProfile(
                stiffness = 52f,
                damping = 8.4f,
                inertiaScale = 0.65f,
                gravityPendulum = 0.03f,
                maxAngleDeg = 22f,
                lagOffsetScale = 1.1f,
                idleBreathAmp = 0.8f
            )
            CharacterPropType.MICROPHONE -> PropPhysicsProfile(
                stiffness = 60f,
                damping = 6.5f,
                inertiaScale = 1.05f,
                gravityPendulum = 0.05f,
                maxAngleDeg = 30f,
                lagOffsetScale = 1.8f,
                idleBreathAmp = 1.4f
            )
            CharacterPropType.COFFEE_MUG -> PropPhysicsProfile(
                stiffness = 44f,
                damping = 6.0f,
                inertiaScale = 1.0f,
                gravityPendulum = 0.35f,
                maxAngleDeg = 28f,
                lagOffsetScale = 1.7f,
                idleBreathAmp = 1.0f
            )
            CharacterPropType.BOOK -> PropPhysicsProfile(
                stiffness = 54f,
                damping = 7.4f,
                inertiaScale = 0.85f,
                gravityPendulum = 0.06f,
                maxAngleDeg = 24f,
                lagOffsetScale = 1.4f,
                idleBreathAmp = 0.9f
            )
            CharacterPropType.SMARTPHONE -> PropPhysicsProfile(
                stiffness = 62f,
                damping = 7.6f,
                inertiaScale = 0.88f,
                gravityPendulum = 0.04f,
                maxAngleDeg = 24f,
                lagOffsetScale = 1.3f,
                idleBreathAmp = 0.9f
            )
            CharacterPropType.CUSTOM_PNG -> PropPhysicsProfile(
                stiffness = 50f,
                damping = 5.8f,
                inertiaScale = 1.15f,
                gravityPendulum = 0.14f,
                maxAngleDeg = 36f,
                lagOffsetScale = 2.2f,
                idleBreathAmp = 1.7f
            )
            CharacterPropType.NONE -> PropPhysicsProfile(
                stiffness = 42f,
                damping = 5.2f,
                inertiaScale = 1.0f,
                gravityPendulum = 0.12f,
                maxAngleDeg = 28f,
                lagOffsetScale = 1.5f,
                idleBreathAmp = 1.4f
            )
        }
        // Head-mounted props experience slightly tighter damping so hats/flowers bounce cleanly without flipping
        return if (attachment == PropAttachmentPoint.HEAD) {
            base.copy(
                stiffness = base.stiffness * 1.14f,
                damping = base.damping * 1.12f,
                maxAngleDeg = (base.maxAngleDeg * 0.82f).coerceAtMost(38f)
            )
        } else {
            base
        }
    }

    /**
     * Evaluates deterministic spring-pendulum sway physics for a character clip at [frame].
     */
    fun evaluatePropSway(
        clip: TimelineClip,
        rig: CharacterRigDefinition?,
        frame: Int,
        fps: Int,
        gesturePreview: StageGesturePreview? = null
    ): PropSwayState {
        if (!clip.propSwayEnabled) return PropSwayState.ZERO
        val strength = clip.propSwayStrength.coerceIn(0f, 2f)
        if (strength <= 0.005f) return PropSwayState.ZERO

        val hasProp = clip.propType != CharacterPropType.NONE
        val hasDynamicAccessory = rig != null && (
            rig.accessory == CharacterAccessory.CAPE ||
                rig.accessory == CharacterAccessory.SCARF ||
                rig.accessory == CharacterAccessory.HAT ||
                rig.accessory == CharacterAccessory.CROWN ||
                rig.accessory == CharacterAccessory.TURBAN ||
                rig.accessory == CharacterAccessory.HALO
            )
        if (!hasProp && !hasDynamicAccessory) return PropSwayState.ZERO

        val safeFps = fps.coerceIn(12, 60).toFloat()
        val dt = 1f / safeFps
        val flipSign = if (clip.flipHorizontal) -1f else 1f
        val baseProfile = profileFor(clip.propType, clip.propAttachment)
        val stiffnessScale = (clip.propSwayStiffness.coerceIn(10f, 140f) / 45f)
        val dampingScale = (clip.propSwayDamping.coerceIn(1.0f, 16f) / 5.5f)
        val customMaxAngle = clip.propSwayMaxAngleDeg.coerceIn(5f, 75f)
        val effectiveMaxAngle = if (kotlin.math.abs(customMaxAngle - 42f) > 0.01f) {
            customMaxAngle
        } else {
            baseProfile.maxAngleDeg
        }
        val profile = baseProfile.copy(
            stiffness = (baseProfile.stiffness * stiffnessScale).coerceIn(8f, 180f),
            damping = (baseProfile.damping * dampingScale).coerceIn(0.8f, 24f),
            maxAngleDeg = effectiveMaxAngle
        )

        val windowFrames = 14
        val startF = (frame - windowFrames).coerceAtLeast(clip.startFrame)
        val cycleFrames = clip.animationPresetType.defaultDurationFrames.coerceAtLeast(8).toFloat()

        // Evaluate kinematic samples in local primitive variables (zero list/object allocations)
        var theta = 0f
        var omega = 0f
        var accTheta = 0f
        var accOmega = 0f

        var prevX = 0f
        var prevY = 0f
        var prevVx = 0f
        var prevVy = 0f
        var prevSocketAngle = 0f
        var prevSocketOmega = 0f
        var prevBobY = 0f
        var prevBobVy = 0f
        var initialized = false

        var lastVx = 0f
        var lastVy = 0f
        var lastSocketWorldDeg = 0f

        for (f in startF..frame) {
            val evalBase = KeyframeEvaluator.evaluate(clip, f)
            val isCurrent = (f == frame)
            val evalX = if (isCurrent && gesturePreview?.draggedClipId == clip.id) {
                (evalBase.x + gesturePreview.clipDeltaX).coerceIn(0.02f, 0.98f)
            } else {
                evalBase.x
            }
            val evalY = if (isCurrent && gesturePreview?.draggedClipId == clip.id) {
                (evalBase.y + gesturePreview.clipDeltaY).coerceIn(0.02f, 0.98f)
            } else {
                evalBase.y
            }
            val offsets = if (isCurrent && gesturePreview?.ikClipId == clip.id && gesturePreview.ikPoseOffsets != null) {
                gesturePreview.ikPoseOffsets
            } else {
                evalBase.poseOffsets
            }

            val localF = (f - clip.startFrame).coerceAtLeast(0)
            val rawPhase = (localF * clip.speedMultiplier) / cycleFrames
            val phase = if (clip.loopAnimation) ((rawPhase % 1f) + 1f) % 1f else rawPhase.coerceIn(0f, 0.999f)
            val basePose = ProceduralRigRenderer.computePose(clip.animationPresetType, phase)
            val posed = basePose.withOffsets(offsets, evalBase.expressionOverride ?: clip.facialExpression)

            val socketLocalDeg = when (clip.propAttachment) {
                PropAttachmentPoint.LEFT_HAND -> posed.bodyTiltDeg + posed.leftArmDeg + posed.leftForearmDeg
                PropAttachmentPoint.RIGHT_HAND -> posed.bodyTiltDeg + posed.rightArmDeg + posed.rightForearmDeg
                PropAttachmentPoint.HEAD -> posed.bodyTiltDeg + posed.headNodDeg
            }
            val socketWorldDeg = evalBase.rotation * flipSign + socketLocalDeg
            lastSocketWorldDeg = socketWorldDeg

            if (!initialized) {
                prevX = evalX
                prevY = evalY
                prevSocketAngle = socketWorldDeg
                prevBobY = posed.bodyBobY
                initialized = true
                continue
            }

            // Stage linear velocity & acceleration (normalized units/sec & units/sec^2)
            val vx = (evalX - prevX) / dt
            val vy = (evalY - prevY) / dt
            val ax = ((vx - prevVx) / dt).coerceIn(-12f, 12f)
            val ay = ((vy - prevVy) / dt).coerceIn(-12f, 12f)

            // Vertical bounce acceleration from walk/jump/dance preset bobbing
            val bobVy = (posed.bodyBobY - prevBobY) / dt
            val bobAy = ((bobVy - prevBobVy) / dt).coerceIn(-420f, 420f)

            // Limb/head angular velocity & acceleration (deg/sec & deg/sec^2)
            val socketOmega = ((socketWorldDeg - prevSocketAngle) / dt).coerceIn(-720f, 720f)
            val socketAlpha = ((socketOmega - prevSocketOmega) / dt).coerceIn(-2800f, 2800f)

            // Motion-driven inertial torque: moving right (+vx / +ax) trails prop leftward (negative in right-facing local space, inverted when flipped)
            val linearDrive = (-ax * 22f - vx * 18f) * flipSign + (ay * 10f - bobAy * 0.085f)
            val angularDrive = (-socketAlpha * 0.022f - socketOmega * 0.14f)

            // Gravity pendulum target: hanging props (like Village Lantern) seek vertical down (-socketWorldDeg)
            val restAngle = -socketWorldDeg * profile.gravityPendulum

            // Subtle living micro-sway so props never look frozen even during quiet Idle breathing
            val tSec = localF * dt
            val breathTorque = sin(tSec * 3.1f + (clip.id.hashCode() and 0xFF) * 0.07f) * profile.idleBreathAmp * 14f

            // Semi-implicit Euler spring-damper step (sub-stepped x2 for rock-solid stability at 12fps..60fps)
            val subDt = dt * 0.5f
            val totalDrive = (linearDrive + angularDrive) * profile.inertiaScale + breathTorque
            for (step in 0 until 2) {
                val springForce = -profile.stiffness * (theta - restAngle) - profile.damping * omega
                omega += (springForce + totalDrive) * subDt
                theta += omega * subDt
                theta = theta.coerceIn(-profile.maxAngleDeg, profile.maxAngleDeg)

                // Built-in cape / scarf / hat accessory flutter oscillator
                val accDrive = (-ax * 18f - vx * 24f) * flipSign - bobAy * 0.06f +
                    sin(tSec * 4.2f) * 18f
                val accSpring = -34f * accTheta - 4.8f * accOmega
                accOmega += (accSpring + accDrive) * subDt
                accTheta += accOmega * subDt
                accTheta = accTheta.coerceIn(-28f, 28f)
            }

            prevX = evalX
            prevY = evalY
            prevVx = vx
            prevVy = vy
            prevSocketAngle = socketWorldDeg
            prevSocketOmega = socketOmega
            prevBobY = posed.bodyBobY
            prevBobVy = bobVy
            lastVx = vx
            lastVy = vy
        }

        // Inject live tactile drag sway when the user is actively dragging the character on stage
        if (gesturePreview != null && gesturePreview.draggedClipId == clip.id &&
            (gesturePreview.dragVelocityX != 0f || gesturePreview.dragVelocityY != 0f)
        ) {
            val dragKick = (-gesturePreview.dragVelocityX * 38f * flipSign + gesturePreview.dragVelocityY * 14f) *
                profile.inertiaScale
            theta = (theta + dragKick).coerceIn(-profile.maxAngleDeg, profile.maxAngleDeg)
            accTheta = (accTheta - gesturePreview.dragVelocityX * 32f * flipSign).coerceIn(-28f, 28f)
        }

        // Scale the calibrated physical response monotonically by propSwayStrength (0f..2f),
        // respecting an explicit user maxAngleDeg cap when customized.
        val maxScaledAngle = if (kotlin.math.abs(customMaxAngle - 42f) > 0.01f) {
            customMaxAngle
        } else {
            (profile.maxAngleDeg * 1.45f).coerceAtMost(68f)
        }
        val scaledAngle = (theta * strength).coerceIn(-maxScaledAngle, maxScaledAngle)
        val scaledAccFlutter = (accTheta * strength).coerceIn(-38f, 38f)

        // Subtle secondary lag displacement in local socket coordinates (in rig-scale units)
        val angleRad = (scaledAngle * PI / 180.0).toFloat()
        val lagX = (((-lastVx * 2.2f * flipSign) + sin(angleRad) * profile.lagOffsetScale) * strength)
            .coerceIn(-6.5f, 6.5f)
        val lagY = (((-lastVy * 1.4f) + (1f - cos(angleRad)) * profile.lagOffsetScale * 0.6f) * strength)
            .coerceIn(-5.0f, 5.0f)

        return PropSwayState(
            angleDeg = scaledAngle,
            offsetX = lagX,
            offsetY = lagY,
            accessoryFlutterDeg = scaledAccFlutter
        )
    }
}
