package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.BuiltInCatalog
import com.example.model.LocalMediaAsset
import com.example.model.MediaAssetType
import com.example.ui.theme.*
import java.io.File

@Composable
fun MediaLibraryScreen(
    assets: List<LocalMediaAsset>,
    onImportUri: (Uri, MediaAssetType?) -> Unit,
    onPlaceOnCanvas: (LocalMediaAsset, asBackground: Boolean) -> Unit,
    onDeleteAsset: (LocalMediaAsset) -> Unit,
    isReplaceMode: Boolean = false,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf<MediaAssetType?>(null) }
    var pendingPlacementAsset by remember { mutableStateOf<LocalMediaAsset?>(null) }

    // Zero-permission Android Photo Picker for Images & Videos
    val visualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImportUri(uri, null)
    }

    // Zero-permission Storage Access Framework picker for Audio, SVG, GIF, WebP, PNG, JPEG
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportUri(uri, null)
    }

    val filteredAssets = remember(assets, searchQuery, selectedTypeFilter) {
        assets.filter { asset ->
            val matchesQuery = searchQuery.isBlank() ||
                asset.name.contains(searchQuery, ignoreCase = true) ||
                asset.category.contains(searchQuery, ignoreCase = true)
            val matchesType = selectedTypeFilter == null || asset.type == selectedTypeFilter
            matchesQuery && matchesType
        }
    }

    Scaffold(
        containerColor = StudioObsidian,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = StudioCharcoal, border = BorderStroke(1.dp, StudioBorderSubtle)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (isReplaceMode) Icons.Rounded.SwapHoriz else Icons.Rounded.PermMedia,
                            null,
                            tint = if (isReplaceMode) ElectricCyan else VioletLayer,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = if (isReplaceMode) "Replace Media" else stringResource(R.string.media_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_media_library_btn")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.nav_close), tint = StudioTextPrimary)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            // Offline Import Actions Row (Zero-permission Photo Picker + SAF)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        visualPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_visual_media_btn")
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import Photo/Video", fontWeight = FontWeight.Bold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        filePicker.launch(arrayOf("audio/*", "image/*", "video/*"))
                    },
                    border = BorderStroke(1.dp, VioletLayer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_audio_file_btn")
                ) {
                    Icon(Icons.Rounded.AudioFile, null, tint = VioletLayer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Audio / SVG / File", color = StudioTextPrimary, maxLines = 1)
                }
            }

            // Search Media Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.media_search_hint), color = StudioTextMuted) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = StudioTextSecondary) },
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
                    .testTag("search_media_input")
            )

            // Type Filter Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedTypeFilter == null,
                        onClick = { selectedTypeFilter = null },
                        label = { Text("All") }
                    )
                }
                items(MediaAssetType.entries) { type ->
                    FilterChip(
                        selected = selectedTypeFilter == type,
                        onClick = { selectedTypeFilter = type },
                        label = { Text(type.displayName) }
                    )
                }
            }

            // Grid of Offline Media Assets
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
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredAssets, key = { it.id }) { asset ->
                        MediaAssetCard(
                            asset = asset,
                            onSelect = {
                                if (isReplaceMode || asset.type == MediaAssetType.AUDIO) {
                                    onPlaceOnCanvas(asset, false)
                                } else {
                                    pendingPlacementAsset = asset
                                }
                            },
                            onDelete = if (!asset.isBundled) {
                                { onDeleteAsset(asset) }
                            } else null
                        )
                    }
                }
            }
        }
    }

    pendingPlacementAsset?.let { asset ->
        AlertDialog(
            onDismissRequest = { pendingPlacementAsset = null },
            containerColor = StudioPanel,
            title = { Text("Place \"${asset.name}\"", color = StudioTextPrimary) },
            text = {
                Text(
                    "Add this offline asset as a full-stage scene background or as a movable image layer on the canvas.",
                    color = StudioTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onPlaceOnCanvas(asset, false)
                        pendingPlacementAsset = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
                ) {
                    Text("Add as Layer", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            onPlaceOnCanvas(asset, true)
                            pendingPlacementAsset = null
                        },
                        border = BorderStroke(1.dp, EmeraldLayer)
                    ) {
                        Text("Set Background", color = EmeraldLayer)
                    }
                    TextButton(onClick = { pendingPlacementAsset = null }) {
                        Text("Cancel", color = StudioTextSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun MediaAssetCard(
    asset: LocalMediaAsset,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val bundledRes = BuiltInCatalog.bundledBackgroundDrawableRes(asset.bundledResKey)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioCard,
        border = BorderStroke(1.dp, StudioBorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("media_asset_${asset.id}")
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioCharcoal),
                contentAlignment = Alignment.Center
            ) {
                when {
                    bundledRes != null -> {
                        Image(
                            painter = painterResource(bundledRes),
                            contentDescription = asset.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    !asset.localFilePath.isNullOrBlank() &&
                        (asset.type == MediaAssetType.IMAGE || asset.type == MediaAssetType.GIF || asset.type == MediaAssetType.BACKGROUND) -> {
                        AsyncImage(
                            model = File(asset.localFilePath),
                            contentDescription = asset.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    asset.type == MediaAssetType.AUDIO -> {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = asset.name, tint = EmeraldLayer, modifier = Modifier.size(38.dp))
                    }
                    asset.type == MediaAssetType.VIDEO -> {
                        Icon(Icons.Rounded.Movie, contentDescription = asset.name, tint = AmberKeyframe, modifier = Modifier.size(38.dp))
                    }
                    else -> {
                        Icon(Icons.Rounded.InsertDriveFile, contentDescription = asset.name, tint = ElectricCyan, modifier = Modifier.size(36.dp))
                    }
                }

                Surface(
                    color = StudioObsidian.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = asset.type.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(StudioObsidian.copy(alpha = 0.78f))
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete asset", tint = CoralPlayhead, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Text(
                text = asset.name,
                style = MaterialTheme.typography.titleSmall,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (asset.isBundled) "Bundled Offline • ${asset.category}" else "Local Storage • ${asset.category}",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextMuted,
                maxLines = 1
            )
        }
    }
}
