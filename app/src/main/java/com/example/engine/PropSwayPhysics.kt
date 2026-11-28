package com.example.engine

import com.example.model.CharacterRigDefinition
import com.example.model.TimelineClip

/**
 * Serializable spring-damper physics configuration exposed in `.fcblueprint.json`
 * and `.fcscript.json` for attached Hand & Head props.
 */
data class PropSwayConfig(
    val swayEnabled: Boolean = true,
    val strength: Float = 1.0f,
    val stiffness: Float = 45f,
    val damping: Float = 5.5f,
    val maxAngleDeg: Float = 42f
)

/**
 * Deterministic, frame-reproducible Spring-Damper Sway Physics facade for Hand & Head props.
 * Delegates directly to [PropSwayPhysicsEngine] so live preview scrubbing and offline
 * MP4/GIF/PNG export evaluate identical spring angles on every frame.
 */
object PropSwayPhysics {

    val DEFAULT_CONFIG = PropSwayConfig()

    fun configFromClip(clip: TimelineClip): PropSwayConfig = PropSwayConfig(
        swayEnabled = clip.propSwayEnabled,
        strength = clip.propSwayStrength,
        stiffness = clip.propSwayStiffness,
        damping = clip.propSwayDamping,
        maxAngleDeg = clip.propSwayMaxAngleDeg
    )

    fun evaluate(
        clip: TimelineClip,
        rig: CharacterRigDefinition?,
        frame: Int,
        fps: Int,
        gesturePreview: StageGesturePreview? = null
    ): PropSwayState = PropSwayPhysicsEngine.evaluatePropSway(
        clip = clip,
        rig = rig,
        frame = frame,
        fps = fps,
        gesturePreview = gesturePreview
    )
}
