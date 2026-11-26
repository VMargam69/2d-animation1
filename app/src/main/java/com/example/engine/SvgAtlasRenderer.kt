package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.example.model.AtlasPartSlot
import com.example.model.SpriteAtlasRigConfig
import java.io.File
import kotlin.math.min

/**
 * Native AndroidSVG renderer & clean SVG sprite-atlas composer.
 *
 * Renders `.svg` character parts and full vector character atlases directly onto high-DPI
 * Android Canvases / Bitmaps at any zoom level without pixelation, while sanitizing inputs
 * against oversized payloads and XML entity declarations.
 */
object SvgAtlasRenderer {

    private const val MAX_SVG_CHARS = 4 * 1024 * 1024 // 4 MB safety cap
    private val parsedSvgCache = LruCache<String, SVG>(24)

    fun isSvgPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return path.trim().endsWith(".svg", ignoreCase = true)
    }

    /** Rejects unsafe XML entity/script constructs and oversized SVG strings before parsing. */
    fun isSafeSvgContent(svgContent: String): Boolean {
        if (svgContent.isBlank() || svgContent.length > MAX_SVG_CHARS) return false
        val lower = svgContent.lowercase()
        if (!lower.contains("<svg")) return false
        if (lower.contains("<!doctype") || lower.contains("<!entity") || lower.contains("<script")) {
            return false
        }
        return true
    }

    fun parseSvgSafe(svgContent: String, cacheKey: String? = null): SVG? {
        if (!isSafeSvgContent(svgContent)) return null
        if (cacheKey != null) {
            parsedSvgCache.get(cacheKey)?.let { return it }
        }
        return runCatching {
            val svg = SVG.getFromString(svgContent)
            if (cacheKey != null) {
                parsedSvgCache.put(cacheKey, svg)
            }
            svg
        }.getOrNull()
    }

    fun readAndParseSvgFile(file: File): SVG? {
        if (!file.exists() || !file.isFile || file.length() <= 0L || file.length() > MAX_SVG_CHARS) {
            return null
        }
        val key = "file:${file.absolutePath}:${file.lastModified()}"
        parsedSvgCache.get(key)?.let { return it }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return parseSvgSafe(text, cacheKey = key)
    }

    fun readAndParseAssetSvg(context: Context, assetPath: String): SVG? {
        val cleanPath = assetPath.removePrefix("asset://").removePrefix("/")
        val key = "asset:$cleanPath"
        parsedSvgCache.get(key)?.let { return it }
        val text = runCatching {
            context.assets.open(cleanPath).bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(8192)
                val sb = StringBuilder()
                var total = 0
                while (true) {
                    val read = reader.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SVG_CHARS) return@use null
                    sb.append(buf, 0, read)
                }
                sb.toString()
            }
        }.getOrNull() ?: return null
        return parseSvgSafe(text, cacheKey = key)
    }

    /**
     * Renders an [SVG] cleanly into a transparent ARGB_8888 [Bitmap] sized to fit [targetWidth] x [targetHeight]
     * while preserving aspect ratio (or exact square for 2048/4096 atlases).
     */
    fun renderSvgToBitmap(svg: SVG, targetWidth: Int, targetHeight: Int = targetWidth): Bitmap? {
        val w = targetWidth.coerceIn(32, 4096)
        val h = targetHeight.coerceIn(32, 4096)
        return runCatching {
            val viewBox = svg.documentViewBox
            val docW = if (viewBox != null && viewBox.width() > 0f) viewBox.width() else svg.documentWidth
            val docH = if (viewBox != null && viewBox.height() > 0f) viewBox.height() else svg.documentHeight
            if (viewBox == null && docW > 0f && docH > 0f) {
                svg.setDocumentViewBox(0f, 0f, docW, docH)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bmp)
            val renderW = if (docW > 0f) docW else w.toFloat()
            val renderH = if (docH > 0f) docH else h.toFloat()
            val scale = min(w.toFloat() / renderW, h.toFloat() / renderH)
            val drawW = renderW * scale
            val drawH = renderH * scale
            val dx = (w - drawW) * 0.5f
            val dy = (h - drawH) * 0.5f
            svg.renderToCanvas(canvas, RectF(dx, dy, dx + drawW, dy + drawH))
            bmp
        }.getOrNull()
    }

    fun renderSvgStringToBitmap(svgContent: String, targetSize: Int = 2048): Bitmap? {
        val svg = parseSvgSafe(svgContent) ?: return null
        return renderSvgToBitmap(svg, targetSize, targetSize)
    }

    fun renderSvgFileToBitmap(file: File, targetSize: Int = 2048): Bitmap? {
        val svg = readAndParseSvgFile(file) ?: return null
        return renderSvgToBitmap(svg, targetSize, targetSize)
    }

    fun renderAssetSvgToBitmap(context: Context, assetPath: String, targetSize: Int = 2048): Bitmap? {
        val svg = readAndParseAssetSvg(context, assetPath) ?: return null
        return renderSvgToBitmap(svg, targetSize, targetSize)
    }

    /**
     * Composes a clean transparent 2048x2048 or 4096x4096 SVG atlas document containing ONLY
     * visible SVG parts / inline SVG paths with zero BG, Chart, or selection guide overlays.
     */
    fun composeCleanAtlasSvg(
        config: SpriteAtlasRigConfig,
        partSvgStringsBySlot: Map<AtlasPartSlot, String>,
        baseSheetSvg: String? = null
    ): String {
        val size = if (config.resolutionPx >= 4096) 4096 else 2048
        val sb = StringBuilder(8192)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $size $size\" width=\"$size\" height=\"$size\">\n")
        sb.append("  <defs>\n")
        for (slot in AtlasPartSlot.entries) {
            val l = slot.u0 * size
            val t = slot.v0 * size
            val w = slot.widthU * size
            val h = slot.heightV * size
            sb.append("    <clipPath id=\"clip_${slot.id}\"><rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\"/></clipPath>\n".format(l, t, w, h))
        }
        sb.append("  </defs>\n")

        if (!baseSheetSvg.isNullOrBlank() && isSafeSvgContent(baseSheetSvg)) {
            val inner = extractInnerSvgMarkup(baseSheetSvg)
            if (inner.isNotBlank()) {
                sb.append("  <g id=\"base_atlas_sheet\">\n")
                sb.append(inner)
                sb.append("\n  </g>\n")
            }
        }

        for (slot in AtlasPartSlot.entries) {
            val placement = config.placementFor(slot)
            if (!placement.visible) continue
            val rawSvg = partSvgStringsBySlot[slot] ?: placement.svgInline ?: continue
            val inner = if (rawSvg.trim().startsWith("<svg", ignoreCase = true)) {
                extractInnerSvgMarkup(rawSvg)
            } else if (rawSvg.trim().startsWith("<")) {
                rawSvg.trim()
            } else {
                // Treat bare SVG path data `d` string cleanly
                "<path d=\"${rawSvg.trim().replace("\"", "'")}\" fill=\"#38BDF8\" stroke=\"#0F172A\" stroke-width=\"6\"/>"
            }
            if (inner.isBlank()) continue

            val l = slot.u0 * size
            val t = slot.v0 * size
            val w = slot.widthU * size
            val h = slot.heightV * size
            val cx = l + w * 0.5f + placement.offsetX * w
            val cy = t + h * 0.5f + placement.offsetY * h
            val sc = placement.scale.coerceIn(0.25f, 2.8f)

            sb.append("  <g id=\"slot_${slot.id}\" clip-path=\"url(#clip_${slot.id})\">\n")
            sb.append("    <g transform=\"translate(%.1f, %.1f) rotate(%.2f) scale(%.3f) translate(%.1f, %.1f)\">\n"
                .format(cx, cy, placement.rotationDeg, sc, -w * 0.5f, -h * 0.5f))
            sb.append("      <svg width=\"%.1f\" height=\"%.1f\" viewBox=\"0 0 256 256\">\n".format(w, h))
            sb.append("        ").append(inner).append("\n")
            sb.append("      </svg>\n")
            sb.append("    </g>\n")
            sb.append("  </g>\n")
        }

        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun extractInnerSvgMarkup(svg: String): String {
        val openEnd = svg.indexOf('>', startIndex = svg.indexOf("<svg", ignoreCase = true).coerceAtLeast(0))
        val closeStart = svg.lastIndexOf("</svg>", ignoreCase = true)
        if (openEnd < 0 || closeStart <= openEnd) return ""
        return svg.substring(openEnd + 1, closeStart).trim()
    }

    fun clearCache() {
        parsedSvgCache.evictAll()
    }
}
