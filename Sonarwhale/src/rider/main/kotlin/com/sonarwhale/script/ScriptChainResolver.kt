package com.sonarwhale.script

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Resolves the ordered list of pre/post script files for a given endpoint + request.
 * Scripts live in a directory hierarchy rooted at [scriptsRoot].
 *
 * Pre-chain: global → collection → tag → endpoint → request
 * Post-chain: request → endpoint → tag → collection → global  (reversed)
 *
 * inherit.off at any level stops all parent levels from being included.
 */
class ScriptChainResolver(private val scriptsRoot: Path) {

    fun resolvePreChain(tag: String, method: String, path: String, requestName: String, collectionId: String = ""): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.PRE, collectionId)

    fun resolvePostChain(tag: String, method: String, path: String, requestName: String, collectionId: String = ""): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.POST, collectionId).reversed()

    private fun buildChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        phase: ScriptPhase,
        collectionId: String = ""
    ): List<ScriptFile> {
        if (!scriptsRoot.exists()) return emptyList()

        val endpointDirName = sanitizeEndpointDir(method, path)
        val requestDirName  = sanitizeName(requestName)
        val tagDirName      = sanitizeName(tag)
        val fileName        = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"

        data class Level(val dir: Path, val level: ScriptLevel)

        val levels = buildList {
            add(Level(scriptsRoot, ScriptLevel.GLOBAL))
            if (collectionId.isNotBlank()) {
                add(Level(scriptsRoot.resolve("collections").resolve(collectionId), ScriptLevel.COLLECTION))
            }
            add(Level(scriptsRoot.resolve(tagDirName), ScriptLevel.TAG))
            add(Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName), ScriptLevel.ENDPOINT))
            add(Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName).resolve(requestDirName), ScriptLevel.REQUEST))
        }

        // Deepest inherit.off wins (more specific level takes precedence over broader parent).
        // E.g. inherit.off at ENDPOINT level excludes GLOBAL+TAG even if TAG also has inherit.off.
        val deepestInheritOff = levels.indexOfLast { it.dir.resolve("inherit.off").exists() }

        val includedLevels = if (deepestInheritOff == -1) {
            levels
        } else {
            levels.drop(deepestInheritOff)
        }

        return includedLevels.mapNotNull { (dir, level) ->
            val scriptFile = dir.resolve(fileName)
            if (scriptFile.exists() && scriptFile.isRegularFile()) {
                ScriptFile(level = level, phase = phase, path = scriptFile)
            } else null
        }
    }

    companion object {
        fun sanitizeName(name: String): String =
            name.trim().replace(' ', '_').replace('/', '_').trimStart('_')

        fun sanitizeEndpointDir(method: String, path: String): String {
            val sanitizedPath = path.trimStart('/').replace('/', '_')
            return "${method.uppercase()}__${sanitizedPath}"
        }
    }
}
