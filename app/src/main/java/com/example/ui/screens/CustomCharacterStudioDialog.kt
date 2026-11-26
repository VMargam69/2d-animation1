package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.LocalAssetStorage
import com.example.engine.PngSkeletonProcessor
import com.example.engine.ProceduralRigRenderer
import com.example.model.*
import com.example.ui.components.CharacterPreviewCanvas
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.hypot

private enum class SkeletonJointId(val label: String, val color: Color) {
    HEAD("Head", Color(0xFF38BDF8)),
    NECK("Neck", Color(0xFF38BDF8)),
    TORSO("Torso", Color(0xFF10B981)),
    PELVIS("Pelvis", Color(0xFF10B981)),
    L_SHOULDER("L Shoulder", Color(0xFFF59E0B)),
    L_HAND("L Hand", Color(0xFFF59E0B)),
    R_SHOULDER("R Shoulder", Color(0xFFF472B6)),
    R_HAND("R Hand", Color(0xFFF472B6)),
    L_HIP("L Hip", Color(0xFFA78BFA)),
    L_FOOT("L Foot", Color(0xFFA78BFA)),
    R_HIP("R Hip", Color(0xFFFB923C)),
    R_FOOT("R Foot", Color(0xFFFB923C))
}

@Composable
fun CustomCharacterStudioDialog(
    initialCharacter: CharacterRigDefinition,
    isNew: Boolean,
    assetStorage: LocalAssetStorage,
    onImportSpriteUri: (Uri, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (CharacterRigDefinition) -> Unit,
    onDelete: (() -> Unit)?
) {
    var draft by remember { mutableStateOf(initialCharacter) }
    var activeTab by remember {
        mutableIntStateOf(if (initialCharacter.customImagePath != null) 1 else 0)
    }
    var previewOrientation by remember { mutableStateOf(CharacterOrientation.FRONT) }
    var previewPreset by remember { mutableStateOf(AnimationPresetType.IDLE_HAPPY) }
    var testSkeletonAnimation by remember { mutableStateOf(false) }
    var isCleaningBg by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val customBitmap = remember(draft.customImagePath) {
        assetStorage.loadBitmapFromFile(draft.customImagePath, maxDimension = 768)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onImportSpriteUri(uri) { savedPath ->
                val bmp = assetStorage.loadBitmapFromFile(savedPath, maxDimension = 768)
                val autoSkel = if (bmp != null) {
                    PngSkeletonProcessor.autoDetectHumanoidSkeleton(bmp)
                } else {
                    SkeletonRigConfig(enabled = true)
                }
                draft = draft.copy(
                    customImagePath = savedPath,
                    skeletonRig = autoSkel,
                    isCustom = true
                )
                activeTab = 1
                testSkeletonAnimation = false
            }
        }
    }

    val outfitSwatches = listOf(
        "#E67E22", "#E11D48", "#2563EB", "#16A34A", "#7C3AED",
        "#0D9488", "#F59E0B", "#DC2626", "#1E293B", "#F8FAFC"
    )
    val skinSwatches = listOf("#F8D9C2", "#F1C4A2", "#E5B48A", "#D99B6C", "#B8784A", "#8D5524", "#5C3820")
    val hairSwatches = listOf("#141210", "#3B271B", "#6B4226", "#B45309", "#D4D4D8", "#991B1B", "#1D4ED8")
    val eyeSwatches = listOf("#2B1E16", "#1D4ED8", "#15803D", "#6B4226", "#475569")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = StudioPanel,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioCharcoal)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isNew) "Custom Character Studio" else "Edit Character Rig",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "Real-Human Costume Builder & PNG-to-Skeleton Converter",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close studio", tint = StudioTextPrimary)
                    }
                }

                // Mode Switch Tabs (Human Costume Rig vs Advanced PNG-to-Skeleton)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModeTabButton(
                        title = "Human & Costume",
                        icon = Icons.Rounded.AccessibilityNew,
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_human_costume_builder")
                    )
                    ModeTabButton(
                        title = "PNG → Skeleton Rig",
                        icon = Icons.Rounded.AutoFixHigh,
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_png_to_skeleton")
                    )
                }

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("Character Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_character_name_input")
                    )

                    if (activeTab == 0) {
                        // --- TAB 1: REAL-HUMAN & COSTUME RIG BUILDER ---
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(196.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(StudioCharcoal)
                                .border(1.dp, StudioBorderSubtle, RoundedCornerShape(14.dp))
                        ) {
                            CharacterPreviewCanvas(
                                rig = draft.copy(customImagePath = null),
                                orientation = previewOrientation,
                                preset = previewPreset,
                                animate = true,
                                customBitmap = null,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Orientation Chips at Top
                            LazyRow(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(CharacterOrientation.entries) { orient ->
                                    val sel = orient == previewOrientation
                                    Surface(
                                        color = if (sel) ElectricCyan else StudioObsidian.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable { previewOrientation = orient }
                                    ) {
                                        Text(
                                            orient.shortLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (sel) StudioObsidian else StudioTextPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            // Live Pose Tester at Bottom
                            LazyRow(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val poses = listOf(
                                    AnimationPresetType.IDLE_HAPPY,
                                    AnimationPresetType.WALK,
                                    AnimationPresetType.RUNNING,
                                    AnimationPresetType.TALKING,
                                    AnimationPresetType.WAVING,
                                    AnimationPresetType.DANCING
                                )
                                items(poses) { pose ->
                                    val sel = pose == previewPreset
                                    Surface(
                                        color = if (sel) AmberKeyframe else StudioObsidian.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable { previewPreset = pose }
                                    ) {
                                        Text(
                                            pose.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (sel) StudioObsidian else StudioTextPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (draft.customImagePath != null) {
                            OutlinedButton(
                                onClick = { draft = draft.copy(customImagePath = null, skeletonRig = null) },
                                border = BorderStroke(1.dp, AmberKeyframe),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Switch to Real-Human Vector Rig (Clear PNG)", color = AmberKeyframe)
                            }
                        }

                        SectionLabel("Category")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val cats = CharacterCategory.entries.filter {
                                it != CharacterCategory.ALL && it != CharacterCategory.PINNED && it != CharacterCategory.LATEST
                            }
                            items(cats) { cat ->
                                FilterChip(
                                    selected = draft.category == cat,
                                    onClick = { draft = draft.copy(category = cat) },
                                    label = { Text(cat.displayName) }
                                )
                            }
                        }

                        SectionLabel("Character Type")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(CharacterBodyType.entries) { body ->
                                FilterChip(
                                    selected = draft.bodyType == body,
                                    onClick = { draft = draft.copy(bodyType = body) },
                                    label = { Text(body.displayName) }
                                )
                            }
                        }

                        if (draft.bodyType == CharacterBodyType.HUMANOID) {
                            SectionLabel("Costume / Outfit Style")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(HumanCostumeStyle.entries) { style ->
                                    FilterChip(
                                        selected = draft.costumeStyle == style,
                                        onClick = { draft = draft.copy(costumeStyle = style) },
                                        label = { Text(style.displayName) },
                                        modifier = Modifier.testTag("costume_style_${style.name.lowercase()}")
                                    )
                                }
                            }

                            SectionLabel("Hair Style")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(HumanHairStyle.entries) { hair ->
                                    FilterChip(
                                        selected = draft.hairStyle == hair,
                                        onClick = { draft = draft.copy(hairStyle = hair) },
                                        label = { Text(hair.displayName) }
                                    )
                                }
                            }

                            SectionLabel("Human Body Build")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(HumanBuildType.entries) { b ->
                                    FilterChip(
                                        selected = draft.buildType == b,
                                        onClick = { draft = draft.copy(buildType = b) },
                                        label = { Text(b.displayName) }
                                    )
                                }
                            }
                        }

                        SectionLabel("Accessory")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(CharacterAccessory.entries) { acc ->
                                FilterChip(
                                    selected = draft.accessory == acc,
                                    onClick = { draft = draft.copy(accessory = acc) },
                                    label = { Text(acc.displayName) }
                                )
                            }
                        }

                        SectionLabel("Skin Tone")
                        StudioColorSwatchRow(skinSwatches, draft.skinColorHex) { draft = draft.copy(skinColorHex = it) }

                        SectionLabel("Primary Outfit Color")
                        StudioColorSwatchRow(outfitSwatches, draft.primaryColorHex) { draft = draft.copy(primaryColorHex = it) }

                        SectionLabel("Pants / Secondary Color")
                        StudioColorSwatchRow(outfitSwatches, draft.secondaryColorHex) { draft = draft.copy(secondaryColorHex = it) }

                        SectionLabel("Accent / Trim Color")
                        StudioColorSwatchRow(outfitSwatches, draft.accentColorHex) { draft = draft.copy(accentColorHex = it) }

                        SectionLabel("Hair Color")
                        StudioColorSwatchRow(hairSwatches, draft.hairColorHex) { draft = draft.copy(hairColorHex = it) }

                        SectionLabel("Eye Color")
                        StudioColorSwatchRow(eyeSwatches, draft.eyeColorHex) { draft = draft.copy(eyeColorHex = it) }
                    } else {
                        // --- TAB 2: ADVANCED PNG-TO-SKELETON & 2048x2048 MODULAR CHART CONVERTER ---
                        val currentSkeleton = draft.skeletonRig ?: SkeletonRigConfig(enabled = true)
                        Surface(
                            color = StudioCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Custom PNG Character Rigging Mode",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ElectricCyan
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CustomRigMode.entries.forEach { mode ->
                                        val selected = currentSkeleton.rigMode == mode
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                draft = draft.copy(
                                                    skeletonRig = currentSkeleton.copy(
                                                        enabled = true,
                                                        rigMode = mode
                                                    )
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = mode.displayName,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("rig_mode_${mode.name.lowercase()}")
                                        )
                                    }
                                }
                                Text(
                                    text = if (currentSkeleton.rigMode == CustomRigMode.MODULAR_CHART_2048) {
                                        "ChromaToons-style 2048×2048 10-Part Modular Texture Chart: Head, Torso, Upper Arms, Forearms+Hands, Thighs & Shins+Feet pivot rigidly inside dedicated UV zones with zero mesh stretching."
                                    } else {
                                        "Single-PNG Mesh Rig: Import any full-body PNG, remove solid background if needed, and auto-detect or drag the 12 skeleton joints."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            photoPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ElectricCyan,
                                            contentColor = StudioObsidian
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("png_converter_import_btn")
                                    ) {
                                        Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (customBitmap == null) {
                                                if (currentSkeleton.rigMode == CustomRigMode.MODULAR_CHART_2048) "Import 2048 Chart" else "Import PNG"
                                            } else {
                                                "Change PNG"
                                            },
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val path = assetStorage.saveBlank2048ModularChartTemplate()
                                                if (path != null) {
                                                    draft = draft.copy(
                                                        customImagePath = path,
                                                        skeletonRig = currentSkeleton.copy(
                                                            enabled = true,
                                                            rigMode = CustomRigMode.MODULAR_CHART_2048
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        border = BorderStroke(1.dp, EmeraldLayer),
                                        modifier = Modifier.testTag("generate_2048_chart_template_btn")
                                    ) {
                                        Icon(Icons.Rounded.GridOn, null, tint = EmeraldLayer, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("2048 Template", color = EmeraldLayer)
                                    }

                                    if (customBitmap != null && currentSkeleton.rigMode == CustomRigMode.SINGLE_PNG_MESH) {
                                        OutlinedButton(
                                            onClick = {
                                                val detected = PngSkeletonProcessor.autoDetectHumanoidSkeleton(customBitmap)
                                                val prev = draft.skeletonRig ?: SkeletonRigConfig(enabled = true)
                                                draft = draft.copy(
                                                    skeletonRig = detected.copy(
                                                        rigMode = prev.rigMode,
                                                        showBoneOverlay = prev.showBoneOverlay,
                                                        removeBackground = prev.removeBackground,
                                                        bgTolerance = prev.bgTolerance
                                                    )
                                                )
                                            },
                                            border = BorderStroke(1.dp, AmberKeyframe),
                                            modifier = Modifier.testTag("auto_rig_skeleton_btn")
                                        ) {
                                            Icon(Icons.Rounded.AutoAwesome, null, tint = AmberKeyframe, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Auto-Rig", color = AmberKeyframe)
                                        }
                                    }
                                }
                            }
                        }

                        if (customBitmap != null) {
                            val skeleton = draft.skeletonRig ?: SkeletonRigConfig(enabled = true)

                            // Toggle between Interactive Joint Drag / UV Chart Preview & Live Skeleton Animation Test
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = !testSkeletonAnimation,
                                    onClick = { testSkeletonAnimation = false },
                                    label = {
                                        Text(
                                            if (skeleton.rigMode == CustomRigMode.MODULAR_CHART_2048) {
                                                "10-Zone Chart Map"
                                            } else {
                                                "Drag Skeleton Joints"
                                            }
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Hub, null, modifier = Modifier.size(16.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = testSkeletonAnimation,
                                    onClick = { testSkeletonAnimation = true },
                                    label = { Text("Live Motion Test") },
                                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(StudioCharcoal)
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            ) {
                                if (testSkeletonAnimation) {
                                    CharacterPreviewCanvas(
                                        rig = draft.copy(skeletonRig = skeleton.copy(enabled = true)),
                                        orientation = CharacterOrientation.FRONT,
                                        preset = previewPreset,
                                        animate = true,
                                        customBitmap = customBitmap,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    LazyRow(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        val poses = listOf(
                                            AnimationPresetType.WALK,
                                            AnimationPresetType.WAVING,
                                            AnimationPresetType.RUNNING,
                                            AnimationPresetType.JUMPING,
                                            AnimationPresetType.DANCING
                                        )
                                        items(poses) { pose ->
                                            val sel = pose == previewPreset
                                            Surface(
                                                color = if (sel) AmberKeyframe else StudioObsidian.copy(alpha = 0.8f),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.clickable { previewPreset = pose }
                                            ) {
                                                Text(
                                                    pose.displayName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (sel) StudioObsidian else StudioTextPrimary,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    InteractiveSkeletonRigCanvas(
                                        bitmap = customBitmap,
                                        skeleton = skeleton,
                                        onSkeletonChanged = { updated ->
                                            draft = draft.copy(skeletonRig = updated.copy(enabled = true))
                                        }
                                    )
                                }
                            }

                            // Advanced PNG Rigging Controls: Enable Skeleton, Show Bones, Background Removal
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable Articulated Skeleton", style = MaterialTheme.typography.bodyMedium, color = StudioTextPrimary)
                                Switch(
                                    checked = skeleton.enabled,
                                    onCheckedChange = { en ->
                                        draft = draft.copy(skeletonRig = skeleton.copy(enabled = en))
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Show Bone Overlay on Stage", style = MaterialTheme.typography.bodyMedium, color = StudioTextPrimary)
                                Switch(
                                    checked = skeleton.showBoneOverlay,
                                    onCheckedChange = { show ->
                                        draft = draft.copy(skeletonRig = skeleton.copy(showBoneOverlay = show))
                                    }
                                )
                            }

                            // Offline Background Cutout Cleanup
                            Surface(
                                color = StudioCard,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StudioBorderSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Background Cutout Tolerance: ${skeleton.bgTolerance}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = StudioTextSecondary
                                        )
                                        OutlinedButton(
                                            enabled = !isCleaningBg && draft.customImagePath != null,
                                            onClick = {
                                                val src = draft.customImagePath ?: return@OutlinedButton
                                                isCleaningBg = true
                                                scope.launch {
                                                    val cleanedPath = assetStorage.cleanPngBackground(src, skeleton.bgTolerance)
                                                    if (cleanedPath != null) {
                                                        val bmp = assetStorage.loadBitmapFromFile(cleanedPath, maxDimension = 768)
                                                        val auto = bmp?.let { PngSkeletonProcessor.autoDetectHumanoidSkeleton(it) } ?: skeleton
                                                        draft = draft.copy(
                                                            customImagePath = cleanedPath,
                                                            skeletonRig = auto.copy(
                                                                enabled = true,
                                                                removeBackground = true,
                                                                bgTolerance = skeleton.bgTolerance,
                                                                showBoneOverlay = skeleton.showBoneOverlay
                                                            )
                                                        )
                                                    }
                                                    isCleaningBg = false
                                                }
                                            },
                                            border = BorderStroke(1.dp, EmeraldLayer)
                                        ) {
                                            Text(if (isCleaningBg) "Cleaning..." else "Remove BG", color = EmeraldLayer)
                                        }
                                    }
                                    Slider(
                                        value = skeleton.bgTolerance.toFloat(),
                                        onValueChange = { v ->
                                            draft = draft.copy(skeletonRig = skeleton.copy(bgTolerance = v.toInt()))
                                        },
                                        valueRange = 10f..100f
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(StudioCharcoal)
                                    .border(1.dp, StudioBorderSubtle, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Rounded.ImageSearch, null, tint = StudioTextMuted, modifier = Modifier.size(40.dp))
                                    Text("No PNG image loaded yet", style = MaterialTheme.typography.titleSmall, color = StudioTextSecondary)
                                    Text("Tap 'Import PNG' above to generate a skeletal rig.", style = MaterialTheme.typography.bodySmall, color = StudioTextMuted)
                                }
                            }
                        }
                    }

                    if (onDelete != null) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = CoralPlayhead)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete Custom Character")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }

                // Bottom Save / Cancel Footer
                HorizontalDivider(color = StudioBorderSubtle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel", color = StudioTextSecondary)
                    }
                    Button(
                        onClick = {
                            val cleanName = draft.name.trim().ifBlank { "Custom Character" }
                            val finalDraft = if (activeTab == 0) {
                                draft.copy(name = cleanName, isCustom = true)
                            } else {
                                draft.copy(
                                    name = cleanName,
                                    isCustom = true,
                                    skeletonRig = (draft.skeletonRig ?: SkeletonRigConfig(enabled = true)).copy(
                                        enabled = draft.customImagePath != null && (draft.skeletonRig?.enabled ?: true)
                                    )
                                )
                            }
                            onSave(finalDraft)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("save_custom_character_btn")
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Character", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveSkeletonRigCanvas(
    bitmap: android.graphics.Bitmap,
    skeleton: SkeletonRigConfig,
    onSkeletonChanged: (SkeletonRigConfig) -> Unit
) {
    val latestSkeleton by rememberUpdatedState(skeleton)
    var activeJoint by remember { mutableStateOf<SkeletonJointId?>(null) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val rect = fitBitmapRect(bitmap.width, bitmap.height, size.width.toFloat(), size.height.toFloat())
                            val nx = ((start.x - rect.left) / rect.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val ny = ((start.y - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            activeJoint = findClosestJoint(latestSkeleton, nx, ny)
                        },
                        onDrag = { change, _ ->
                            val joint = activeJoint ?: return@detectDragGestures
                            change.consume()
                            val rect = fitBitmapRect(bitmap.width, bitmap.height, size.width.toFloat(), size.height.toFloat())
                            val nx = ((change.position.x - rect.left) / rect.width.coerceAtLeast(1f)).coerceIn(0.02f, 0.98f)
                            val ny = ((change.position.y - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0.02f, 0.98f)
                            onSkeletonChanged(updateJoint(latestSkeleton, joint, nx, ny))
                        },
                        onDragEnd = { activeJoint = null },
                        onDragCancel = { activeJoint = null }
                    )
                }
                .testTag("skeleton_joint_editor_canvas")
        ) {
            val rect = fitBitmapRect(bitmap.width, bitmap.height, size.width, size.height)
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                dstSize = IntSize(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1))
            )

            fun pt(nx: Float, ny: Float) = Offset(rect.left + nx * rect.width, rect.top + ny * rect.height)

            if (skeleton.rigMode == CustomRigMode.MODULAR_CHART_2048) {
                com.example.engine.PropAndModularChartRenderer.MODULAR_CHART_ZONES.forEach { zone ->
                    val topLeft = pt(zone.u0, zone.v0)
                    val boxW = (zone.u1 - zone.u0) * rect.width
                    val boxH = (zone.v1 - zone.v0) * rect.height
                    drawRect(
                        color = Color(zone.guideColor),
                        topLeft = topLeft,
                        size = androidx.compose.ui.geometry.Size(boxW, boxH),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                    )
                    val pivot = Offset(
                        topLeft.x + zone.pivotU * boxW,
                        topLeft.y + zone.pivotV * boxH
                    )
                    drawCircle(color = StudioObsidian, radius = 6f, center = pivot)
                    drawCircle(color = Color(zone.guideColor), radius = 4f, center = pivot)
                }
            } else {
                val bones = listOf(
                    pt(skeleton.headX, skeleton.headY) to pt(skeleton.neckX, skeleton.neckY),
                    pt(skeleton.neckX, skeleton.neckY) to pt(skeleton.torsoX, skeleton.torsoY),
                    pt(skeleton.torsoX, skeleton.torsoY) to pt(skeleton.pelvisX, skeleton.pelvisY),
                    pt(skeleton.neckX, skeleton.neckY) to pt(skeleton.leftShoulderX, skeleton.leftShoulderY),
                    pt(skeleton.leftShoulderX, skeleton.leftShoulderY) to pt(skeleton.leftHandX, skeleton.leftHandY),
                    pt(skeleton.neckX, skeleton.neckY) to pt(skeleton.rightShoulderX, skeleton.rightShoulderY),
                    pt(skeleton.rightShoulderX, skeleton.rightShoulderY) to pt(skeleton.rightHandX, skeleton.rightHandY),
                    pt(skeleton.pelvisX, skeleton.pelvisY) to pt(skeleton.leftHipX, skeleton.leftHipY),
                    pt(skeleton.leftHipX, skeleton.leftHipY) to pt(skeleton.leftFootX, skeleton.leftFootY),
                    pt(skeleton.pelvisX, skeleton.pelvisY) to pt(skeleton.rightHipX, skeleton.rightHipY),
                    pt(skeleton.rightHipX, skeleton.rightHipY) to pt(skeleton.rightFootX, skeleton.rightFootY)
                )
                bones.forEach { (a, b) ->
                    drawLine(color = ElectricCyan, start = a, end = b, strokeWidth = 4.5f)
                }

                SkeletonJointId.entries.forEach { j ->
                    val (jx, jy) = jointCoords(skeleton, j)
                    val center = pt(jx, jy)
                    val isDragging = j == activeJoint
                    drawCircle(color = StudioObsidian, radius = if (isDragging) 14f else 10f, center = center)
                    drawCircle(color = j.color, radius = if (isDragging) 10f else 7f, center = center)
                }
            }
        }

        // Active Joint / Chart Hint Pill
        Surface(
            color = StudioObsidian.copy(alpha = 0.85f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Text(
                text = if (skeleton.rigMode == CustomRigMode.MODULAR_CHART_2048) {
                    "2048×2048 10-Zone Modular Chart (Tap Live Motion Test)"
                } else {
                    activeJoint?.let { "Dragging: ${it.label}" } ?: "Drag any colored bone dot to fit PNG"
                },
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

private data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitBitmapRect(bmpW: Int, bmpH: Int, canvasW: Float, canvasH: Float): FitRect {
    val pad = 16f
    val availW = (canvasW - pad * 2).coerceAtLeast(10f)
    val availH = (canvasH - pad * 2).coerceAtLeast(10f)
    val aspect = bmpW.toFloat() / bmpH.coerceAtLeast(1).toFloat()
    val drawW: Float
    val drawH: Float
    if (availW / availH > aspect) {
        drawH = availH
        drawW = availH * aspect
    } else {
        drawW = availW
        drawH = availW / aspect
    }
    return FitRect(
        left = (canvasW - drawW) / 2f,
        top = (canvasH - drawH) / 2f,
        width = drawW,
        height = drawH
    )
}

private fun jointCoords(s: SkeletonRigConfig, joint: SkeletonJointId): Pair<Float, Float> = when (joint) {
    SkeletonJointId.HEAD -> s.headX to s.headY
    SkeletonJointId.NECK -> s.neckX to s.neckY
    SkeletonJointId.TORSO -> s.torsoX to s.torsoY
    SkeletonJointId.PELVIS -> s.pelvisX to s.pelvisY
    SkeletonJointId.L_SHOULDER -> s.leftShoulderX to s.leftShoulderY
    SkeletonJointId.L_HAND -> s.leftHandX to s.leftHandY
    SkeletonJointId.R_SHOULDER -> s.rightShoulderX to s.rightShoulderY
    SkeletonJointId.R_HAND -> s.rightHandX to s.rightHandY
    SkeletonJointId.L_HIP -> s.leftHipX to s.leftHipY
    SkeletonJointId.L_FOOT -> s.leftFootX to s.leftFootY
    SkeletonJointId.R_HIP -> s.rightHipX to s.rightHipY
    SkeletonJointId.R_FOOT -> s.rightFootX to s.rightFootY
}

private fun findClosestJoint(s: SkeletonRigConfig, nx: Float, ny: Float): SkeletonJointId? {
    var best: SkeletonJointId? = null
    var bestDist = 0.14f
    for (j in SkeletonJointId.entries) {
        val (jx, jy) = jointCoords(s, j)
        val d = hypot(nx - jx, ny - jy)
        if (d < bestDist) {
            bestDist = d
            best = j
        }
    }
    return best
}

private fun updateJoint(s: SkeletonRigConfig, joint: SkeletonJointId, nx: Float, ny: Float): SkeletonRigConfig =
    when (joint) {
        SkeletonJointId.HEAD -> s.copy(headX = nx, headY = ny)
        SkeletonJointId.NECK -> s.copy(neckX = nx, neckY = ny)
        SkeletonJointId.TORSO -> s.copy(torsoX = nx, torsoY = ny)
        SkeletonJointId.PELVIS -> s.copy(pelvisX = nx, pelvisY = ny)
        SkeletonJointId.L_SHOULDER -> s.copy(leftShoulderX = nx, leftShoulderY = ny)
        SkeletonJointId.L_HAND -> s.copy(leftHandX = nx, leftHandY = ny)
        SkeletonJointId.R_SHOULDER -> s.copy(rightShoulderX = nx, rightShoulderY = ny)
        SkeletonJointId.R_HAND -> s.copy(rightHandX = nx, rightHandY = ny)
        SkeletonJointId.L_HIP -> s.copy(leftHipX = nx, leftHipY = ny)
        SkeletonJointId.L_FOOT -> s.copy(leftFootX = nx, leftFootY = ny)
        SkeletonJointId.R_HIP -> s.copy(rightHipX = nx, rightHipY = ny)
        SkeletonJointId.R_FOOT -> s.copy(rightFootX = nx, rightFootY = ny)
    }

@Composable
private fun ModeTabButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) ElectricCyanDim else StudioCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) ElectricCyan else StudioBorderSubtle),
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = if (selected) ElectricCyan else StudioTextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) StudioTextPrimary else StudioTextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
}

@Composable
private fun StudioColorSwatchRow(
    hexes: List<String>,
    selectedHex: String,
    onSelect: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hexes) { hex ->
            val col = Color(ProceduralRigRenderer.parseColorSafe(hex, 0xFF38BDF8.toInt()))
            val selected = hex.equals(selectedHex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(col)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) ElectricCyan else StudioBorder,
                        shape = CircleShape
                    )
                    .clickable { onSelect(hex) }
            )
        }
    }
}
