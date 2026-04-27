# Scripts Tab, Layer Toggles & Auth Execution Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three bugs (auth config corruption on load, pre-script env always empty, stale varMap used for auth after pre-scripts) and add a Scripts tab to the request panel with per-level pre/post script inheritance toggles.

**Architecture:** `HierarchyConfig` gains two `Set<String>` fields for disabled script levels. `ScriptChainResolver` filters disabled levels from the chain. `SonarwhaleScriptService.executePreScripts()` is seeded from `VariableResolver.buildMap()` instead of the deprecated env stub; `executePostScripts()` handles persistence. `RequestPanel` replaces the URL-bar Pre/Post buttons with a Scripts tab containing 4 script-open buttons and two toggle grids (endpoint defaults + request overrides). `AuthConfigPanel.setAuth()` suppresses listeners during field population.

**Tech Stack:** Kotlin, Swing (JPanel/JCheckBox/JButton/GridBagLayout), JUnit 5, IntelliJ Platform SDK, Gson

---

## File Map

| File | Change |
|---|---|
| `src/rider/main/kotlin/com/sonarwhale/model/HierarchyConfig.kt` | Add `disabledPreLevels: Set<String>`, `disabledPostLevels: Set<String>` |
| `src/rider/main/kotlin/com/sonarwhale/toolwindow/AuthConfigPanel.kt` | Add `isLoading` flag to suppress onChange during `setAuth()` |
| `src/rider/main/kotlin/com/sonarwhale/script/ScriptChainResolver.kt` | Add `disabledLevels: Set<ScriptLevel>` param to `resolvePreChain`, `resolvePostChain`, `buildChain` |
| `src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt` | Add tests for disabled-level filtering (file already exists — append) |
| `src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt` | Add `varMap`, `collectionId`, `disabledLevels` params to both execute methods; fix `flushEnvChanges()` |
| `src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt` | Remove URL-bar buttons; add Scripts tab with 4 buttons + 2 toggle grids; fix `sendRequest()` to use `postScriptVarMap` for auth |
| `src/rider/main/kotlin/com/sonarwhale/toolwindow/HierarchyConfigPanel.kt` | Enhance Scripts tab with disabled-levels toggle grid; update `setConfig()` to refresh checkboxes |

---

## Task 1: Add `disabledPreLevels` / `disabledPostLevels` to `HierarchyConfig`

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/model/HierarchyConfig.kt`

- [ ] **Step 1: Edit the file**

Replace the entire file content:

```kotlin
package com.sonarwhale.model

/** Shared config block carried at every level of the hierarchy tree. */
data class HierarchyConfig(
    val variables: List<VariableEntry> = emptyList(),
    val auth: AuthConfig = AuthConfig(),
    /** Names of ScriptLevel values whose scripts are suppressed at this level (pre-phase). */
    val disabledPreLevels: Set<String> = emptySet(),
    /** Names of ScriptLevel values whose scripts are suppressed at this level (post-phase). */
    val disabledPostLevels: Set<String> = emptySet()
)
```

- [ ] **Step 2: Build to verify no compile errors**

```bash
cd /Users/koellman/IdeaProjects/sonarwhale/Sonarwhale
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/model/HierarchyConfig.kt
git commit -m "feat: add disabledPreLevels/disabledPostLevels to HierarchyConfig"
```

---

## Task 2: Fix `AuthConfigPanel` — suppress onChange during `setAuth()`

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/AuthConfigPanel.kt`

**Context:** `setAuth()` currently calls `modeCombo.selectedItem = auth.mode` first, which fires the ActionListener immediately. `buildFromFields()` is called while sibling fields still hold their old values, corrupting the saved auth config.

- [ ] **Step 1: Add `isLoading` field and guard both change methods**

In `AuthConfigPanel`, add a private field after the existing field declarations:

```kotlin
private var isLoading = false
```

Replace `onModeChanged()`:

```kotlin
private fun onModeChanged() {
    if (isLoading) return
    auth = buildFromFields()
    updateDisplay()
    onChange?.invoke(auth)
}
```

Replace `emitChange()`:

```kotlin
private fun emitChange() {
    if (isLoading) return
    auth = buildFromFields()
    onChange?.invoke(auth)
}
```

Wrap the body of `setAuth()` with the flag:

```kotlin
fun setAuth(newAuth: AuthConfig, newInherited: AuthMode = AuthMode.NONE) {
    isLoading = true
    try {
        auth = newAuth
        inheritedMode = newInherited
        modeCombo.selectedItem = auth.mode
        bearerTokenField.text = auth.bearerToken
        basicUserField.text = auth.basicUsername
        basicPassField.text = auth.basicPassword
        apiKeyNameField.text = auth.apiKeyName
        apiKeyValueField.text = auth.apiKeyValue
        apiKeyLocationCombo.selectedItem = auth.apiKeyLocation
        oauthTokenUrlField.text = auth.oauthTokenUrl
        oauthClientIdField.text = auth.oauthClientId
        oauthClientSecretField.text = auth.oauthClientSecret
        oauthScopeField.text = auth.oauthScope
        updateDisplay()
    } finally {
        isLoading = false
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew compileKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/AuthConfigPanel.kt
git commit -m "fix: suppress AuthConfigPanel onChange during setAuth() field population"
```

---

## Task 3: Add `disabledLevels` filtering to `ScriptChainResolver` + tests

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/script/ScriptChainResolver.kt`
- Modify: `src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt`

- [ ] **Step 1: Write failing tests — append to ScriptChainResolverTest.kt**

Open `src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt` and append the following tests inside the class body (before the closing `}`):

```kotlin
    @Test
    fun `disabled GLOBAL level excludes global pre script`() {
        pre() // global pre.js
        pre("Users") // tag pre.js
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.GLOBAL)
        )
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
        assertTrue(chain.any { it.level == ScriptLevel.TAG })
    }

    @Test
    fun `disabled TAG level excludes tag pre script but keeps global`() {
        pre() // global
        pre("Users") // tag
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.TAG)
        )
        assertTrue(chain.none { it.level == ScriptLevel.TAG })
        assertTrue(chain.any { it.level == ScriptLevel.GLOBAL })
    }

    @Test
    fun `multiple disabled levels excludes all specified`() {
        pre() // global
        pre("Users") // tag
        pre("Users", "GET__api_users") // endpoint
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.GLOBAL, ScriptLevel.TAG)
        )
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
        assertTrue(chain.none { it.level == ScriptLevel.TAG })
        assertTrue(chain.any { it.level == ScriptLevel.ENDPOINT })
    }

    @Test
    fun `disabled levels also filter post chain`() {
        post() // global
        post("Users") // tag
        val chain = resolver().resolvePostChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.GLOBAL)
        )
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
        assertTrue(chain.any { it.level == ScriptLevel.TAG })
    }

    @Test
    fun `empty disabledLevels returns full chain`() {
        pre() // global
        pre("Users") // tag
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = emptySet()
        )
        assertEquals(2, chain.size)
    }
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew test 2>&1 | grep -A5 "ScriptChainResolverTest"
```

Expected: compilation failure because `resolvePreChain`/`resolvePostChain` don't accept `disabledLevels` yet.

- [ ] **Step 3: Update `ScriptChainResolver.kt`**

Replace the entire file:

```kotlin
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
 * [disabledLevels] additionally suppresses specific levels regardless of inherit.off.
 */
class ScriptChainResolver(private val scriptsRoot: Path) {

    fun resolvePreChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
    ): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.PRE, collectionId, disabledLevels)

    fun resolvePostChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
    ): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.POST, collectionId, disabledLevels).reversed()

    private fun buildChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        phase: ScriptPhase,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
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
        val deepestInheritOff = levels.indexOfLast { it.dir.resolve("inherit.off").exists() }

        val includedLevels = if (deepestInheritOff == -1) {
            levels
        } else {
            levels.drop(deepestInheritOff)
        }

        return includedLevels.mapNotNull { (dir, level) ->
            if (level in disabledLevels) return@mapNotNull null
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
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
./gradlew test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/ScriptChainResolver.kt \
        src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt
git commit -m "feat: add disabledLevels filtering to ScriptChainResolver"
```

---

## Task 4: Fix `SonarwhaleScriptService` — seed env from `varMap`, fix `flushEnvChanges`

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt`

**Context:**
- `executePreScripts()` currently calls `stateService.getActiveEnvironment()` which returns `null` (deprecated stub) — so `ctx.envSnapshot` starts empty and `sw.env.get(...)` always returns `undefined`.
- `flushEnvChanges()` calls `stateService.upsertEnvironment()` — also a no-op stub. Must be replaced with `CollectionService.updateConfig()`.
- Remove `flushEnvChanges` from `executePreScripts`; call it once at the end of `executePostScripts`.

- [ ] **Step 1: Replace `executePreScripts`, `executePostScripts`, and `flushEnvChanges`**

Replace the three methods in `SonarwhaleScriptService.kt`. Keep all other methods unchanged.

**New `executePreScripts`** (replaces lines 28–55):

```kotlin
/**
 * Executes pre-scripts and returns the modified [ScriptContext].
 * Must be called from a background thread — sw.http makes blocking network calls.
 * Errors are captured into [console] rather than thrown.
 *
 * @param varMap  Resolved variable map from VariableResolver — seeds sw.env so scripts can read vars.
 * @param collectionId  Active collection ID — used by flushEnvChanges after post-scripts.
 * @param disabledLevels  Script levels to skip (Set<String> of ScriptLevel names).
 */
fun executePreScripts(
    endpoint: ApiEndpoint,
    request: SavedRequest,
    url: String,
    headers: Map<String, String>,
    body: String,
    varMap: Map<String, String> = emptyMap(),
    collectionId: String = "",
    disabledLevels: Set<String> = emptySet(),
    console: ConsoleOutput = ConsoleOutput()
): ScriptContext {
    val env = varMap.toMutableMap()   // seed from resolved vars — NOT the deprecated getActiveEnvironment()
    val ctx = ScriptContext(
        envSnapshot = env,
        request = MutableRequestContext(
            url = url,
            method = endpoint.method.name,
            headers = headers.toMutableMap(),
            body = body
        )
    )
    val tag = endpoint.tags.firstOrNull() ?: "Default"
    val disabledScriptLevels = disabledLevels
        .mapNotNull { runCatching { ScriptLevel.valueOf(it) }.getOrNull() }
        .toSet()
    val chain = resolver.resolvePreChain(tag, endpoint.method.name, endpoint.path, request.name, collectionId, disabledScriptLevels)
    runCatching { engine.executeChain(chain, ctx, console) }
        .onFailure { e ->
            console.log(LogLevel.ERROR, "Pre-script chain failed: ${e.message ?: e.javaClass.simpleName}")
        }
    // Do NOT flush here — caller merges envSnapshot into varMap and calls executePostScripts which flushes.
    return ctx
}
```

**New `executePostScripts`** (replaces lines 62–85):

```kotlin
/**
 * Executes post-scripts and returns the collected [TestResult]s.
 * Also persists any env changes made by pre- or post-scripts to the collection.
 * Must be called from a background thread.
 * Errors are captured into [console] rather than thrown.
 *
 * @param collectionId     Active collection ID — for persisting env changes.
 * @param originalVarMap   The var map from before pre-scripts ran — used to detect changes.
 * @param disabledLevels   Script levels to skip (Set<String> of ScriptLevel names).
 */
fun executePostScripts(
    endpoint: ApiEndpoint,
    request: SavedRequest,
    statusCode: Int,
    responseHeaders: Map<String, String>,
    responseBody: String,
    scriptContext: ScriptContext,
    collectionId: String = "",
    originalVarMap: Map<String, String> = emptyMap(),
    disabledLevels: Set<String> = emptySet(),
    console: ConsoleOutput = ConsoleOutput()
): List<TestResult> {
    val response = ResponseContext(statusCode, responseHeaders, responseBody)
    val postCtx = ScriptContext(
        envSnapshot = scriptContext.envSnapshot,
        request = scriptContext.request,
        response = response
    )
    val tag = endpoint.tags.firstOrNull() ?: "Default"
    val disabledScriptLevels = disabledLevels
        .mapNotNull { runCatching { ScriptLevel.valueOf(it) }.getOrNull() }
        .toSet()
    val chain = resolver.resolvePostChain(tag, endpoint.method.name, endpoint.path, request.name, collectionId, disabledScriptLevels)
    runCatching { engine.executeChain(chain, postCtx, console) }
        .onFailure { e ->
            console.log(LogLevel.ERROR, "Post-script chain failed: ${e.message ?: e.javaClass.simpleName}")
        }
    flushEnvChanges(postCtx.envSnapshot, originalVarMap, collectionId)
    return postCtx.testResults
}
```

**New `flushEnvChanges`** (replaces lines 214–221):

```kotlin
private fun flushEnvChanges(
    snapshot: MutableMap<String, String>,
    originalVarMap: Map<String, String>,
    collectionId: String
) {
    // Only persist keys that scripts actually changed
    val changed = snapshot.filter { (k, v) -> originalVarMap[k] != v }
    if (changed.isEmpty()) return

    ApplicationManager.getApplication().invokeLater {
        val collectionService = com.sonarwhale.service.CollectionService.getInstance(project)
        val collection = collectionService.getById(collectionId) ?: return@invokeLater
        val existing = collection.config.variables.toMutableList()
        changed.forEach { (k, v) ->
            val idx = existing.indexOfFirst { it.key == k }
            if (idx >= 0) existing[idx] = existing[idx].copy(value = v)
            else existing.add(com.sonarwhale.model.VariableEntry(key = k, value = v, enabled = true))
        }
        collectionService.updateConfig(collectionId, collection.config.copy(variables = existing))
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` (callers of `executePreScripts`/`executePostScripts` still compile because new params have defaults).

- [ ] **Step 3: Run tests**

```bash
./gradlew test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt
git commit -m "fix: seed script env from varMap, fix flushEnvChanges to use CollectionService"
```

---

## Task 5: Update `RequestPanel` — Scripts tab + postScriptVarMap for auth

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt`

**Summary of changes:**
1. Remove `preScriptButton` and `postScriptButton` fields and their URL-bar placement.
2. Add four checkbox maps as private fields.
3. Add `buildScriptsTab()` method.
4. Add `buildToggleGrid()` helper.
5. Add `updateEndpointToggles()` and `updateRequestToggles()` helpers.
6. Update `showRequest()` and `showEndpoint()` to load toggle state.
7. Update `openOrCreateScript()` to accept a `ScriptLevel` param.
8. Update `sendRequest()` to: pass `varMap`/`collectionId`/`disabledLevels` to script methods; build `postScriptVarMap`; use it for auth.
9. Add `ScriptLevel` import.

- [ ] **Step 1: Remove `preScriptButton` and `postScriptButton` fields**

Delete these two field declarations (lines 70–79):
```kotlin
private val preScriptButton = JButton("Pre").apply {
    font = font.deriveFont(10f)
    toolTipText = "Create or open pre-script for this request"
    isFocusable = false
}
private val postScriptButton = JButton("Post").apply {
    font = font.deriveFont(10f)
    toolTipText = "Create or open post-script for this request"
    isFocusable = false
}
```

- [ ] **Step 2: Add checkbox map fields**

After the `private val tabs = CollapsibleTabPane()` line, add:

```kotlin
import com.sonarwhale.script.ScriptLevel   // add to imports at top of file

// Scripts-tab checkbox state (populated in buildScriptsTab, read in show*/save methods)
private val endpointPreChecks  = mutableMapOf<ScriptLevel, javax.swing.JCheckBox>()
private val endpointPostChecks = mutableMapOf<ScriptLevel, javax.swing.JCheckBox>()
private val requestPreChecks   = mutableMapOf<ScriptLevel, javax.swing.JCheckBox>()
private val requestPostChecks  = mutableMapOf<ScriptLevel, javax.swing.JCheckBox>()
```

(If the import already exists or must go in the import block at the top, move it there.)

- [ ] **Step 3: Replace `init` block — add Scripts tab, remove button wiring**

In the `init` block, replace:
```kotlin
tabs.addTab("Params", paramsTable)
tabs.addTab("Headers", headersTable)
tabs.addTab("Body", bodyPanel)
tabs.addTab("Auth", authConfigPanel)

add(buildTopBar(), BorderLayout.NORTH)
add(tabs, BorderLayout.CENTER)

sendButton.addActionListener { sendRequest() }
saveButton.addActionListener { if (previewMode) createNewRequest() else saveRequest() }
setDefaultButton.addActionListener { setAsDefault() }
preScriptButton.addActionListener  { openOrCreateScript(ScriptPhase.PRE) }
postScriptButton.addActionListener { openOrCreateScript(ScriptPhase.POST) }
paramsTable.addChangeListener { updateComputedUrl() }
```

With:
```kotlin
tabs.addTab("Params", paramsTable)
tabs.addTab("Headers", headersTable)
tabs.addTab("Body", bodyPanel)
tabs.addTab("Auth", authConfigPanel)
tabs.addTab("Scripts", com.intellij.ui.components.JBScrollPane(buildScriptsTab()))

add(buildTopBar(), BorderLayout.NORTH)
add(tabs, BorderLayout.CENTER)

sendButton.addActionListener { sendRequest() }
saveButton.addActionListener { if (previewMode) createNewRequest() else saveRequest() }
setDefaultButton.addActionListener { setAsDefault() }
paramsTable.addChangeListener { updateComputedUrl() }
```

- [ ] **Step 4: Update `buildUrlBar()` — remove Pre/Post buttons**

Replace `buildUrlBar()`:

```kotlin
private fun buildUrlBar(): JPanel {
    val bar = JPanel(GridBagLayout())
    val gbc = GridBagConstraints()
    gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST

    gbc.gridx = 0; gbc.weightx = 1.0; gbc.insets = Insets(0, 0, 0, 6)
    bar.add(computedUrlField, gbc)

    gbc.gridx = 1; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 4)
    bar.add(sendButton, gbc)

    gbc.gridx = 2; gbc.insets = Insets(0, 0, 0, 4)
    bar.add(saveButton, gbc)

    gbc.gridx = 3; gbc.insets = Insets(0, 0, 0, 0)
    bar.add(setDefaultButton, gbc)

    return bar
}
```

- [ ] **Step 5: Add `buildScriptsTab()` and `buildToggleGrid()` methods**

Add after `buildUrlBar()`:

```kotlin
private fun buildScriptsTab(): JPanel {
    val panel = JPanel()
    panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
    panel.border = JBUI.Borders.empty(8)

    val endpointPreBtn  = JButton("Endpoint Pre-script").apply  { addActionListener { openOrCreateScript(ScriptPhase.PRE,  ScriptLevel.ENDPOINT) } }
    val endpointPostBtn = JButton("Endpoint Post-script").apply { addActionListener { openOrCreateScript(ScriptPhase.POST, ScriptLevel.ENDPOINT) } }
    val requestPreBtn   = JButton("Request Pre-script").apply   { addActionListener { openOrCreateScript(ScriptPhase.PRE,  ScriptLevel.REQUEST) } }
    val requestPostBtn  = JButton("Request Post-script").apply  { addActionListener { openOrCreateScript(ScriptPhase.POST, ScriptLevel.REQUEST) } }

    val btnRow1 = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).also {
        it.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        it.add(endpointPreBtn); it.add(endpointPostBtn)
    }
    val btnRow2 = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).also {
        it.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        it.add(requestPreBtn); it.add(requestPostBtn)
    }

    val endpointLevels = listOf(ScriptLevel.GLOBAL, ScriptLevel.COLLECTION, ScriptLevel.TAG)
    val requestLevels  = listOf(ScriptLevel.GLOBAL, ScriptLevel.COLLECTION, ScriptLevel.TAG, ScriptLevel.ENDPOINT)

    panel.add(com.intellij.ui.components.JBLabel("Endpoint scripts:").apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
    panel.add(javax.swing.Box.createVerticalStrut(4))
    panel.add(btnRow1)
    panel.add(javax.swing.Box.createVerticalStrut(8))
    panel.add(com.intellij.ui.components.JBLabel("Request scripts:").apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
    panel.add(javax.swing.Box.createVerticalStrut(4))
    panel.add(btnRow2)
    panel.add(javax.swing.Box.createVerticalStrut(12))
    panel.add(com.intellij.ui.components.JBLabel("Disable inherited (all requests):").apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
    panel.add(javax.swing.Box.createVerticalStrut(4))
    panel.add(buildToggleGrid(endpointLevels, endpointPreChecks, endpointPostChecks) { saveEndpointToggles() })
    panel.add(javax.swing.Box.createVerticalStrut(10))
    panel.add(com.intellij.ui.components.JBLabel("Disable inherited (this request):").apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
    panel.add(javax.swing.Box.createVerticalStrut(4))
    panel.add(buildToggleGrid(requestLevels, requestPreChecks, requestPostChecks) { saveRequestToggles() })

    return panel
}

private fun buildToggleGrid(
    levels: List<ScriptLevel>,
    preChecks: MutableMap<ScriptLevel, javax.swing.JCheckBox>,
    postChecks: MutableMap<ScriptLevel, javax.swing.JCheckBox>,
    onChanged: () -> Unit
): JPanel {
    val grid = JPanel(GridBagLayout())
    grid.alignmentX = java.awt.Component.LEFT_ALIGNMENT
    val gbc = GridBagConstraints().apply {
        anchor = GridBagConstraints.WEST
        insets = Insets(2, 0, 2, 8)
    }

    gbc.gridy = 0; gbc.gridx = 0; grid.add(JPanel(), gbc)
    gbc.gridx = 1; grid.add(com.intellij.ui.components.JBLabel("Pre"), gbc)
    gbc.gridx = 2; grid.add(com.intellij.ui.components.JBLabel("Post"), gbc)

    levels.forEachIndexed { i, level ->
        val preCb  = javax.swing.JCheckBox().apply { isSelected = true; addActionListener { onChanged() } }
        val postCb = javax.swing.JCheckBox().apply { isSelected = true; addActionListener { onChanged() } }
        preChecks[level]  = preCb
        postChecks[level] = postCb

        gbc.gridy = i + 1
        gbc.gridx = 0; grid.add(com.intellij.ui.components.JBLabel(level.name.lowercase().replaceFirstChar { it.uppercase() }), gbc)
        gbc.gridx = 1; grid.add(preCb,  gbc)
        gbc.gridx = 2; grid.add(postCb, gbc)
    }
    return grid
}
```

- [ ] **Step 6: Add toggle save helpers**

```kotlin
private fun saveEndpointToggles() {
    val ep = currentEndpoint ?: return
    val existing = stateService.getEndpointConfig(ep.id)
    val disabledPre  = endpointPreChecks.entries.filter  { !it.value.isSelected }.map { it.key.name }.toSet()
    val disabledPost = endpointPostChecks.entries.filter { !it.value.isSelected }.map { it.key.name }.toSet()
    stateService.setEndpointConfig(existing.copy(config = existing.config.copy(
        disabledPreLevels = disabledPre,
        disabledPostLevels = disabledPost
    )))
}

private fun saveRequestToggles() {
    val req = currentRequest ?: return
    val disabledPre  = requestPreChecks.entries.filter  { !it.value.isSelected }.map { it.key.name }.toSet()
    val disabledPost = requestPostChecks.entries.filter { !it.value.isSelected }.map { it.key.name }.toSet()
    currentRequest = req.copy(config = req.config.copy(
        disabledPreLevels = disabledPre,
        disabledPostLevels = disabledPost
    ))
    autoSave()
}

private fun updateEndpointToggles(disabledPre: Set<String>, disabledPost: Set<String>) {
    endpointPreChecks.forEach  { (level, cb) -> cb.isSelected = !disabledPre.contains(level.name) }
    endpointPostChecks.forEach { (level, cb) -> cb.isSelected = !disabledPost.contains(level.name) }
}

private fun updateRequestToggles(disabledPre: Set<String>, disabledPost: Set<String>) {
    requestPreChecks.forEach  { (level, cb) -> cb.isSelected = !disabledPre.contains(level.name) }
    requestPostChecks.forEach { (level, cb) -> cb.isSelected = !disabledPost.contains(level.name) }
}
```

- [ ] **Step 7: Update `showRequest()` to load toggle state**

In `showRequest()`, after loading auth (around line 207), add:

```kotlin
val epConfig = stateService.getEndpointConfig(endpoint.id)
updateEndpointToggles(epConfig.config.disabledPreLevels, epConfig.config.disabledPostLevels)
updateRequestToggles(request.config.disabledPreLevels, request.config.disabledPostLevels)
```

- [ ] **Step 8: Update `showEndpoint()` to load toggle state**

In `showEndpoint()`, after loading auth (around line 254), add:

```kotlin
val epConfig = stateService.getEndpointConfig(endpoint.id)
updateEndpointToggles(epConfig.config.disabledPreLevels, epConfig.config.disabledPostLevels)
updateRequestToggles(emptySet(), emptySet())
```

- [ ] **Step 9: Update `openOrCreateScript()` to accept ScriptLevel**

Replace:

```kotlin
private fun openOrCreateScript(phase: ScriptPhase) {
    val endpoint = currentEndpoint ?: return
    val request  = currentRequest ?: SavedRequest(name = currentRequestName)
    val scriptService = SonarwhaleScriptService.getInstance(project)
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating script…", false) {
        override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
            val path = scriptService.getOrCreateScript(endpoint, request, phase, ScriptLevel.REQUEST)
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    })
}
```

With:

```kotlin
private fun openOrCreateScript(phase: ScriptPhase, level: ScriptLevel = ScriptLevel.REQUEST) {
    val endpoint = currentEndpoint ?: return
    val request  = currentRequest ?: SavedRequest(name = currentRequestName)
    val scriptService = SonarwhaleScriptService.getInstance(project)
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating script…", false) {
        override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
            val path = scriptService.getOrCreateScript(endpoint, request, phase, level)
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    })
}
```

- [ ] **Step 10: Fix `sendRequest()` — pass varMap/colId/disabledLevels to scripts; build `postScriptVarMap` for auth**

In `sendRequest()`, after `val savedRequest = currentRequest ?: SavedRequest(name = currentRequestName)` (around line 496), add:

```kotlin
// Compute effective disabled script levels (union of endpoint + request)
val epCfg = stateService.getEndpointConfig(endpoint.id)
val reqCfg = currentRequest?.config ?: com.sonarwhale.model.HierarchyConfig()
val effectiveDisabledPre  = epCfg.config.disabledPreLevels  + reqCfg.disabledPreLevels
val effectiveDisabledPost = epCfg.config.disabledPostLevels + reqCfg.disabledPostLevels
```

In `doInBackground()`, replace the `executePreScripts` call:

```kotlin
// BEFORE:
val ctx = scriptService.executePreScripts(
    endpoint = endpoint,
    request  = savedRequest,
    url      = resolvedUrl,
    headers  = initialHeaders,
    body     = initialBody,
    console  = consoleOutput
)

// AFTER:
val ctx = scriptService.executePreScripts(
    endpoint       = endpoint,
    request        = savedRequest,
    url            = resolvedUrl,
    headers        = initialHeaders,
    body           = initialBody,
    varMap         = varMap,
    collectionId   = colId,
    disabledLevels = effectiveDisabledPre,
    console        = consoleOutput
)
```

After `scriptContext = ctx`, before building the HTTP request, add:

```kotlin
// Merge env changes from pre-scripts so auth tokens set by sw.env.set() are available
val postScriptVarMap = varMap.toMutableMap().also { it.putAll(ctx.envSnapshot) }
```

Replace `authResolver.applyToRequest(builder, authUrlForBuilder, effectiveAuth, varMap, varResolver)` with:

```kotlin
authResolver.applyToRequest(builder, authUrlForBuilder, effectiveAuth, postScriptVarMap, varResolver)
```

Replace the `executePostScripts` call:

```kotlin
// BEFORE:
testResults = scriptService.executePostScripts(
    endpoint        = endpoint,
    request         = savedRequest,
    statusCode      = response.statusCode(),
    responseHeaders = responseHeaders,
    responseBody    = response.body(),
    scriptContext   = ctx,
    console         = consoleOutput
)

// AFTER:
testResults = scriptService.executePostScripts(
    endpoint        = endpoint,
    request         = savedRequest,
    statusCode      = response.statusCode(),
    responseHeaders = responseHeaders,
    responseBody    = response.body(),
    scriptContext   = ctx,
    collectionId    = colId,
    originalVarMap  = varMap,
    disabledLevels  = effectiveDisabledPost,
    console         = consoleOutput
)
```

- [ ] **Step 11: Build**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 12: Run tests**

```bash
./gradlew test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 13: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt
git commit -m "feat: add Scripts tab to RequestPanel with layer toggles and fix pre-script auth flow"
```

---

## Task 6: Enhance `HierarchyConfigPanel` Scripts tab with toggle grid

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/HierarchyConfigPanel.kt`

- [ ] **Step 1: Add checkbox-map fields to `HierarchyConfigPanel`**

After `private val authPanel = AuthConfigPanel(...)`, add:

```kotlin
private val preCheckboxes  = mutableMapOf<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>()
private val postCheckboxes = mutableMapOf<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>()
```

- [ ] **Step 2: Replace `buildScriptsTab()`**

Replace the existing `buildScriptsTab()` method:

```kotlin
private fun buildScriptsTab(): JPanel {
    val panel = JPanel()
    panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
    panel.border = JBUI.Borders.empty(8)

    val preBtn = JButton("Open Pre-Script").apply {
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        addActionListener { openScript(ScriptPhase.PRE) }
    }
    val postBtn = JButton("Open Post-Script").apply {
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        addActionListener { openScript(ScriptPhase.POST) }
    }

    panel.add(preBtn)
    panel.add(javax.swing.Box.createVerticalStrut(4))
    panel.add(postBtn)

    // Determine which parent levels can be toggled based on the current node's level
    val parentLevels: List<com.sonarwhale.script.ScriptLevel> = when (scriptContext?.level) {
        com.sonarwhale.script.ScriptLevel.COLLECTION ->
            listOf(com.sonarwhale.script.ScriptLevel.GLOBAL)
        com.sonarwhale.script.ScriptLevel.TAG ->
            listOf(com.sonarwhale.script.ScriptLevel.GLOBAL, com.sonarwhale.script.ScriptLevel.COLLECTION)
        com.sonarwhale.script.ScriptLevel.ENDPOINT ->
            listOf(com.sonarwhale.script.ScriptLevel.GLOBAL, com.sonarwhale.script.ScriptLevel.COLLECTION, com.sonarwhale.script.ScriptLevel.TAG)
        else -> emptyList()
    }

    if (parentLevels.isNotEmpty()) {
        panel.add(javax.swing.Box.createVerticalStrut(12))
        panel.add(com.intellij.ui.components.JBLabel("Disable inherited:").apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        })
        panel.add(javax.swing.Box.createVerticalStrut(4))
        panel.add(buildToggleGrid(parentLevels))
    }

    return panel
}

private fun buildToggleGrid(levels: List<com.sonarwhale.script.ScriptLevel>): JPanel {
    val grid = JPanel(java.awt.GridBagLayout())
    grid.alignmentX = java.awt.Component.LEFT_ALIGNMENT
    val gbc = java.awt.GridBagConstraints().apply {
        anchor = java.awt.GridBagConstraints.WEST
        insets = java.awt.Insets(2, 0, 2, 8)
    }

    gbc.gridy = 0; gbc.gridx = 0; grid.add(JPanel(), gbc)
    gbc.gridx = 1; grid.add(com.intellij.ui.components.JBLabel("Pre"), gbc)
    gbc.gridx = 2; grid.add(com.intellij.ui.components.JBLabel("Post"), gbc)

    levels.forEachIndexed { i, level ->
        val preCb = javax.swing.JCheckBox().apply {
            isSelected = !config.disabledPreLevels.contains(level.name)
            addActionListener { onToggleChanged() }
        }
        val postCb = javax.swing.JCheckBox().apply {
            isSelected = !config.disabledPostLevels.contains(level.name)
            addActionListener { onToggleChanged() }
        }
        preCheckboxes[level]  = preCb
        postCheckboxes[level] = postCb

        gbc.gridy = i + 1
        gbc.gridx = 0; grid.add(com.intellij.ui.components.JBLabel(
            level.name.lowercase().replaceFirstChar { it.uppercase() }), gbc)
        gbc.gridx = 1; grid.add(preCb,  gbc)
        gbc.gridx = 2; grid.add(postCb, gbc)
    }
    return grid
}

private fun onToggleChanged() {
    val disabledPre  = preCheckboxes.entries.filter  { !it.value.isSelected }.map { it.key.name }.toSet()
    val disabledPost = postCheckboxes.entries.filter { !it.value.isSelected }.map { it.key.name }.toSet()
    config = config.copy(disabledPreLevels = disabledPre, disabledPostLevels = disabledPost)
    onSave(config)
}
```

- [ ] **Step 3: Update `setConfig()` to refresh checkboxes**

Replace:

```kotlin
fun setConfig(newConfig: HierarchyConfig) {
    config = newConfig
    variablesPanel.setVariables(newConfig.variables)
    authPanel.setAuth(newConfig.auth)
}
```

With:

```kotlin
fun setConfig(newConfig: HierarchyConfig) {
    config = newConfig
    variablesPanel.setVariables(newConfig.variables)
    authPanel.setAuth(newConfig.auth)
    preCheckboxes.forEach  { (level, cb) -> cb.isSelected = !newConfig.disabledPreLevels.contains(level.name) }
    postCheckboxes.forEach { (level, cb) -> cb.isSelected = !newConfig.disabledPostLevels.contains(level.name) }
}
```

- [ ] **Step 4: Build**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run all tests**

```bash
./gradlew test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/HierarchyConfigPanel.kt
git commit -m "feat: add disabled-level toggle grid to HierarchyConfigPanel Scripts tab"
```

---

## Task 7: Final build and plugin package verification

- [ ] **Step 1: Full build + tests**

```bash
cd /Users/koellman/IdeaProjects/sonarwhale/Sonarwhale
./gradlew test 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 2: Build plugin zip**

```bash
./gradlew buildPlugin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, zip created in `build/distributions/`.

- [ ] **Step 3: Commit any remaining changes**

```bash
git status
# If clean, nothing to do. If dirty, stage and commit.
```
