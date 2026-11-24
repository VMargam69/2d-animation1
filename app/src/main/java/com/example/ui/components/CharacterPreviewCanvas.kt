package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.engine.ProceduralRigRenderer
import com.example.model.AnimationPresetType
import com.example.model.CharacterOrientation
import com.example.model.CharacterRigDefinition
import kotlin.math.min

/**
 * Reusable live-animated procedural character preview used inside Character Library cards,
 * Animation Library cards, and custom character creator dialog.
 */
@Composable
fun CharacterPreviewCanvas(
    rig: CharacterRigDefinition,
    orientation: CharacterOrientation = CharacterOrientation.FRONT,
    preset: AnimationPresetType = AnimationPresetType.IDLE,
    animate: Boolean = true,
    customBitmap: Bitmap? = null,
    modifier: Modifier = Modifier
) {
    val phase: Float = if (animate) {
        val transition = rememberInfiniteTransition(label = "rig_preview_${rig.id}_${preset.name}")
        val durationMs = ((preset.defaultDurationFrames * 1000) / 24).coerceIn(700, 2400)
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )
        animated
    } else {
        0.15f
    }

    Canvas(modifier = modifier.fillMaxSize()) {
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
                customBitmap = customBitmap
            )
            nativeCanvas.restore()
        }
    }
}
