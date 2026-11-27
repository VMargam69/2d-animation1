package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.ProceduralRigRenderer
import com.example.model.SceneLightingMood
import com.example.model.SceneParticleType
import com.example.model.SceneVfxConfig
import com.example.ui.theme.*
import java.util.Locale

/**
 * Natural Scene VFX & Atmosphere Studio Dialog.
 * Controls 7 deterministic particle systems, 5 cinematic lighting/color-grading moods,
 * adjustable vignette, and deterministic camera shake that render identically in live
 * preview and MP4/GIF/PNG exports.
 */
@Composable
fun SceneVfxStudioDialog(
    vfxConfig: SceneVfxConfig,
    onUpdateVfx: ((SceneVfxConfig) -> SceneVfxConfig) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = StudioPanel,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .heightIn(max = 660.dp)
                .testTag("scene_vfx_studio_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(VioletLayer.copy(alpha = 0.22f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = VioletLayer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Scene VFX & Atmosphere",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StudioTextPrimary
                            )
                            Text(
                                text = "Frame-synced in Live Preview & Video Exports",
                                fontSize = 11.sp,
                                color = StudioTextSecondary
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("vfx_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close VFX studio",
                            tint = StudioTextSecondary
                        )
                    }
                }

                // 1. Atmospheric Particle Systems (7 effects + None)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ATMOSPHERIC PARTICLES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = StudioTextMuted
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SceneParticleType.entries.forEach { particle ->
                            val isSelected = vfxConfig.particleType == particle
                            val accent = Color(ProceduralRigRenderer.parseColorSafe(particle.accentHex, 0xFF38BDF8.toInt()))
                            Surface(
                                color = if (isSelected) accent.copy(alpha = 0.22f) else StudioCard,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) accent else StudioBorderSubtle
                                ),
                                modifier = Modifier
                                    .widthIn(min = 76.dp)
                                    .heightIn(min = 54.dp)
                                    .clickable {
                                        onUpdateVfx { it.copy(particleType = particle) }
                                    }
                                    .testTag("vfx_particle_${particle.name.lowercase()}")
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = particleIcon(particle),
                                        contentDescription = particle.displayName,
                                        tint = if (isSelected) accent else StudioTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = particle.displayName,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) accent else StudioTextPrimary
                                    )
                                }
                            }
                        }
                    }

                    if (vfxConfig.particleType != SceneParticleType.NONE) {
                        VfxSliderRow(
                            label = "Particle Density",
                            valueText = "${(vfxConfig.particleIntensity * 100).toInt()}%",
                            value = vfxConfig.particleIntensity,
                            range = 0.1f..1.0f,
                            accent = ElectricCyan,
                            testTag = "vfx_particle_density_slider",
                            onValueChange = { v -> onUpdateVfx { it.copy(particleIntensity = v) } }
                        )
                        VfxSliderRow(
                            label = "Particle Speed",
                            valueText = String.format(Locale.US, "%.1fx", vfxConfig.particleSpeed),
                            value = vfxConfig.particleSpeed,
                            range = 0.3f..2.5f,
                            accent = ElectricCyan,
                            testTag = "vfx_particle_speed_slider",
                            onValueChange = { v -> onUpdateVfx { it.copy(particleSpeed = v) } }
                        )
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 2. Cinematic Color-Grading Moods (5 moods + Natural)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "CINEMATIC LIGHTING MOOD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = StudioTextMuted
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SceneLightingMood.entries.forEach { mood ->
                            val isSelected = vfxConfig.lightingMood == mood
                            val swatch = Color(ProceduralRigRenderer.parseColorSafe(mood.swatchHex, 0xFF475569.toInt()))
                            Surface(
                                color = if (isSelected) swatch.copy(alpha = 0.24f) else StudioCard,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) swatch else StudioBorderSubtle
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        onUpdateVfx { it.copy(lightingMood = mood) }
                                    }
                                    .testTag("vfx_mood_${mood.name.lowercase()}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(swatch)
                                    )
                                    Text(
                                        text = mood.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) StudioTextPrimary else StudioTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 3. Lens Vignette & Deterministic Camera Shake
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "LENS & CAMERA ATMOSPHERE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = StudioTextMuted
                    )
                    VfxSliderRow(
                        label = "Cinematic Vignette",
                        valueText = "${(vfxConfig.vignetteIntensity * 100).toInt()}%",
                        value = vfxConfig.vignetteIntensity,
                        range = 0f..1f,
                        accent = AmberKeyframe,
                        testTag = "vfx_vignette_slider",
                        onValueChange = { v -> onUpdateVfx { it.copy(vignetteIntensity = v) } }
                    )
                    VfxSliderRow(
                        label = "Camera Shake",
                        valueText = "${(vfxConfig.cameraShakeIntensity * 100).toInt()}%",
                        value = vfxConfig.cameraShakeIntensity,
                        range = 0f..1f,
                        accent = CoralPlayhead,
                        testTag = "vfx_camera_shake_slider",
                        onValueChange = { v -> onUpdateVfx { it.copy(cameraShakeIntensity = v) } }
                    )
                }

                // Bottom Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onUpdateVfx { SceneVfxConfig() } },
                        enabled = vfxConfig.hasActiveVfx,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("vfx_reset_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("Reset VFX")
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = StudioObsidian
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                            .testTag("vfx_done_btn")
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun VfxSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, fontSize = 12.sp, color = StudioTextSecondary)
            Text(
                text = valueText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = StudioBorder
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

private fun particleIcon(type: SceneParticleType): ImageVector = when (type) {
    SceneParticleType.NONE -> Icons.Rounded.Block
    SceneParticleType.RAIN -> Icons.Rounded.WaterDrop
    SceneParticleType.SNOW -> Icons.Rounded.AcUnit
    SceneParticleType.FIREFLIES -> Icons.Rounded.Flare
    SceneParticleType.PETALS -> Icons.Rounded.LocalFlorist
    SceneParticleType.SPARKLES -> Icons.Rounded.AutoAwesome
    SceneParticleType.CONFETTI -> Icons.Rounded.Celebration
    SceneParticleType.EMBERS -> Icons.Rounded.LocalFireDepartment
}
