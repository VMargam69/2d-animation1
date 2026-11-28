package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.model.CharacterOrientation
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Availability status of offline MediaPipe `.task` bundles inside `app/src/main/assets/mocap/`.
 */
data class MocapModelAvailability(
    val hasFaceModel: Boolean,
    val hasPoseModel: Boolean,
    val hasHandModel: Boolean
) {
    val anyModelAvailable: Boolean get() = hasFaceModel || hasPoseModel || hasHandModel
    val allModelsAvailable: Boolean get() = hasFaceModel && hasPoseModel && hasHandModel

    fun missingAssetPaths(): List<String> = buildList {
        if (!hasFaceModel) add("app/src/main/assets/${MediaPipeMocapRunner.ASSET_FACE_MODEL}")
        if (!hasPoseModel) add("app/src/main/assets/${MediaPipeMocapRunner.ASSET_POSE_MODEL}")
        if (!hasHandModel) add("app/src/main/assets/${MediaPipeMocapRunner.ASSET_HAND_MODEL}")
    }
}

/**
 * 100% Offline On-Device MediaPipe Tasks Vision Runner (`FaceLandmarker`, `PoseLandmarker`, `HandLandmarker`).
 *
 * - Loads bundled `.task` files strictly from local `assets/mocap/` (zero network calls).
 * - Never crashes if `.task` models are not yet bundled: reports exact availability via [inspectModelAvailability].
 * - Drives both Live CameraX frame analysis AND offline Gallery MP4 Video-to-Animation frame extraction.
 */
class MediaPipeMocapRunner(private val context: Context) {

    companion object {
        private const val TAG = "MediaPipeMocapRunner"
        const val ASSET_FACE_MODEL = "mocap/face_landmarker.task"
        const val ASSET_POSE_MODEL = "mocap/pose_landmarker_lite.task"
        const val ASSET_HAND_MODEL = "mocap/hand_landmarker.task"

        fun inspectModelAvailability(context: Context): MocapModelAvailability {
            fun exists(path: String): Boolean = runCatching {
                context.assets.open(path).use { it.available() >= 0 }
            }.getOrDefault(false)

            return MocapModelAvailability(
                hasFaceModel = exists(ASSET_FACE_MODEL),
                hasPoseModel = exists(ASSET_POSE_MODEL),
                hasHandModel = exists(ASSET_HAND_MODEL)
            )
        }
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var poseLandmarker: PoseLandmarker? = null
    private var handLandmarker: HandLandmarker? = null
    private var initializedMode: MocapCaptureMode? = null

    val availability: MocapModelAvailability = inspectModelAvailability(context)

    /**
     * Safely initializes only the `.task` landmarkers needed for [mode] that exist in `assets/mocap/`.
     */
    @Synchronized
    fun ensureInitialized(mode: MocapCaptureMode): String? {
        if (initializedMode == mode) return null
        closeLandmarkers()
        val errors = mutableListOf<String>()

        val needFace = mode == MocapCaptureMode.FACE_ONLY || mode == MocapCaptureMode.FULL_FACE_BODY_HANDS
        val needPose = mode == MocapCaptureMode.BODY_AND_HANDS || mode == MocapCaptureMode.FULL_FACE_BODY_HANDS
        val needHand = mode == MocapCaptureMode.BODY_AND_HANDS || mode == MocapCaptureMode.FULL_FACE_BODY_HANDS

        if (needFace && availability.hasFaceModel) {
            runCatching {
                val baseOpts = BaseOptions.builder()
                    .setModelAssetPath(ASSET_FACE_MODEL)
                    .build()
                val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOpts)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(context, opts)
            }.onFailure { e ->
                Log.w(TAG, "FaceLandmarker init skipped: ${e.message}")
                errors.add("Face model: ${e.message}")
            }
        }

        if (needPose && availability.hasPoseModel) {
            runCatching {
                val baseOpts = BaseOptions.builder()
                    .setModelAssetPath(ASSET_POSE_MODEL)
                    .build()
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOpts)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumPoses(1)
                    .build()
                poseLandmarker = PoseLandmarker.createFromOptions(context, opts)
            }.onFailure { e ->
                Log.w(TAG, "PoseLandmarker init skipped: ${e.message}")
                errors.add("Pose model: ${e.message}")
            }
        }

        if (needHand && availability.hasHandModel) {
            runCatching {
                val baseOpts = BaseOptions.builder()
                    .setModelAssetPath(ASSET_HAND_MODEL)
                    .build()
                val opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOpts)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
                    .build()
                handLandmarker = HandLandmarker.createFromOptions(context, opts)
            }.onFailure { e ->
                Log.w(TAG, "HandLandmarker init skipped: ${e.message}")
                errors.add("Hand model: ${e.message}")
            }
        }

        initializedMode = mode
        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Runs active MediaPipe landmarkers on a single [Bitmap] frame offline and retargets it
     * via [MediaPipeMocapRetargeter].
     */
    @Synchronized
    fun processBitmap(
        bitmap: Bitmap,
        timestampMs: Long,
        mode: MocapCaptureMode,
        enforceJointLimits: Boolean = true,
        orientation: CharacterOrientation = CharacterOrientation.FRONT
    ): MocapRetargetedFrame {
        ensureInitialized(mode)
        val mpImage = BitmapImageBuilder(bitmap).build()

        var blendshapes: Map<String, Float> = emptyMap()
        var faceLandmarks: List<MocapLandmark3D> = emptyList()
        var headMatrix: FloatArray? = null
        var poseLandmarks: List<MocapLandmark3D> = emptyList()
        var primaryHand: List<MocapLandmark3D> = emptyList()
        var secondaryHand: List<MocapLandmark3D>? = null

        faceLandmarker?.let { fl ->
            runCatching {
                val res = fl.detect(mpImage)
                val firstFace = res.faceLandmarks().firstOrNull()
                if (firstFace != null) {
                    faceLandmarks = firstFace.map { lm -> MocapLandmark3D(lm.x(), lm.y(), lm.z()) }
                }
                val bsOpt = res.faceBlendshapes()
                if (bsOpt.isPresent && bsOpt.get().isNotEmpty()) {
                    blendshapes = bsOpt.get()[0].associate { cat -> cat.categoryName() to cat.score() }
                }
                val matOpt = res.facialTransformationMatrixes()
                if (matOpt.isPresent && matOpt.get().isNotEmpty()) {
                    headMatrix = matOpt.get()[0]
                }
            }
        }

        poseLandmarker?.let { pl ->
            runCatching {
                val res = pl.detect(mpImage)
                val firstPose = res.landmarks().firstOrNull()
                if (firstPose != null) {
                    poseLandmarks = firstPose.map { lm ->
                        MocapLandmark3D(
                            x = lm.x(),
                            y = lm.y(),
                            z = lm.z(),
                            visibility = lm.visibility().orElse(1f)
                        )
                    }
                }
            }
        }

        handLandmarker?.let { hl ->
            runCatching {
                val res = hl.detect(mpImage)
                val hands = res.landmarks()
                if (hands.isNotEmpty()) {
                    primaryHand = hands[0].map { lm -> MocapLandmark3D(lm.x(), lm.y(), lm.z()) }
                }
                if (hands.size >= 2) {
                    secondaryHand = hands[1].map { lm -> MocapLandmark3D(lm.x(), lm.y(), lm.z()) }
                }
            }
        }

        return MediaPipeMocapRetargeter.retargetCombinedFrame(
            timestampMs = timestampMs,
            blendshapes = blendshapes,
            faceLandmarks = faceLandmarks,
            headMatrix4x4 = headMatrix,
            poseLandmarks = poseLandmarks,
            primaryHandLandmarks = primaryHand,
            secondaryHandLandmarks = secondaryHand,
            enforceJointLimits = enforceJointLimits,
            orientation = orientation
        )
    }

    /**
     * Offline Gallery Video-to-Animation extractor: decodes frames from a local MP4 [videoUri]
     * using [MediaMetadataRetriever] on `Dispatchers.IO`, runs on-device MediaPipe tracking +
     * EMA smoothing, and returns the sequence of [MocapRetargetedFrame]s.
     */
    suspend fun extractMotionFromVideoUri(
        videoUri: Uri,
        mode: MocapCaptureMode,
        targetFps: Int,
        sampleEveryNFrames: Int,
        emaAlpha: Float,
        enforceJointLimits: Boolean,
        orientation: CharacterOrientation,
        maxDurationSec: Float = 15f,
        onProgress: (progress: Float, latestFrame: MocapRetargetedFrame?) -> Unit
    ): Result<List<MocapRetargetedFrame>> = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceIn(200L, (maxDurationSec * 1000f).toLong())
                    ?: 4000L
                val safeStep = sampleEveryNFrames.coerceIn(1, 6)
                val stepMs = ((1000.0 / targetFps.coerceIn(12, 30)) * safeStep).toLong().coerceAtLeast(42L)
                val totalSteps = ((durationMs / stepMs).toInt() + 1).coerceIn(1, 360)

                val smoother = MocapEmaSmoother(emaAlpha)
                val out = ArrayList<MocapRetargetedFrame>(totalSteps)

                for (i in 0 until totalSteps) {
                    coroutineContext.ensureActive()
                    val tMs = (i * stepMs).coerceAtMost(durationMs)
                    val frameBmp = retriever.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: continue
                    try {
                        val raw = processBitmap(
                            bitmap = frameBmp,
                            timestampMs = tMs,
                            mode = mode,
                            enforceJointLimits = enforceJointLimits,
                            orientation = orientation
                        )
                        val smoothed = smoother.smooth(raw)
                        out.add(smoothed)
                        onProgress((i + 1).toFloat() / totalSteps.toFloat(), smoothed)
                    } finally {
                        if (!frameBmp.isRecycled) frameBmp.recycle()
                    }
                }
                out
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    @Synchronized
    fun closeLandmarkers() {
        runCatching { faceLandmarker?.close() }
        runCatching { poseLandmarker?.close() }
        runCatching { handLandmarker?.close() }
        faceLandmarker = null
        poseLandmarker = null
        handLandmarker = null
        initializedMode = null
    }
}
