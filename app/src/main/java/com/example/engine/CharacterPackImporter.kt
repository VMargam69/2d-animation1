package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class CharacterPackImportResult(
    val character: CharacterRigDefinition?,
    val message: String
)

/**
 * Offline HD Character Pack loader (from `assets/characters/<id>/`) and 1-tap safe importer
 * for `.zip`, `.fcchar.json` / `.json`, and `.svg` character packs via zero-permission SAF URIs.
 *
 * Security safeguards:
 * - Strictly blocks Zip Slip path traversal (`canonicalPath` must stay inside target folder).
 * - Enforces entry count (<= 64) and total uncompressed byte limit (<= 25 MB) against zip bombs.
 * - Allow-lists only safe asset extensions (`.png`, `.svg`, `.json`).
 * - Sanitizes all SVG inputs via [SvgAtlasRenderer.isSafeSvgContent].
 */
object CharacterPackImporter {

    const val ASSET_URI_PREFIX = "asset://"
    private const val MAX_ZIP_ENTRIES = 64
    private const val MAX_ZIP_TOTAL_BYTES = 25L * 1024L * 1024L // 25 MB
    private const val MAX_SINGLE_FILE_BYTES = 12L * 1024L * 1024L // 12 MB
    private val ALLOWED_EXTENSIONS = setOf("png", "svg", "json")

    /**
     * Loads all bundled offline HD character packs packaged inside `app/src/main/assets/characters/<id>/`.
     * Each subfolder may contain `atlas.svg` or `atlas.png` alongside `character.fcchar.json` or `character.json`.
     */
    fun loadBundledAssetPacks(context: Context): List<CharacterRigDefinition> {
        val assetManager = context.assets
        val packDirs = runCatching { assetManager.list("characters")?.toList().orEmpty() }.getOrDefault(emptyList())
        if (packDirs.isEmpty()) return emptyList()

        val loaded = mutableListOf<CharacterRigDefinition>()
        for (dirName in packDirs.sorted()) {
            val folder = "characters/$dirName"
            val files = runCatching { assetManager.list(folder)?.toList().orEmpty() }.getOrDefault(emptyList())
            if (files.isEmpty()) continue

            val jsonFile = files.firstOrNull { it.endsWith(".fcchar.json", ignoreCase = true) }
                ?: files.firstOrNull { it.equals("character.json", ignoreCase = true) }
                ?: files.firstOrNull { it.endsWith(".json", ignoreCase = true) }
            val atlasFile = files.firstOrNull { it.equals("atlas.svg", ignoreCase = true) }
                ?: files.firstOrNull { it.equals("atlas.png", ignoreCase = true) }
                ?: files.firstOrNull { it.endsWith(".svg", ignoreCase = true) || it.endsWith(".png", ignoreCase = true) }

            if (atlasFile == null) continue
            val atlasAssetUri = "$ASSET_URI_PREFIX$folder/$atlasFile"
            val rawJson = jsonFile?.let { jf ->
                runCatching {
                    assetManager.open("$folder/$jf").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull()
            }
            val parsedCode = rawJson?.let { CharacterCodeCodec.fromJson(it) }
            val rigId = parsedCode?.id?.takeIf { it.isNotBlank() } ?: "bundled_$dirName"
            val rigName = parsedCode?.name?.takeIf { it.isNotBlank() }
                ?: dirName.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val category = parsedCode?.category?.let { raw ->
                runCatching { CharacterCategory.valueOf(raw.uppercase()) }.getOrNull()
            } ?: CharacterCategory.DESI
            val pose = AtlasPoseTemplate.fromId(parsedCode?.poseTemplate)
            val isSvg = atlasFile.endsWith(".svg", ignoreCase = true) ||
                parsedCode?.renderMode.equals("SVG", ignoreCase = true)

            val atlasRigConfig = if (parsedCode != null) {
                CharacterCodeCodec.toRigConfig(
                    code = parsedCode,
                    resolvedAtlasPath = atlasAssetUri
                ).copy(renderMode = if (isSvg) "SVG" else "PNG")
            } else {
                SpriteAtlasRigConfig(
                    poseTemplate = pose,
                    resolutionPx = 2048,
                    textureName = rigName,
                    renderMode = if (isSvg) "SVG" else "PNG",
                    importedFullSheetPath = atlasAssetUri
                )
            }

            loaded += CharacterRigDefinition(
                id = rigId,
                name = rigName,
                category = category,
                bodyType = CharacterBodyType.HUMANOID,
                supportedOrientations = listOf(
                    pose.defaultOrientation,
                    CharacterOrientation.FRONT,
                    CharacterOrientation.THREE_QUARTER_FRONT,
                    CharacterOrientation.SIDE
                ).distinct(),
                isCustom = false,
                isLatest = true,
                customImagePath = atlasAssetUri,
                skeletonRig = SkeletonRigConfig(enabled = true, rigMode = CustomRigMode.ATLAS_RIG_PRO),
                spriteAtlasRig = atlasRigConfig
            )
        }
        return loaded
    }

    /**
     * Safely validates a ZIP entry path against Zip Slip path traversal.
     * Exposed internally for unit testing.
     */
    internal fun resolveSafeZipEntryFile(targetDir: File, entryName: String): File? {
        val cleanName = entryName.trim()
        if (cleanName.isEmpty() || cleanName.contains('\u0000')) return null
        val destCanonical = targetDir.canonicalPath
        val outFile = File(targetDir, cleanName)
        val outCanonical = outFile.canonicalPath
        if (!outCanonical.startsWith(destCanonical + File.separator)) {
            return null
        }
        return outFile
    }

    /**
     * Extracts a ZIP stream safely into [targetDir] enforcing Zip Slip, extension allow-list,
     * entry-count cap, and total uncompressed size limit.
     * Exposed internally for unit testing.
     */
    internal fun extractZipSafely(inputStream: InputStream, targetDir: File): Result<List<File>> {
        if (!targetDir.exists()) targetDir.mkdirs()
        val extracted = mutableListOf<File>()
        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(8192)

        ZipInputStream(inputStream).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    return Result.failure(IllegalArgumentException("Pack exceeds max $MAX_ZIP_ENTRIES files."))
                }
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val entryName = entry.name
                // Skip macOS metadata folders silently
                if (entryName.startsWith("__MACOSX/") || entryName.substringAfterLast('/').startsWith("._")) {
                    zis.closeEntry()
                    continue
                }
                val outFile = resolveSafeZipEntryFile(targetDir, entryName)
                    ?: return Result.failure(SecurityException("Blocked unsafe path in archive: $entryName"))

                val ext = outFile.extension.lowercase()
                if (ext !in ALLOWED_EXTENSIONS) {
                    zis.closeEntry()
                    continue
                }
                outFile.parentFile?.mkdirs()

                var entryBytes = 0L
                FileOutputStream(outFile).use { fos ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read <= 0) break
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_SINGLE_FILE_BYTES || totalBytes > MAX_ZIP_TOTAL_BYTES) {
                            fos.flush()
                            outFile.delete()
                            return Result.failure(IllegalArgumentException("Archive exceeds safe 25 MB limit."))
                        }
                        fos.write(buffer, 0, read)
                    }
                }
                extracted += outFile
                zis.closeEntry()
            }
        }
        return Result.success(extracted)
    }

    /**
     * 1-Tap Character Pack Importer supporting `.zip`, `.fcchar.json` / `.json`, and `.svg` files
     * selected via zero-permission SAF `OpenDocument`.
     */
    fun importCharacterPackFromUri(context: Context, uri: Uri): CharacterPackImportResult {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: "ImportedHero"
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val lowerName = displayName.lowercase()

        val packsRoot = File(context.filesDir, "custom_characters").apply { mkdirs() }
        val packId = "pack_${UUID.randomUUID().toString().take(10)}"
        val packDir = File(packsRoot, packId).apply { mkdirs() }

        return try {
            when {
                lowerName.endsWith(".zip") || mime.contains("zip") -> {
                    val extracted = resolver.openInputStream(uri)?.use { stream ->
                        extractZipSafely(stream, packDir)
                    } ?: return CharacterPackImportResult(null, "Could not open selected .zip pack.")

                    val files = extracted.getOrElse { err ->
                        packDir.deleteRecursively()
                        return CharacterPackImportResult(null, err.message ?: "Invalid .zip character pack.")
                    }
                    buildCharacterFromExtractedFiles(packDir, packId, displayName.substringBeforeLast('.'), files)
                }

                lowerName.endsWith(".json") || mime.contains("json") || mime.startsWith("text/") -> {
                    val jsonText = resolver.openInputStream(uri)?.use { readBoundedText(it) }
                        ?: return CharacterPackImportResult(null, "Could not read character code file.")
                    importFromCharacterCodeString(packDir, packId, jsonText)
                }

                lowerName.endsWith(".svg") || mime.contains("svg") -> {
                    val svgText = resolver.openInputStream(uri)?.use { readBoundedText(it) }
                        ?: return CharacterPackImportResult(null, "Could not read SVG atlas.")
                    importFromRawSvgAtlas(packDir, packId, displayName.substringBeforeLast('.'), svgText)
                }

                else -> {
                    packDir.deleteRecursively()
                    CharacterPackImportResult(null, "Select a .zip pack, .fcchar.json code, or .svg atlas.")
                }
            }
        } catch (t: Throwable) {
            packDir.deleteRecursively()
            CharacterPackImportResult(null, t.localizedMessage ?: "Failed to import character pack.")
        }
    }

    fun importFromCharacterCodeString(
        packDir: File,
        packId: String,
        rawCodeJson: String
    ): CharacterPackImportResult {
        val code = CharacterCodeCodec.fromJson(rawCodeJson)
            ?: return CharacterPackImportResult(null, "Invalid FrameCraft .fcchar.json schema.")
        if (!packDir.exists()) packDir.mkdirs()

        // If inline atlas SVG or slot SVG paths are included, save a clean atlas.svg offline
        val slotSvgs = LinkedHashMap<AtlasPartSlot, String>()
        for ((slotId, slotData) in code.slots) {
            val slot = AtlasPartSlot.fromId(slotId) ?: continue
            val svgPart = slotData.svgPathData
            if (!svgPart.isNullOrBlank()) {
                slotSvgs[slot] = svgPart
            }
        }
        val baseAtlasSvg = code.atlasSvg?.takeIf { SvgAtlasRenderer.isSafeSvgContent(it) }
        val resolvedAtlasFile: File? = when {
            baseAtlasSvg != null && slotSvgs.isEmpty() -> {
                File(packDir, "atlas.svg").apply { writeText(baseAtlasSvg, Charsets.UTF_8) }
            }
            baseAtlasSvg != null || slotSvgs.isNotEmpty() -> {
                val draftConfig = CharacterCodeCodec.toRigConfig(code)
                val composed = SvgAtlasRenderer.composeCleanAtlasSvg(draftConfig, slotSvgs, baseAtlasSvg)
                File(packDir, "atlas.svg").apply { writeText(composed, Charsets.UTF_8) }
            }
            else -> null
        }

        val rigConfig = CharacterCodeCodec.toRigConfig(
            code = code,
            resolvedAtlasPath = resolvedAtlasFile?.absolutePath
        ).copy(
            renderMode = if (resolvedAtlasFile != null || code.renderMode.equals("SVG", true)) "SVG" else "PNG"
        )
        val jsonOut = File(packDir, "character.fcchar.json")
        jsonOut.writeText(CharacterCodeCodec.toJson(code), Charsets.UTF_8)

        val category = runCatching { CharacterCategory.valueOf(code.category.uppercase()) }
            .getOrDefault(CharacterCategory.LATEST)
        val charDef = CharacterRigDefinition(
            id = packId,
            name = rigConfig.textureName,
            category = category,
            bodyType = CharacterBodyType.HUMANOID,
            supportedOrientations = listOf(
                rigConfig.poseTemplate.defaultOrientation,
                CharacterOrientation.FRONT,
                CharacterOrientation.THREE_QUARTER_FRONT,
                CharacterOrientation.SIDE
            ).distinct(),
            isCustom = true,
            isLatest = true,
            customImagePath = resolvedAtlasFile?.absolutePath,
            skeletonRig = SkeletonRigConfig(enabled = true, rigMode = CustomRigMode.ATLAS_RIG_PRO),
            spriteAtlasRig = rigConfig.copy(metadataJsonPath = jsonOut.absolutePath)
        )
        return CharacterPackImportResult(charDef, "Imported \"${charDef.name}\" (${rigConfig.renderMode} rig)")
    }

    private fun importFromRawSvgAtlas(
        packDir: File,
        packId: String,
        fallbackName: String,
        svgText: String
    ): CharacterPackImportResult {
        if (!SvgAtlasRenderer.isSafeSvgContent(svgText)) {
            packDir.deleteRecursively()
            return CharacterPackImportResult(null, "Invalid or unsafe SVG file.")
        }
        val svgFile = File(packDir, "atlas.svg").apply { writeText(svgText, Charsets.UTF_8) }
        val cleanName = fallbackName.ifBlank { "SVG Hero" }
        val rigConfig = SpriteAtlasRigConfig(
            poseTemplate = AtlasPoseTemplate.THREE_QUARTER_FRONT,
            resolutionPx = 2048,
            textureName = cleanName,
            renderMode = "SVG",
            importedFullSheetPath = svgFile.absolutePath
        )
        val charDef = CharacterRigDefinition(
            id = packId,
            name = cleanName,
            category = CharacterCategory.LATEST,
            bodyType = CharacterBodyType.HUMANOID,
            supportedOrientations = listOf(
                CharacterOrientation.THREE_QUARTER_FRONT,
                CharacterOrientation.FRONT,
                CharacterOrientation.SIDE
            ),
            isCustom = true,
            isLatest = true,
            customImagePath = svgFile.absolutePath,
            skeletonRig = SkeletonRigConfig(enabled = true, rigMode = CustomRigMode.ATLAS_RIG_PRO),
            spriteAtlasRig = rigConfig
        )
        return CharacterPackImportResult(charDef, "Imported HD SVG Atlas \"$cleanName\"")
    }

    private fun buildCharacterFromExtractedFiles(
        packDir: File,
        packId: String,
        fallbackName: String,
        files: List<File>
    ): CharacterPackImportResult {
        if (files.isEmpty()) {
            packDir.deleteRecursively()
            return CharacterPackImportResult(null, "No valid PNG, SVG, or JSON files found inside .zip pack.")
        }
        val jsonFile = files.firstOrNull { it.name.endsWith(".fcchar.json", ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals("character.json", ignoreCase = true) }
            ?: files.firstOrNull { it.extension.equals("json", ignoreCase = true) }

        val parsedCode = jsonFile?.let { jf ->
            runCatching { CharacterCodeCodec.fromJson(jf.readText(Charsets.UTF_8)) }.getOrNull()
        }

        // Match full atlas (atlas.svg / atlas.png / referenced atlasFile)
        val atlasFile = parsedCode?.atlasFile?.let { target ->
            files.firstOrNull { it.name.equals(target.substringAfterLast('/'), ignoreCase = true) }
        }
            ?: files.firstOrNull { it.name.equals("atlas.svg", ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals("atlas.png", ignoreCase = true) }
            ?: files.firstOrNull { it.name.contains("atlas", ignoreCase = true) && (it.extension.equals("svg", true) || it.extension.equals("png", true)) }
            ?: files.singleOrNull { it.extension.equals("svg", true) || it.extension.equals("png", true) }

        // Map individual slot files (e.g., head.png, chest_body.svg, etc.)
        val resolvedSlots = LinkedHashMap<String, String>()
        for (f in files) {
            if (f == atlasFile) continue
            if (!f.extension.equals("png", true) && !f.extension.equals("svg", true)) continue
            if (f.extension.equals("svg", true)) {
                val content = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                if (content == null || !SvgAtlasRenderer.isSafeSvgContent(content)) continue
            }
            val base = f.nameWithoutExtension.lowercase()
            val matchedSlot = AtlasPartSlot.entries.firstOrNull { slot ->
                slot.id == base || slot.shortCode.lowercase() == base
            }
            if (matchedSlot != null) {
                resolvedSlots[matchedSlot.id] = f.absolutePath
            }
        }
        // Also resolve explicit assetPath entries declared inside character.fcchar.json
        parsedCode?.slots?.forEach { (slotId, slotData) ->
            val validSlot = AtlasPartSlot.fromId(slotId) ?: return@forEach
            val refName = slotData.assetPath?.substringAfterLast('/') ?: return@forEach
            val matchedFile = files.firstOrNull { it.name.equals(refName, ignoreCase = true) }
            if (matchedFile != null) {
                resolvedSlots[validSlot.id] = matchedFile.absolutePath
            }
        }

        // Validate SVG atlas safety if present
        if (atlasFile != null && atlasFile.extension.equals("svg", true)) {
            val content = runCatching { atlasFile.readText(Charsets.UTF_8) }.getOrNull()
            if (content == null || !SvgAtlasRenderer.isSafeSvgContent(content)) {
                packDir.deleteRecursively()
                return CharacterPackImportResult(null, "SVG atlas inside .zip failed safety validation.")
            }
        }

        if (atlasFile == null && resolvedSlots.isEmpty()) {
            packDir.deleteRecursively()
            return CharacterPackImportResult(null, "No atlas or matching 30-slot body parts found in .zip.")
        }

        val isSvgPack = atlasFile?.extension.equals("svg", true) ||
            resolvedSlots.values.any { it.endsWith(".svg", true) } ||
            parsedCode?.renderMode.equals("SVG", true)

        val rigConfig = if (parsedCode != null) {
            CharacterCodeCodec.toRigConfig(
                code = parsedCode,
                resolvedAtlasPath = atlasFile?.absolutePath,
                resolvedSlotPaths = resolvedSlots
            ).copy(renderMode = if (isSvgPack) "SVG" else "PNG")
        } else {
            val placements = resolvedSlots.mapValues { (slotId, path) ->
                AtlasPartPlacement(slotId = slotId, partImagePath = path, visible = true)
            }
            SpriteAtlasRigConfig(
                poseTemplate = AtlasPoseTemplate.THREE_QUARTER_FRONT,
                resolutionPx = 2048,
                textureName = fallbackName.ifBlank { "ImportedPack" },
                renderMode = if (isSvgPack) "SVG" else "PNG",
                importedFullSheetPath = atlasFile?.absolutePath,
                placements = placements
            )
        }

        val category = parsedCode?.category?.let { raw ->
            runCatching { CharacterCategory.valueOf(raw.uppercase()) }.getOrNull()
        } ?: CharacterCategory.LATEST

        val charDef = CharacterRigDefinition(
            id = packId,
            name = rigConfig.textureName,
            category = category,
            bodyType = CharacterBodyType.HUMANOID,
            supportedOrientations = listOf(
                rigConfig.poseTemplate.defaultOrientation,
                CharacterOrientation.FRONT,
                CharacterOrientation.THREE_QUARTER_FRONT,
                CharacterOrientation.SIDE
            ).distinct(),
            isCustom = true,
            isLatest = true,
            customImagePath = atlasFile?.absolutePath ?: resolvedSlots.values.firstOrNull(),
            skeletonRig = SkeletonRigConfig(enabled = true, rigMode = CustomRigMode.ATLAS_RIG_PRO),
            spriteAtlasRig = rigConfig.copy(metadataJsonPath = jsonFile?.absolutePath)
        )
        return CharacterPackImportResult(
            character = charDef,
            message = "Imported \"${charDef.name}\" (${if (isSvgPack) "HD SVG" else "PNG"} pack, ${resolvedSlots.size.takeIf { it > 0 } ?: 30} slots)"
        )
    }

    private fun readBoundedText(stream: InputStream): String? {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_SINGLE_FILE_BYTES) return null
            baos.write(buf, 0, read)
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
