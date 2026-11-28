package com.example.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PoseCategory
import com.example.model.SavedPose
import com.example.ui.theme.*

/**
 * Compact 2-Column Reusable IK Pose Library panel inside the IK Studio.
 * Supports category filtering, search, 1-tap Apply (with Undo), Save Current Pose,
 * Rename, and Delete with 100% offline local JSON + thumbnail storage.
 */
@Composable
fun IkPoseLibraryPanel(
    savedPoses: List<SavedPose>,
    thumbnailLoader: (String?) -> Bitmap?,
    onOpenSaveDialog: () -> Unit,
    onApplyPose: (SavedPose) -> Unit,
    onRenamePose: (poseId: String, newName: String, newCategory: PoseCategory) -> Unit,
    onDeletePose: (poseId: String) -> Unit,
    onDeletePoses: (Set<String>) -> Unit = { ids -> ids.forEach(onDeletePose) },
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<PoseCategory?>(null) }
    var posePendingRename by remember { mutableStateOf<SavedPose?>(null) }
    var posePendingDelete by remember { mutableStateOf<SavedPose?>(null) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedPoseIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    // Keep selectedPoseIds pruned to existing poses and auto-exit if library becomes empty
    LaunchedEffect(savedPoses) {
        val validIds = savedPoses.map { it.id }.toSet()
        selectedPoseIds = selectedPoseIds.intersect(validIds)
        if (savedPoses.isEmpty()) {
            isMultiSelectMode = false
        }
    }

    val filteredPoses = remember(savedPoses, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        savedPoses.filter { pose ->
            val catMatch = selectedCategory == null || pose.category == selectedCategory
            val textMatch = q.isEmpty() ||
                pose.name.lowercase().contains(q) ||
                pose.category.displayName.lowercase().contains(q)
            catMatch && textMatch
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Primary "+ Save Current Pose" CTA + Search Box + Multi-Select Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onOpenSaveDialog,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("pose_library_save_current_btn")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Save current pose",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Save Pose",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search poses...", fontSize = 12.sp, color = StudioTextSecondary) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search saved poses",
                        tint = StudioTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = StudioTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = StudioTextPrimary, fontSize = 12.sp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = StudioBorderSubtle,
                    focusedContainerColor = StudioPanel,
                    unfocusedContainerColor = StudioPanel
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("pose_library_search_input")
            )

            if (savedPoses.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        isMultiSelectMode = !isMultiSelectMode
                        if (!isMultiSelectMode) selectedPoseIds = emptySet()
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isMultiSelectMode) ElectricCyan else StudioBorderSubtle
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isMultiSelectMode) ElectricCyanDim else StudioPanel,
                        contentColor = if (isMultiSelectMode) ElectricCyan else StudioTextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("pose_library_multi_select_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (isMultiSelectMode) Icons.Rounded.Done else Icons.Rounded.Checklist,
                        contentDescription = if (isMultiSelectMode) "Exit multi-select" else "Select multiple poses",
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isMultiSelectMode) "Done" else "Select",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        // Bulk Selection Action Bar when Multi-Select Mode is active
        if (isMultiSelectMode) {
            val visibleIds = remember(filteredPoses) { filteredPoses.map { it.id }.toSet() }
            val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedPoseIds }
            Surface(
                color = StudioPanel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pose_library_multi_select_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${selectedPoseIds.size} selected",
                        color = ElectricCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            selectedPoseIds = if (allVisibleSelected) {
                                selectedPoseIds - visibleIds
                            } else {
                                selectedPoseIds + visibleIds
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("pose_library_select_all_btn")
                    ) {
                        Text(
                            text = if (allVisibleSelected) "Clear" else "Select All",
                            color = StudioTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = { showBulkDeleteConfirm = true },
                        enabled = selectedPoseIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CoralPlayhead,
                            contentColor = Color.White,
                            disabledContainerColor = StudioCard,
                            disabledContentColor = StudioTextMuted
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("pose_library_bulk_delete_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete selected poses",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Delete (${selectedPoseIds.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Category Filter Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item(key = "all") {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All (${savedPoses.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricCyanDim,
                        selectedLabelColor = ElectricCyan,
                        containerColor = StudioPanel,
                        labelColor = StudioTextSecondary
                    ),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .testTag("pose_cat_chip_all")
                )
            }
            items(PoseCategory.entries, key = { it.name }) { cat ->
                val count = savedPoses.count { it.category == cat }
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                    label = { Text("${cat.displayName} ($count)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricCyanDim,
                        selectedLabelColor = ElectricCyan,
                        containerColor = StudioPanel,
                        labelColor = StudioTextSecondary
                    ),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .testTag("pose_cat_chip_${cat.name.lowercase()}")
                )
            }
        }

        // 2-Column Reusable Pose Grid (or Helpful Empty State)
        if (filteredPoses.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(StudioPanel.copy(alpha = 0.55f))
                    .padding(16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccessibilityNew,
                        contentDescription = null,
                        tint = ElectricCyan.copy(alpha = 0.7f),
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = if (savedPoses.isEmpty()) "No saved IK poses yet" else "No poses match your filter",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (savedPoses.isEmpty()) {
                            "Drag limb handles above, then tap \"+ Save Current Pose\" to reuse it across scenes."
                        } else {
                            "Try clearing the search query or selecting All categories."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("pose_library_grid")
            ) {
                items(filteredPoses, key = { it.id }) { pose ->
                    val isChecked = pose.id in selectedPoseIds
                    SavedPoseGridCard(
                        pose = pose,
                        thumbnailLoader = thumbnailLoader,
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = isChecked,
                        onApply = {
                            if (isMultiSelectMode) {
                                selectedPoseIds = if (isChecked) selectedPoseIds - pose.id else selectedPoseIds + pose.id
                            } else {
                                onApplyPose(pose)
                            }
                        },
                        onLongPress = {
                            if (!isMultiSelectMode) {
                                isMultiSelectMode = true
                                selectedPoseIds = setOf(pose.id)
                            } else {
                                selectedPoseIds = if (isChecked) selectedPoseIds - pose.id else selectedPoseIds + pose.id
                            }
                        },
                        onRename = { posePendingRename = pose },
                        onDelete = { posePendingDelete = pose }
                    )
                }
            }
        }
    }

    posePendingRename?.let { target ->
        SaveOrRenamePoseDialog(
            title = "Rename Saved Pose",
            confirmLabel = "Update",
            initialName = target.name,
            initialCategory = target.category,
            onDismiss = { posePendingRename = null },
            onConfirm = { newName, newCategory ->
                onRenamePose(target.id, newName, newCategory)
                posePendingRename = null
            }
        )
    }

    posePendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { posePendingDelete = null },
            containerColor = StudioCharcoal,
            title = { Text("Delete Pose?", color = StudioTextPrimary) },
            text = {
                Text(
                    "Remove \"${target.name}\" from your local IK Pose Library?",
                    color = StudioTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePose(target.id)
                        posePendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralPlayhead),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { posePendingDelete = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }
    if (showBulkDeleteConfirm && selectedPoseIds.isNotEmpty()) {
        val count = selectedPoseIds.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            containerColor = StudioCharcoal,
            title = {
                Text(
                    text = if (count == 1) "Delete 1 Pose?" else "Delete $count Poses?",
                    color = StudioTextPrimary
                )
            },
            text = {
                Text(
                    text = "Remove $count selected ${if (count == 1) "pose" else "poses"} from your local IK Pose Library? This cannot be undone.",
                    color = StudioTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = selectedPoseIds
                        showBulkDeleteConfirm = false
                        isMultiSelectMode = false
                        selectedPoseIds = emptySet()
                        onDeletePoses(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralPlayhead),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("pose_library_bulk_delete_confirm_btn")
                ) {
                    Text("Delete ($count)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBulkDeleteConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedPoseGridCard(
    pose: SavedPose,
    thumbnailLoader: (String?) -> Bitmap?,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onApply: () -> Unit,
    onLongPress: () -> Unit = {},
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val thumbBitmap = remember(pose.thumbnailPath) { thumbnailLoader(pose.thumbnailPath) }

    Surface(
        color = if (isSelected) ElectricCyanDim.copy(alpha = 0.25f) else StudioPanel,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) ElectricCyan else StudioBorderSubtle
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onApply,
                onLongClick = onLongPress
            )
            .testTag("saved_pose_card_${pose.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Thumbnail Preview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .background(StudioObsidian)
            ) {
                if (thumbBitmap != null && !thumbBitmap.isRecycled) {
                    Image(
                        bitmap = thumbBitmap.asImageBitmap(),
                        contentDescription = "Pose preview for ${pose.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccessibilityNew,
                            contentDescription = "Pose silhouette for ${pose.name}",
                            tint = ElectricCyan.copy(alpha = 0.75f),
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                // Category badge
                Surface(
                    color = StudioObsidian.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = pose.category.displayName,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Multi-select checkbox badge OR overflow menu for Rename / Delete (48dp touch target)
                if (isMultiSelectMode) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) ElectricCyan else StudioObsidian.copy(alpha = 0.8f))
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected pose ${pose.name}" else "Unselected pose ${pose.name}",
                            tint = if (isSelected) StudioObsidian else StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("saved_pose_menu_${pose.id}")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(StudioObsidian.copy(alpha = 0.78f))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Pose options for ${pose.name}",
                                    tint = StudioTextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = StudioCharcoal
                        ) {
                            DropdownMenuItem(
                                text = { Text("Apply Pose", color = ElectricCyan) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = ElectricCyan)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onApply()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename", color = StudioTextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, tint = StudioTextPrimary)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = CoralPlayhead) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = CoralPlayhead)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            // Pose Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = pose.name,
                    color = StudioTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when {
                        isMultiSelectMode && isSelected -> "Selected"
                        isMultiSelectMode -> "Select"
                        else -> "Tap"
                    },
                    color = if (isSelected) ElectricCyan else if (isMultiSelectMode) StudioTextSecondary else ElectricCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * In-app Dialog used for "+ Save Current Pose" and "Rename Pose".
 */
@Composable
fun SaveOrRenamePoseDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialCategory: PoseCategory,
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: PoseCategory) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var category by remember { mutableStateOf(initialCategory) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCharcoal,
        title = {
            Text(
                text = title,
                color = StudioTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 36) name = it },
                    label = { Text("Pose Name") },
                    placeholder = { Text("e.g., Hero Landing") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = StudioBorderSubtle,
                        focusedLabelColor = ElectricCyan,
                        focusedTextColor = StudioTextPrimary,
                        unfocusedTextColor = StudioTextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_pose_name_input")
                )

                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextSecondary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PoseCategory.entries, key = { it.name }) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.displayName, fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyanDim,
                                selectedLabelColor = ElectricCyan,
                                containerColor = StudioPanel,
                                labelColor = StudioTextSecondary
                            ),
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .testTag("save_pose_cat_${cat.name.lowercase()}")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = name.trim().ifBlank { "Custom Pose" }
                    onConfirm(clean, category)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = StudioObsidian
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save_pose_confirm_btn")
            ) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel", color = StudioTextSecondary)
            }
        }
    )
}
