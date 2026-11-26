package com.example.ui.screens.maker

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.*
import com.example.ui.components.CharacterPreviewCanvas
import com.example.ui.theme.*

/**
 * Custom Character Setup Screen:
 * Presents the 3 pose templates (3-4 Front, Front, Back / Side) with animated pose previews
 * and a "Create" button for each template to launch the interactive Custom Character Maker.
 */
@Composable
fun CharacterSetupScreen(
    onCreateWithPose: (AtlasPoseTemplate) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.character_setup_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                    Surface(
                        color = ElectricCyanDim,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.GridView, null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                            Text(
                                text = "2K / 4K Atlas",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
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
                // Concise feature strip explaining the offline workflow
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
                                text = "Import individual transparent body-part PNGs or a full 2048×2048 / 4096×4096 sprite sheet. 100% offline.",
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
