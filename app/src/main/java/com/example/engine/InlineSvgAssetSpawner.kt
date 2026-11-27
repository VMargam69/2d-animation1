package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.LruCache
import com.example.model.*
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Universal Inline SVG Asset Spawner & Hierarchical Sub-Part Renderer.
 *
 * Allows `.fcscript.json` director scripts to invent brand-new props, trees, animals,
 * backgrounds, or full 2048x2048 4-band SVG characters that are NOT in FrameCraft's
 * built-in library:
 * - Strictly sanitizes untrusted SVG XML before parsing (blocks `<script>`, `<foreignObject>`,
 *   `<filter>`, `<!DOCTYPE`, `<!ENTITY`, `javascript:`, remote `http(s)://` hrefs, and huge payloads).
 * - Extracts `viewBox` and `<g id="..." data-pivot="x,y">` sub-groups cleanly so individual
 *   parts (e.g., `tree_canopy`, `bird_wing_l`, `animal_tail`) can animate around their exact pivots!
 * - Caches high-DPI `ARGB_8888` bitmaps in a memory-bounded [LruCache] (capped safely to avoid
 *   OOM on low-RAM Android devices).
 * - Persists sanitized `.svg` files into offline app-local storage so spawned assets remain
 *   100% editable offline even after reopening the project.
 */
object InlineSvgAssetSpawner {

    private const val MAX_SVG_XML_CHARS = 350_000
    private const val MAX_SUB_PARTS = 24
    private const val DEFAULT_PART_RENDER_DIM = 512

    private val FORBIDDEN_SVG_PATTERNS = listOf(
        Regex("<\\s*script\\b", RegexOption.IGNORE_CASE),
        Regex("<\\s*foreignObject\\b", RegexOption.IGNORE_CASE),
        Regex("<\\s*filter\\b", RegexOption.IGNORE_CASE),
        Regex("<\\s*fe[A-Za-z]+\\b", RegexOption.IGNORE_CASE),
        Regex("<!DOCTYPE", RegexOption.IGNORE_CASE),
        Regex("<!ENTITY", RegexOption.IGNORE_CASE),
        Regex("javascript\\s*:", RegexOption.IGNORE_CASE),
        Regex("on[a-z]+\\s*=", RegexOption.IGNORE_CASE),
        Regex("(?:xlink:)?href\\s*=\\s*[\"']\\s*https?://", RegexOption.IGNORE_CASE)
    )

    data class SvgViewBox(
        val minX: Float,
        val minY: Float,
        val width: Float,
        val height: Float
    )

    data class ParsedSvgSubPart(
        val nodeId: String,
        val pivotX: Float,
        val pivotY: Float,
        val standaloneSvgXml: String
    )

    data class ParsedInlineSvgDocument(
        val id: String,
        val viewBox: SvgViewBox,
        val sanitizedFullSvg: String,
        val subParts: List<ParsedSvgSubPart>
    )

    data class SanitizeResult(
        val isValid: Boolean,
        val sanitizedSvg: String? = null,
        val errorMessage: String? = null
    )

    // Memory-bounded bitmap cache: max 16 MB or 1/12th of JVM max heap (whichever is smaller)
    private val maxCacheBytes: Int = run {
        val heapShare = (Runtime.getRuntime().maxMemory() / 12L).toInt().coerceAtLeast(4 * 1024 * 1024)
        min(16 * 1024 * 1024, heapShare)
    }

    private val partBitmapCache: LruCache<String, Bitmap> by lazy {
        object : LruCache<String, Bitmap>(maxCacheBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return runCatching { value.allocationByteCount }.getOrElse { value.byteCount }.coerceAtLeast(1024)
            }
        }
    }

    private val parsedDocCacheLock = Any()
    private val parsedDocCache = object : LinkedHashMap<String, ParsedInlineSvgDocument>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedInlineSvgDocument>?): Boolean =
            size > 48
    }

    fun clearCaches() {
        runCatching { partBitmapCache.evictAll() }
        synchronized(parsedDocCacheLock) { parsedDocCache.clear() }
    }

    /**
     * Validates and normalizes untrusted inline SVG XML for strict AndroidSVG safety.
     */
    fun sanitizeSvgXml(rawXml: String, fallbackViewBox: String = "0 0 256 256"): SanitizeResult {
        val trimmed = rawXml.trim()
        if (trimmed.isEmpty()) {
            return SanitizeResult(false, errorMessage = "SVG XML is empty")
        }
        if (trimmed.length > MAX_SVG_XML_CHARS) {
            return SanitizeResult(false, errorMessage = "SVG XML exceeds ${MAX_SVG_XML_CHARS} character safety limit")
        }
        for (pattern in FORBIDDEN_SVG_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return SanitizeResult(false, errorMessage = "SVG rejected: contains disallowed construct (${pattern.pattern})")
            }
        }
        if (!trimmed.contains("<svg", ignoreCase = true) || !trimmed.contains("</svg>", ignoreCase = true)) {
            return SanitizeResult(false, errorMessage = "SVG XML must contain root <svg>...</svg> tags")
        }

        // Ensure xmlns and viewBox attributes are present on the root <svg> tag for AndroidSVG
        val svgOpenRegex = Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val firstMatch = svgOpenRegex.find(trimmed)
        val normalized = if (firstMatch != null) {
            var attrs = firstMatch.groupValues[1]
            if (!attrs.contains("xmlns=", ignoreCase = true)) {
                attrs = " xmlns=\"http://www.w3.org/2000/svg\"$attrs"
            }
            if (!attrs.contains("viewBox=", ignoreCase = true)) {
                val safeVb = parseViewBox(fallbackViewBox)
                attrs = "$attrs viewBox=\"${safeVb.minX} ${safeVb.minY} ${safeVb.width} ${safeVb.height}\""
            }
            trimmed.replaceRange(firstMatch.range, "<svg$attrs>")
        } else {
            trimmed
        }
        return SanitizeResult(true, sanitizedSvg = normalized)
    }

    /**
     * Parses viewBox bounds from a `viewBox="minX minY w h"` string or directly from SVG markup.
     */
    fun parseViewBox(viewBoxStr: String?, svgXml: String? = null): SvgViewBox {
        val candidate = viewBoxStr?.takeIf { it.isNotBlank() } ?: run {
            if (svgXml.isNullOrBlank()) null
            else Regex("""viewBox\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(svgXml)?.groupValues?.getOrNull(1)
        }
        if (!candidate.isNullOrBlank()) {
            val parts = candidate.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (parts.size == 4 && parts[2] > 0f && parts[3] > 0f) {
                return SvgViewBox(parts[0], parts[1], parts[2], parts[3])
            }
        }
        return SvgViewBox(0f, 0f, 256f, 256f)
    }

    /**
     * Extracts top-level `<g id="..." data-pivot="x,y">...</g>` groups cleanly using balanced
     * tag depth scanning (pure Kotlin — works identically in JVM unit tests and Android runtime).
     */
    fun parseInlineSvgDocument(def: InlineSvgAssetDef): ParsedInlineSvgDocument? {
        val cacheKey = "${def.id}:${def.svgXml.hashCode()}"
        synchronized(parsedDocCacheLock) {
            parsedDocCache[cacheKey]?.let { return it }
        }

        val sanitized = sanitizeSvgXml(def.svgXml, def.viewBox)
        val xml = sanitized.sanitizedSvg ?: return null
        val viewBox = parseViewBox(def.viewBox, xml)
        val inner = extractInnerMarkup(xml)

        val subParts = extractTopLevelGroups(inner, viewBox)
        val parsed = ParsedInlineSvgDocument(
            id = def.id,
            viewBox = viewBox,
            sanitizedFullSvg = xml,
            subParts = subParts
        )
        synchronized(parsedDocCacheLock) {
            parsedDocCache[cacheKey] = parsed
        }
        return parsed
    }

    /**
     * Persists a sanitized [InlineSvgAssetDef] to offline app-local storage (`inline_svg_assets/<id>.svg`)
     * and enriches [InlineSvgAssetDef.subPartPivots] + [InlineSvgAssetDef.localSvgPath].
     */
    fun spawnAndPersist(
        context: Context?,
        rawDef: InlineSvgAssetDef
    ): InlineSvgAssetDef? {
        val parsed = parseInlineSvgDocument(rawDef) ?: return null
        var savedPath: String? = rawDef.localSvgPath
        if (context != null) {
            val dir = File(context.filesDir, "inline_svg_assets").apply { mkdirs() }
            val safeFileId = rawDef.id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "inline_svg" }
            val outFile = File(dir, "$safeFileId.svg")
            runCatching {
                outFile.writeText(parsed.sanitizedFullSvg, Charsets.UTF_8)
                savedPath = outFile.absolutePath
            }
        }
        val pivots = parsed.subParts.map { part ->
            SvgSubPartPivot(nodeId = part.nodeId, pivotX = part.pivotX, pivotY = part.pivotY)
        }
        return rawDef.copy(
            viewBox = "${parsed.viewBox.minX} ${parsed.viewBox.minY} ${parsed.viewBox.width} ${parsed.viewBox.height}",
            svgXml = parsed.sanitizedFullSvg,
            localSvgPath = savedPath,
            subPartPivots = pivots
        )
    }

    /**
     * Builds a native editable [CharacterRigDefinition] when an inline SVG asset has type `CHARACTER`.
     * Uses `ATLAS_RIG_PRO` if the viewBox is a 2048/4096 4-band atlas, or `SINGLE_PNG_MESH` otherwise
     * (and `LocalAssetStorage` renders `.svg` via `SvgAtlasRenderer` automatically!).
     */
    fun buildCharacterRigFromInlineSvg(
        def: InlineSvgAssetDef,
        displayName: String? = null
    ): CharacterRigDefinition {
        val vb = parseViewBox(def.viewBox, def.svgXml)
        val isSquareAtlas = vb.width >= 1024f && kotlin.math.abs(vb.width - vb.height) < 16f
        val rigName = (displayName?.takeIf { it.isNotBlank() }
            ?: def.id.removePrefix("svg_").replace('_', ' ')
                .split(' ')
                .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } })
            .take(28)

        return CharacterRigDefinition(
            id = def.id,
            name = rigName,
            category = CharacterCategory.LATEST,
            bodyType = CharacterBodyType.HUMANOID,
            isCustom = true,
            isLatest = true,
            customImagePath = def.localSvgPath,
            skeletonRig = SkeletonRigConfig(
                enabled = true,
                rigMode = if (isSquareAtlas) CustomRigMode.ATLAS_RIG_PRO else CustomRigMode.SINGLE_PNG_MESH
            ),
            spriteAtlasRig = if (isSquareAtlas) {
                SpriteAtlasRigConfig(
                    resolutionPx = vb.width.toInt().coerceIn(1024, 4096),
                    textureName = rigName,
                    renderMode = "SVG",
                    importedFullSheetPath = def.localSvgPath,
                    inlineAtlasSvg = def.svgXml
                )
            } else null
        )
    }

    /**
     * Renders an [InlineSvgAssetDef] onto [canvas] centered at (0, 0) within [targetMaxDim].
     * When [nodeTransforms] contains animation tracks targeting `<g id="...">` sub-parts, each
     * sub-part is drawn with its own cached bitmap transformed around its exact `data-pivot="x,y"`!
     */
    fun drawInlineSvgAsset(
        canvas: Canvas,
        def: InlineSvgAssetDef,
        targetMaxDim: Float,
        alpha: Float,
        tintOverlay: Int?,
        nodeTransforms: Map<String, EvaluatedNodeTransform> = emptyMap(),
        paint: Paint
    ): Boolean {
        val parsed = parseInlineSvgDocument(def) ?: return false
        val vb = parsed.viewBox
        val aspect = vb.width / vb.height.coerceAtLeast(1f)
        val drawW = if (aspect >= 1f) targetMaxDim else targetMaxDim * aspect
        val drawH = if (aspect >= 1f) targetMaxDim / aspect else targetMaxDim
        val scaleXToCanvas = drawW / vb.width.coerceAtLeast(1f)
        val scaleYToCanvas = drawH / vb.height.coerceAtLeast(1f)

        val renderDim = max(256, min(DEFAULT_PART_RENDER_DIM, targetMaxDim.toInt().coerceAtLeast(256)))

        // Fast path: no sub-part animation tracks -> draw single full SVG bitmap
        val hasAnimatedSubParts = parsed.subParts.isNotEmpty() &&
            nodeTransforms.keys.any { k -> parsed.subParts.any { it.nodeId.equals(k, ignoreCase = true) } }

        val prevColorFilter = paint.colorFilter
        paint.colorFilter = if (tintOverlay != null) {
            PorterDuffColorFilter(tintOverlay, PorterDuff.Mode.SRC_ATOP)
        } else null

        try {
            if (!hasAnimatedSubParts) {
                val bmp = getOrRenderBitmap(
                    cacheKey = "full:${def.id}:${parsed.sanitizedFullSvg.hashCode()}:$renderDim",
                    svgXml = parsed.sanitizedFullSvg,
                    renderW = if (aspect >= 1f) renderDim else (renderDim * aspect).toInt().coerceAtLeast(64),
                    renderH = if (aspect >= 1f) (renderDim / aspect).toInt().coerceAtLeast(64) else renderDim
                ) ?: return false

                paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
                val dst = RectF(-drawW / 2f, -drawH / 2f, drawW / 2f, drawH / 2f)
                canvas.drawBitmap(bmp, null, dst, paint)
                return true
            }

            // Hierarchical sub-part rendering with exact per-part pivot transforms
            val renderW = if (aspect >= 1f) renderDim else (renderDim * aspect).toInt().coerceAtLeast(64)
            val renderH = if (aspect >= 1f) (renderDim / aspect).toInt().coerceAtLeast(64) else renderDim
            val dst = RectF(-drawW / 2f, -drawH / 2f, drawW / 2f, drawH / 2f)

            for (part in parsed.subParts) {
                val bmp = getOrRenderBitmap(
                    cacheKey = "part:${def.id}:${part.nodeId}:${part.standaloneSvgXml.hashCode()}:$renderDim",
                    svgXml = part.standaloneSvgXml,
                    renderW = renderW,
                    renderH = renderH
                ) ?: continue

                val tr = nodeTransforms[part.nodeId]
                    ?: nodeTransforms.entries.firstOrNull { it.key.equals(part.nodeId, ignoreCase = true) }?.value

                val partAlpha = (alpha * (tr?.opacity ?: 1f)).coerceIn(0f, 1f)
                if (partAlpha <= 0.01f) continue

                canvas.save()
                if (tr != null) {
                    val pivotCanvasX = -drawW / 2f + (part.pivotX - vb.minX) * scaleXToCanvas
                    val pivotCanvasY = -drawH / 2f + (part.pivotY - vb.minY) * scaleYToCanvas
                    canvas.translate(tr.dx * drawW, tr.dy * drawH)
                    canvas.translate(pivotCanvasX, pivotCanvasY)
                    canvas.rotate(tr.rotation)
                    canvas.scale(tr.scaleX, tr.scaleY)
                    canvas.translate(-pivotCanvasX, -pivotCanvasY)
                }
                paint.alpha = (partAlpha * 255f).toInt()
                canvas.drawBitmap(bmp, null, dst, paint)
                canvas.restore()
            }
            return true
        } finally {
            paint.colorFilter = prevColorFilter
        }
    }

    private fun getOrRenderBitmap(
        cacheKey: String,
        svgXml: String,
        renderW: Int,
        renderH: Int
    ): Bitmap? {
        partBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
        val svg = SvgAtlasRenderer.parseSvgSafe(svgXml) ?: return null
        val bmp = runCatching {
            val out = Bitmap.createBitmap(renderW.coerceIn(32, 1024), renderH.coerceIn(32, 1024), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            svg.renderToCanvas(c)
            out
        }.getOrNull() ?: return null
        partBitmapCache.put(cacheKey, bmp)
        return bmp
    }

    private fun extractInnerMarkup(svgXml: String): String {
        val openEnd = svgXml.indexOf('>', svgXml.indexOf("<svg", ignoreCase = true).coerceAtLeast(0))
        val closeStart = svgXml.lastIndexOf("</svg>", ignoreCase = true)
        if (openEnd < 0 || closeStart <= openEnd) return ""
        return svgXml.substring(openEnd + 1, closeStart)
    }

    /**
     * Scans balanced `<g ...>...</g>` blocks at top level inside the root `<svg>`.
     * Extracts optional `id="..."` and `data-pivot="x,y"` attributes (defaulting pivot to viewBox center).
     */
    internal fun extractTopLevelGroups(innerXml: String, viewBox: SvgViewBox): List<ParsedSvgSubPart> {
        val results = ArrayList<ParsedSvgSubPart>()
        val tagRegex = Regex("<(/?)\\s*g\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val matches = tagRegex.findAll(innerXml).toList()

        var depth = 0
        var openMatch: MatchResult? = null

        for (m in matches) {
            val isClose = m.groupValues[1] == "/"
            val attrs = m.groupValues[2]
            val isSelfClosing = !isClose && attrs.trimEnd().endsWith("/")

            if (!isClose) {
                if (depth == 0) {
                    openMatch = m
                }
                if (!isSelfClosing) {
                    depth++
                } else if (depth == 0) {
                    // Self-closing top-level <g .../> — nothing to draw
                    openMatch = null
                }
            } else {
                if (depth > 0) depth--
                if (depth == 0 && openMatch != null) {
                    val startIdx = openMatch.range.first
                    val endIdx = m.range.last + 1
                    val groupAttrs = openMatch.groupValues[2]
                    val nodeId = extractAttr(groupAttrs, "id")
                    if (!nodeId.isNullOrBlank()) {
                        val (px, py) = parsePivotAttr(extractAttr(groupAttrs, "data-pivot"), viewBox)
                        val groupMarkup = innerXml.substring(startIdx, endIdx)
                        val standalone = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox.minX} ${viewBox.minY} ${viewBox.width} ${viewBox.height}">$groupMarkup</svg>"""
                        results.add(
                            ParsedSvgSubPart(
                                nodeId = nodeId,
                                pivotX = px,
                                pivotY = py,
                                standaloneSvgXml = standalone
                            )
                        )
                        if (results.size >= MAX_SUB_PARTS) break
                    }
                    openMatch = null
                }
            }
        }
        return results
    }

    private fun extractAttr(attrs: String, name: String): String? {
        val regex = Regex("""\b$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parsePivotAttr(raw: String?, viewBox: SvgViewBox): Pair<Float, Float> {
        if (!raw.isNullOrBlank()) {
            val nums = raw.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (nums.size >= 2) {
                return nums[0] to nums[1]
            }
        }
        return (viewBox.minX + viewBox.width * 0.5f) to (viewBox.minY + viewBox.height * 0.5f)
    }
}
