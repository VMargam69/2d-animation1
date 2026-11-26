package com.example.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hash-based navigation routes for FrameCraft Studio:
 *  - #/projects
 *  - #/characters
 *  - #/animations/{characterId} (optional ?swapClipId=...)
 *  - #/editor
 *  - #/media
 */
sealed interface HashRoute {
    val hash: String

    data object ProjectDashboard : HashRoute {
        override val hash: String = HASH_PROJECTS
    }

    data object CharacterLibrary : HashRoute {
        override val hash: String = HASH_CHARACTERS
    }

    data class AnimationLibrary(
        val characterId: String? = null,
        val swapClipId: String? = null
    ) : HashRoute {
        override val hash: String = buildString {
            append(HASH_ANIMATIONS)
            if (!characterId.isNullOrBlank()) {
                append("/")
                append(characterId)
            }
            if (!swapClipId.isNullOrBlank()) {
                append("?swapClipId=")
                append(swapClipId)
            }
        }
    }

    data class AnimationEditor(val projectId: String? = null) : HashRoute {
        override val hash: String = if (projectId.isNullOrBlank()) HASH_EDITOR else "$HASH_EDITOR/$projectId"
    }

    data object MediaLibrary : HashRoute {
        override val hash: String = HASH_MEDIA
    }

    data object CharacterSetup : HashRoute {
        override val hash: String = HASH_CHARACTER_SETUP
    }

    data class CharacterMaker(
        val poseId: String = "three_quarter_front",
        val editCharacterId: String? = null
    ) : HashRoute {
        override val hash: String = buildString {
            append(HASH_CHARACTER_MAKER)
            append("/")
            append(poseId)
            if (!editCharacterId.isNullOrBlank()) {
                append("?editCharId=")
                append(editCharacterId)
            }
        }
    }

    companion object {
        const val HASH_PROJECTS = "#/projects"
        const val HASH_CHARACTERS = "#/characters"
        const val HASH_ANIMATIONS = "#/animations"
        const val HASH_EDITOR = "#/editor"
        const val HASH_MEDIA = "#/media"
        const val HASH_CHARACTER_SETUP = "#/character-setup"
        const val HASH_CHARACTER_MAKER = "#/character-maker"

        fun parse(rawHash: String): HashRoute {
            val normalized = rawHash.trim().removePrefix("#").ifBlank { "/projects" }
            val pathPart = normalized.substringBefore('?')
            val queryPart = normalized.substringAfter('?', "")
            val queryParams = if (queryPart.isBlank()) {
                emptyMap()
            } else {
                queryPart.split('&').mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) pair.substring(0, idx) to pair.substring(idx + 1) else null
                }.toMap()
            }
            val segments = pathPart.split('/').filter { it.isNotBlank() }
            return when (segments.firstOrNull()?.lowercase()) {
                "projects" -> ProjectDashboard
                "characters" -> CharacterLibrary
                "animations" -> AnimationLibrary(
                    characterId = segments.getOrNull(1),
                    swapClipId = queryParams["swapClipId"]
                )
                "editor" -> AnimationEditor(projectId = segments.getOrNull(1))
                "media" -> MediaLibrary
                "character-setup" -> CharacterSetup
                "character-maker" -> CharacterMaker(
                    poseId = segments.getOrNull(1) ?: "three_quarter_front",
                    editCharacterId = queryParams["editCharId"]
                )
                else -> ProjectDashboard
            }
        }
    }
}

/**
 * Lightweight HashRouter managing hash URLs (#/projects, #/characters, #/animations, #/editor, #/media)
 * and a proper navigation back-stack for Jetpack Compose.
 */
class HashRouter(initialHash: String = HashRoute.HASH_PROJECTS) {

    private val _backStack = MutableStateFlow(listOf(initialHash))
    val backStack: StateFlow<List<String>> = _backStack.asStateFlow()

    val currentHash: String
        get() = _backStack.value.lastOrNull() ?: HashRoute.HASH_PROJECTS

    val currentRoute: HashRoute
        get() = HashRoute.parse(currentHash)

    val canGoBack: Boolean
        get() = _backStack.value.size > 1

    fun navigate(hash: String, singleTop: Boolean = true) {
        _backStack.update { stack ->
            if (singleTop && stack.lastOrNull() == hash) stack else stack + hash
        }
    }

    fun navigate(route: HashRoute, singleTop: Boolean = true) {
        navigate(route.hash, singleTop)
    }

    fun replace(hash: String) {
        _backStack.update { stack ->
            if (stack.isEmpty()) listOf(hash) else stack.dropLast(1) + hash
        }
    }

    fun popBackStack(): Boolean {
        if (_backStack.value.size <= 1) return false
        _backStack.update { stack -> stack.dropLast(1) }
        return true
    }

    fun popUpTo(targetPrefix: String, inclusive: Boolean = false) {
        _backStack.update { stack ->
            val idx = stack.indexOfLast { it.startsWith(targetPrefix) }
            if (idx == -1) stack
            else {
                val end = if (inclusive) idx else idx + 1
                stack.take(end).ifEmpty { listOf(HashRoute.HASH_PROJECTS) }
            }
        }
    }
}
