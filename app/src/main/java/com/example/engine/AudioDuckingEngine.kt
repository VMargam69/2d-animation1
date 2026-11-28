package com.example.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.model.AudioDuckingConfig
import com.example.model.AudioSyncMetadata
import com.example.model.AudioTrackRole
import com.example.model.TimelineClip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Input PCM track buffer for offline multi-track audio mixing with Smart Auto Audio Ducking.
 */
data class PcmTrackInput(
    val role: AudioTrackRole,
    val startTimeMs: Long,
    val volume: Float = 1.0f,
    val samples: ShortArray
)

/**
 * Smart Auto Audio Ducking DSP Engine for FrameCraft.
 *
 * Classifies audio tracks into `DIALOGUE`, `BGM`, and `SFX`. Whenever active dialogue
 * exceeds [AudioDuckingConfig.voiceThreshold], smoothly ducks all `BGM` tracks down to
 * [AudioDuckingConfig.duckTargetVolume] (default `0.25f`) using exponential attack/release
 * smoothing (`attackMs` / `releaseMs`), and smoothly restores BGM once dialogue finishes.
 *
 * Pure-Kotlin envelope math + PCM16 mixer runs identically in live playback, unit tests,
 * and offline MP4 audio muxing.
 */
object AudioDuckingEngine {

    const val DEFAULT_STEP_MS = 20

    fun inferEffectiveRole(clip: TimelineClip): AudioTrackRole {
        if (clip.audioTrackRole != AudioTrackRole.BGM) return clip.audioTrackRole
        val lower = "${clip.name} ${clip.audioLabel.orEmpty()}".lowercase()
        return when {
            lower.contains("voice") || lower.contains("dialogue") || lower.contains("dialog") ||
                lower.contains("speech") || lower.contains("narrat") || lower.contains("vocal") ->
                AudioTrackRole.DIALOGUE
            lower.contains("sfx") || lower.contains("effect") || lower.contains("boom") ||
                lower.contains("whoosh") || lower.contains("footstep") ->
                AudioTrackRole.SFX
            else -> AudioTrackRole.BGM
        }
    }

    /**
     * Evaluates whether any `DIALOGUE` clip has active voice energy above [voiceThreshold] at [timeMs].
     */
    fun isDialogueActiveAtMs(
        dialogueClips: List<TimelineClip>,
        audioSyncByClipId: Map<String, AudioSyncMetadata>,
        timeMs: Long,
        fps: Int,
        voiceThreshold: Float
    ): Boolean {
        if (dialogueClips.isEmpty()) return false
        val safeFps = fps.coerceAtLeast(1).toFloat()
        val threshold = voiceThreshold.coerceIn(0.01f, 0.95f)

        for (clip in dialogueClips) {
            val startMs = ((clip.startFrame * 1000f) / safeFps).toLong()
            val endMs = ((clip.endFrame * 1000f) / safeFps).toLong()
            if (timeMs < startMs || timeMs >= endMs) continue

            val sync = audioSyncByClipId[clip.id]
            val localTimeMs = (timeMs - startMs) + clip.audioTrimStartMs
            if (sync != null && sync.volumeCurve.isNotEmpty()) {
                val sample = sync.volumeCurve.minByOrNull { kotlin.math.abs(it.timeMs - localTimeMs) }
                if (sample != null && sample.gain >= threshold) return true
            } else if (sync != null && sync.phonemeMappings.isNotEmpty()) {
                val localFrame = ((localTimeMs * safeFps) / 1000f).toInt()
                val cue = sync.phonemeMappings.minByOrNull { kotlin.math.abs(it.frame - localFrame) }
                if (cue != null && cue.amplitude >= threshold) return true
            } else {
                // No extracted waveform curve yet: active timeline bounds of a DIALOGUE clip count as active voice
                return true
            }
        }
        return false
    }

    /**
     * Computes a deterministic, frame/time-reproducible BGM gain envelope (0.05..1.0) sampled
     * every [stepMs] across `[0..totalDurationMs]`.
     */
    fun computeBgmGainEnvelope(
        sceneClips: List<TimelineClip>,
        audioSyncTracks: List<AudioSyncMetadata>,
        config: AudioDuckingConfig,
        totalDurationMs: Long,
        fps: Int,
        stepMs: Int = DEFAULT_STEP_MS
    ): FloatArray {
        val safeStep = stepMs.coerceIn(5, 100)
        val steps = ((totalDurationMs.coerceAtLeast(safeStep.toLong()) / safeStep).toInt() + 2).coerceAtLeast(2)
        val envelope = FloatArray(steps) { 1.0f }
        if (!config.duckingEnabled) return envelope

        val dialogueClips = sceneClips.filter { clip ->
            (!clip.audioPath.isNullOrBlank() || clip.audioLabel != null || clip.audioTrackRole == AudioTrackRole.DIALOGUE) &&
                inferEffectiveRole(clip) == AudioTrackRole.DIALOGUE
        }
        if (dialogueClips.isEmpty()) return envelope

        val syncMap = audioSyncTracks.associateBy { it.clipId }
        val targetDuck = config.duckTargetVolume.coerceIn(0.05f, 0.85f)
        val attackTau = config.attackMs.coerceIn(20, 2000).toFloat()
        val releaseTau = config.releaseMs.coerceIn(40, 4000).toFloat()
        val attackCoeff = (1.0 - exp(-safeStep.toDouble() / attackTau.toDouble())).toFloat().coerceIn(0.01f, 1f)
        val releaseCoeff = (1.0 - exp(-safeStep.toDouble() / releaseTau.toDouble())).toFloat().coerceIn(0.01f, 1f)

        var gain = 1.0f
        for (i in 0 until steps) {
            val tMs = i.toLong() * safeStep
            val speaking = isDialogueActiveAtMs(
                dialogueClips = dialogueClips,
                audioSyncByClipId = syncMap,
                timeMs = tMs,
                fps = fps,
                voiceThreshold = config.voiceThreshold
            )
            val desired = if (speaking) targetDuck else 1.0f
            val coeff = if (desired < gain) attackCoeff else releaseCoeff
            gain += (desired - gain) * coeff
            envelope[i] = gain.coerceIn(targetDuck, 1.0f)
        }
        return envelope
    }

    /**
     * Linearly interpolates the gain envelope at [timeMs].
     */
    fun sampleGainAtMs(
        envelope: FloatArray,
        timeMs: Long,
        stepMs: Int = DEFAULT_STEP_MS
    ): Float {
        if (envelope.isEmpty()) return 1.0f
        val safeStep = stepMs.coerceAtLeast(1).toFloat()
        val pos = (timeMs.coerceAtLeast(0L) / safeStep).coerceIn(0f, (envelope.size - 1).toFloat())
        val i0 = pos.toInt().coerceIn(0, envelope.lastIndex)
        val i1 = (i0 + 1).coerceIn(0, envelope.lastIndex)
        val frac = pos - i0
        return envelope[i0] + (envelope[i1] - envelope[i0]) * frac
    }

    /**
     * Convenience real-time gain evaluation for live `MediaPlayer.setVolume(...)` during preview playback.
     */
    fun computeClipGainAtMs(
        clip: TimelineClip,
        sceneClips: List<TimelineClip>,
        audioSyncTracks: List<AudioSyncMetadata>,
        config: AudioDuckingConfig,
        timeMs: Long,
        fps: Int
    ): Float {
        val baseVol = clip.audioVolume.coerceIn(0f, 1.5f)
        if (!config.duckingEnabled || inferEffectiveRole(clip) != AudioTrackRole.BGM) {
            return baseVol.coerceIn(0f, 1f)
        }
        val windowEndMs = max(timeMs + 200L, ((clip.endFrame * 1000f) / fps.coerceAtLeast(1)).toLong())
        val env = computeBgmGainEnvelope(
            sceneClips = sceneClips,
            audioSyncTracks = audioSyncTracks,
            config = config,
            totalDurationMs = windowEndMs,
            fps = fps
        )
        return (baseVol * sampleGainAtMs(env, timeMs)).coerceIn(0f, 1f)
    }

    /**
     * Applies [envelope] cleanly to a 16-bit PCM sample buffer for offline MP4 BGM ducking.
     */
    fun applyDuckingEnvelopeToPcm16(
        pcmSamples: ShortArray,
        sampleRateHz: Int,
        channelCount: Int,
        startTimeMs: Long,
        envelope: FloatArray,
        stepMs: Int = DEFAULT_STEP_MS,
        clipVolume: Float = 1.0f
    ): ShortArray {
        val safeRate = sampleRateHz.coerceAtLeast(8000)
        val safeCh = channelCount.coerceIn(1, 2)
        val out = ShortArray(pcmSamples.size)
        val frames = pcmSamples.size / safeCh
        for (f in 0 until frames) {
            val tMs = startTimeMs + (f * 1000L) / safeRate
            val gain = sampleGainAtMs(envelope, tMs, stepMs) * clipVolume
            val baseIdx = f * safeCh
            for (c in 0 until safeCh) {
                val scaled = (pcmSamples[baseIdx + c] * gain).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[baseIdx + c] = scaled.toShort()
            }
        }
        return out
    }

    /**
     * Mixes multiple PCM16 tracks (`DIALOGUE`, `BGM`, `SFX`) applying [envelope] strictly to `BGM` tracks
     * with soft headroom limiting so mixed dialogue + ducked BGM never harshly clips.
     */
    fun mixTracksWithDucking(
        tracks: List<PcmTrackInput>,
        envelope: FloatArray,
        totalSamples: Int,
        sampleRateHz: Int,
        channelCount: Int = 1,
        stepMs: Int = DEFAULT_STEP_MS
    ): ShortArray {
        val safeRate = sampleRateHz.coerceAtLeast(8000)
        val safeCh = channelCount.coerceIn(1, 2)
        val accum = FloatArray(totalSamples.coerceAtLeast(0))
        val totalFrames = accum.size / safeCh

        for (track in tracks) {
            val startFrame = ((track.startTimeMs.coerceAtLeast(0L) * safeRate) / 1000L).toInt()
            val trackFrames = track.samples.size / safeCh
            val isBgm = track.role == AudioTrackRole.BGM
            for (f in 0 until trackFrames) {
                val dstFrame = startFrame + f
                if (dstFrame !in 0 until totalFrames) continue
                val tMs = (dstFrame * 1000L) / safeRate
                val duckGain = if (isBgm) sampleGainAtMs(envelope, tMs, stepMs) else 1.0f
                val totalGain = track.volume.coerceIn(0f, 1.5f) * duckGain
                val srcBase = f * safeCh
                val dstBase = dstFrame * safeCh
                for (c in 0 until safeCh) {
                    accum[dstBase + c] += (track.samples[srcBase + c] / 32768f) * totalGain
                }
            }
        }

        val out = ShortArray(accum.size)
        for (i in accum.indices) {
            val v = accum[i]
            val limited = if (kotlin.math.abs(v) > 0.92f) tanh(v.toDouble()).toFloat() else v
            out[i] = (limited * 32767f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    /**
     * Decodes a local audio clip file to mono 44.1kHz PCM16 via Android MediaExtractor + MediaCodec
     * for offline ducked MP4 audio mixing. Returns null safely if decoding fails.
     */
    fun decodeAudioFileToPcm16(
        audioPath: String,
        trimStartMs: Long = 0L,
        maxDurationMs: Long = 60_000L,
        targetSampleRate: Int = 44100
    ): ShortArray? {
        val file = File(audioPath)
        if (!file.exists() || !file.canRead()) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return runCatching {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return@runCatching null
            extractor.selectTrack(audioTrackIndex)
            if (trimStartMs > 0L) {
                extractor.seekTo(trimStartMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@runCatching null
            val srcRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }
            val dec = codec!!
            val maxSrcSamples = ((maxDurationMs.coerceIn(200L, 120_000L) * srcRate) / 1000L).toInt()
            val mono = ArrayList<Short>(maxSrcSamples.coerceAtMost(256_000))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && mono.size < maxSrcSamples) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val read = extractor.readSampleData(buf, 0)
                        if (read < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx >= 0) {
                    val outBuf = dec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frameCount = shortBuf.remaining() / srcCh
                        for (f in 0 until frameCount) {
                            if (mono.size >= maxSrcSamples) break
                            val sample = if (srcCh == 2) {
                                ((shortBuf.get().toInt() + shortBuf.get().toInt()) / 2).toShort()
                            } else {
                                shortBuf.get()
                            }
                            mono.add(sample)
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            // Linearly resample to targetSampleRate if needed
            if (srcRate == targetSampleRate || mono.isEmpty()) {
                ShortArray(mono.size) { mono[it] }
            } else {
                val ratio = srcRate.toDouble() / targetSampleRate.toDouble()
                val outLen = (mono.size / ratio).toInt().coerceAtLeast(1)
                ShortArray(outLen) { i ->
                    val srcPos = i * ratio
                    val idx0 = srcPos.toInt().coerceIn(0, mono.lastIndex)
                    val idx1 = (idx0 + 1).coerceIn(0, mono.lastIndex)
                    val frac = (srcPos - idx0).toFloat()
                    (mono[idx0] + (mono[idx1] - mono[idx0]) * frac).roundToInt().toShort()
                }
            }
        }.getOrNull().also {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Mixes all scene audio clips with Smart Auto Audio Ducking and encodes/muxes an AAC track
     * directly into [muxer] (given an existing video-only MP4 file -> remuxes video + ducked AAC
     * cleanly into [outputMp4]). Non-fatal: returns true if audio muxing succeeded.
     */
    fun remuxVideoWithDuckedAudio(
        videoOnlyMp4: File,
        outputMp4: File,
        sceneClips: List<TimelineClip>,
        audioSyncTracks: List<AudioSyncMetadata>,
        duckingConfig: AudioDuckingConfig,
        totalDurationMs: Long,
        fps: Int
    ): Boolean {
        val playableClips = sceneClips.filter { !it.audioPath.isNullOrBlank() && File(it.audioPath).exists() }
        if (playableClips.isEmpty() || !videoOnlyMp4.exists()) return false

        val sampleRate = 44100
        val tracks = playableClips.mapNotNull { clip ->
            val clipDurMs = (((clip.durationFrames * 1000f) / fps.coerceAtLeast(1)).toLong()).coerceAtLeast(100L)
            val pcm = decodeAudioFileToPcm16(
                audioPath = clip.audioPath!!,
                trimStartMs = clip.audioTrimStartMs,
                maxDurationMs = clipDurMs,
                targetSampleRate = sampleRate
            ) ?: return@mapNotNull null
            PcmTrackInput(
                role = inferEffectiveRole(clip),
                startTimeMs = ((clip.startFrame * 1000f) / fps.coerceAtLeast(1)).toLong(),
                volume = clip.audioVolume,
                samples = pcm
            )
        }
        if (tracks.isEmpty()) return false

        val envelope = computeBgmGainEnvelope(
            sceneClips = sceneClips,
            audioSyncTracks = audioSyncTracks,
            config = duckingConfig,
            totalDurationMs = totalDurationMs,
            fps = fps
        )
        val totalSamples = ((totalDurationMs.coerceAtLeast(200L) * sampleRate) / 1000L).toInt()
        val mixedPcm = mixTracksWithDucking(
            tracks = tracks,
            envelope = envelope,
            totalSamples = totalSamples,
            sampleRateHz = sampleRate,
            channelCount = 1
        )

        var aacEncoder: MediaCodec? = null
        val videoExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return runCatching {
            videoExtractor.setDataSource(videoOnlyMp4.absolutePath)
            var videoTrackSrc = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    videoTrackSrc = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackSrc < 0 || videoFormat == null) return@runCatching false

            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            aacEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val enc = aacEncoder!!

            // First encode all mixed PCM into AAC packets in memory so we know the exact outputFormat before starting muxer
            val encodedPackets = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
            var encodedAudioFormat: MediaFormat? = null
            val pcmBytes = ByteArray(mixedPcm.size * 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixedPcm)

            var offset = 0
            var eosQueued = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!eosQueued) {
                    val inIdx = enc.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = enc.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remaining = pcmBytes.size - offset
                        if (remaining <= 0) {
                            enc.queueInputBuffer(inIdx, 0, 0, (offset / 2L) * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val chunk = minOf(remaining, inBuf.capacity())
                            inBuf.put(pcmBytes, offset, chunk)
                            val ptsUs = (offset / 2L) * 1_000_000L / sampleRate
                            enc.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
                            offset += chunk
                        }
                    }
                }
                val outIdx = enc.dequeueOutputBuffer(info, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        encodedAudioFormat = enc.outputFormat
                    }
                    outIdx >= 0 -> {
                        val outBuf = enc.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val copy = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(copy)
                            val bi = MediaCodec.BufferInfo().apply {
                                set(0, copy.size, info.presentationTimeUs, info.flags)
                            }
                            encodedPackets.add(copy to bi)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            if (encodedAudioFormat == null || encodedPackets.isEmpty()) return@runCatching false

            muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val m = muxer!!
            val outVideoTrack = m.addTrack(videoFormat)
            val outAudioTrack = m.addTrack(encodedAudioFormat)
            m.start()

            // Copy video samples verbatim
            videoExtractor.selectTrack(videoTrackSrc)
            val vBuf = ByteBuffer.allocate(512 * 1024)
            val vInfo = MediaCodec.BufferInfo()
            while (true) {
                val sz = videoExtractor.readSampleData(vBuf, 0)
                if (sz < 0) break
                vInfo.offset = 0
                vInfo.size = sz
                vInfo.presentationTimeUs = videoExtractor.sampleTime
                vInfo.flags = videoExtractor.sampleFlags
                m.writeSampleData(outVideoTrack, vBuf, vInfo)
                videoExtractor.advance()
            }

            // Write ducked AAC audio packets
            for ((bytes, aInfo) in encodedPackets) {
                m.writeSampleData(outAudioTrack, ByteBuffer.wrap(bytes), aInfo)
            }
            m.stop()
            true
        }.getOrDefault(false).also {
            runCatching { aacEncoder?.stop() }
            runCatching { aacEncoder?.release() }
            runCatching { videoExtractor.release() }
            runCatching { muxer?.release() }
        }
    }
}
