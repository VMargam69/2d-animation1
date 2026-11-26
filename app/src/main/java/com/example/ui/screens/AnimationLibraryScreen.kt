package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.BuiltInCatalog
import com.example.data.LocalAssetStorage
import com.example.model.*
import com.example.ui.components.CharacterPreviewCanvas
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun AnimationLibraryScreen(
    selectedCharacter: CharacterRigDefinition,
    presets: List<AnimationPreset>,
    assetStorage: LocalAssetStorage,
    onBack: () -> Unit,
    onApplyAnimation: (
        orientation: CharacterOrientation,
        preset: AnimationPresetType,
        durationFrames: Int,
        speedMultiplier: Float,
        loopAnimation: Boolean
    ) -> Unit
) {
    BackHandler { onBack() }

    val availablePresets = remember(presets) { presets.ifEmpty { BuiltInCatalog.animationPresets } }
    var searchQuery by remember { mutableStateOf("") }
    var selectedOrientation by remember { mutableStateOf(CharacterOrientation.FRONT) }
    var selectedPreset by remember(availablePresets) { mutableStateOf(availablePresets.first()) }
    var durationFrames by remember { mutableIntStateOf(96) }
    var speedMultiplier by remember { mutableFloatStateOf(1.0f) }
    var loopAnimation by remember { mutableStateOf(true) }

    val bitmapVersion by assetStorage.bitmapVersion.collectAsStateWithLifecycle()
    val isAtlasPro = selectedCharacter.spriteAtlasRig != null || selectedCharacter.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO
    val atlasSlices = remember(selectedCharacter.customImagePath, isAtlasPro, bitmapVersion) {
        if (isAtlasPro) assetStorage.getCachedOrSliceAtlas(selectedCharacter.customImagePath) else null
    }
    val customBitmap = remember(selectedCharacter.customImagePath, isAtlasPro, bitmapVersion) {
        if (!isAtlasPro) assetStorage.getCachedOrRequestBitmap(selectedCharacter.customImagePath, null, maxDimension = 512) else null
    }

    val filteredPresets = remember(availablePresets, searchQuery, selectedOrientation) {
        availablePresets.filter { preset ->
            val matchesQuery = searchQuery.isBlank() ||
                preset.name.contains(searchQuery, ignoreCase = true) ||
                preset.tag.contains(searchQuery, ignoreCase = true) ||
                preset.description.contains(searchQuery, ignoreCase = true)
            val matchesOrientation = selectedOrientation in preset.supportedOrientations
            matchesQuery && matchesOrientation
        }
    }

    Scaffold(
        containerColor = StudioObsidian,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // Selected Character Header Section (matching prompt spec)
            Surface(color = StudioCharcoal, border = BorderStroke(1.dp, StudioBorderSubtle)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("anim_lib_back_btn")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = StudioTextPrimary
                        )
                    }

                    // Selected Character Live Thumbnail
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioPanel)
                    ) {
                        CharacterPreviewCanvas(
                            rig = selectedCharacter,
                            orientation = selectedOrientation,
                            preset = selectedPreset.type,
                            animate = true,
                            customBitmap = customBitmap,
                            atlasSlices = atlasSlices
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedCharacter.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.animations_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }

                    Surface(
                        color = EmeraldLayer.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, EmeraldLayer.copy(alpha = 0.65f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = EmeraldLayer, modifier = Modifier.size(14.dp))
                            Text(
                                text = "Selected",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldLayer
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Animation Duration, Speed, Loop & Apply Bar
            Surface(
                color = StudioCharcoal,
                border = BorderStroke(1.dp, StudioBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedPreset.name} • ${selectedOrientation.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = ElectricCyan
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Loop", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                            Switch(
                                checked = loopAnimation,
                                onCheckedChange = { loopAnimation = it },
                                modifier = Modifier.testTag("anim_loop_switch")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Duration: ${durationFrames}F",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary
                            )
                            Slider(
                                value = durationFrames.toFloat(),
                                onValueChange = { durationFrames = it.toInt() },
                                valueRange = 24f..240f,
                                colors = SliderDefaults.colors(
                                    thumbColor = ElectricCyan,
                                    activeTrackColor = ElectricCyan
                                )
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speed: ${String.format(Locale.US, "%.1fx", speedMultiplier)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary
                            )
                            Slider(
                                value = speedMultiplier,
                                onValueChange = { speedMultiplier = (it * 10f).toInt() / 10f },
                                valueRange = 0.4f..2.5f,
                                colors = SliderDefaults.colors(
                                    thumbColor = AmberKeyframe,
                                    activeTrackColor = AmberKeyframe
                                )
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onApplyAnimation(
                                selectedOrientation,
                                selectedPreset.type,
                                durationFrames,
                                speedMultiplier,
                                loopAnimation
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = StudioObsidian
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("apply_animation_btn")
                    ) {
                        Icon(Icons.Rounded.AddToQueue, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Apply to Canvas & Timeline", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            // Search Animations Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.animations_search_hint), color = StudioTextMuted) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = StudioTextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = StudioPanel,
                    unfocusedContainerColor = StudioPanel,
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = StudioBorder,
                    focusedTextColor = StudioTextPrimary,
                    unfocusedTextColor = StudioTextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_animations_input")
            )

            // Orientation Filter Chips (Front, 3/4 Front, 3/4 Back, Back, Side)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(CharacterOrientation.entries, key = { it.name }) { orient ->
                    val selected = orient == selectedOrientation
                    FilterChip(
                        selected = selected,
                        onClick = { selectedOrientation = orient },
                        label = { Text(orient.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = StudioPanel,
                            labelColor = StudioTextSecondary,
                            selectedContainerColor = AmberKeyframeDim,
                            selectedLabelColor = StudioTextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = StudioBorderSubtle,
                            selectedBorderColor = AmberKeyframe
                        ),
                        modifier = Modifier.testTag("orient_chip_${orient.name.lowercase()}")
                    )
                }
            }

            // 2-Column Responsive Grid of Live Animated Preset Cards
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = when {
                    maxWidth >= 840.dp -> 4
                    maxWidth >= 580.dp -> 3
                    else -> 2
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredPresets, key = { it.id }) { preset ->
                        val isSelected = preset.id == selectedPreset.id
                        AnimationPresetCard(
                            preset = preset,
                            character = selectedCharacter,
                            orientation = selectedOrientation,
                            customBitmap = customBitmap,
                            atlasSlices = atlasSlices,
                            isSelected = isSelected,
                            onClick = { selectedPreset = preset }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimationPresetCard(
    preset: AnimationPreset,
    character: CharacterRigDefinition,
    orientation: CharacterOrientation,
    customBitmap: android.graphics.Bitmap?,
    atlasSlices: Map<AtlasPartSlot, android.graphics.Bitmap>?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) ElectricCyanDim.copy(alpha = 0.55f) else StudioCard,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) ElectricCyan else StudioBorderSubtle
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("anim_preset_${preset.type.name.lowercase()}")
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF1A2233), Color(0xFF10141F)))
                    )
            ) {
                CharacterPreviewCanvas(
                    rig = character,
                    orientation = orientation,
                    preset = preset.type,
                    animate = true,
                    customBitmap = customBitmap,
                    atlasSlices = atlasSlices,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = StudioObsidian.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = preset.tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberKeyframe,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (isSelected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected preset",
                        tint = ElectricCyan,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${preset.defaultDurationFrames}F",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted
                )
            }
        }
    }
}
