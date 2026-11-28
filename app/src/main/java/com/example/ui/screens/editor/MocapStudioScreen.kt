package com.example.ui.screens.editor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.engine.*
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

private enum class MocapSourceTab(val label: String) {
    LIVE_CAMERA("Live Camera"),
    VIDEO_TO_ANIM("Video-to-Anim (MP4)")
}

/**
 * 100% Offline On-Device Motion Capture Studio Modal (MediaPipe Face + Pose + Hand Recognition).
 *
 * Supports:
 * 1. Live CameraX recording with front/back flip and EMA jitter smoothing.
 * 2. Offline Gallery MP4 Video-to-Animation via zero-permission Android Photo Picker (`PickVisualMedia`).
 * 3. Graceful fallback instructions if offline `.task` bundles are not yet placed in `assets/mocap/`.
 * 4. Baking smoothed keyframes onto the selected character's timeline tracks (`IK Pose`, `Expression`,
 *    `Hands`, `Legs`) with full Undo snapshot support.
 */
@Composable
fun MocapStudioScreen(
    clip: TimelineClip,
    rig: CharacterRigDefinition,
    currentFrame: Int,
    fps: Int,
    customBitmap: Bitmap?,
    atlasSlices: Map<AtlasPartSlot, Bitmap>?,
    onBakeMocapKeyframes: (
        clipId: String,
        frames: List<MocapRetargetedFrame>,
        config: MocapBakeConfig
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val runner = remember(context) { MediaPipeMocapRunner(context) }
    val availability = runner.availability
    val smoother = remember { MocapEmaSmoother(0.38f) }

    DisposableEffect(runner) {
        onDispose { runner.closeLandmarkers() }
    }

    var sourceTab by remember { mutableStateOf(MocapSourceTab.LIVE_CAMERA) }
    var captureMode by remember { mutableStateOf(MocapCaptureMode.FULL_FACE_BODY_HANDS) }
    var useFrontCamera by remember { mutableStateOf(true) }

    // Camera runtime permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionDeniedOnce = true
    }

    // Live / Video mocap state
    var latestFrame by remember { mutableStateOf(MocapRetargetedFrame()) }
    var isRecordingLive by remember { mutableStateOf(false) }
    var recordedFrames by remember { mutableStateOf<List<MocapRetargetedFrame>>(emptyList()) }
    var liveRecordBuffer by remember { mutableStateOf(mutableListOf<MocapRetargetedFrame>()) }

    // Offline Video-to-Animation state
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var isExtractingVideo by remember { mutableStateOf(false) }
    var videoProgress by remember { mutableFloatStateOf(0f) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    // Configurable smoothing & bake parameters
    var emaAlpha by remember { mutableFloatStateOf(0.38f) }
    var sampleEveryN by remember { mutableIntStateOf(2) }
    var bakeIkPose by remember { mutableStateOf(true) }
    var bakeExpression by remember { mutableStateOf(true) }
    var bakeHands by remember { mutableStateOf(true) }
    var bakeLegs by remember { mutableStateOf(true) }
    var enforceJointLimits by remember { mutableStateOf(true) }

    LaunchedEffect(emaAlpha) {
        smoother.alpha = emaAlpha
    }

    // Zero-permission Android Photo Picker for Offline Video-to-Animation (Google Play compliant!)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            isExtractingVideo = true
            videoProgress = 0f
            statusNotice = "Extracting pose, face & hand motion offline..."
            scope.launch {
                val res = runner.extractMotionFromVideoUri(
                    videoUri = uri,
                    mode = captureMode,
                    targetFps = fps,
                    sampleEveryNFrames = sampleEveryN,
                    emaAlpha = emaAlpha,
                    enforceJointLimits = enforceJointLimits,
                    orientation = clip.orientation
                ) { prog, frame ->
                    scope.launch(Dispatchers.Main) {
                        videoProgress = prog
                        if (frame != null) latestFrame = frame
                    }
                }
                withContext(Dispatchers.Main) {
                    isExtractingVideo = false
                    res.onSuccess { frames ->
                        recordedFrames = frames
                        statusNotice = if (availability.anyModelAvailable) {
                            "Extracted ${frames.size} smoothed frames offline. Tap Bake to Timeline!"
                        } else {
                            "Decoded ${frames.size} video frames. Bundle .task models in assets/mocap/ for full landmark tracking."
                        }
                    }.onFailure { err ->
                        statusNotice = "Could not decode video: ${err.message ?: "unsupported format"}"
                    }
                }
            }
        }
    }

    val needsMissingModelForMode = when (captureMode) {
        MocapCaptureMode.FACE_ONLY -> !availability.hasFaceModel
        MocapCaptureMode.BODY_AND_HANDS -> !availability.hasPoseModel || !availability.hasHandModel
        MocapCaptureMode.FULL_FACE_BODY_HANDS -> !availability.allModelsAvailable
    }

    Dialog(
        onDismissRequest = {
            isRecordingLive = false
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            color = StudioObsidian,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .testTag("mocap_studio_modal")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ─── 1. TOP BAR ───
                Surface(
                    color = StudioCharcoal,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isRecordingLive = false
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("mocap_close_btn")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close Mocap Studio", tint = StudioTextPrimary)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Offline Mocap Studio",
                                color = StudioTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "${rig.name} • F$currentFrame • 100% On-Device",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            color = if (availability.allModelsAvailable) EmeraldLayer.copy(alpha = 0.16f) else AmberKeyframe.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (availability.allModelsAvailable) EmeraldLayer else AmberKeyframe
                            )
                        ) {
                            Text(
                                text = if (availability.allModelsAvailable) "3/3 Models" else "Offline Setup",
                                color = if (availability.allModelsAvailable) EmeraldLayer else AmberKeyframe,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // ─── 2. SOURCE TABS (Live Camera | Video-to-Anim MP4) ───
                TabRow(
                    selectedTabIndex = sourceTab.ordinal,
                    containerColor = StudioCharcoal,
                    contentColor = ElectricCyan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MocapSourceTab.entries.forEach { tab ->
                        Tab(
                            selected = sourceTab == tab,
                            onClick = {
                                isRecordingLive = false
                                sourceTab = tab
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("mocap_tab_${tab.name.lowercase()}"),
                            text = { Text(tab.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }

                // ─── 3. SCROLLABLE STUDIO BODY ───
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Tracking Mode Selector Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MocapCaptureMode.entries.forEach { mode ->
                            FilterChip(
                                selected = captureMode == mode,
                                onClick = {
                                    captureMode = mode
                                    smoother.reset()
                                },
                                label = { Text(mode.displayName, fontSize = 11.5.sp) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (mode) {
                                            MocapCaptureMode.FACE_ONLY -> Icons.Rounded.Face
                                            MocapCaptureMode.BODY_AND_HANDS -> Icons.Rounded.AccessibilityNew
                                            MocapCaptureMode.FULL_FACE_BODY_HANDS -> Icons.Rounded.RecordVoiceOver
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                },
                                modifier = Modifier.testTag("mocap_mode_${mode.name.lowercase()}")
                            )
                        }
                    }

                    // Graceful Offline `.task` Model Asset Status Banner (never crashes if unbundled!)
                    if (needsMissingModelForMode) {
                        Surface(
                            color = Color(0xFF1E1910),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AmberKeyframe.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("mocap_missing_models_banner")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Info,
                                        contentDescription = "Offline model asset info",
                                        tint = AmberKeyframe,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Offline MediaPipe .task Models Not Bundled Yet",
                                        color = AmberKeyframe,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "Place official Google MediaPipe Tasks Vision `.task` files in your Android assets folder for 100% offline tracking:",
                                    color = StudioTextSecondary,
                                    fontSize = 11.sp
                                )
                                availability.missingAssetPaths().forEach { path ->
                                    Text(
                                        text = "• $path",
                                        color = StudioTextPrimary,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    // ─── SPLIT VIEWPORT: CAMERA/VIDEO + LIVE 2D RETARGETED RIG ───
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(216.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Left Pane: CameraX Live Preview OR Offline Video Extractor Card
                        Surface(
                            color = Color(0xFF0A0F1A),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isRecordingLive) CoralPlayhead else StudioBorderSubtle),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            if (sourceTab == MocapSourceTab.LIVE_CAMERA) {
                                if (hasCameraPermission) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        CameraXMocapPreview(
                                            useFrontCamera = useFrontCamera,
                                            captureMode = captureMode,
                                            enforceJointLimits = enforceJointLimits,
                                            orientation = clip.orientation,
                                            runner = runner,
                                            smoother = smoother,
                                            isRecording = isRecordingLive,
                                            onFrameTracked = { frame ->
                                                latestFrame = frame
                                                if (isRecordingLive) {
                                                    liveRecordBuffer.add(frame)
                                                    if (liveRecordBuffer.size % 4 == 0) {
                                                        recordedFrames = liveRecordBuffer.toList()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        // Flip camera button
                                        IconButton(
                                            onClick = { useFrontCamera = !useFrontCamera },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(StudioObsidian.copy(alpha = 0.72f))
                                                .testTag("mocap_flip_camera_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.FlipCameraAndroid,
                                                contentDescription = "Switch front or rear camera",
                                                tint = StudioTextPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        if (isRecordingLive) {
                                            Surface(
                                                color = CoralPlayhead,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(6.dp)
                                            ) {
                                                Text(
                                                    text = "REC • ${liveRecordBuffer.size}f",
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Videocam,
                                            contentDescription = null,
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(30.dp)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = if (permissionDeniedOnce) {
                                                "Camera permission denied. Enable Camera to use Live Mocap."
                                            } else {
                                                "Grant Camera access for 100% offline live tracking"
                                            },
                                            color = StudioTextSecondary,
                                            fontSize = 11.sp
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Button(
                                            onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                                            modifier = Modifier
                                                .height(44.dp)
                                                .testTag("mocap_grant_camera_btn")
                                        ) {
                                            Text("Enable Camera", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                // Video-to-Animation (Gallery MP4) Pane
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VideoLibrary,
                                        contentDescription = null,
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = when {
                                            isExtractingVideo -> "Analyzing ${(videoProgress * 100).roundToInt()}%..."
                                            selectedVideoUri != null -> "Video ready (${recordedFrames.size} frames)"
                                            else -> "Pick any offline MP4 video from Gallery"
                                        },
                                        color = StudioTextPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (isExtractingVideo) {
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { videoProgress.coerceIn(0f, 1f) },
                                            color = ElectricCyan,
                                            trackColor = StudioBorderSubtle,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        Spacer(Modifier.height(8.dp))
                                        Button(
                                            onClick = {
                                                videoPickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                                            modifier = Modifier
                                                .height(44.dp)
                                                .testTag("mocap_pick_video_btn")
                                        ) {
                                            Text("Pick Video MP4", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Right Pane: Live 2D Retargeted FrameCraft Rig Preview
                        Surface(
                            color = Color(0xFF0B1220),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StudioBorderSubtle),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("mocap_rig_preview_canvas")
                                ) {
                                    val w = size.width.coerceAtLeast(1f)
                                    val h = size.height.coerceAtLeast(1f)
                                    val baseScale = (min(w, h) / 260f) * 0.95f
                                    val organic = FacialExpressionEngine.evaluateOrganicFace(
                                        activePresetId = null,
                                        previousPresetId = null,
                                        fallbackExpression = latestFrame.expression,
                                        framesSinceKey = 12,
                                        globalFrame = currentFrame,
                                        fps = fps,
                                        blinkOverride = latestFrame.blink,
                                        mouthOverride = latestFrame.mouthOverride,
                                        eyeOverride = latestFrame.eyeOverride,
                                        eyebrowOverride = latestFrame.eyebrowOverride
                                    ).copy(
                                        eyeLookX = latestFrame.gazeTarget.x,
                                        eyeLookY = latestFrame.gazeTarget.y
                                    )
                                    drawIntoCanvas { composeCanvas ->
                                        val nc = composeCanvas.nativeCanvas
                                        nc.save()
                                        nc.translate(w * 0.5f, h * 0.58f)
                                        if (clip.flipHorizontal) nc.scale(-1f, 1f)
                                        ProceduralRigRenderer.drawCharacter(
                                            canvas = nc,
                                            rig = rig,
                                            orientation = clip.orientation,
                                            preset = AnimationPresetType.IDLE,
                                            phase = 0f,
                                            rigScale = baseScale,
                                            alpha = 1f,
                                            customBitmap = customBitmap,
                                            poseOffsets = latestFrame.poseOffsets,
                                            facialExpression = latestFrame.expression,
                                            handPose = latestFrame.handPose,
                                            blinkOverride = latestFrame.blink,
                                            atlasSlices = atlasSlices,
                                            organicFace = organic
                                        )
                                        nc.restore()
                                    }
                                }
                                // Live Telemetry Chips
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "${latestFrame.visemeCode} (${latestFrame.mthCode}) • ${latestFrame.palmGesture.displayName}",
                                        color = ElectricCyan,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Tilt ${latestFrame.headTiltDeg.roundToInt()}° • Blink ${if (latestFrame.blink) "YES" else "NO"}",
                                        color = StudioTextSecondary,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }

                    if (!statusNotice.isNullOrBlank()) {
                        Text(
                            text = statusNotice!!,
                            color = ElectricCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // ─── EMA SMOOTHING & SAMPLE RATE CONTROLS ───
                    Surface(
                        color = StudioPanel,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("EMA Smoothing (Anti-Jitter)", color = StudioTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("α = ${String.format("%.2f", emaAlpha)}", color = ElectricCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = emaAlpha,
                                onValueChange = { emaAlpha = it },
                                valueRange = 0.15f..0.85f,
                                colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("mocap_ema_slider")
                            )

                            Text("Keyframe Bake Cadence", color = StudioTextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1, 2, 3, 4).forEach { step ->
                                    FilterChip(
                                        selected = sampleEveryN == step,
                                        onClick = { sampleEveryN = step },
                                        label = { Text("Every ${step}f", fontSize = 11.sp) },
                                        modifier = Modifier.testTag("mocap_sample_rate_$step")
                                    )
                                }
                            }

                            Text("Target Timeline Tracks", color = StudioTextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = bakeIkPose,
                                    onClick = { bakeIkPose = !bakeIkPose },
                                    label = { Text("IK Pose", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("mocap_track_ik")
                                )
                                FilterChip(
                                    selected = bakeExpression,
                                    onClick = { bakeExpression = !bakeExpression },
                                    label = { Text("Expression", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("mocap_track_expr")
                                )
                                FilterChip(
                                    selected = bakeHands,
                                    onClick = { bakeHands = !bakeHands },
                                    label = { Text("Hands", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("mocap_track_hands")
                                )
                                FilterChip(
                                    selected = bakeLegs,
                                    onClick = { bakeLegs = !bakeLegs },
                                    label = { Text("Legs", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("mocap_track_legs")
                                )
                                FilterChip(
                                    selected = enforceJointLimits,
                                    onClick = { enforceJointLimits = !enforceJointLimits },
                                    label = { Text("Joint Limits", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("mocap_track_limits")
                                )
                            }
                        }
                    }
                }

                // ─── 4. STICKY BOTTOM ACTION BAR ───
                HorizontalDivider(color = StudioBorderSubtle)
                Surface(
                    color = StudioCharcoal,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (sourceTab == MocapSourceTab.LIVE_CAMERA) {
                            Button(
                                onClick = {
                                    if (!hasCameraPermission) {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    } else if (isRecordingLive) {
                                        isRecordingLive = false
                                        recordedFrames = liveRecordBuffer.toList()
                                        statusNotice = "Captured ${recordedFrames.size} live frames. Ready to bake!"
                                    } else {
                                        smoother.reset()
                                        liveRecordBuffer = mutableListOf()
                                        recordedFrames = emptyList()
                                        isRecordingLive = true
                                        statusNotice = "Recording live mocap..."
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRecordingLive) CoralPlayhead else AmberKeyframe,
                                    contentColor = if (isRecordingLive) Color.White else StudioObsidian
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("mocap_record_toggle_btn")
                            ) {
                                Icon(
                                    imageVector = if (isRecordingLive) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = if (isRecordingLive) "Stop (${liveRecordBuffer.size}f)" else "Record Live",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                isRecordingLive = false
                                val framesToBake = recordedFrames.ifEmpty { liveRecordBuffer.toList() }
                                if (framesToBake.isNotEmpty()) {
                                    onBakeMocapKeyframes(
                                        clip.id,
                                        framesToBake,
                                        MocapBakeConfig(
                                            sampleEveryNFrames = sampleEveryN,
                                            bakeIkPose = bakeIkPose,
                                            bakeExpression = bakeExpression,
                                            bakeHands = bakeHands,
                                            bakeLegs = bakeLegs,
                                            enforceJointLimits = enforceJointLimits
                                        )
                                    )
                                    onDismiss()
                                }
                            },
                            enabled = !isRecordingLive && !isExtractingVideo && recordedFrames.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("mocap_bake_timeline_btn")
                        ) {
                            Icon(Icons.Rounded.Diamond, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "Bake (${recordedFrames.size}f)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraXMocapPreview(
    useFrontCamera: Boolean,
    captureMode: MocapCaptureMode,
    enforceJointLimits: Boolean,
    orientation: CharacterOrientation,
    runner: MediaPipeMocapRunner,
    smoother: MocapEmaSmoother,
    isRecording: Boolean,
    onFrameTracked: (MocapRetargetedFrame) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val latestMode by rememberUpdatedState(captureMode)
    val latestLimits by rememberUpdatedState(enforceJointLimits)
    val latestOnTracked by rememberUpdatedState(onFrameTracked)
    val latestFront by rememberUpdatedState(useFrontCamera)

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(useFrontCamera) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                provider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { proxy: ImageProxy ->
                    try {
                        val bmp = imageProxyToUprightBitmap(proxy, latestFront)
                        if (bmp != null) {
                            val raw = runner.processBitmap(
                                bitmap = bmp,
                                timestampMs = SystemClock.uptimeMillis(),
                                mode = latestMode,
                                enforceJointLimits = latestLimits,
                                orientation = orientation
                            )
                            val smoothed = smoother.smooth(raw)
                            ContextCompat.getMainExecutor(context).execute {
                                latestOnTracked(smoothed)
                            }
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    } catch (_: Throwable) {
                        // Safe drop on transient camera frame error
                    } finally {
                        proxy.close()
                    }
                }

                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

private fun imageProxyToUprightBitmap(proxy: ImageProxy, mirrorHorizontal: Boolean): Bitmap? {
    val raw = runCatching { proxy.toBitmap() }.getOrNull() ?: return null
    val rot = proxy.imageInfo.rotationDegrees.toFloat()
    if (rot == 0f && !mirrorHorizontal) return raw
    val m = Matrix().apply {
        if (rot != 0f) postRotate(rot)
        if (mirrorHorizontal) postScale(-1f, 1f)
    }
    return try {
        val transformed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
        if (transformed !== raw && !raw.isRecycled) raw.recycle()
        transformed
    } catch (_: Throwable) {
        raw
    }
}
