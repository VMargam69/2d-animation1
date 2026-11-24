package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import com.example.R
import com.example.data.LocalAssetStorage
import com.example.engine.ProceduralRigRenderer
import com.example.model.*
import com.example.ui.components.CharacterPreviewCanvas
import com.example.ui.theme.*
import java.util.UUID

@Composable
fun CharacterLibraryScreen(
    characters: List<CharacterRigDefinition>,
    assetStorage: LocalAssetStorage,
    onSelectCharacter: (CharacterRigDefinition) -> Unit,
    onTogglePin: (CharacterRigDefinition) -> Unit,
    onSaveCharacter: (CharacterRigDefinition) -> Unit,
    onDeleteCharacter: (CharacterRigDefinition) -> Unit,
    onImportSpriteUri: (Uri, (String) -> Unit) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(CharacterCategory.ALL) }
    var editingCharacter by remember { mutableStateOf<CharacterRigDefinition?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    val filteredCharacters = remember(characters, searchQuery, selectedCategory) {
        characters.filter { char ->
            val matchesQuery = searchQuery.isBlank() ||
                char.name.contains(searchQuery, ignoreCase = true) ||
                char.category.displayName.contains(searchQuery, ignoreCase = true) ||
                char.bodyType.displayName.contains(searchQuery, ignoreCase = true)

            val matchesCategory = when (selectedCategory) {
                CharacterCategory.ALL -> true
                CharacterCategory.PINNED -> char.isPinned
                CharacterCategory.LATEST -> char.isLatest || char.isCustom
                else -> char.category == selectedCategory
            }
            matchesQuery && matchesCategory
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
                            Icons.Rounded.Groups,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.characters_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = StudioTextPrimary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isCreatingNew = true
                                editingCharacter = CharacterRigDefinition(
                                    id = UUID.randomUUID().toString(),
                                    name = "Custom Character",
                                    category = CharacterCategory.DESI,
                                    isCustom = true,
                                    isLatest = true
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("create_character_button")
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.characters_create),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("close_character_library_btn")
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.nav_close),
                                tint = StudioTextPrimary
                            )
                        }
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

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.characters_search_hint), color = StudioTextMuted) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = StudioTextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear search", tint = StudioTextSecondary)
                        }
                    }
                },
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
                    .testTag("search_characters_input")
            )

            // Category Filter Chips (Desi, Western, Indonesian, Village, Birds, Animals, Vehicles, Religious, Horror, Latest, Pinned)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(CharacterCategory.entries, key = { it.name }) { cat ->
                    val selected = cat == selectedCategory
                    FilterChip(
                        selected = selected,
                        onClick = { selectedCategory = cat },
                        label = {
                            Text(
                                text = cat.displayName,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        leadingIcon = if (cat == CharacterCategory.PINNED) {
                            { Icon(Icons.Rounded.PushPin, null, modifier = Modifier.size(15.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = StudioPanel,
                            labelColor = StudioTextSecondary,
                            selectedContainerColor = ElectricCyanDim,
                            selectedLabelColor = StudioTextPrimary,
                            selectedLeadingIconColor = ElectricCyan
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = StudioBorderSubtle,
                            selectedBorderColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("char_cat_${cat.name.lowercase()}")
                    )
                }
            }

            // Responsive 2-column grid (3-4 columns on tablets/landscape)
            if (filteredCharacters.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.PersonSearch, null, tint = StudioTextMuted, modifier = Modifier.size(44.dp))
                        Text("No matching characters", style = MaterialTheme.typography.titleMedium, color = StudioTextSecondary)
                        Text(
                            "Try another category or create a custom local character.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextMuted
                        )
                    }
                }
            } else {
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
                        items(filteredCharacters, key = { it.id }) { character ->
                            CharacterCard(
                                character = character,
                                assetStorage = assetStorage,
                                onSelect = { onSelectCharacter(character) },
                                onEdit = {
                                    isCreatingNew = false
                                    editingCharacter = character
                                },
                                onTogglePin = { onTogglePin(character) }
                            )
                        }
                    }
                }
            }
        }
    }

    editingCharacter?.let { target ->
        CustomCharacterStudioDialog(
            initialCharacter = target,
            isNew = isCreatingNew,
            assetStorage = assetStorage,
            onImportSpriteUri = onImportSpriteUri,
            onDismiss = { editingCharacter = null },
            onSave = { updated ->
                onSaveCharacter(updated)
                editingCharacter = null
            },
            onDelete = if (target.isCustom && !isCreatingNew) {
                {
                    onDeleteCharacter(target)
                    editingCharacter = null
                }
            } else null
        )
    }
}

@Composable
private fun CharacterCard(
    character: CharacterRigDefinition,
    assetStorage: LocalAssetStorage,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit
) {
    val customBitmap = remember(character.customImagePath) {
        assetStorage.loadBitmapFromFile(character.customImagePath, maxDimension = 512)
    }
    val hasSkeleton = character.skeletonRig?.enabled == true && character.customImagePath != null

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioCard,
        border = BorderStroke(
            1.dp,
            if (character.isPinned) AmberKeyframe.copy(alpha = 0.65f) else StudioBorderSubtle
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("character_card_${character.id}")
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(146.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF1A2233), Color(0xFF111622)))
                    )
            ) {
                CharacterPreviewCanvas(
                    rig = character,
                    orientation = CharacterOrientation.FRONT,
                    preset = AnimationPresetType.IDLE,
                    animate = false,
                    customBitmap = customBitmap,
                    modifier = Modifier.fillMaxSize()
                )

                Surface(
                    color = StudioObsidian.copy(alpha = 0.80f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = if (hasSkeleton) "PNG Rig" else character.category.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasSkeleton) EmeraldLayer else ElectricCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(StudioObsidian.copy(alpha = 0.7f))
                            .testTag("pin_char_${character.id}")
                    ) {
                        Icon(
                            imageVector = if (character.isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin character",
                            tint = if (character.isPinned) AmberKeyframe else StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(StudioObsidian.copy(alpha = 0.7f))
                            .testTag("edit_char_${character.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit character",
                            tint = StudioTextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = character.name,
                style = MaterialTheme.typography.titleSmall,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(character.supportedOrientations) { orient ->
                    Surface(
                        color = StudioPanel,
                        shape = RoundedCornerShape(5.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle)
                    ) {
                        Text(
                            text = orient.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
