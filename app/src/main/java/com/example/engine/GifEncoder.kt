package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Pure Kotlin standards-compliant GIF89a animated encoder with 6x6x6 + grayscale
 * global palette quantization and LZW compression (zero external native dependencies).
 */
class GifEncoder(
    private val width: Int,
    private val height: Int,
    private val delayCentiseconds: Int = 4,
    private val loopCount: Int = 0
) {
    private val paletteRgb = ByteArray(256 * 3)
    private val buffer = ByteArrayOutputStream()
    private var started = false

    init {
        buildGlobalPalette()
    }

    private fun buildGlobalPalette() {
        var idx = 0
        // 216-color 6x6x6 uniform color cube (indices 0..215)
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    paletteRgb[idx++] = (r * 51).toByte()
                    paletteRgb[idx++] = (g * 51).toByte()
                    paletteRgb[idx++] = (b * 51).toByte()
                }
            }
        }
        // 40-step grayscale ramp (indices 216..255)
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
        // Header: GIF89a
        buffer.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor
        writeShortLE(width)
        writeShortLE(height)
        buffer.write(0xF7) // Global Color Table flag=1, color res=7, sort=0, size=7 (256 entries)
        buffer.write(0x00) // Background color index
        buffer.write(0x00) // Pixel aspect ratio
        // Global Color Table (768 bytes)
        buffer.write(paletteRgb)
        // Netscape 2.0 Application Extension for looping
        buffer.write(0x21) // Extension introducer
        buffer.write(0xFF) // Application extension label
        buffer.write(0x0B) // Block size 11
        buffer.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        buffer.write(0x03) // Sub-block size
        buffer.write(0x01) // Loop sub-block ID
        writeShortLE(loopCount)
        buffer.write(0x00) // Block terminator
    }

    fun addFrame(bitmap: Bitmap) {
        if (!started) start()
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        addPixels(pixels)
    }

    fun addPixels(pixels: IntArray) {
        if (!started) start()
        val indexed = ByteArray(pixels.size)
        for (i in pixels.indices) {
            indexed[i] = quantizePixel(pixels[i]).toByte()
        }

        // Graphic Control Extension
        buffer.write(0x21)
        buffer.write(0xF9)
        buffer.write(0x04) // Block size
        buffer.write(0x04) // Disposal method: Do not dispose
        writeShortLE(delayCentiseconds.coerceAtLeast(2))
        buffer.write(0x00) // Transparent color index (unused)
        buffer.write(0x00) // Terminator

        // Image Descriptor
        buffer.write(0x2C)
        writeShortLE(0)
        writeShortLE(0)
        writeShortLE(width)
        writeShortLE(height)
        buffer.write(0x00) // No local color table

        // LZW Minimum Code Size = 8
        buffer.write(0x08)
        writeLzwEncoded(indexed)
        buffer.write(0x00) // Block terminator
    }

    fun finish(output: OutputStream) {
        if (!started) start()
        buffer.write(0x3B) // GIF Trailer
        buffer.writeTo(output)
        output.flush()
    }

    private fun quantizePixel(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        // Prefer grayscale ramp for near-neutral pixels for crisp dark UI & shadows
        val maxDiff = maxOf(kotlin.math.abs(r - g), kotlin.math.abs(g - b), kotlin.math.abs(r - b))
        if (maxDiff <= 10) {
            val lum = (r * 3 + g * 6 + b) / 10
            return 216 + ((lum * 39) / 255).coerceIn(0, 39)
        }
        val ri = ((r * 5 + 127) / 255).coerceIn(0, 5)
        val gi = ((g * 5 + 127) / 255).coerceIn(0, 5)
        val bi = ((b * 5 + 127) / 255).coerceIn(0, 5)
        return ri * 36 + gi * 6 + bi
    }

    private fun writeLzwEncoded(pixels: ByteArray) {
        val minCodeSize = 8
        val clearCode = 1 shl minCodeSize
        val eofCode = clearCode + 1

        var codeSize = minCodeSize + 1
        var nextCode = eofCode + 1
        val maxTableSize = 4096

        val table = HashMap<Int, Int>(4096)
        val subBlock = ByteArray(255)
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
        if (pixels.isEmpty()) {
            emitCode(eofCode)
            if (bitCount > 0) {
                subBlock[subPos++] = (bitBuf and 0xFF).toByte()
            }
            flushSubBlock()
            return
        }

        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val existing = table[key]
            if (existing != null) {
                prefix = existing
            } else {
                emitCode(prefix)
                if (nextCode < maxTableSize) {
                    table[key] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    emitCode(clearCode)
                    table.clear()
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
