package com.sonarwhale.script

import java.nio.file.Path

enum class ScriptLevel { GLOBAL, TAG, ENDPOINT, REQUEST }
enum class ScriptPhase { PRE, POST }

data class ScriptFile(
    val level: ScriptLevel,
    val phase: ScriptPhase,
    val path: Path
)
