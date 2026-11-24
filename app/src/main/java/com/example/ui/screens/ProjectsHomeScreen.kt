package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BuiltInCatalog
import com.example.data.db.ProjectSummary
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectsHomeScreen(
    projects: List<ProjectSummary>,
    hasRecoverableProject: Boolean,
    onCreateProject: (name: String, templateId: String) -> Unit,
    onOpenProject: (String) -> Unit,
    onRecoverLastProject: () -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenCharacterLibrary: () -> Unit,
    onOpenMediaLibrary: () -> Unit
) {
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var selectedTemplateForDialog by remember { mutableStateOf("tpl_village_walk") }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }

    Scaffold(
        containerColor = StudioObsidian,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    selectedTemplateForDialog = "tpl_blank_stage"
                    showNewProjectDialog = true
                },
                containerColor = ElectricCyan,
                contentColor = StudioObsidian,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("new_project_fab")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.projects_new))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.projects_new), fontWeight = FontWeight.Bold)
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Studio Header Banner
            item {
                StudioHeaderCard(
                    hasRecoverableProject = hasRecoverableProject,
                    onRecoverLastProject = onRecoverLastProject,
                    onOpenCharacterLibrary = onOpenCharacterLibrary,
                    onOpenMediaLibrary = onOpenMediaLibrary
                )
            }

            // Starter Templates Strip
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Offline Starter Templates",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "100% Local • No Internet",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldLayer
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(BuiltInCatalog.sceneTemplates, key = { it.id }) { tpl ->
                            TemplateQuickCard(
                                template = tpl,
                                onClick = {
                                    selectedTemplateForDialog = tpl.id
                                    showNewProjectDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // Saved Offline Projects Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.projects_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioTextPrimary
                    )
                    Surface(
                        color = StudioPanel,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle)
                    ) {
                        Text(
                            text = "${projects.size} saved",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (projects.isEmpty()) {
                item {
                    Surface(
                        color = StudioPanel,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.MovieCreation,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                stringResource(R.string.projects_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioTextPrimary
                            )
                            Text(
                                stringResource(R.string.projects_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                        }
                    }
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectSummaryCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onRename = { renameTarget = project },
                        onDuplicate = { onDuplicateProject(project.id) },
                        onDelete = { onDeleteProject(project.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            initialTemplateId = selectedTemplateForDialog,
            onDismiss = { showNewProjectDialog = false },
            onConfirm = { name, tplId ->
                showNewProjectDialog = false
                onCreateProject(name, tplId)
            }
        )
    }

    renameTarget?.let { target ->
        RenameProjectDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameProject(target.id, newName)
                renameTarget = null
            }
        )
    }
}

@Composable
private fun StudioHeaderCard(
    hasRecoverableProject: Boolean,
    onRecoverLastProject: () -> Unit,
    onOpenCharacterLibrary: () -> Unit,
    onOpenMediaLibrary: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, StudioBorder),
        color = StudioPanel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF172034), Color(0xFF131826), Color(0xFF1B1F30))
                    )
                )
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(ElectricCyan)
                            )
                            Text(
                                text = "FRAMECRAFT STUDIO",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                        Text(
                            text = "Offline 2D Animator",
                            style = MaterialTheme.typography.headlineMedium,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "Manual rigs, keyframe timeline, onion skin & local MP4/GIF export",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hasRecoverableProject) {
                        Button(
                            onClick = onRecoverLastProject,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberKeyframe,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("resume_last_project_btn")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume", fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenCharacterLibrary,
                        border = BorderStroke(1.dp, StudioBorder),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_characters_btn")
                    ) {
                        Icon(Icons.Outlined.Groups, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Characters", color = StudioTextPrimary, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onOpenMediaLibrary,
                        border = BorderStroke(1.dp, StudioBorder),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_media_btn")
                    ) {
                        Icon(Icons.Outlined.PermMedia, contentDescription = null, tint = VioletLayer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Media", color = StudioTextPrimary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateQuickCard(
    template: BuiltInCatalog.SceneTemplate,
    onClick: () -> Unit
) {
    val bgRes = when (template.id) {
        "tpl_village_walk" -> R.drawable.img_bg_village_sunset_1795538340845
        "tpl_city_chat" -> R.drawable.img_bg_city_twilight_1795538340690
        "tpl_forest_dance" -> R.drawable.img_bg_forest_meadow_1795538340989
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioCard,
        border = BorderStroke(1.dp, StudioBorderSubtle),
        modifier = Modifier
            .width(196.dp)
            .clickable(onClick = onClick)
            .testTag("template_card_${template.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .background(StudioCharcoal),
                contentAlignment = Alignment.Center
            ) {
                if (bgRes != null) {
                    Image(
                        painter = painterResource(bgRes),
                        contentDescription = template.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.AddBox,
                        contentDescription = template.title,
                        tint = ElectricCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Surface(
                    color = StudioObsidian.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = template.aspectRatio.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = template.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = template.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProjectSummaryCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val bgRes = BuiltInCatalog.bundledBackgroundDrawableRes(project.previewBgKey)
    val dateText = remember(project.updatedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(project.updatedAt))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioCard,
        border = BorderStroke(1.dp, if (project.isLastOpened) ElectricCyan.copy(alpha = 0.55f) else StudioBorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("project_card_${project.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioCharcoal),
                contentAlignment = Alignment.Center
            ) {
                if (bgRes != null) {
                    Image(
                        painter = painterResource(bgRes),
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.Animation,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetaPill(project.aspectRatioLabel)
                    MetaPill("${project.fps} FPS")
                    MetaPill("${project.durationFrames}F")
                }
                Text(
                    text = "${project.sceneCount} scene(s) • Edited $dateText",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextMuted
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("project_menu_${project.id}")
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Project actions", tint = StudioTextSecondary)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = StudioPanel
                ) {
                    DropdownMenuItem(
                        text = { Text("Open in Editor", color = StudioTextPrimary) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = ElectricCyan) },
                        onClick = { menuExpanded = false; onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", color = StudioTextPrimary) },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null, tint = StudioTextSecondary) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate", color = StudioTextPrimary) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = StudioTextSecondary) },
                        onClick = { menuExpanded = false; onDuplicate() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = CoralPlayhead) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = CoralPlayhead) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaPill(text: String) {
    Surface(
        color = StudioPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, StudioBorderSubtle)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextSecondary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun NewProjectDialog(
    initialTemplateId: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("My Animation") }
    var templateId by remember { mutableStateOf(initialTemplateId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Create Offline Project", color = StudioTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_project_name_input")
                )
                Text("Starter Template", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuiltInCatalog.sceneTemplates.forEach { tpl ->
                        val selected = tpl.id == templateId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) ElectricCyanDim else StudioCard,
                            border = BorderStroke(1.dp, if (selected) ElectricCyan else StudioBorderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { templateId = tpl.id }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(tpl.title, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                                Text(tpl.subtitle, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, templateId) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                modifier = Modifier.testTag("confirm_create_project_btn")
            ) {
                Text("Create", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) }
        }
    )
}

@Composable
private fun RenameProjectDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Rename Project", color = StudioTextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) }
        }
    )
}
