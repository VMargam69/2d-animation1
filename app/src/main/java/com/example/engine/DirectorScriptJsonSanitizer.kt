package com.example.engine

/**
 * Result of auto-sanitizing messy LLM-generated `.fcscript.json` output before JSON parsing.
 */
data class JsonSanitizationResult(
    val sanitizedJson: String,
    val fixesApplied: List<String> = emptyList(),
    val extractedFromFence: Boolean = false
)

/**
 * Resilient, string-literal-aware JSON Auto-Sanitizer & Error Fix Prompt Builder for external AI outputs.
 *
 * Safely cleans common LLM formatting glitches without corrupting strings, URLs (`http://`),
 * or inline SVG XML attributes:
 * 1. Strips UTF-8 BOM & surrounding whitespace.
 * 2. Extracts JSON from markdown code fences (` ```json ... ``` ` or ` ``` ... ``` `).
 * 3. Strips leading/trailing conversational prose outside the outermost balanced `{ ... }` object.
 * 4. Removes `//` single-line comments and `/* ... */` block comments outside string literals.
 * 5. Removes trailing commas before `}` or `]` outside string literals.
 */
object DirectorScriptJsonSanitizer {

    private val FENCE_REGEX = Regex("```(?:json|JSON|fcscript)?\\s*\\r?\\n?([\\s\\S]*?)```")
    private val LINE_COL_REGEX = Regex("""(?i)(?:line\s+(\d+)|at\s+line\s+(\d+))""")
    private val PATH_REGEX = Regex("""at\s+path\s+(\$[^\s,]*)""")

    fun sanitizeLlmJson(rawInput: String): JsonSanitizationResult {
        if (rawInput.isBlank()) {
            return JsonSanitizationResult(sanitizedJson = "")
        }

        val fixes = mutableListOf<String>()
        var working = rawInput.removePrefix("\uFEFF").trim()
        var extractedFromFence = false

        // 1. Extract from markdown code fence if present
        val fenceMatches = FENCE_REGEX.findAll(working).toList()
        if (fenceMatches.isNotEmpty()) {
            val bestBlock = fenceMatches
                .map { it.groupValues[1].trim() }
                .filter { it.contains("{") }
                .maxByOrNull { it.length }
                ?: fenceMatches.first().groupValues[1].trim()
            if (bestBlock.isNotEmpty()) {
                working = bestBlock
                extractedFromFence = true
                fixes.add("Extracted JSON from markdown code fence")
            }
        }

        // 2. Strip leading & trailing conversational prose outside balanced { ... }
        val extractedObj = extractOutermostJsonObject(working)
        if (extractedObj != null && extractedObj.length < working.length) {
            working = extractedObj
            fixes.add("Stripped surrounding conversational text")
        }

        // 3. Remove // and /* ... */ comments outside double-quoted strings
        val (noComments, commentsRemoved) = stripCommentsOutsideStrings(working)
        if (commentsRemoved > 0) {
            working = noComments
            fixes.add("Removed $commentsRemoved JSON comment(s)")
        }

        // 4. Remove trailing commas before } or ] outside double-quoted strings
        val (noTrailingCommas, commasRemoved) = stripTrailingCommasOutsideStrings(working)
        if (commasRemoved > 0) {
            working = noTrailingCommas
            fixes.add("Fixed $commasRemoved trailing comma(s)")
        }

        return JsonSanitizationResult(
            sanitizedJson = working.trim(),
            fixesApplied = fixes,
            extractedFromFence = extractedFromFence
        )
    }

    /**
     * Finds the outermost balanced `{ ... }` span ignoring braces inside string literals.
     * If unclosed (truncated LLM output), strips only leading prose before the first `{`.
     */
    private fun extractOutermostJsonObject(text: String): String? {
        val firstBrace = text.indexOf('{')
        if (firstBrace < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        var endBrace = -1

        for (i in firstBrace until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endBrace = i
                        break
                    }
                }
            }
        }

        return when {
            endBrace > firstBrace -> text.substring(firstBrace, endBrace + 1).trim()
            firstBrace > 0 -> text.substring(firstBrace).trim()
            else -> null
        }
    }

    private fun stripCommentsOutsideStrings(input: String): Pair<String, Int> {
        val sb = StringBuilder(input.length)
        var inString = false
        var escaped = false
        var i = 0
        var removed = 0
        val len = input.length

        while (i < len) {
            val ch = input[i]
            if (inString) {
                sb.append(ch)
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                i++
                continue
            }

            if (ch == '"') {
                inString = true
                sb.append(ch)
                i++
                continue
            }

            // Single-line comment //
            if (ch == '/' && i + 1 < len && input[i + 1] == '/') {
                removed++
                i += 2
                while (i < len && input[i] != '\n' && input[i] != '\r') {
                    i++
                }
                continue
            }

            // Multi-line comment /* ... */
            if (ch == '/' && i + 1 < len && input[i + 1] == '*') {
                removed++
                i += 2
                while (i + 1 < len && !(input[i] == '*' && input[i + 1] == '/')) {
                    if (input[i] == '\n') sb.append('\n') // preserve line numbers for diagnostics
                    i++
                }
                if (i + 1 < len) {
                    i += 2
                } else {
                    i = len
                }
                continue
            }

            sb.append(ch)
            i++
        }
        return sb.toString() to removed
    }

    private fun stripTrailingCommasOutsideStrings(input: String): Pair<String, Int> {
        val sb = StringBuilder(input.length)
        var inString = false
        var escaped = false
        var removed = 0
        val len = input.length

        for (i in 0 until len) {
            val ch = input[i]
            if (inString) {
                sb.append(ch)
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            if (ch == '"') {
                inString = true
                sb.append(ch)
                continue
            }

            if (ch == ',') {
                var lookahead = i + 1
                while (lookahead < len && input[lookahead].isWhitespace()) {
                    lookahead++
                }
                if (lookahead < len && (input[lookahead] == '}' || input[lookahead] == ']')) {
                    removed++
                    continue
                }
            }

            sb.append(ch)
        }
        return sb.toString() to removed
    }

    /**
     * Extracts a readable line/field hint and a focused 3-line offending snippet from a Moshi parse error.
     */
    fun extractErrorDiagnostics(sanitizedJson: String, throwable: Throwable): Pair<String, String?> {
        val rawMsg = throwable.message.orEmpty().trim()
        val lines = sanitizedJson.lines()

        val pathMatch = PATH_REGEX.find(rawMsg)?.groupValues?.getOrNull(1)
        val lineMatch = LINE_COL_REGEX.find(rawMsg)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        }

        // Try locating offending field name from JSON path if line wasn't reported directly
        val targetFieldName = pathMatch
            ?.substringAfterLast('.')
            ?.substringBefore('[')
            ?.takeIf { it.isNotBlank() && it != "$" }

        val resolvedLineNum = lineMatch
            ?: targetFieldName?.let { field ->
                val idx = lines.indexOfFirst { it.contains("\"$field\"") }
                if (idx >= 0) idx + 1 else null
            }

        val hint = buildString {
            if (pathMatch != null) append("Field path: $pathMatch")
            if (resolvedLineNum != null) {
                if (isNotEmpty()) append(" • ")
                append("Near line $resolvedLineNum")
            }
            if (isEmpty()) {
                append("Check matching braces { }, quotes \" \", and field value types (numbers vs strings).")
            }
        }

        val snippet = when {
            resolvedLineNum != null && lines.isNotEmpty() -> {
                val start = (resolvedLineNum - 2).coerceAtLeast(0)
                val end = (resolvedLineNum + 1).coerceAtMost(lines.lastIndex)
                (start..end).joinToString("\n") { idx ->
                    val marker = if (idx + 1 == resolvedLineNum) ">>" else "  "
                    "$marker ${idx + 1}: ${lines[idx].take(140)}"
                }
            }
            sanitizedJson.isNotBlank() -> {
                val tail = if (sanitizedJson.length > 360) {
                    "...\n" + sanitizedJson.takeLast(360)
                } else {
                    sanitizedJson
                }
                tail
            }
            else -> null
        }

        return hint to snippet
    }

    /**
     * Builds a 1-tap "Copy Fix Prompt for AI" payload containing the exact parse/validation errors,
     * line/field hint, offending snippet, and strict repair instructions.
     */
    fun buildErrorFixPrompt(
        rawInput: String,
        errors: List<String>,
        errorHint: String?,
        errorSnippet: String?
    ): String {
        val sb = StringBuilder(1200)
        sb.appendLine("# FRAMECRAFT `.fcscript.json` REPAIR REQUEST")
        sb.appendLine("The `.fcscript.json` you generated failed validation in FrameCraft Studio. Fix ONLY the error(s) below and return the complete, valid `.fcscript.json` JSON object (pure JSON only, no markdown commentary, no comments, no trailing commas).")
        sb.appendLine()
        sb.appendLine("## EXACT ERROR(S)")
        if (errors.isEmpty()) {
            sb.appendLine("- Unknown JSON / schema validation error.")
        } else {
            errors.forEach { err -> sb.appendLine("- $err") }
        }
        if (!errorHint.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("## LINE / FIELD HINT")
            sb.appendLine(errorHint)
        }
        val snippet = errorSnippet?.takeIf { it.isNotBlank() }
            ?: rawInput.trim().take(500).takeIf { it.isNotBlank() }
        if (!snippet.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("## OFFENDING JSON SNIPPET")
            sb.appendLine("```json")
            sb.appendLine(snippet)
            sb.appendLine("```")
        }
        sb.appendLine()
        sb.append("Please reply with the complete repaired `.fcscript.json` JSON now.")
        return sb.toString()
    }
}
