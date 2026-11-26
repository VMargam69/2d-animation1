package com.example.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Extracted normalized waveform envelope (0f..1f) and real track duration for an audio file.
 * Used by TimelinePanel to render frame-accurate audio waveforms for lip-sync and beat alignment.
 */
data class AudioWaveformData(
    val amplitudes: FloatArray,
    val durationMs: Long
) {
    val isEmpty: Boolean get() = amplitudes.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioWaveformData) return false
        return durationMs == other.durationMs && amplitudes.contentEquals(other.amplitudes)
    }

    override fun hashCode(): Int {
        var result = amplitudes.contentHashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }
}

/**
 * High-performance, offline audio waveform extractor with LRU memory caching.
 *
 * - Runs strictly off the main thread (`Dispatchers.IO`).
 * - Fast streaming parser for uncompressed PCM `.wav` files (zero codec overhead).
 * - Universal Android `MediaExtractor` + `MediaCodec` decoder for `.mp3`, `.aac`, `.m4a`,
 *   `.ogg`, `.flac`, and `.opus` with seek-stride sampling for long audio tracks (> 90s).
 * - Combines Peak + RMS energy so speech syllables and sharp transients pop clearly
 *   for mouth-shape and keyframe syncing.
 */
object AudioWaveformExtractor {

    private const val DEFAULT_BUCKETS = 320
    private const val MAX_AUDIO_FILE_BYTES = 150L * 1024L * 1024L // 150 MB safety cap
    private const val LONG_TRACK_THRESHOLD_US = 90_000_000L // 90 seconds -> seek-stride mode
    private const val SILENCE_FLOOR = 0.008f

    private data class CachedEntry(
        val lastModified: Long,
        val fileLength: Long,
        val data: AudioWaveformData
    )

    private val cache = LruCache<String, CachedEntry>(32)

    /** Fast non-blocking memory-cache lookup for Compose initial state (zero disk I/O). */
    fun getCached(audioPath: String?): AudioWaveformData? {
        if (audioPath.isNullOrBlank()) return null
        return cache.get(audioPath)?.data
    }

    /** Evicts cached waveform when an audio asset is deleted or overwritten. */
    fun evict(audioPath: String?) {
        if (audioPath.isNullOrBlank()) return
        cache.remove(audioPath)
    }

    fun clearCache() {
        cache.evictAll()
    }

    /**
     * Extracts or retrieves a cached [AudioWaveformData] for [audioPath] on [Dispatchers.IO].
     * Returns null if the file is missing, unreadable, oversized, or has no audio track.
     */
    suspend fun extractWaveform(
        audioPath: String,
        targetBuckets: Int = DEFAULT_BUCKETS
    ): AudioWaveformData? = withContext(Dispatchers.IO) {
        if (audioPath.isBlank()) return@withContext null
        val file = File(audioPath)
        if (!file.exists() || !file.isFile) return@withContext null
        val fileLen = file.length()
        if (fileLen <= 0L || fileLen > MAX_AUDIO_FILE_BYTES) return@withContext null
        val modified = file.lastModified()

        val existing = cache.get(audioPath)
        if (existing != null && existing.lastModified == modified && existing.fileLength == fileLen) {
            return@withContext existing.data
        }

        val buckets = targetBuckets.coerceIn(48, 600)
        val extracted = runCatching {
            // Fast path for uncompressed PCM WAV files
            parseWavWaveform(file, buckets)
                ?: decodeWithMediaCodec(file, buckets)
        }.getOrNull()

        if (extracted != null && extracted.amplitudes.isNotEmpty()) {
            cache.put(audioPath, CachedEntry(modified, fileLen, extracted))
        }
        extracted
    }

    /**
     * Pure-Kotlin streaming RIFF/WAVE PCM waveform parser (8-bit unsigned & 16-bit signed PCM).
     * Returns null if the file is not uncompressed PCM WAV so caller can fall back to MediaCodec.
     */
    internal fun parseWavWaveform(file: File, targetBuckets: Int = DEFAULT_BUCKETS): AudioWaveformData? {
        val buckets = targetBuckets.coerceIn(16, 600)
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44L) return@use null
                val header = ByteArray(12)
                raf.readFully(header)
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                val wave = String(header, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return@use null

                var audioFormat = -1
                var numChannels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataOffset = -1L
                var dataSize = 0L

                val chunkHeader = ByteArray(8)
                var guard = 0
                while (raf.filePointer + 8L <= raf.length() && guard < 64) {
                    guard++
                    raf.readFully(chunkHeader)
                    val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int.toLong() and 0xFFFFFFFFL

                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize < 16L || raf.filePointer + chunkSize > raf.length()) return@use null
                            val fmtBuf = ByteArray(16)
                            raf.readFully(fmtBuf)
                            val bb = ByteBuffer.wrap(fmtBuf).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormat = bb.short.toInt() and 0xFFFF
                            numChannels = bb.short.toInt() and 0xFFFF
                            sampleRate = bb.int
                            bb.int // byteRate
                            bb.short // blockAlign
                            bitsPerSample = bb.short.toInt() and 0xFFFF
                            val remaining = chunkSize - 16L
                            if (remaining > 0L) raf.seek(raf.filePointer + remaining)
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataSize = min(chunkSize, raf.length() - dataOffset)
                            break
                        }
                        else -> {
                            val nextPos = raf.filePointer + chunkSize + (chunkSize and 1L)
                            if (nextPos > raf.length()) break
                            raf.seek(nextPos)
                        }
                    }
                }

                // Only fast-path standard uncompressed PCM (format 1) with 8-bit or 16-bit samples
                if (audioFormat != 1 || numChannels !in 1..8 || sampleRate <= 0) return@use null
                if (bitsPerSample != 8 && bitsPerSample != 16) return@use null
                if (dataOffset < 0L || dataSize <= 0L) return@use null

                val bytesPerSample = bitsPerSample / 8
                val frameBytes = bytesPerSample * numChannels
                val totalFrames = dataSize / frameBytes
                if (totalFrames <= 0L) return@use null

                val durationMs = (totalFrames * 1000L) / sampleRate.toLong()
                val peaks = FloatArray(buckets)
                val sumSq = DoubleArray(buckets)
                val counts = IntArray(buckets)

                raf.seek(dataOffset)
                // Keep buffer aligned to an exact multiple of frameBytes
                val bufFrames = (16384 / frameBytes).coerceAtLeast(1)
                val buf = ByteArray(bufFrames * frameBytes)
                var remainingBytes = dataSize
                var frameCursor = 0L

                while (remainingBytes >= frameBytes) {
                    val toRead = min(buf.size.toLong(), remainingBytes).toInt()
                    val alignedRead = (toRead / frameBytes) * frameBytes
                    if (alignedRead <= 0) break
                    val read = raf.read(buf, 0, alignedRead)
                    if (read <= 0) break
                    val framesInChunk = read / frameBytes

                    var offset = 0
                    for (f in 0 until framesInChunk) {
                        var monoAbs = 0f
                        for (ch in 0 until numChannels) {
                            val sample = if (bitsPerSample == 16) {
                                val lo = buf[offset].toInt() and 0xFF
                                val hi = buf[offset + 1].toInt()
                                val pcm16 = (hi shl 8) or lo
                                abs(pcm16 / 32768f)
                            } else {
                                abs(((buf[offset].toInt() and 0xFF) - 128) / 128f)
                            }
                            if (sample > monoAbs) monoAbs = sample
                            offset += bytesPerSample
                        }
                        val bIdx = ((frameCursor * buckets) / totalFrames)
                            .toInt()
                            .coerceIn(0, buckets - 1)
                        if (monoAbs > peaks[bIdx]) peaks[bIdx] = monoAbs
                        sumSq[bIdx] += (monoAbs * monoAbs).toDouble()
                        counts[bIdx]++
                        frameCursor++
                    }
                    remainingBytes -= read.toLong()
                }

                val rms = FloatArray(buckets) { i ->
                    if (counts[i] > 0) sqrt(sumSq[i] / counts[i]).toFloat() else 0f
                }
                AudioWaveformData(
                    amplitudes = normalizeAmplitudes(peaks, rms),
                    durationMs = durationMs
                )
            }
        }.getOrNull()
    }

    /**
     * Decodes compressed audio tracks (MP3, AAC, M4A, OGG, FLAC, Opus) using Android's
     * MediaExtractor + MediaCodec into normalized amplitude buckets.
     */
    private fun decodeWithMediaCodec(file: File, targetBuckets: Int): AudioWaveformData? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) return null

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val channelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else {
                1
            }
            val sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else {
                44100
            }

            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            return if (durationUs > LONG_TRACK_THRESHOLD_US) {
                decodeLongTrackWithSeekStride(
                    extractor = extractor,
                    codec = codec,
                    durationUs = durationUs,
                    channelCount = channelCount,
                    targetBuckets = targetBuckets
                )
            } else {
                decodeFullTrackSequential(
                    extractor = extractor,
                    codec = codec,
                    durationUs = durationUs,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    targetBuckets = targetBuckets
                )
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeFullTrackSequential(
        extractor: MediaExtractor,
        codec: MediaCodec,
        durationUs: Long,
        sampleRate: Int,
        channelCount: Int,
        targetBuckets: Int
    ): AudioWaveformData? {
        val peaks = FloatArray(targetBuckets)
        val sumSq = DoubleArray(targetBuckets)
        val counts = IntArray(targetBuckets)
        val bufferInfo = MediaCodec.BufferInfo()

        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var actualChannels = channelCount
        var actualSampleRate = sampleRate
        var inputDone = false
        var outputDone = false
        var decodedSamplesTotal = 0L
        var maxObservedPtsUs = durationUs
        val maxDecodeBytes = 48L * 1024L * 1024L
        var decodedBytesTotal = 0L
        var idleRounds = 0

        // When duration is unknown, accumulate coarse chunks then resample at end
        val fallbackPeaks = if (durationUs <= 0L) ArrayList<Float>(512) else null

        while (!outputDone && decodedBytesTotal < maxDecodeBytes && idleRounds < 200) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(8_000L)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 8_000L)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        actualChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        actualSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ) {
                        pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
                outIdx >= 0 -> {
                    idleRounds = 0
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        decodedBytesTotal += bufferInfo.size
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)

                        val frameStats = measureBufferEnergy(outBuf, pcmEncoding, actualChannels)
                        val framesInBuf = frameStats.frameCount
                        val ptsUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                        val bufDurationUs = if (actualSampleRate > 0) {
                            (framesInBuf * 1_000_000L) / actualSampleRate
                        } else {
                            0L
                        }
                        if (ptsUs + bufDurationUs > maxObservedPtsUs) {
                            maxObservedPtsUs = ptsUs + bufDurationUs
                        }

                        if (durationUs > 0L) {
                            val startBucket = ((ptsUs * targetBuckets) / durationUs)
                                .toInt()
                                .coerceIn(0, targetBuckets - 1)
                            val endBucket = (((ptsUs + bufDurationUs) * targetBuckets) / durationUs)
                                .toInt()
                                .coerceIn(startBucket, targetBuckets - 1)
                            for (b in startBucket..endBucket) {
                                if (frameStats.peak > peaks[b]) peaks[b] = frameStats.peak
                                sumSq[b] += (frameStats.rms * frameStats.rms).toDouble()
                                counts[b]++
                            }
                        } else {
                            fallbackPeaks?.add(0.68f * frameStats.peak + 0.32f * frameStats.rms)
                        }
                        decodedSamplesTotal += framesInBuf
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    idleRounds++
                }
            }
        }

        val resolvedDurationMs = when {
            durationUs > 0L -> durationUs / 1000L
            maxObservedPtsUs > 0L -> maxObservedPtsUs / 1000L
            decodedSamplesTotal > 0L -> (decodedSamplesTotal * 1000L) / actualSampleRate.toLong()
            else -> 0L
        }

        val finalAmps = if (durationUs > 0L) {
            val rms = FloatArray(targetBuckets) { i ->
                if (counts[i] > 0) sqrt(sumSq[i] / counts[i]).toFloat() else 0f
            }
            fillSparseGaps(normalizeAmplitudes(peaks, rms))
        } else {
            val raw = fallbackPeaks ?: return null
            if (raw.isEmpty()) return null
            resampleRawList(raw, targetBuckets)
        }

        return AudioWaveformData(amplitudes = finalAmps, durationMs = resolvedDurationMs)
    }

    /**
     * Seek-stride decoder for long audio files (> 90 seconds) so a 5-10 minute soundtrack
     * extracts in < 200ms while preserving true time alignment across the timeline.
     */
    private fun decodeLongTrackWithSeekStride(
        extractor: MediaExtractor,
        codec: MediaCodec,
        durationUs: Long,
        channelCount: Int,
        targetBuckets: Int
    ): AudioWaveformData {
        val peaks = FloatArray(targetBuckets)
        val rmsArr = FloatArray(targetBuckets)
        val bufferInfo = MediaCodec.BufferInfo()
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var actualChannels = channelCount

        for (b in 0 until targetBuckets) {
            val targetUs = (b.toLong() * durationUs) / targetBuckets.toLong()
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()

            var bucketPeak = 0f
            var bucketRmsSum = 0.0
            var bucketFrames = 0
            var framesQueued = 0

            // Decode up to 3 small packets at each bucket timestamp
            for (step in 0 until 8) {
                if (framesQueued < 3) {
                    val inIdx = codec.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                framesQueued = 3
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                                framesQueued++
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 4_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        actualChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ) {
                        pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val stats = measureBufferEnergy(outBuf, pcmEncoding, actualChannels)
                        if (stats.peak > bucketPeak) bucketPeak = stats.peak
                        bucketRmsSum += (stats.rms * stats.rms).toDouble()
                        bucketFrames++
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bucketFrames >= 2 || (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
            peaks[b] = bucketPeak
            rmsArr[b] = if (bucketFrames > 0) sqrt(bucketRmsSum / bucketFrames).toFloat() else 0f
        }

        return AudioWaveformData(
            amplitudes = normalizeAmplitudes(peaks, rmsArr),
            durationMs = durationUs / 1000L
        )
    }

    private data class BufferEnergy(val peak: Float, val rms: Float, val frameCount: Long)

    private fun measureBufferEnergy(
        buffer: ByteBuffer,
        pcmEncoding: Int,
        channels: Int
    ): BufferEnergy {
        val dup = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0f
        var sumSq = 0.0
        var sampleCount = 0L

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = dup.asFloatBuffer()
                val n = fb.remaining()
                // Stride by 2 on dense buffers to keep CPU load tiny
                val stride = if (n > 2048) 2 else 1
                var i = 0
                while (i < n) {
                    val v = abs(fb.get(i)).coerceIn(0f, 1f)
                    if (v > peak) peak = v
                    sumSq += (v * v).toDouble()
                    sampleCount++
                    i += stride
                }
                return BufferEnergy(
                    peak = peak,
                    rms = if (sampleCount > 0) sqrt(sumSq / sampleCount).toFloat() else 0f,
                    frameCount = (n / channels.coerceAtLeast(1)).toLong()
                )
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = dup.remaining()
                var i = 0
                while (i < n) {
                    val v = abs(((dup.get(i).toInt() and 0xFF) - 128) / 128f)
                    if (v > peak) peak = v
                    sumSq += (v * v).toDouble()
                    sampleCount++
                    i++
                }
                return BufferEnergy(
                    peak = peak,
                    rms = if (sampleCount > 0) sqrt(sumSq / sampleCount).toFloat() else 0f,
                    frameCount = (n / channels.coerceAtLeast(1)).toLong()
                )
            }
            else -> { // ENCODING_PCM_16BIT
                val sb = dup.asShortBuffer()
                val n = sb.remaining()
                val stride = if (n > 4096) 2 else 1
                var i = 0
                while (i < n) {
                    val v = abs(sb.get(i) / 32768f)
                    if (v > peak) peak = v
                    sumSq += (v * v).toDouble()
                    sampleCount++
                    i += stride
                }
                return BufferEnergy(
                    peak = peak,
                    rms = if (sampleCount > 0) sqrt(sumSq / sampleCount).toFloat() else 0f,
                    frameCount = (n / channels.coerceAtLeast(1)).toLong()
                )
            }
        }
    }

    /**
     * Pure-Kotlin PCM-16 peak+RMS extractor (unit-testable on JVM without Android codecs).
     */
    internal fun extractPeaksFromPcm16(
        samples: ShortArray,
        channels: Int = 1,
        bucketCount: Int = 64
    ): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val totalFrames = samples.size / ch
        val buckets = bucketCount.coerceIn(1, 1024)
        if (totalFrames <= 0) return FloatArray(buckets)

        val peaks = FloatArray(buckets)
        val sumSq = DoubleArray(buckets)
        val counts = IntArray(buckets)

        for (f in 0 until totalFrames) {
            var monoAbs = 0f
            val base = f * ch
            for (c in 0 until ch) {
                val v = abs(samples[base + c] / 32768f)
                if (v > monoAbs) monoAbs = v
            }
            val b = ((f.toLong() * buckets) / totalFrames).toInt().coerceIn(0, buckets - 1)
            if (monoAbs > peaks[b]) peaks[b] = monoAbs
            sumSq[b] += (monoAbs * monoAbs).toDouble()
            counts[b]++
        }
        val rms = FloatArray(buckets) { i ->
            if (counts[i] > 0) sqrt(sumSq[i] / counts[i]).toFloat() else 0f
        }
        return normalizeAmplitudes(peaks, rms)
    }

    /**
     * Combines peak (transient clarity) + RMS (vowel body), applies silence gating
     * and gentle perceptual compression so speech peaks are effortless to read.
     */
    internal fun normalizeAmplitudes(peaks: FloatArray, rms: FloatArray): FloatArray {
        val n = min(peaks.size, rms.size)
        if (n == 0) return FloatArray(0)
        val blended = FloatArray(n)
        var maxVal = 0f
        for (i in 0 until n) {
            val v = (0.68f * peaks[i] + 0.32f * rms[i]).coerceIn(0f, 1f)
            blended[i] = v
            if (v > maxVal) maxVal = v
        }
        if (maxVal < SILENCE_FLOOR) {
            return FloatArray(n) { 0f }
        }
        val normScale = 1f / maxVal
        return FloatArray(n) { i ->
            val raw = blended[i]
            if (raw < SILENCE_FLOOR * 0.5f) {
                0f
            } else {
                (raw * normScale).coerceIn(0f, 1f).pow(0.84f)
            }
        }
    }

    private fun fillSparseGaps(amps: FloatArray): FloatArray {
        if (amps.size < 3) return amps
        val out = amps.copyOf()
        for (i in 1 until amps.lastIndex) {
            if (out[i] == 0f && out[i - 1] > 0.04f && out[i + 1] > 0.04f) {
                out[i] = (out[i - 1] + out[i + 1]) * 0.5f
            }
        }
        return out
    }

    private fun resampleRawList(raw: List<Float>, targetBuckets: Int): FloatArray {
        val peaks = FloatArray(targetBuckets)
        val rms = FloatArray(targetBuckets)
        for (i in 0 until targetBuckets) {
            val start = (i * raw.size) / targetBuckets
            val end = max(start + 1, ((i + 1) * raw.size) / targetBuckets).coerceAtMost(raw.size)
            var p = 0f
            var sum = 0f
            for (j in start until end) {
                val v = raw[j]
                if (v > p) p = v
                sum += v
            }
            peaks[i] = p
            rms[i] = sum / (end - start).coerceAtLeast(1)
        }
        return normalizeAmplitudes(peaks, rms)
    }

    /**
     * Resolves the effective trim end timestamp in milliseconds, honoring full track duration
     * when [trimEndMs] is 0 or exceeds [audioDurationMs].
     */
    internal fun effectiveTrimEndMs(
        audioDurationMs: Long,
        trimStartMs: Long,
        trimEndMs: Long
    ): Long {
        val safeStart = trimStartMs.coerceAtLeast(0L)
        return when {
            audioDurationMs > 0L && trimEndMs > safeStart -> min(trimEndMs, audioDurationMs)
            audioDurationMs > 0L -> audioDurationMs
            trimEndMs > safeStart -> trimEndMs
            else -> 0L
        }
    }

    /**
     * Maps a timeline local frame range `[startLocalFrame, endLocalFrame]` to the maximum
     * waveform peak in that exact playback time window (respecting [trimStartMs] and [trimEndMs])
     * so zoomed-out bars never miss sharp speech consonants or beat transients.
     *
     * Returns `-1f` when the frame lies past the real or trimmed end of the audio file so the
     * timeline can clearly distinguish active sound from trailing silence.
     */
    internal fun samplePeakInFrameRange(
        waveform: AudioWaveformData,
        startLocalFrame: Float,
        endLocalFrame: Float,
        clipDurationFrames: Int,
        fps: Int,
        speedMultiplier: Float,
        trimStartMs: Long = 0L,
        trimEndMs: Long = 0L
    ): Float {
        val amps = waveform.amplitudes
        if (amps.isEmpty()) return 0f

        val safeFps = fps.coerceAtLeast(1).toFloat()
        val safeSpeed = speedMultiplier.coerceIn(0.1f, 4f)
        val safeTrimStart = trimStartMs.coerceAtLeast(0L).toFloat()

        val startFrac: Float
        val endFrac: Float

        if (waveform.durationMs > 0L) {
            val durMs = waveform.durationMs.toFloat()
            val effectiveEnd = effectiveTrimEndMs(waveform.durationMs, trimStartMs, trimEndMs).toFloat()
            val startMs = safeTrimStart + (startLocalFrame * 1000f * safeSpeed) / safeFps
            val endMs = safeTrimStart + (endLocalFrame * 1000f * safeSpeed) / safeFps
            if (startMs >= effectiveEnd || startMs >= durMs) return -1f
            val clampedEndMs = min(endMs, effectiveEnd)
            startFrac = (startMs / durMs).coerceIn(0f, 1f)
            endFrac = (clampedEndMs / durMs).coerceIn(startFrac, 1f)
        } else {
            val denom = clipDurationFrames.coerceAtLeast(1).toFloat()
            startFrac = (startLocalFrame / denom).coerceIn(0f, 1f)
            endFrac = (endLocalFrame / denom).coerceIn(startFrac, 1f)
        }

        val lastIdx = amps.lastIndex
        val i0 = floor(startFrac * lastIdx).toInt().coerceIn(0, lastIdx)
        val i1 = ceil(endFrac * lastIdx).toInt().coerceIn(i0, lastIdx)
        var maxPeak = 0f
        for (i in i0..i1) {
            if (amps[i] > maxPeak) maxPeak = amps[i]
        }
        return maxPeak
    }
}
