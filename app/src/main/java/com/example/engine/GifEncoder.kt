package com.example.engine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Arrays
import kotlin.math.abs

/**
 * High-speed pure Kotlin GIF89a animated encoder with 6x6x6 + grayscale global palette
 * quantization and zero-allocation primitive open-addressed LZW hash compression.
 */
class GifEncoder(
    private val width: Int,
    private val height: Int,
    private val delayCentiseconds: Int = 4,
    private val loopCount: Int = 0
) {
    private val paletteRgb = ByteArray(256 * 3)
    private val buffer = ByteArrayOutputStream(256 * 1024)
    private var started = false

    // Reusable buffers across all frames (eliminates millions of GC allocations during GIF export)
    private val pixelScratch = IntArray(width * height)
    private val indexedScratch = ByteArray(width * height)
    private val subBlock = ByteArray(255)

    // Primitive open-addressed LZW dictionary (size 8192 = power of 2 for 4096 max entries, <50% load factor)
    private val hashMask = 8191
    private val hashKeys = IntArray(8192)
    private val hashVals = IntArray(8192)

    init {
        buildGlobalPalette()
    }

    private fun buildGlobalPalette() {
        var idx = 0
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    paletteRgb[idx++] = (r * 51).toByte()
                    paletteRgb[idx++] = (g * 51).toByte()
                    paletteRgb[idx++] = (b * 51).toByte()
                }
            }
        }
        for (i in 0 until 40) {
            val v = ((i * 255) / 39).toByte()
            paletteRgb[idx++] = v
            paletteRgb[idx++] = v
            paletteRgb[idx++] = v
        }
    }

    fun start() {
        if (started) return
        started = true
        buffer.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShortLE(width)
        writeShortLE(height)
        buffer.write(0xF7)
        buffer.write(0x00)
        buffer.write(0x00)
        buffer.write(paletteRgb)
        buffer.write(0x21)
        buffer.write(0xFF)
        buffer.write(0x0B)
        buffer.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        buffer.write(0x03)
        buffer.write(0x01)
        writeShortLE(loopCount)
        buffer.write(0x00)
    }

    fun addFrame(bitmap: Bitmap) {
        if (!started) start()
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        scaled.getPixels(pixelScratch, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        addPixels(pixelScratch)
    }

    fun addPixels(pixels: IntArray) {
        if (!started) start()
        val count = minOf(pixels.size, indexedScratch.size)
        for (i in 0 until count) {
            indexedScratch[i] = quantizePixel(pixels[i]).toByte()
        }

        // Graphic Control Extension
        buffer.write(0x21)
        buffer.write(0xF9)
        buffer.write(0x04)
        buffer.write(0x04)
        writeShortLE(delayCentiseconds.coerceAtLeast(2))
        buffer.write(0x00)
        buffer.write(0x00)

        // Image Descriptor
        buffer.write(0x2C)
        writeShortLE(0)
        writeShortLE(0)
        writeShortLE(width)
        writeShortLE(height)
        buffer.write(0x00)

        // LZW Minimum Code Size = 8
        buffer.write(0x08)
        writeLzwEncoded(indexedScratch, count)
        buffer.write(0x00)
    }

    fun finish(output: OutputStream) {
        if (!started) start()
        buffer.write(0x3B)
        buffer.writeTo(output)
        output.flush()
    }

    private fun quantizePixel(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val drg = abs(r - g)
        val dgb = abs(g - b)
        val drb = abs(r - b)
        val maxDiff = if (drg >= dgb) (if (drg >= drb) drg else drb) else (if (dgb >= drb) dgb else drb)
        if (maxDiff <= 10) {
            val lum = (r * 3 + g * 6 + b) / 10
            var gi = (lum * 39) / 255
            if (gi < 0) gi = 0 else if (gi > 39) gi = 39
            return 216 + gi
        }
        var ri = (r * 5 + 127) / 255
        var gi = (g * 5 + 127) / 255
        var bi = (b * 5 + 127) / 255
        if (ri > 5) ri = 5
        if (gi > 5) gi = 5
        if (bi > 5) bi = 5
        return ri * 36 + gi * 6 + bi
    }

    private fun writeLzwEncoded(pixels: ByteArray, length: Int) {
        val minCodeSize = 8
        val clearCode = 1 shl minCodeSize
        val eofCode = clearCode + 1

        var codeSize = minCodeSize + 1
        var nextCode = eofCode + 1
        val maxTableSize = 4096

        Arrays.fill(hashKeys, -1)

        var subPos = 0
        var bitBuf = 0
        var bitCount = 0

        fun flushSubBlock() {
            if (subPos > 0) {
                buffer.write(subPos)
                buffer.write(subBlock, 0, subPos)
                subPos = 0
            }
        }

        fun emitCode(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                subBlock[subPos++] = (bitBuf and 0xFF).toByte()
                bitBuf = bitBuf ushr 8
                bitCount -= 8
                if (subPos == 255) flushSubBlock()
            }
        }

        emitCode(clearCode)
        if (length == 0) {
            emitCode(eofCode)
            if (bitCount > 0) subBlock[subPos++] = (bitBuf and 0xFF).toByte()
            flushSubBlock()
            return
        }

        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until length) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k

            // Open-addressed probe in primitive IntArray (zero boxing)
            var slot = ((key * 2654435761L).toInt() ushr 19) and hashMask
            var found = -1
            while (true) {
                val existingKey = hashKeys[slot]
                if (existingKey == key) {
                    found = hashVals[slot]
                    break
                }
                if (existingKey == -1) break
                slot = (slot + 1) and hashMask
            }

            if (found != -1) {
                prefix = found
            } else {
                emitCode(prefix)
                if (nextCode < maxTableSize) {
                    hashKeys[slot] = key
                    hashVals[slot] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    emitCode(clearCode)
                    Arrays.fill(hashKeys, -1)
                    codeSize = minCodeSize + 1
                    nextCode = eofCode + 1
                }
                prefix = k
            }
        }

        emitCode(prefix)
        emitCode(eofCode)
        if (bitCount > 0) {
            subBlock[subPos++] = (bitBuf and 0xFF).toByte()
        }
        flushSubBlock()
    }

    private fun writeShortLE(value: Int) {
        buffer.write(value and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
    }
}
