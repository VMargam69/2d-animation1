package com.example.ui.screens.maker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.R
import com.example.model.*
import com.example.ui.components.CharacterPreviewCanvas
import com.example.ui.theme.*

/**
 * Custom Character Setup Screen:
 * Presents the 3 pose templates (3-4 Front, Front, Back / Side) with animated pose previews,
 * a "Create" button for each template, and a 1-tap offline ZIP/SVG/JSON pack importer.
 */
@Composable
fun CharacterSetupScreen(
    onCreateWithPose: (AtlasPoseTemplate) -> Unit,
    onImportCharacterPack: (Uri, (String) -> Unit) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    var importMessage by remember { mutableStateOf<String?>(null) }
    val packImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCharacterPack(uri) { msg -> importMessage = msg }
        }
    }

    val previewRig = remember {
        CharacterRigDefinition(
            id = "setup_template_preview",
            name = "Template Rig",
            category = CharacterCategory.DESI,
            skinColorHex = "#E8B78E",
            primaryColorHex = "#38BDF8",
            secondaryColorHex = "#1E293B",
            accentColorHex = "#F59E0B",
            hairColorHex = "#1E1B18",
            costumeStyle = HumanCostumeStyle.CASUAL_JACKET,
            hairStyle = HumanHairStyle.SIDE_PART
        )
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
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("char_setup_back_btn")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = StudioTextPrimary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.character_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "2K/4K PNG • HD SVG • Code/ZIP",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            packImportLauncher.launch(
                                arrayOf("application/zip", "application/json", "image/svg+xml", "text/plain", "*/*")
                            )
                        },
                        border = BorderStroke(1.dp, EmeraldLayer),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("setup_import_pack_btn")
                    ) {
                        Icon(Icons.Rounded.FolderZip, null, tint = EmeraldLayer, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Import Pack",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldLayer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWide = maxWidth >= 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                importMessage?.let { msg ->
                    Surface(
                        color = ElectricCyanDim,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { importMessage = null }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Close, "Dismiss", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Concise feature strip explaining the offline PNG + SVG + Code/ZIP workflow
                Surface(
                    color = StudioPanel,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StudioBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = AmberKeyframe,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Choose a Pose Rig Template",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioTextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Build with PNG or crisp vector SVG parts, import/export .fcchar.json code, or load 1-tap .zip packs completely offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AtlasPoseTemplate.entries.forEach { pose ->
                            PoseTemplateCard(
                                pose = pose,
                                previewRig = previewRig,
                                onCreate = { onCreateWithPose(pose) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    AtlasPoseTemplate.entries.forEach { pose ->
                        PoseTemplateCard(
                            pose = pose,
                            previewRig = previewRig,
                            onCreate = { onCreateWithPose(pose) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PoseTemplateCard(
    pose: AtlasPoseTemplate,
    previewRig: CharacterRigDefinition,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = when (pose) {
        AtlasPoseTemplate.THREE_QUARTER_FRONT -> ElectricCyan
        AtlasPoseTemplate.FRONT -> EmeraldLayer
        AtlasPoseTemplate.BACK_SIDE -> AmberKeyframe
    }
    val previewPreset = when (pose) {
        AtlasPoseTemplate.THREE_QUARTER_FRONT -> AnimationPresetType.WAVING
        AtlasPoseTemplate.FRONT -> AnimationPresetType.TALKING
        AtlasPoseTemplate.BACK_SIDE -> AnimationPresetType.WALK
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioCard,
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier.testTag("pose_template_card_${pose.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A2438), Color(0xFF0E1420))
                        )
                    )
            ) {
                CharacterPreviewCanvas(
                    rig = previewRig,
                    orientation = pose.defaultOrientation,
                    preset = previewPreset,
                    animate = true,
                    modifier = Modifier.fillMaxSize()
                )

                Surface(
                    color = StudioObsidian.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = pose.badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    color = StudioObsidian.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "30 Slots • IK Ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = pose.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = StudioTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = pose.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
            }

            Button(
                onClick = onCreate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("create_pose_${pose.id}")
            ) {
                Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.character_setup_create),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
