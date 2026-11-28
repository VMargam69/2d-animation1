package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.example.engine.ProceduralRigRenderer
import com.example.model.*
import com.example.ui.theme.*
import kotlin.math.roundToInt

@Composable
private fun FxInspectorFrame(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    testTagPrefix: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = StudioCharcoal,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.16f))
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = StudioTextSecondary
                        )
                    }
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("${testTagPrefix}_reset_btn")
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = "Reset", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset", color = StudioTextSecondary, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)
                content()
                HorizontalDivider(color = StudioBorderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = StudioObsidian),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("${testTagPrefix}_done_btn")
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FxLabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color = ElectricCyan,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = StudioTextPrimary)
            Text(valueText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = StudioBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

/**
 * 1. Animated Tears FX Inspector (Deterministic anime/cartoon eye glints & falling teardrop trails).
 */
@Composable
fun TearsFxInspectorDialog(
    clip: TimelineClip,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onDismiss: () -> Unit
) {
    val cfg = clip.tearsConfig
    FxInspectorFrame(
        title = "Animated Tears FX",
        subtitle = "Deterministic eye glints & falling tear trails",
        icon = Icons.Rounded.WaterDrop,
        accent = Color(0xFF38BDF8),
        onReset = {
            onUpdateClip { it.copy(tearsConfig = TearsFxConfig()) }
        },
        onDismiss = onDismiss,
        testTagPrefix = "tears_fx"
    ) {
        Text("Tear Style", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TearsFxStyle.entries) { style ->
                val selected = cfg.style == style
                FilterChip(
                    selected = selected,
                    onClick = {
                        onUpdateClip { c ->
                            c.copy(tearsConfig = c.tearsConfig.copy(style = style))
                        }
                    },
                    label = { Text(style.displayName, fontSize = 12.sp) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("tears_style_${style.name.lowercase()}")
                )
            }
        }

        FxLabeledSlider(
            label = "Intensity",
            valueText = "${(cfg.intensity * 100f).roundToInt()}%",
            value = cfg.intensity,
            valueRange = 0f..1f,
            accent = Color(0xFF38BDF8),
            onValueChange = { v ->
                onUpdateClip { c ->
                    val nextStyle = if (c.tearsConfig.style == TearsFxStyle.OFF && v > 0.05f) TearsFxStyle.FLOWING else c.tearsConfig.style
                    c.copy(tearsConfig = c.tearsConfig.copy(style = nextStyle, intensity = v))
                }
            },
            testTag = "tears_intensity_slider"
        )

        FxLabeledSlider(
            label = "Flow Speed",
            valueText = String.format("%.2fx", cfg.speed),
            value = cfg.speed,
            valueRange = 0.5f..2.0f,
            accent = Color(0xFF38BDF8),
            onValueChange = { v ->
                onUpdateClip { c -> c.copy(tearsConfig = c.tearsConfig.copy(speed = v)) }
            },
            testTag = "tears_speed_slider"
        )
    }
}

/**
 * 2. Dynamic Ground / Drop Shadow Inspector.
 */
@Composable
fun ShadowInspectorDialog(
    clip: TimelineClip,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onDismiss: () -> Unit
) {
    val cfg = clip.shadowConfig
    val shadowSwatches = listOf(
        "#0B101B" to "Obsidian",
        "#0F172A" to "Slate",
        "#1E1B4B" to "Indigo",
        "#291811" to "Umber",
        "#06222B" to "Deep Teal"
    )

    FxInspectorFrame(
        title = "Dynamic Shadow",
        subtitle = if (clip.characterId != null) "Auto-tracks feet & scales naturally when jumping" else "Soft ground or directional drop shadow",
        icon = Icons.Rounded.LensBlur,
        accent = VioletLayer,
        onReset = {
            onUpdateClip { it.copy(shadowConfig = ShadowConfig()) }
        },
        onDismiss = onDismiss,
        testTagPrefix = "shadow_fx"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Enable Shadow", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = StudioTextPrimary)
            Switch(
                checked = cfg.enabled,
                onCheckedChange = { enabled ->
                    onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = enabled)) }
                },
                modifier = Modifier.testTag("shadow_enabled_switch")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShadowMode.entries.forEach { mode ->
                val selected = cfg.mode == mode
                FilterChip(
                    selected = selected,
                    onClick = {
                        onUpdateClip { c ->
                            c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, mode = mode))
                        }
                    },
                    label = { Text(mode.displayName, fontSize = 12.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("shadow_mode_${mode.name.lowercase()}")
                )
            }
        }

        FxLabeledSlider(
            label = "Opacity",
            valueText = "${(cfg.opacity * 100f).roundToInt()}%",
            value = cfg.opacity,
            valueRange = 0f..1f,
            accent = VioletLayer,
            onValueChange = { v ->
                onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, opacity = v)) }
            },
            testTag = "shadow_opacity_slider"
        )

        FxLabeledSlider(
            label = "Softness",
            valueText = "${(cfg.softness * 100f).roundToInt()}%",
            value = cfg.softness,
            valueRange = 0f..1f,
            accent = VioletLayer,
            onValueChange = { v ->
                onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, softness = v)) }
            },
            testTag = "shadow_softness_slider"
        )

        FxLabeledSlider(
            label = "Width Scale",
            valueText = String.format("%.2fx", cfg.scaleX),
            value = cfg.scaleX,
            valueRange = 0.25f..2.2f,
            accent = VioletLayer,
            onValueChange = { v ->
                onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, scaleX = v)) }
            },
            testTag = "shadow_scalex_slider"
        )

        FxLabeledSlider(
            label = "Height Scale",
            valueText = String.format("%.2fx", cfg.scaleY),
            value = cfg.scaleY,
            valueRange = 0.12f..1.5f,
            accent = VioletLayer,
            onValueChange = { v ->
                onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, scaleY = v)) }
            },
            testTag = "shadow_scaley_slider"
        )

        FxLabeledSlider(
            label = "Offset X",
            valueText = "${(cfg.offsetX * 100f).roundToInt()}%",
            value = cfg.offsetX,
            valueRange = -0.5f..0.5f,
            accent = VioletLayer,
            onValueChange = { v ->
                onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, offsetX = v)) }
            },
            testTag = "shadow_offsetx_slider"
        )

        FxLabeledSlider(
            label = "Offset Y",
            valueText = "${(cfg.offsetY * 100f).roundToInt()}%",
            value = cfg.offsetY,
            valueRange = -0.5f..0.5f,
            accent = VioletLayer,
            onValueChange = { v ->
                onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, offsetY = v)) }
            },
            testTag = "shadow_offsety_slider"
        )

        Text("Shadow Tint", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            shadowSwatches.forEach { (hex, _) ->
                val swatchColor = Color(ProceduralRigRenderer.parseColorSafe(hex, 0xFF0B101B.toInt()))
                val selected = cfg.colorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(
                            width = if (selected) 2.5.dp else 1.dp,
                            color = if (selected) ElectricCyan else StudioBorder,
                            shape = CircleShape
                        )
                        .clickable {
                            onUpdateClip { c -> c.copy(shadowConfig = c.shadowConfig.copy(enabled = true, colorHex = hex)) }
                        }
                )
            }
        }
    }
}

/**
 * 3. Per-Clip Colour Adjust Inspector (ColorMatrix: brightness, contrast, saturation, hue, warmth).
 */
@Composable
fun ColorAdjustInspectorDialog(
    clip: TimelineClip,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onDismiss: () -> Unit
) {
    val adj = clip.colorAdjust
    val presets = listOf(
        "Vivid" to ClipColorAdjust(brightness = 0.05f, contrast = 1.18f, saturation = 1.45f, warmth = 0.08f),
        "Warm Sunset" to ClipColorAdjust(brightness = 0.04f, contrast = 1.08f, saturation = 1.2f, warmth = 0.55f),
        "Cool Night" to ClipColorAdjust(brightness = -0.14f, contrast = 1.12f, saturation = 0.85f, warmth = -0.52f),
        "Noir B&W" to ClipColorAdjust(brightness = -0.04f, contrast = 1.35f, saturation = 0f),
        "Soft Pastel" to ClipColorAdjust(brightness = 0.14f, contrast = 0.86f, saturation = 0.78f, warmth = 0.12f)
    )

    FxInspectorFrame(
        title = "Colour Adjust",
        subtitle = "Per-clip ColorMatrix grading (live & export)",
        icon = Icons.Rounded.Tune,
        accent = AmberKeyframe,
        onReset = {
            onUpdateClip { it.copy(colorAdjust = ClipColorAdjust()) }
        },
        onDismiss = onDismiss,
        testTagPrefix = "color_adjust"
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { (label, presetAdj) ->
                AssistChip(
                    onClick = { onUpdateClip { c -> c.copy(colorAdjust = presetAdj) } },
                    label = { Text(label, fontSize = 11.sp) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }

        FxLabeledSlider(
            label = "Brightness",
            valueText = "${if (adj.brightness >= 0f) "+" else ""}${(adj.brightness * 100f).roundToInt()}%",
            value = adj.brightness,
            valueRange = -1f..1f,
            accent = AmberKeyframe,
            onValueChange = { v -> onUpdateClip { c -> c.copy(colorAdjust = c.colorAdjust.copy(brightness = v)) } },
            testTag = "adjust_brightness_slider"
        )

        FxLabeledSlider(
            label = "Contrast",
            valueText = String.format("%.2fx", adj.contrast),
            value = adj.contrast,
            valueRange = 0.2f..2.5f,
            accent = AmberKeyframe,
            onValueChange = { v -> onUpdateClip { c -> c.copy(colorAdjust = c.colorAdjust.copy(contrast = v)) } },
            testTag = "adjust_contrast_slider"
        )

        FxLabeledSlider(
            label = "Saturation",
            valueText = "${(adj.saturation * 100f).roundToInt()}%",
            value = adj.saturation,
            valueRange = 0f..2.5f,
            accent = AmberKeyframe,
            onValueChange = { v -> onUpdateClip { c -> c.copy(colorAdjust = c.colorAdjust.copy(saturation = v)) } },
            testTag = "adjust_saturation_slider"
        )

        FxLabeledSlider(
            label = "Hue",
            valueText = "${adj.hueDeg.roundToInt()}°",
            value = adj.hueDeg,
            valueRange = -180f..180f,
            accent = AmberKeyframe,
            onValueChange = { v -> onUpdateClip { c -> c.copy(colorAdjust = c.colorAdjust.copy(hueDeg = v)) } },
            testTag = "adjust_hue_slider"
        )

        FxLabeledSlider(
            label = "Warmth",
            valueText = "${if (adj.warmth >= 0f) "+" else ""}${(adj.warmth * 100f).roundToInt()}%",
            value = adj.warmth,
            valueRange = -1f..1f,
            accent = AmberKeyframe,
            onValueChange = { v -> onUpdateClip { c -> c.copy(colorAdjust = c.colorAdjust.copy(warmth = v)) } },
            testTag = "adjust_warmth_slider"
        )
    }
}

/**
 * 4. Interactive Clip Masking Inspector (All shapes + position/size/feather/invert).
 */
@Composable
fun MaskInspectorDialog(
    clip: TimelineClip,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onDismiss: () -> Unit
) {
    val cfg = clip.maskConfig
    val activeType = clip.effectiveMaskType

    FxInspectorFrame(
        title = "Clip Mask",
        subtitle = "Shape crop with live position, size, feather & invert",
        icon = Icons.Rounded.FilterTiltShift,
        accent = ElectricCyan,
        onReset = {
            onUpdateClip {
                it.copy(
                    maskType = ClipMaskType.NONE,
                    maskRadius = 0.46f,
                    maskConfig = ClipMaskConfig()
                )
            }
        },
        onDismiss = onDismiss,
        testTagPrefix = "mask_fx"
    ) {
        Text("Mask Shape", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ClipMaskType.entries) { maskType ->
                val selected = activeType == maskType
                FilterChip(
                    selected = selected,
                    onClick = {
                        onUpdateClip { c ->
                            c.copy(
                                maskType = maskType,
                                maskConfig = c.maskConfig.copy(type = maskType)
                            )
                        }
                    },
                    label = { Text(maskType.displayName, fontSize = 12.sp) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("mask_type_${maskType.name.lowercase()}")
                )
            }
        }

        if (activeType != ClipMaskType.NONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Invert Mask", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = StudioTextPrimary)
                Switch(
                    checked = cfg.invert,
                    onCheckedChange = { inv ->
                        onUpdateClip { c ->
                            c.copy(
                                maskType = activeType,
                                maskConfig = c.maskConfig.copy(type = activeType, invert = inv)
                            )
                        }
                    },
                    modifier = Modifier.testTag("mask_invert_switch")
                )
            }

            FxLabeledSlider(
                label = "Width",
                valueText = "${(cfg.width * 100f).roundToInt()}%",
                value = cfg.width,
                valueRange = 0.1f..1.4f,
                accent = ElectricCyan,
                onValueChange = { v ->
                    onUpdateClip { c ->
                        c.copy(
                            maskType = activeType,
                            maskRadius = (v * 0.5f).coerceIn(0.12f, 0.95f),
                            maskConfig = c.maskConfig.copy(type = activeType, width = v)
                        )
                    }
                },
                testTag = "mask_width_slider"
            )

            FxLabeledSlider(
                label = "Height",
                valueText = "${(cfg.height * 100f).roundToInt()}%",
                value = cfg.height,
                valueRange = 0.1f..1.4f,
                accent = ElectricCyan,
                onValueChange = { v ->
                    onUpdateClip { c ->
                        c.copy(
                            maskType = activeType,
                            maskConfig = c.maskConfig.copy(type = activeType, height = v)
                        )
                    }
                },
                testTag = "mask_height_slider"
            )

            FxLabeledSlider(
                label = "Center X",
                valueText = "${(cfg.centerX * 100f).roundToInt()}%",
                value = cfg.centerX,
                valueRange = -0.5f..0.5f,
                accent = ElectricCyan,
                onValueChange = { v ->
                    onUpdateClip { c ->
                        c.copy(
                            maskType = activeType,
                            maskConfig = c.maskConfig.copy(type = activeType, centerX = v)
                        )
                    }
                },
                testTag = "mask_centerx_slider"
            )

            FxLabeledSlider(
                label = "Center Y",
                valueText = "${(cfg.centerY * 100f).roundToInt()}%",
                value = cfg.centerY,
                valueRange = -0.5f..0.5f,
                accent = ElectricCyan,
                onValueChange = { v ->
                    onUpdateClip { c ->
                        c.copy(
                            maskType = activeType,
                            maskConfig = c.maskConfig.copy(type = activeType, centerY = v)
                        )
                    }
                },
                testTag = "mask_centery_slider"
            )

            FxLabeledSlider(
                label = "Feather",
                valueText = "${(cfg.feather * 100f).roundToInt()}%",
                value = cfg.feather,
                valueRange = 0f..1f,
                accent = ElectricCyan,
                onValueChange = { v ->
                    onUpdateClip { c ->
                        c.copy(
                            maskType = activeType,
                            maskConfig = c.maskConfig.copy(type = activeType, feather = v)
                        )
                    }
                },
                testTag = "mask_feather_slider"
            )
        }
    }
}
