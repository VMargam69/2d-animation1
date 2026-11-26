package com.example.engine

import com.example.model.AnimationScene
import com.example.model.AudioSyncMetadata
import com.example.model.PhonemeFrameMapping
import com.example.model.ProjectDocument
import com.example.model.TimelineClip
import com.example.model.VolumeCurvePoint
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class LipSyncMouthCue(
    val frame: Int,
    val timeMs: Long,
    val peak: Float,
    val mouthShape: String // "MTH1" (closed) .. "MTH4" (wide open)
)

data class CharacterExpressionKeyExport(
    val frame: Int,
    val expression: String,
    val blink: Boolean? = null
)

data class CharacterLipSyncTrack(
    val clipId: String,
    val characterName: String,
    val startFrame: Int,
    val durationFrames: Int,
    val baseExpression: String,
    val expressionKeyframes: List<CharacterExpressionKeyExport> = emptyList()
)

/**
 * Lightweight, shareable JSON schema for audio waveform peaks, trim window,
 * derived lip-sync mouth cues (MTH1–MTH4), phoneme frame mappings, duration-based volume curves,
 * and synced character expression tracks.
 */
data class WaveformLipSyncExport(
    val schemaVersion: Int = 1,
    val format: String = "framecraft_waveform_lipsync",
    val projectName: String,
    val sceneName: String,
    val clipId: String,
    val clipName: String,
    val fps: Int,
    val startFrame: Int,
    val durationFrames: Int,
    val speedMultiplier: Float,
    val audioDurationMs: Long,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val waveformSensitivity: Float = 1.0f,
    val peakCount: Int,
    val peaks: List<Float>,
    val lipSyncCues: List<LipSyncMouthCue>,
    val phonemeMappings: List<PhonemeFrameMapping> = emptyList(),
    val volumeCurve: List<VolumeCurvePoint> = emptyList(),
    val syncedCharacters: List<CharacterLipSyncTrack> = emptyList()
)

object AudioWaveformJsonCodec {

    const val MIN_SENSITIVITY = 0.4f
    const val MAX_SENSITIVITY = 3.0f
    private const val MAX_JSON_CHARS = 2 * 1024 * 1024 // 2 MB cap

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val adapter by lazy {
        moshi.adapter(WaveformLipSyncExport::class.java)
    }

    /** Scales a normalized peak amplitude (0..1) by user-adjustable waveform sensitivity (0.4x..3.0x). */
    fun scaleAmplitude(amplitude: Float, sensitivity: Float): Float {
        if (amplitude < 0f) return amplitude
        val clampedSens = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        return (amplitude * clampedSens).coerceIn(0f, 1f)
    }

    /** Maps a normalized peak amplitude (0..1) into standard 4-stage mouth shapes (MTH1–MTH4). */
    fun mouthShapeForPeak(peak: Float): String = when {
        peak < 0.12f -> "MTH1"
        peak < 0.38f -> "MTH2"
        peak < 0.68f -> "MTH3"
        else -> "MTH4"
    }

    /** Maps a normalized peak amplitude (0..1) into standard cartoon animation phoneme classes. */
    fun phonemeForPeak(peak: Float): String = when {
        peak < 0.10f -> "REST"
        peak < 0.28f -> "MBP"
        peak < 0.52f -> "EE"
        peak < 0.76f -> "AA"
        else -> "OH"
    }

    /**
     * Builds structured [AudioSyncMetadata] suitable for persisting inside [ProjectDocument.audioSyncTracks],
     * including frame-aligned [PhonemeFrameMapping] cues and a duration-based [VolumeCurvePoint] envelope.
     */
    fun buildAudioSyncMetadata(
        project: ProjectDocument,
        audioClip: TimelineClip,
        waveform: AudioWaveformData?,
        sensitivity: Float = 1.0f,
        volumeCurveSamples: Int = 32
    ): AudioSyncMetadata {
        val fps = project.fps.coerceAtLeast(1)
        val speed = audioClip.speedMultiplier.coerceIn(0.1f, 4f)
        val clampedSens = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        val totalAudioMs = waveform?.durationMs ?: 0L
        val trimStart = audioClip.audioTrimStartMs.coerceAtLeast(0L)
        val trimEnd = AudioWaveformExtractor.effectiveTrimEndMs(
            audioDurationMs = totalAudioMs,
            trimStartMs = trimStart,
            trimEndMs = audioClip.audioTrimEndMs
        )
        val clipFrames = audioClip.durationFrames.coerceAtLeast(1)

        // 1. Frame-aligned phoneme mappings (transition-deduplicated)
        val phonemes = mutableListOf<PhonemeFrameMapping>()
        if (waveform != null && !waveform.isEmpty) {
            var lastPhoneme: String? = null
            for (localF in 0 until clipFrames) {
                val rawAmp = AudioWaveformExtractor.samplePeakInFrameRange(
                    waveform = waveform,
                    startLocalFrame = localF.toFloat(),
                    endLocalFrame = (localF + 1).toFloat(),
                    clipDurationFrames = clipFrames,
                    fps = fps,
                    speedMultiplier = speed,
                    trimStartMs = trimStart,
                    trimEndMs = trimEnd
                )
                val scaledAmp = scaleAmplitude(rawAmp.coerceAtLeast(0f), clampedSens)
                val phoneme = phonemeForPeak(scaledAmp)
                if (phoneme != lastPhoneme) {
                    val globalFrame = audioClip.startFrame + localF
                    val timeMs = (globalFrame.toLong() * 1000L) / fps.toLong()
                    phonemes += PhonemeFrameMapping(
                        frame = globalFrame,
                        timeMs = timeMs,
                        phoneme = phoneme,
                        mouthShape = mouthShapeForPeak(scaledAmp),
                        amplitude = round3(scaledAmp)
                    )
                    lastPhoneme = phoneme
                }
            }
        }

        // 2. Duration-based volume curve envelope across the active clip duration
        val curvePoints = mutableListOf<VolumeCurvePoint>()
        val targetSamples = volumeCurveSamples.coerceIn(4, 128)
        val activeSpanMs = if (trimEnd > trimStart) {
            trimEnd - trimStart
        } else {
            ((clipFrames.toDouble() * 1000.0) / fps.toDouble()).roundToLong().coerceAtLeast(100L)
        }
        val stepFrames = clipFrames.toFloat() / targetSamples.toFloat()
        for (i in 0 until targetSamples) {
            val f0 = i * stepFrames
            val f1 = (i + 1) * stepFrames
            val rawGain = if (waveform != null && !waveform.isEmpty) {
                AudioWaveformExtractor.samplePeakInFrameRange(
                    waveform = waveform,
                    startLocalFrame = f0,
                    endLocalFrame = f1,
                    clipDurationFrames = clipFrames,
                    fps = fps,
                    speedMultiplier = speed,
                    trimStartMs = trimStart,
                    trimEndMs = trimEnd
                ).coerceAtLeast(0f)
            } else {
                1.0f
            }
            val ratio = if (targetSamples <= 1) 0f else i.toFloat() / (targetSamples - 1).toFloat()
            val timeMs = trimStart + (activeSpanMs * ratio).roundToLong()
            val frame = audioClip.startFrame + f0.roundToInt().coerceIn(0, clipFrames - 1)
            curvePoints += VolumeCurvePoint(
                timeMs = timeMs,
                frame = frame,
                gain = round3(scaleAmplitude(rawGain, clampedSens))
            )
        }

        return AudioSyncMetadata(
            clipId = audioClip.id,
            clipName = audioClip.name,
            fps = fps,
            durationMs = totalAudioMs,
            trimStartMs = trimStart,
            trimEndMs = trimEnd,
            waveformSensitivity = round3(clampedSens),
            phonemeMappings = phonemes,
            volumeCurve = curvePoints
        )
    }

    /**
     * Builds a lightweight [WaveformLipSyncExport] from the active project/scene, an audio clip,
     * and its extracted [AudioWaveformData], respecting the clip's current trim window and sensitivity.
     */
    fun buildExport(
        project: ProjectDocument,
        scene: AnimationScene,
        audioClip: TimelineClip,
        waveform: AudioWaveformData?,
        maxExportPeaks: Int = 160,
        sensitivity: Float = 1.0f
    ): WaveformLipSyncExport {
        val fps = project.fps.coerceAtLeast(1)
        val speed = audioClip.speedMultiplier.coerceIn(0.1f, 4f)
        val clampedSens = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        val totalAudioMs = waveform?.durationMs ?: 0L
        val trimStart = audioClip.audioTrimStartMs.coerceAtLeast(0L)
        val trimEnd = AudioWaveformExtractor.effectiveTrimEndMs(
            audioDurationMs = totalAudioMs,
            trimStartMs = trimStart,
            trimEndMs = audioClip.audioTrimEndMs
        )

        val clipFrames = audioClip.durationFrames.coerceAtLeast(1)
        val targetPeaks = maxExportPeaks.coerceIn(24, 320)

        // 1. Sample compact trimmed peak array evenly across the clip's active frames
        val trimmedPeaks = if (waveform != null && !waveform.isEmpty) {
            val framesPerBucket = clipFrames.toFloat() / targetPeaks.toFloat()
            List(targetPeaks) { idx ->
                val f0 = idx * framesPerBucket
                val f1 = (idx + 1) * framesPerBucket
                val raw = AudioWaveformExtractor.samplePeakInFrameRange(
                    waveform = waveform,
                    startLocalFrame = f0,
                    endLocalFrame = f1,
                    clipDurationFrames = clipFrames,
                    fps = fps,
                    speedMultiplier = speed,
                    trimStartMs = trimStart,
                    trimEndMs = trimEnd
                )
                round3(scaleAmplitude(raw.coerceAtLeast(0f), clampedSens))
            }
        } else {
            emptyList()
        }

        // 2. Generate lightweight transition-deduplicated lip-sync mouth cues per frame
        val cues = mutableListOf<LipSyncMouthCue>()
        if (waveform != null && !waveform.isEmpty) {
            var lastMouth: String? = null
            for (localF in 0 until clipFrames) {
                val amp = AudioWaveformExtractor.samplePeakInFrameRange(
                    waveform = waveform,
                    startLocalFrame = localF.toFloat(),
                    endLocalFrame = (localF + 1).toFloat(),
                    clipDurationFrames = clipFrames,
                    fps = fps,
                    speedMultiplier = speed,
                    trimStartMs = trimStart,
                    trimEndMs = trimEnd
                )
                val cleanAmp = scaleAmplitude(amp.coerceAtLeast(0f), clampedSens)
                val mouth = mouthShapeForPeak(cleanAmp)
                if (mouth != lastMouth) {
                    val globalFrame = audioClip.startFrame + localF
                    val timeMs = (globalFrame.toLong() * 1000L) / fps.toLong()
                    cues += LipSyncMouthCue(
                        frame = globalFrame,
                        timeMs = timeMs,
                        peak = round3(cleanAmp),
                        mouthShape = mouth
                    )
                    lastMouth = mouth
                }
            }
        }

        val syncMeta = buildAudioSyncMetadata(
            project = project,
            audioClip = audioClip,
            waveform = waveform,
            sensitivity = clampedSens
        )

        // 3. Snapshot synced character clips & expression/blink keyframes in the same scene
        val syncedChars = scene.clips
            .filter { !it.characterId.isNullOrBlank() }
            .map { charClip ->
                val exprKeys = charClip.keyframes.mapNotNull { kf ->
                    val expr = kf.expressionOverride?.name
                    val blink = kf.blinkOverride
                    if (expr != null || blink != null) {
                        CharacterExpressionKeyExport(
                            frame = charClip.startFrame + kf.frame,
                            expression = expr ?: charClip.facialExpression.name,
                            blink = blink
                        )
                    } else {
                        null
                    }
                }
                CharacterLipSyncTrack(
                    clipId = charClip.id,
                    characterName = charClip.name,
                    startFrame = charClip.startFrame,
                    durationFrames = charClip.durationFrames,
                    baseExpression = charClip.facialExpression.name,
                    expressionKeyframes = exprKeys
                )
            }

        return WaveformLipSyncExport(
            schemaVersion = 1,
            projectName = project.name,
            sceneName = scene.name,
            clipId = audioClip.id,
            clipName = audioClip.name,
            fps = fps,
            startFrame = audioClip.startFrame,
            durationFrames = clipFrames,
            speedMultiplier = round3(speed),
            audioDurationMs = totalAudioMs,
            trimStartMs = trimStart,
            trimEndMs = trimEnd,
            waveformSensitivity = round3(clampedSens),
            peakCount = trimmedPeaks.size,
            peaks = trimmedPeaks,
            lipSyncCues = cues,
            phonemeMappings = syncMeta.phonemeMappings,
            volumeCurve = syncMeta.volumeCurve,
            syncedCharacters = syncedChars
        )
    }

    fun toJson(export: WaveformLipSyncExport, pretty: Boolean = false): String {
        return if (pretty) adapter.indent("  ").toJson(export) else adapter.toJson(export)
    }

    fun fromJson(rawJson: String): WaveformLipSyncExport? {
        if (rawJson.isBlank() || rawJson.length > MAX_JSON_CHARS) return null
        return runCatching {
            val parsed = adapter.fromJson(rawJson) ?: return null
            if (parsed.schemaVersion != 1 || parsed.format != "framecraft_waveform_lipsync") return null
            parsed
        }.getOrNull()
    }

    private fun round3(value: Float): Float = (value * 1000f).roundToInt() / 1000f
}
