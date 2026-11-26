package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.engine.ProceduralRigRenderer
import com.example.model.*
import kotlin.math.min

/**
 * Reusable live-animated character preview used inside Character Library cards,
 * Animation Library cards, Character Setup templates, and Custom Character Maker.
 */
@Composable
fun CharacterPreviewCanvas(
    rig: CharacterRigDefinition,
    orientation: CharacterOrientation = CharacterOrientation.FRONT,
    preset: AnimationPresetType = AnimationPresetType.IDLE,
    animate: Boolean = true,
    customBitmap: Bitmap? = null,
    atlasSlices: Map<AtlasPartSlot, Bitmap>? = null,
    facialExpression: FacialExpression = FacialExpression.AUTO,
    handPose: HandPoseType = HandPoseType.OPEN,
    blinkOverride: Boolean? = null,
    mouthSlotOverride: AtlasPartSlot? = null,
    modifier: Modifier = Modifier
) {
    val animatedPhaseState = if (animate) {
        val transition = rememberInfiniteTransition(label = "rig_preview_${rig.id}_${preset.name}")
        val durationMs = ((preset.defaultDurationFrames * 1000) / 24).coerceIn(700, 2400)
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )
    } else {
        null
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val phase = animatedPhaseState?.value ?: 0.15f
        drawIntoCanvas { composeCanvas ->
            val nativeCanvas = composeCanvas.nativeCanvas
            val minDim = min(size.width, size.height)
            val rigScale = minDim / 215f
            nativeCanvas.save()
            nativeCanvas.translate(size.width * 0.5f, size.height * 0.57f)
            ProceduralRigRenderer.drawCharacter(
                canvas = nativeCanvas,
                rig = rig,
                orientation = orientation,
                preset = preset,
                phase = phase,
                rigScale = rigScale,
                alpha = 1f,
                customBitmap = customBitmap,
                facialExpression = facialExpression,
                handPose = handPose,
                blinkOverride = blinkOverride,
                mouthSlotOverride = mouthSlotOverride,
                atlasSlices = atlasSlices
            )
            nativeCanvas.restore()
        }
    }
}
