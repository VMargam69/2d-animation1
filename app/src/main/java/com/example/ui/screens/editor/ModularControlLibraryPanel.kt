package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.*
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Reference Matte-Dark Control Palette
private val ControlBg = Color(0xFF121316)
private val ControlCardBg = Color(0xFF181A1E)
private val ControlCardSelectedBg = Color(0xFF1D2433)
private val ControlBorder = Color(0xFF26282E)
private val ControlDivider = Color(0xFF21242B)
private val ControlBlue = Color(0xFF3B82F6)
private val ControlTextPrimary = Color(0xFFF3F4F6)
private val ControlTextSecondary = Color(0xFF9CA3AF)
private val SilhouetteWhite = Color(0xFFF5F6F8)
private val SilhouetteSoftWhite = Color(0xFFE5E7EB)
private val SilhouetteInk = Color(0xFF14161A)

/**
 * Pure JVM-testable helper computing the 2D silhouette limb endpoints for a [ModularActionSample].
 * Uses the EXACT Android Canvas FK rotation convention of [HumanRigDrawer] and [SpriteAtlasRigRenderer]:
 * - Hanging vector `(0, +L)` rotated by `canvas.rotate(deg)` maps to `(ox - sin(deg)*L, oy + cos(deg)*L)`.
 * - Forearm total angle = `upperArmDeg + forearmDeg`; Shin total angle = `thighDeg + kneeDeg`.
 * - Left arm: `+arm` swings outward-left/up, `-forearm` folds inward across torso/face.
 * - Right arm: `-arm` swings outward-right/up, `+forearm` folds inward across torso/face.
 */
data class SilhouetteRigGeometry(
    val headCenterX: Float,
    val headCenterY: Float,
    val headRadius: Float,
    val neckX: Float,
    val neckY: Float,
    val hipX: Float,
    val hipY: Float,
    val leftShoulder: Pair<Float, Float>,
    val leftElbow: Pair<Float, Float>,
    val leftHand: Pair<Float, Float>,
    val rightShoulder: Pair<Float, Float>,
    val rightElbow: Pair<Float, Float>,
    val rightHand: Pair<Float, Float>,
    val leftHip: Pair<Float, Float>,
    val leftKnee: Pair<Float, Float>,
    val leftFoot: Pair<Float, Float>,
    val rightHip: Pair<Float, Float>,
    val rightKnee: Pair<Float, Float>,
    val rightFoot: Pair<Float, Float>,
    val headTiltDeg: Float
)

object SilhouettePreviewGeometryBuilder {

    /**
     * Exact Android Canvas forward-kinematics for a downward-hanging limb `(0, +len)` rotated by
     * `canvas.rotate(angleDeg)`.
     */
    private fun jointEnd(ox: Float, oy: Float, len: Float, angleDeg: Float): Pair<Float, Float> {
        val rad = angleDeg * (PI / 180.0)
        return Pair(
            ox - sin(rad).toFloat() * len,
            oy + cos(rad).toFloat() * len
        )
    }

    fun build(
        action: ModularActionDef,
        sample: ModularActionSample,
        width: Float,
        height: Float
    ): SilhouetteRigGeometry {
        val u = min(width, height).coerceAtLeast(1f)
        val cx = width * 0.5f
        val bobY = (sample.bodyBobDelta / 100f) * u * 0.28f
        val torsoTiltRad = (sample.torsoTiltDelta.coerceIn(-45f, 45f) * PI / 180.0).toFloat()

        val isLegs = action.track == ModularActionTrack.LEGS
        val isHands = action.track == ModularActionTrack.HANDS
        val isFacial = action.track == ModularActionTrack.FACIAL

        // Isolated part framing so Head, Hands, and Legs each sit cleanly centered in their card
        val hipX = cx
        val hipY = when {
            isLegs -> height * 0.24f + bobY * 0.45f
            isHands -> height * 0.72f + bobY * 0.3f
            else -> height * 0.94f
        }
        val torsoLen = when {
            isLegs -> u * 0.20f
            isHands -> u * 0.38f
            else -> u * 0.28f
        }
        val neckX = hipX + sin(torsoTiltRad) * torsoLen
        val neckY = if (isHands) height * 0.30f + bobY * 0.25f else hipY - cos(torsoTiltRad) * torsoLen

        val headR = when {
            isFacial -> u * 0.33f
            isHands -> u * 0.12f
            else -> u * 0.09f
        }
        val headCx = if (isFacial) cx + sample.gazeX * u * 0.03f else neckX + sin(torsoTiltRad) * headR * 1.1f
        val headCy = if (isFacial) height * 0.50f + (sample.headNodDelta * 0.002f * u) else neckY - headR * 1.15f

        // Isolated Arms framing (pair of arms spaced naturally, or centered single right arm)
        val isSingleRight = sample.silhouetteStyle == "single_right_arm"
        val shoulderHalfW = when {
            isHands && isSingleRight -> u * 0.04f
            isHands -> u * 0.175f
            else -> u * 0.10f
        }
        val upperArmLen = if (isHands) u * 0.29f else u * 0.15f
        val foreArmLen = if (isHands) u * 0.26f else u * 0.14f

        val lSh = Pair(neckX - shoulderHalfW, neckY)
        val rSh = Pair(if (isSingleRight && isHands) cx else neckX + shoulderHalfW, neckY)

        // Exact Canvas FK angles: Left +arm = outward-left, -forearm = fold inward; Right -arm = outward-right, +forearm = fold inward
        val lArmDeg = sample.leftArmDelta
        val lForeDeg = lArmDeg + sample.leftForearmDelta
        val rArmDeg = sample.rightArmDelta
        val rForeDeg = rArmDeg + sample.rightForearmDelta

        val lEl = jointEnd(lSh.first, lSh.second, upperArmLen, lArmDeg)
        val lHa = jointEnd(lEl.first, lEl.second, foreArmLen, lForeDeg)
        val rEl = jointEnd(rSh.first, rSh.second, upperArmLen, rArmDeg)
        val rHa = jointEnd(rEl.first, rEl.second, foreArmLen, rForeDeg)

        // Isolated Legs framing
        val hipHalfW = if (isLegs) u * 0.115f else u * 0.06f
        val thighLen = if (isLegs) u * 0.34f else u * 0.19f
        val shinLen = if (isLegs) u * 0.33f else u * 0.18f
        val lHp = Pair(hipX - hipHalfW, hipY)
        val rHp = Pair(hipX + hipHalfW, hipY)

        val lThighDeg = sample.leftLegDelta
        val lShinDeg = lThighDeg + sample.leftKneeDelta
        val rThighDeg = sample.rightLegDelta
        val rShinDeg = rThighDeg + sample.rightKneeDelta

        val lKn = jointEnd(lHp.first, lHp.second, thighLen, lThighDeg)
        val lFt = jointEnd(lKn.first, lKn.second, shinLen, lShinDeg)
        val rKn = jointEnd(rHp.first, rHp.second, thighLen, rThighDeg)
        val rFt = jointEnd(rKn.first, rKn.second, shinLen, rShinDeg)

        return SilhouetteRigGeometry(
            headCenterX = headCx,
            headCenterY = headCy,
            headRadius = headR,
            neckX = neckX,
            neckY = neckY,
            hipX = hipX,
            hipY = hipY,
            leftShoulder = lSh,
            leftElbow = lEl,
            leftHand = lHa,
            rightShoulder = rSh,
            rightElbow = rEl,
            rightHand = rHa,
            leftHip = lHp,
            leftKnee = lKn,
            leftFoot = lFt,
            rightHip = rHp,
            rightKnee = rKn,
            rightFoot = rFt,
            headTiltDeg = sample.headNodDelta
        )
    }
}

/**
 * Pro "Control" Modular Action Library Panel matching the crisp matte-dark reference workflow:
 * 1. Search bar (`Search...`) + `Reset` button.
 * 2. Stacked `Speaking` (`Auto Lip Sync` | `Yes` | `No`) & `Eyeblink` (`Enable` | `Disable` | `Eye Closed`)
 *    radio groups with Studio Blue selection dots and icons.
 * 3. 3 Searchable 2-Column Grids (180 Desi/Story presets) with **24fps Isolated White Silhouettes**:
 *    - Expressions (53 presets): sculpted cartoon head ONLY
 *    - Hand Parts (88 presets): isolated arms & hands ONLY (or single right arm for R-part presets)
 *    - Leg Parts (39 presets): isolated legs & shoes ONLY (including cross-legged, figure-four, seated profile)
 * 4. Sticky pure-white `Apply Changes` button layering selections non-destructively with Undo.
 */
@Composable
fun ModularControlLibraryPanel(
    initialSelection: ModularControlSelection,
    onSelectionPreviewChanged: (ModularControlSelection, ModularActionDef?) -> Unit,
    onApplyChanges: (ModularControlSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeSectionFilter by remember { mutableStateOf<ModularActionTrack?>(null) }
    var selection by remember(initialSelection) { mutableStateOf(initialSelection) }
    var lastTappedAction by remember { mutableStateOf<ModularActionDef?>(null) }

    // Smooth 24fps shared animation clock driving all visible isolated white silhouettes
    var animTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(42L) // ~24fps silky procedural animation
            animTick = (animTick + 1) % 100_000
        }
    }

    LaunchedEffect(selection, lastTappedAction) {
        onSelectionPreviewChanged(selection, lastTappedAction)
    }

    val filteredExpressions = remember(searchQuery, activeSectionFilter) {
        if (activeSectionFilter != null && activeSectionFilter != ModularActionTrack.FACIAL) emptyList()
        else ModularActionEngine.search(searchQuery, ModularActionTrack.FACIAL)
    }
    val filteredHands = remember(searchQuery, activeSectionFilter) {
        if (activeSectionFilter != null && activeSectionFilter != ModularActionTrack.HANDS) emptyList()
        else ModularActionEngine.search(searchQuery, ModularActionTrack.HANDS)
    }
    val filteredLegs = remember(searchQuery, activeSectionFilter) {
        if (activeSectionFilter != null && activeSectionFilter != ModularActionTrack.LEGS) emptyList()
        else ModularActionEngine.search(searchQuery, ModularActionTrack.LEGS)
    }
    val totalVisible = filteredExpressions.size + filteredHands.size + filteredLegs.size

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ControlBg)
            .testTag("modular_control_library_panel")
    ) {
        // ─── 1. STICKY SEARCH BAR + QUICK RESET & SECTION FILTER STRIP ───
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ControlBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = ControlCardBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ControlBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search modular actions",
                            tint = ControlTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search...",
                                    color = ControlTextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = ControlTextPrimary, fontSize = 13.sp),
                                cursorBrush = SolidColor(ControlBlue),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("control_search_input")
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("control_search_clear_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear search",
                                    tint = ControlTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        searchQuery = ""
                        activeSectionFilter = null
                        selection = ModularControlSelection()
                        lastTappedAction = null
                    },
                    border = BorderStroke(1.dp, ControlBorder),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = ControlCardBg),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("control_reset_btn")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = "Reset modular control selections",
                        tint = ControlTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Reset", color = ControlTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Compact Section Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SectionFilterChip(
                    label = "All (${ModularActionEngine.catalog.size})",
                    selected = activeSectionFilter == null,
                    testTag = "control_filter_all",
                    onClick = { activeSectionFilter = null }
                )
                SectionFilterChip(
                    label = "Expressions (${ModularActionEngine.actionsForTrack(ModularActionTrack.FACIAL).size})",
                    selected = activeSectionFilter == ModularActionTrack.FACIAL,
                    testTag = "control_filter_facial",
                    onClick = { activeSectionFilter = ModularActionTrack.FACIAL }
                )
                SectionFilterChip(
                    label = "Hand Parts (${ModularActionEngine.actionsForTrack(ModularActionTrack.HANDS).size})",
                    selected = activeSectionFilter == ModularActionTrack.HANDS,
                    testTag = "control_filter_hands",
                    onClick = { activeSectionFilter = ModularActionTrack.HANDS }
                )
                SectionFilterChip(
                    label = "Leg Parts (${ModularActionEngine.actionsForTrack(ModularActionTrack.LEGS).size})",
                    selected = activeSectionFilter == ModularActionTrack.LEGS,
                    testTag = "control_filter_legs",
                    onClick = { activeSectionFilter = ModularActionTrack.LEGS }
                )
            }
        }

        // ─── 2. SCROLLABLE SPEAKING + EYEBLINK RADIOS & 2-COLUMN SILHOUETTE GRIDS ───
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("modular_actions_grid")
        ) {
            // Stacked Speaking & Eyeblink cards (hidden only when user actively searches for a specific preset)
            if (searchQuery.isBlank() && activeSectionFilter == null) {
                item(span = { GridItemSpan(2) }, key = "control_speaking_card") {
                    StackedRadioGroupCard(
                        title = "Speaking",
                        options = listOf(
                            Triple(CharacterSpeakingMode.AUTO_LIP_SYNC, CharacterSpeakingMode.AUTO_LIP_SYNC.displayName, Icons.Rounded.GraphicEq),
                            Triple(CharacterSpeakingMode.YES, CharacterSpeakingMode.YES.displayName, Icons.Rounded.RecordVoiceOver),
                            Triple(CharacterSpeakingMode.NO, CharacterSpeakingMode.NO.displayName, Icons.Rounded.SentimentNeutral)
                        ),
                        selected = selection.speakingMode,
                        tagPrefix = "control_speaking",
                        onSelect = { mode -> selection = selection.copy(speakingMode = mode) }
                    )
                }

                item(span = { GridItemSpan(2) }, key = "control_eyeblink_card") {
                    StackedRadioGroupCard(
                        title = "Eyeblink",
                        options = listOf(
                            Triple(CharacterEyeblinkMode.ENABLE, CharacterEyeblinkMode.ENABLE.displayName, null),
                            Triple(CharacterEyeblinkMode.DISABLE, CharacterEyeblinkMode.DISABLE.displayName, null),
                            Triple(CharacterEyeblinkMode.EYE_CLOSED, CharacterEyeblinkMode.EYE_CLOSED.displayName, null)
                        ),
                        selected = selection.eyeblinkMode,
                        tagPrefix = "control_eyeblink",
                        onSelect = { mode -> selection = selection.copy(eyeblinkMode = mode) }
                    )
                }
            }

            if (totalVisible == 0) {
                item(span = { GridItemSpan(2) }, key = "empty_search_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SearchOff,
                            contentDescription = null,
                            tint = ControlTextSecondary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "No presets match \"$searchQuery\"",
                            color = ControlTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                if (filteredExpressions.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "header_expressions") {
                        ModularSectionHeader(
                            title = "Expressions",
                            selectedName = ModularActionEngine.findById(selection.expressionId)?.name,
                            onClearSection = { selection = selection.copy(expressionId = null) }
                        )
                    }
                    items(filteredExpressions, key = { it.id }) { action ->
                        val isSelected = selection.expressionId == action.id
                        LiveSilhouetteActionCard(
                            action = action,
                            isSelected = isSelected,
                            animTick = animTick,
                            onClick = {
                                val nextId = if (isSelected) null else action.id
                                selection = selection.copy(expressionId = nextId)
                                lastTappedAction = if (nextId != null) action else null
                            }
                        )
                    }
                }

                if (filteredHands.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "header_hands") {
                        ModularSectionHeader(
                            title = "Hand Parts",
                            selectedName = ModularActionEngine.findById(selection.handId)?.name,
                            onClearSection = { selection = selection.copy(handId = null) }
                        )
                    }
                    items(filteredHands, key = { it.id }) { action ->
                        val isSelected = selection.handId == action.id
                        LiveSilhouetteActionCard(
                            action = action,
                            isSelected = isSelected,
                            animTick = animTick,
                            onClick = {
                                val nextId = if (isSelected) null else action.id
                                selection = selection.copy(handId = nextId)
                                lastTappedAction = if (nextId != null) action else null
                            }
                        )
                    }
                }

                if (filteredLegs.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "header_legs") {
                        ModularSectionHeader(
                            title = "Leg Parts",
                            selectedName = ModularActionEngine.findById(selection.legId)?.name,
                            onClearSection = { selection = selection.copy(legId = null) }
                        )
                    }
                    items(filteredLegs, key = { it.id }) { action ->
                        val isSelected = selection.legId == action.id
                        LiveSilhouetteActionCard(
                            action = action,
                            isSelected = isSelected,
                            animTick = animTick,
                            onClick = {
                                val nextId = if (isSelected) null else action.id
                                selection = selection.copy(legId = nextId)
                                lastTappedAction = if (nextId != null) action else null
                            }
                        )
                    }
                }
            }
        }

        // ─── 3. STICKY PURE-WHITE `Apply Changes` BAR ───
        HorizontalDivider(color = ControlBorder)
        Surface(
            color = ControlBg,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val summaryParts = buildList {
                    ModularActionEngine.findById(selection.expressionId)?.let { add(it.name) }
                    ModularActionEngine.findById(selection.handId)?.let { add(it.name) }
                    ModularActionEngine.findById(selection.legId)?.let { add(it.name) }
                }
                if (summaryParts.isNotEmpty()) {
                    Text(
                        text = summaryParts.joinToString("  •  "),
                        color = ControlTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = { onApplyChanges(selection) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0F1115)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("control_apply_changes_btn")
                ) {
                    Text(
                        text = if (selection.activeCount > 0) {
                            "Apply Changes (${selection.activeCount})"
                        } else {
                            "Apply Changes"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> StackedRadioGroupCard(
    title: String,
    options: List<Triple<T, String, ImageVector?>>,
    selected: T,
    tagPrefix: String,
    onSelect: (T) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = ControlTextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            color = ControlCardBg,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ControlBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { idx, (value, label, icon) ->
                    val isSelected = value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("${tagPrefix}_${label.lowercase().replace(" ", "_")}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Crisp Studio Blue Radio Ring
                        Canvas(modifier = Modifier.size(16.dp)) {
                            if (isSelected) {
                                drawCircle(color = ControlBlue, radius = size.minDimension * 0.48f)
                                drawCircle(color = Color.White, radius = size.minDimension * 0.20f)
                            } else {
                                drawCircle(
                                    color = Color(0xFFD1D5DB),
                                    radius = size.minDimension * 0.44f,
                                    style = Stroke(width = 1.6.dp.toPx())
                                )
                            }
                        }
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = ControlTextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = label,
                            color = ControlTextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                    if (idx < options.lastIndex) {
                        HorizontalDivider(color = ControlDivider, thickness = 0.8.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionFilterChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) ControlBlue.copy(alpha = 0.20f) else ControlCardBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) ControlBlue else ControlBorder),
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                color = if (selected) Color(0xFF93C5FD) else ControlTextSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ModularSectionHeader(
    title: String,
    selectedName: String?,
    onClearSection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = ControlTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (selectedName != null) {
            Surface(
                color = ControlBlue.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, ControlBlue.copy(alpha = 0.6f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClearSection)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = selectedName,
                        color = Color(0xFF93C5FD),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear $title",
                        tint = Color(0xFF93C5FD),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveSilhouetteActionCard(
    action: ModularActionDef,
    isSelected: Boolean,
    animTick: Int,
    onClick: () -> Unit
) {
    val cycleTicks = action.durationFrames.coerceIn(16, 48)
    val progress = ((animTick % cycleTicks).toFloat() / cycleTicks.toFloat()).coerceIn(0f, 1f)

    Surface(
        color = if (isSelected) ControlCardSelectedBg else ControlCardBg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isSelected) 1.6.dp else 1.dp,
            color = if (isSelected) ControlBlue else ControlBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("modular_action_card_${action.id}")
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiveSilhouettePreviewCanvas(
                    action = action,
                    progress = progress,
                    accent = ControlBlue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                )

                Text(
                    text = action.name,
                    color = if (isSelected) Color.White else ControlTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(ControlBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

/**
 * Pixel-perfect 24fps isolated white-silhouette Canvas renderer (zero GIF/video assets).
 * Renders ONLY the sculpted part matching each track:
 * - FACIAL -> sculpted white cartoon head ONLY
 * - HANDS -> isolated white cartoon arms & hands ONLY (or single right arm for R-part presets)
 * - LEGS -> isolated white cartoon legs & shoes ONLY (including cross-legged, figure-four, seated profile)
 */
@Composable
fun LiveSilhouettePreviewCanvas(
    action: ModularActionDef,
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 2f || h <= 2f) return@Canvas

        val sample = ModularActionEngine.evaluateAtProgress(action, progress)
        val geom = SilhouettePreviewGeometryBuilder.build(action, sample, w, h)

        when (action.track) {
            ModularActionTrack.FACIAL -> drawIsolatedHeadSilhouette(geom, sample)
            ModularActionTrack.HANDS -> drawIsolatedArmsSilhouette(geom, sample)
            ModularActionTrack.LEGS -> drawIsolatedLegsSilhouette(geom, sample)
        }
    }
}

/**
 * Isolated sculpted white cartoon head ONLY (matching Reference Screenshot 1):
 * Rounded hair crest, subtle ear nub, crisp dark eyebrows, expressive eyes with gaze/blink/arcs,
 * nose mark, expressive mouth with teeth/tongue details, and head turn for left/right/behind.
 */
private fun DrawScope.drawIsolatedHeadSilhouette(
    g: SilhouetteRigGeometry,
    sample: ModularActionSample
) {
    val r = g.headRadius.coerceAtLeast(10f)
    val cx = g.headCenterX
    val cy = g.headCenterY
    val style = sample.silhouetteStyle

    val headTurnX = when (style) {
        "head_turn_left" -> -0.26f
        "head_turn_right" -> 0.26f
        "head_turn_behind" -> 0.34f
        else -> (sample.gazeX * 0.10f).coerceIn(-0.18f, 0.18f)
    }

    withTransform({
        rotate(degrees = g.headTiltDeg.coerceIn(-22f, 22f), pivot = Offset(cx, cy))
        scale(
            scaleX = sample.squashX.coerceIn(0.88f, 1.14f),
            scaleY = sample.squashY.coerceIn(0.88f, 1.14f),
            pivot = Offset(cx, cy)
        )
    }) {
        // Subtle back/side ear nub
        val earSide = if (headTurnX >= 0f) -1f else 1f
        drawOval(
            color = SilhouetteSoftWhite,
            topLeft = Offset(cx + earSide * r * 0.88f - r * 0.16f, cy - r * 0.12f),
            size = Size(r * 0.32f, r * 0.42f)
        )

        // Sculpted cartoon head contour (slightly taller jaw + rounded hair crown)
        val headPath = Path().apply {
            moveTo(cx - r * 0.84f, cy - r * 0.18f)
            cubicTo(
                cx - r * 0.90f, cy - r * 0.94f,
                cx - r * 0.30f, cy - r * 1.10f,
                cx + r * 0.08f, cy - r * 1.06f
            )
            cubicTo(
                cx + r * 0.66f, cy - r * 1.04f,
                cx + r * 0.94f, cy - r * 0.72f,
                cx + r * 0.88f, cy - r * 0.12f
            )
            cubicTo(
                cx + r * 0.86f, cy + r * 0.56f,
                cx + r * 0.44f, cy + r * 0.98f,
                cx, cy + r * 0.98f
            )
            cubicTo(
                cx - r * 0.46f, cy + r * 0.98f,
                cx - r * 0.84f, cy + r * 0.54f,
                cx - r * 0.84f, cy - r * 0.18f
            )
            close()
        }
        drawPath(path = headPath, color = SilhouetteWhite)

        // If turned almost fully behind, show minimal hair contour lines only
        if (style == "head_turn_behind") {
            drawArc(
                color = SilhouetteInk.copy(alpha = 0.25f),
                startAngle = 210f,
                sweepAngle = 115f,
                useCenter = false,
                topLeft = Offset(cx - r * 0.55f, cy - r * 0.68f),
                size = Size(r * 1.10f, r * 0.78f),
                style = Stroke(width = (r * 0.07f).coerceAtLeast(1.2f), cap = StrokeCap.Round)
            )
            return@withTransform
        }

        val fcx = cx + headTurnX * r
        val eyeDx = r * 0.30f
        val eyeY = cy - r * 0.06f + sample.gazeY * r * 0.10f
        val gazeShiftX = sample.gazeX * r * 0.12f
        val isClosed = sample.blinkOverride == true || sample.eyeShapeOverride == EyeShapeOverride.CLOSED_SLEEP
        val isHappyArc = sample.eyeShapeOverride == EyeShapeOverride.HAPPY_ARC
        val isWide = sample.eyeShapeOverride == EyeShapeOverride.WIDE_SHOCK
        val isSquint = sample.eyeShapeOverride == EyeShapeOverride.SQUINT_ANGRY
        val isWinkRight = sample.eyeShapeOverride == EyeShapeOverride.WINK_RIGHT

        // Eyebrows (crisp cartoon strokes)
        val browY = eyeY - r * 0.28f
        val browTilt = when (sample.eyebrowShapeOverride) {
            EyebrowShapeOverride.FURROWED_ANGRY -> r * 0.12f
            EyebrowShapeOverride.WORRIED_SAD -> -r * 0.11f
            EyebrowShapeOverride.RAISED_SURPRISE -> -r * 0.14f
            else -> 0f
        }
        val browLift = when (sample.eyebrowShapeOverride) {
            EyebrowShapeOverride.RAISED_SURPRISE -> -r * 0.08f
            EyebrowShapeOverride.FURROWED_ANGRY -> r * 0.04f
            else -> 0f
        }
        listOf(-1f, 1f).forEach { side ->
            val bx = fcx + side * eyeDx
            val innerX = bx - side * r * 0.15f
            val outerX = bx + side * r * 0.17f
            val innerY = browY + browLift + browTilt
            val outerY = browY + browLift - browTilt * 0.45f
            drawLine(
                color = SilhouetteInk,
                start = Offset(innerX, innerY),
                end = Offset(outerX, outerY),
                strokeWidth = (r * 0.115f).coerceAtLeast(1.8f),
                cap = StrokeCap.Round
            )
        }

        // Eyes (expressive crisp dark ink)
        listOf(-1f, 1f).forEach { side ->
            val ex = fcx + side * eyeDx + gazeShiftX
            val closedHere = isClosed || (isWinkRight && side > 0f)
            when {
                closedHere -> {
                    drawArc(
                        color = SilhouetteInk,
                        startAngle = 15f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(ex - r * 0.15f, eyeY - r * 0.06f),
                        size = Size(r * 0.30f, r * 0.16f),
                        style = Stroke(width = (r * 0.10f).coerceAtLeast(1.6f), cap = StrokeCap.Round)
                    )
                }
                isHappyArc -> {
                    drawArc(
                        color = SilhouetteInk,
                        startAngle = 195f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(ex - r * 0.15f, eyeY - r * 0.09f),
                        size = Size(r * 0.30f, r * 0.22f),
                        style = Stroke(width = (r * 0.10f).coerceAtLeast(1.6f), cap = StrokeCap.Round)
                    )
                }
                else -> {
                    val ew = when {
                        isWide -> r * 0.15f
                        isSquint -> r * 0.13f
                        else -> r * 0.12f
                    }
                    val eh = when {
                        isWide -> r * 0.17f
                        isSquint -> r * 0.075f
                        else -> r * 0.13f
                    }
                    drawOval(
                        color = SilhouetteInk,
                        topLeft = Offset(ex - ew, eyeY - eh),
                        size = Size(ew * 2f, eh * 2f)
                    )
                    if (!isSquint) {
                        drawCircle(
                            color = SilhouetteWhite,
                            radius = (r * 0.04f).coerceAtLeast(0.8f),
                            center = Offset(ex + ew * 0.28f, eyeY - eh * 0.28f)
                        )
                    }
                }
            }
        }

        // Subtle cartoon nose mark
        val noseY = cy + r * 0.16f
        drawArc(
            color = SilhouetteInk.copy(alpha = 0.65f),
            startAngle = 25f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(fcx - r * 0.07f, noseY - r * 0.05f),
            size = Size(r * 0.14f, r * 0.11f),
            style = Stroke(width = (r * 0.065f).coerceAtLeast(1.1f), cap = StrokeCap.Round)
        )

        // Expressive Mouth
        val my = cy + r * 0.44f
        when (sample.mouthShapeOverride) {
            MouthShapeOverride.SMILE, MouthShapeOverride.SMIRK -> {
                drawArc(
                    color = SilhouetteInk,
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(fcx - r * 0.24f, my - r * 0.14f),
                    size = Size(r * 0.48f, r * 0.26f),
                    style = Stroke(width = (r * 0.10f).coerceAtLeast(1.7f), cap = StrokeCap.Round)
                )
            }
            MouthShapeOverride.OPEN_LAUGH, MouthShapeOverride.TALK_AA -> {
                drawOval(
                    color = SilhouetteInk,
                    topLeft = Offset(fcx - r * 0.21f, my - r * 0.11f),
                    size = Size(r * 0.42f, r * 0.28f)
                )
                // Upper teeth hint
                drawRoundRect(
                    color = SilhouetteWhite,
                    topLeft = Offset(fcx - r * 0.14f, my - r * 0.09f),
                    size = Size(r * 0.28f, r * 0.065f),
                    cornerRadius = CornerRadius(r * 0.03f, r * 0.03f)
                )
            }
            MouthShapeOverride.SHOCK_O -> {
                drawOval(
                    color = SilhouetteInk,
                    topLeft = Offset(fcx - r * 0.14f, my - r * 0.10f),
                    size = Size(r * 0.28f, r * 0.28f)
                )
            }
            MouthShapeOverride.TALK_EE, MouthShapeOverride.GRIT_ANGRY -> {
                drawRoundRect(
                    color = SilhouetteInk,
                    topLeft = Offset(fcx - r * 0.24f, my - r * 0.06f),
                    size = Size(r * 0.48f, r * 0.16f),
                    cornerRadius = CornerRadius(r * 0.06f, r * 0.06f)
                )
                drawLine(
                    color = SilhouetteWhite,
                    start = Offset(fcx - r * 0.18f, my + r * 0.02f),
                    end = Offset(fcx + r * 0.18f, my + r * 0.02f),
                    strokeWidth = (r * 0.04f).coerceAtLeast(0.8f)
                )
            }
            MouthShapeOverride.FROWN -> {
                drawArc(
                    color = SilhouetteInk,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(fcx - r * 0.22f, my - r * 0.02f),
                    size = Size(r * 0.44f, r * 0.22f),
                    style = Stroke(width = (r * 0.095f).coerceAtLeast(1.6f), cap = StrokeCap.Round)
                )
            }
            else -> {
                drawLine(
                    color = SilhouetteInk,
                    start = Offset(fcx - r * 0.17f, my + r * 0.02f),
                    end = Offset(fcx + r * 0.17f, my + r * 0.02f),
                    strokeWidth = (r * 0.09f).coerceAtLeast(1.5f),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Draws a tapered cartoon limb segment (thick at proximal joint, sleeker at distal joint)
 * so arms and legs read like authentic vector character cutouts instead of thin stick lines.
 */
private fun DrawScope.drawTaperedLimb(
    start: Offset,
    end: Offset,
    startRadius: Float,
    endRadius: Float,
    color: Color
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (len < 0.5f) {
        drawCircle(color = color, radius = startRadius, center = start)
        return
    }
    val nx = -dy / len
    val ny = dx / len
    val path = Path().apply {
        moveTo(start.x + nx * startRadius, start.y + ny * startRadius)
        lineTo(end.x + nx * endRadius, end.y + ny * endRadius)
        lineTo(end.x - nx * endRadius, end.y - ny * endRadius)
        lineTo(start.x - nx * startRadius, start.y - ny * startRadius)
        close()
    }
    drawPath(path = path, color = color)
    drawCircle(color = color, radius = startRadius, center = start)
    drawCircle(color = color, radius = endRadius, center = end)
}

/**
 * Isolated white cartoon arms & hands ONLY (NO head, NO torso — matching Reference Screenshots 2 & 3).
 * Auto-fits bounding box to canvas center so raised, crossed, wide, or single-right-arm poses
 * never clip outside the card!
 */
private fun DrawScope.drawIsolatedArmsSilhouette(
    g: SilhouetteRigGeometry,
    sample: ModularActionSample
) {
    val isSingleRight = sample.silhouetteStyle == "single_right_arm"
    val rawPoints = if (isSingleRight) {
        listOf(
            Offset(g.rightShoulder.first, g.rightShoulder.second),
            Offset(g.rightElbow.first, g.rightElbow.second),
            Offset(g.rightHand.first, g.rightHand.second)
        )
    } else {
        listOf(
            Offset(g.leftShoulder.first, g.leftShoulder.second),
            Offset(g.leftElbow.first, g.leftElbow.second),
            Offset(g.leftHand.first, g.leftHand.second),
            Offset(g.rightShoulder.first, g.rightShoulder.second),
            Offset(g.rightElbow.first, g.rightElbow.second),
            Offset(g.rightHand.first, g.rightHand.second)
        )
    }

    val minX = rawPoints.minOf { it.x }
    val maxX = rawPoints.maxOf { it.x }
    val minY = rawPoints.minOf { it.y }
    val maxY = rawPoints.maxOf { it.y }
    val bboxW = (maxX - minX).coerceAtLeast(14f)
    val bboxH = (maxY - minY).coerceAtLeast(18f)
    val fitScale = min((size.width * 0.62f) / bboxW, (size.height * 0.72f) / bboxH).coerceIn(0.62f, 1.25f)
    val bCx = (minX + maxX) * 0.5f
    val bCy = (minY + maxY) * 0.5f
    val tx = size.width * 0.5f - bCx
    val ty = size.height * 0.5f - bCy

    fun mapPt(p: Pair<Float, Float>): Offset {
        return Offset(
            size.width * 0.5f + (p.first - bCx) * fitScale,
            size.height * 0.5f + (p.second - bCy) * fitScale
        )
    }

    val baseR = (size.minDimension * 0.068f * fitScale.coerceIn(0.8f, 1.15f)).coerceIn(3.4f, 6.2f)
    val handType = sample.handPoseOverride ?: HandPoseType.OPEN

    fun drawArm(sh: Offset, el: Offset, ha: Offset, color: Color) {
        drawTaperedLimb(sh, el, startRadius = baseR * 1.05f, endRadius = baseR * 0.84f, color = color)
        drawTaperedLimb(el, ha, startRadius = baseR * 0.82f, endRadius = baseR * 0.62f, color = color)
        // Sculpted palm / fist / pointing hand silhouette at wrist
        val dirX = ha.x - el.x
        val dirY = ha.y - el.y
        val len = kotlin.math.hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dirX / len
        val uy = dirY / len
        when (handType) {
            HandPoseType.FIST, HandPoseType.PROP_GRIP -> {
                drawCircle(color = color, radius = baseR * 0.92f, center = Offset(ha.x + ux * baseR * 0.35f, ha.y + uy * baseR * 0.35f))
            }
            HandPoseType.POINT -> {
                drawCircle(color = color, radius = baseR * 0.75f, center = ha)
                drawLine(
                    color = color,
                    start = ha,
                    end = Offset(ha.x + ux * baseR * 1.9f, ha.y + uy * baseR * 1.9f),
                    strokeWidth = baseR * 0.65f,
                    cap = StrokeCap.Round
                )
            }
            else -> {
                // Open palm oval + tiny thumb silhouette
                val palmCenter = Offset(ha.x + ux * baseR * 0.55f, ha.y + uy * baseR * 0.55f)
                drawCircle(color = color, radius = baseR * 0.88f, center = palmCenter)
                drawLine(
                    color = color,
                    start = palmCenter,
                    end = Offset(palmCenter.x - uy * baseR * 0.95f, palmCenter.y + ux * baseR * 0.95f),
                    strokeWidth = baseR * 0.55f,
                    cap = StrokeCap.Round
                )
            }
        }
    }

    if (!isSingleRight) {
        drawArm(mapPt(g.leftShoulder), mapPt(g.leftElbow), mapPt(g.leftHand), SilhouetteSoftWhite)
    }
    drawArm(mapPt(g.rightShoulder), mapPt(g.rightElbow), mapPt(g.rightHand), SilhouetteWhite)
}

/**
 * Isolated white cartoon legs & shoes ONLY (NO head, NO torso, NO arms).
 * Supports standard walking/standing/kicking/jumping FK pairs plus dedicated sculpted silhouettes
 * for `cross_legged` (palathi), `figure_four` (wall lean), and `seated_profile` (driving/sitting).
 */
private fun DrawScope.drawIsolatedLegsSilhouette(
    g: SilhouetteRigGeometry,
    sample: ModularActionSample
) {
    val u = size.minDimension
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val baseR = (u * 0.082f).coerceIn(3.8f, 6.8f)

    when (sample.silhouetteStyle) {
        "cross_legged" -> {
            // Iconic Indian palathi cross-legged silhouette
            val hipY = cy - u * 0.14f
            val lHip = Offset(cx - u * 0.14f, hipY)
            val rHip = Offset(cx + u * 0.14f, hipY)
            val lKnee = Offset(cx - u * 0.36f, cy + u * 0.08f)
            val rKnee = Offset(cx + u * 0.36f, cy + u * 0.08f)
            val lFoot = Offset(cx + u * 0.14f, cy + u * 0.24f)
            val rFoot = Offset(cx - u * 0.14f, cy + u * 0.26f)
            drawTaperedLimb(lHip, lKnee, baseR * 1.15f, baseR * 0.88f, SilhouetteSoftWhite)
            drawTaperedLimb(lKnee, lFoot, baseR * 0.86f, baseR * 0.65f, SilhouetteSoftWhite)
            drawTaperedLimb(rHip, rKnee, baseR * 1.15f, baseR * 0.88f, SilhouetteWhite)
            drawTaperedLimb(rKnee, rFoot, baseR * 0.86f, baseR * 0.65f, SilhouetteWhite)
            drawShoeCap(lFoot, dirRight = true, r = baseR, color = SilhouetteSoftWhite)
            drawShoeCap(rFoot, dirRight = false, r = baseR, color = SilhouetteWhite)
            return
        }
        "figure_four" -> {
            // Casual wall-lean crossed-ankle silhouette
            val lHip = Offset(cx - u * 0.11f, cy - u * 0.30f)
            val lKnee = Offset(cx - u * 0.12f, cy + u * 0.02f)
            val lFoot = Offset(cx - u * 0.10f, cy + u * 0.34f)
            val rHip = Offset(cx + u * 0.11f, cy - u * 0.30f)
            val rKnee = Offset(cx + u * 0.22f, cy + u * 0.02f)
            val rFoot = Offset(cx - u * 0.02f, cy + u * 0.28f)
            drawTaperedLimb(lHip, lKnee, baseR * 1.10f, baseR * 0.85f, SilhouetteSoftWhite)
            drawTaperedLimb(lKnee, lFoot, baseR * 0.82f, baseR * 0.62f, SilhouetteSoftWhite)
            drawShoeCap(lFoot, dirRight = false, r = baseR, color = SilhouetteSoftWhite)
            drawTaperedLimb(rHip, rKnee, baseR * 1.10f, baseR * 0.85f, SilhouetteWhite)
            drawTaperedLimb(rKnee, rFoot, baseR * 0.82f, baseR * 0.62f, SilhouetteWhite)
            drawShoeCap(rFoot, dirRight = true, r = baseR, color = SilhouetteWhite)
            return
        }
        "seated_profile" -> {
            // Profile seated pair (Car Drive / Bail Gadi / Sitting Chair)
            val pedalShift = sin(sample.rightLegDelta * PI / 180.0).toFloat() * u * 0.06f
            val hipBack = Offset(cx - u * 0.26f, cy - u * 0.12f)
            val hipFront = Offset(cx - u * 0.18f, cy - u * 0.08f)
            val kneeBack = Offset(cx + u * 0.08f, cy - u * 0.10f)
            val kneeFront = Offset(cx + u * 0.16f, cy - u * 0.06f)
            val footBack = Offset(cx + u * 0.14f, cy + u * 0.28f)
            val footFront = Offset(cx + u * 0.26f + pedalShift, cy + u * 0.26f)
            drawTaperedLimb(hipBack, kneeBack, baseR * 1.15f, baseR * 0.88f, SilhouetteSoftWhite)
            drawTaperedLimb(kneeBack, footBack, baseR * 0.84f, baseR * 0.64f, SilhouetteSoftWhite)
            drawShoeCap(footBack, dirRight = true, r = baseR, color = SilhouetteSoftWhite)
            drawTaperedLimb(hipFront, kneeFront, baseR * 1.15f, baseR * 0.88f, SilhouetteWhite)
            drawTaperedLimb(kneeFront, footFront, baseR * 0.84f, baseR * 0.64f, SilhouetteWhite)
            drawShoeCap(footFront, dirRight = true, r = baseR, color = SilhouetteWhite)
            return
        }
    }

    // Auto-fit standard FK legs cleanly into card bounds
    val rawPoints = listOf(
        Offset(g.leftHip.first, g.leftHip.second),
        Offset(g.leftKnee.first, g.leftKnee.second),
        Offset(g.leftFoot.first, g.leftFoot.second),
        Offset(g.rightHip.first, g.rightHip.second),
        Offset(g.rightKnee.first, g.rightKnee.second),
        Offset(g.rightFoot.first, g.rightFoot.second)
    )
    val minX = rawPoints.minOf { it.x }
    val maxX = rawPoints.maxOf { it.x }
    val minY = rawPoints.minOf { it.y }
    val maxY = rawPoints.maxOf { it.y }
    val bboxW = (maxX - minX).coerceAtLeast(16f)
    val bboxH = (maxY - minY).coerceAtLeast(22f)
    val fitScale = min((size.width * 0.62f) / bboxW, (size.height * 0.76f) / bboxH).coerceIn(0.62f, 1.18f)
    val bCx = (minX + maxX) * 0.5f
    val bCy = (minY + maxY) * 0.5f

    fun mapPt(p: Pair<Float, Float>): Offset = Offset(
        size.width * 0.5f + (p.first - bCx) * fitScale,
        size.height * 0.5f + (p.second - bCy) * fitScale
    )

    val lHp = mapPt(g.leftHip)
    val lKn = mapPt(g.leftKnee)
    val lFt = mapPt(g.leftFoot)
    val rHp = mapPt(g.rightHip)
    val rKn = mapPt(g.rightKnee)
    val rFt = mapPt(g.rightFoot)
    val legR = (baseR * fitScale.coerceIn(0.82f, 1.10f)).coerceIn(3.5f, 6.6f)

    // Back leg
    drawTaperedLimb(lHp, lKn, legR * 1.12f, legR * 0.86f, SilhouetteSoftWhite)
    drawTaperedLimb(lKn, lFt, legR * 0.84f, legR * 0.64f, SilhouetteSoftWhite)
    drawShoeCap(lFt, dirRight = (lFt.x >= lKn.x), r = legR, color = SilhouetteSoftWhite)

    // Front leg
    drawTaperedLimb(rHp, rKn, legR * 1.12f, legR * 0.86f, SilhouetteWhite)
    drawTaperedLimb(rKn, rFt, legR * 0.84f, legR * 0.64f, SilhouetteWhite)
    drawShoeCap(rFt, dirRight = (rFt.x >= rKn.x || abs(rFt.x - rKn.x) < 2f), r = legR, color = SilhouetteWhite)
}

private fun DrawScope.drawShoeCap(ankle: Offset, dirRight: Boolean, r: Float, color: Color) {
    val dir = if (dirRight) 1f else -1f
    val shoeW = r * 2.15f
    val shoeH = r * 1.05f
    val leftX = if (dir > 0f) ankle.x - r * 0.55f else ankle.x - shoeW + r * 0.55f
    drawRoundRect(
        color = color,
        topLeft = Offset(leftX, ankle.y - shoeH * 0.35f),
        size = Size(shoeW, shoeH),
        cornerRadius = CornerRadius(shoeH * 0.5f, shoeH * 0.5f)
    )
}
