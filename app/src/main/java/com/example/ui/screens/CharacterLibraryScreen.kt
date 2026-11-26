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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onOpenCharacterMakerSetup: () -> Unit,
    onEditAtlasCharacter: (CharacterRigDefinition) -> Unit,
    onImportCharacterPack: (Uri, (String) -> Unit) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    val bitmapVersion by assetStorage.bitmapVersion.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(CharacterCategory.ALL) }
    var editingCharacter by remember { mutableStateOf<CharacterRigDefinition?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var importBanner by remember { mutableStateOf<String?>(null) }

    val packImporterLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCharacterPack(uri) { msg -> importBanner = msg }
        }
    }

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
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.characters_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedButton(
                        onClick = {
                            packImporterLauncher.launch(
                                arrayOf("application/zip", "application/json", "image/svg+xml", "text/plain", "*/*")
                            )
                        },
                        border = BorderStroke(1.dp, EmeraldLayer),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("import_character_pack_btn")
                    ) {
                        Icon(Icons.Rounded.FolderZip, contentDescription = null, tint = EmeraldLayer, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Import",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldLayer,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = onOpenCharacterMakerSetup,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = StudioObsidian
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("create_character_button")
                    ) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.characters_create),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    IconButton(
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
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("quick_procedural_char_btn")
                    ) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = "Quick procedural or single-PNG rig",
                            tint = AmberKeyframe,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("close_character_library_btn")
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.nav_close),
                            tint = StudioTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
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

            importBanner?.let { msg ->
                Surface(
                    color = ElectricCyanDim,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { importBanner = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Close, "Dismiss import status", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

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
                                bitmapVersion = bitmapVersion,
                                onSelect = { onSelectCharacter(character) },
                                onEdit = {
                                    if (character.spriteAtlasRig != null || character.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO) {
                                        onEditAtlasCharacter(character)
                                    } else {
                                        isCreatingNew = false
                                        editingCharacter = character
                                    }
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
    bitmapVersion: Int,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit
) {
    val isAtlasPro = character.spriteAtlasRig != null || character.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO
    val isHdSvg = character.spriteAtlasRig?.isSvgMode == true ||
        com.example.engine.SvgAtlasRenderer.isSvgPath(character.customImagePath)
    val atlasSlices = remember(character.customImagePath, isAtlasPro, bitmapVersion) {
        if (isAtlasPro) assetStorage.getCachedOrSliceAtlas(character.customImagePath) else null
    }
    val customBitmap = remember(character.customImagePath, isAtlasPro, bitmapVersion) {
        if (!isAtlasPro) assetStorage.getCachedOrRequestBitmap(character.customImagePath, null, maxDimension = 512) else null
    }
    val hasSkeleton = character.skeletonRig?.enabled == true && character.customImagePath != null

    val badgeLabel = when {
        isHdSvg -> "HD SVG"
        isAtlasPro -> "Atlas Pro"
        hasSkeleton -> "PNG Rig"
        else -> character.category.displayName
    }
    val badgeColor = when {
        isHdSvg -> EmeraldLayer
        isAtlasPro || hasSkeleton -> ElectricCyan
        else -> AmberKeyframe
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioCard,
        border = BorderStroke(
            1.dp,
            when {
                character.isPinned -> AmberKeyframe.copy(alpha = 0.7f)
                isHdSvg -> EmeraldLayer.copy(alpha = 0.45f)
                else -> StudioBorderSubtle
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("character_card_${character.id}")
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // Dedicated top-card strip ABOVE the thumbnail so badges and action icons NEVER overlap the character's head
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable(onClick = onTogglePin)
                            .testTag("pin_char_${character.id}")
                    ) {
                        Icon(
                            imageVector = if (character.isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin character",
                            tint = if (character.isPinned) AmberKeyframe else StudioTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(StudioPanel)
                            .clickable(onClick = onEdit)
                            .testTag("edit_char_${character.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit character",
                            tint = StudioTextPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // Unobstructed character preview thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF1A2233), Color(0xFF111622)))
                    )
            ) {
                CharacterPreviewCanvas(
                    rig = character,
                    orientation = character.spriteAtlasRig?.poseTemplate?.defaultOrientation ?: CharacterOrientation.FRONT,
                    preset = AnimationPresetType.IDLE,
                    animate = false,
                    customBitmap = customBitmap,
                    atlasSlices = atlasSlices,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 4.dp)
                )
            }

            Text(
                text = character.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Compact non-clipping orientation pills (F, 3/4 F, 3/4 B, Back, Side)
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
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
