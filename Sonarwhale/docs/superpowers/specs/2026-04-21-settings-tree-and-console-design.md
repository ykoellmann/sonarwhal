# Settings Tree Group Node & Console Improvements Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `Sonarwhale` settings node a group node (no content panel), and improve the console panel to show the full request in timeline order with selectable text.

**Architecture:** Two independent UI changes. The settings fix is a one-liner marker interface. The console changes add main-request recording to `RequestPanel` and replace non-selectable `JBLabel` rows in `ConsolePanel` with non-editable `JTextArea` rows.

**Tech Stack:** Kotlin, IntelliJ Platform `Configurable.NoPanel`, `JTextArea`, `ConsoleOutput`

---

## Section 1: Settings Tree Group Node

`SonarwhaleConfigurable` currently returns a real panel from `createComponent()`, causing IntelliJ to render "Sonarwhale" as a selectable settings page. The fix: implement `Configurable.NoPanel` — a marker interface that tells the IDE to treat the node as a group (like "Database"). The IDE auto-selects the first child when the user clicks the parent.

### Changes

**`settings/SonarwhaleConfigurable.kt`** — modified:
- Add `Configurable.NoPanel` to the `implements` clause
- Remove `createComponent()` override entirely
- Remove unused imports: `JBColor`, `JBLabel`, `JBUI`, `BorderLayout`, `JPanel`, `JComponent`
- Keep `isModified()`, `apply()`, `reset()` as no-ops (required by `Configurable`)

Result:
```kotlin
class SonarwhaleConfigurable(private val project: Project) : Configurable, Configurable.NoPanel {
    override fun getDisplayName() = "Sonarwhale"
    override fun isModified() = false
    override fun apply() {}
    override fun reset() {}
}
```

---

## Section 2: Console — Timeline Order + Selectable Text

### 2a: Record main request to ConsoleOutput

**`toolwindow/RequestPanel.kt`** — modified in `sendRequest()` → SwingWorker `doInBackground()`:

The HTTP call currently runs untracked. Wrap it to record both success and failure:

```kotlin
// After building the request, replace bare client.send() with:
val start = System.currentTimeMillis()
val response = try {
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
} catch (e: Exception) {
    val duration = System.currentTimeMillis() - start
    consoleOutput.http(
        endpoint.method.name, finalUrl, 0, duration,
        finalHeaders, finalBody.ifEmpty { null }, emptyMap(), "", e.message
    )
    throw e   // re-throw so done() still shows the error in the response panel
}
val duration = System.currentTimeMillis() - start

val responseHeaders = response.headers().map().mapValues { (_, vs) -> vs.firstOrNull() ?: "" }

// Record before post-scripts — timeline order: pre-logs → main request → post-logs
consoleOutput.http(
    endpoint.method.name, finalUrl, response.statusCode(), duration,
    finalHeaders, finalBody.ifEmpty { null },
    responseHeaders, response.body(), null
)

// ── Post-scripts ── (existing code continues here)
```

`finalBody` is already available as a local `String` in scope at that point (from the pre-script ctx). `finalHeaders` is the `Map<String, String>` also already in scope.

### 2b: Selectable text in ConsolePanel

**`toolwindow/ConsolePanel.kt`** — rewrite row-building methods:

Replace all `JBLabel`-based row panels with non-editable `JTextArea` rows. Each row becomes a single `JTextArea` that spans the full panel width, is selectable, and wraps properly.

**Helper** (added to `ConsolePanel`):
```kotlin
private fun textRow(text: String, fg: java.awt.Color, italic: Boolean = false): JTextArea =
    JTextArea(text).apply {
        isEditable  = false
        isOpaque    = false
        lineWrap    = true
        wrapStyleWord = true
        foreground  = fg
        font        = if (italic)
            Font(Font.MONOSPACED, Font.ITALIC, 11)
        else
            Font(Font.MONOSPACED, Font.PLAIN, 11)
        border      = JBUI.Borders.empty(1, 4)
    }
```

**`buildBoundaryRow()`**:
```kotlin
private fun buildBoundaryRow(entry: ConsoleEntry.ScriptBoundary): JTextArea {
    val phase = if (entry.phase == ScriptPhase.PRE) "pre" else "post"
    val name  = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
    return textRow("▶ $name [$phase]", JBColor.GRAY, italic = true)
}
```

**`buildLogRow()`**:
```kotlin
private fun buildLogRow(entry: ConsoleEntry.LogEntry): JTextArea {
    val color = when (entry.level) {
        LogLevel.LOG   -> JBColor.foreground()
        LogLevel.WARN  -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
        LogLevel.ERROR -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
    }
    val prefix = when (entry.level) {
        LogLevel.LOG   -> ""
        LogLevel.WARN  -> "⚠  "
        LogLevel.ERROR -> "✕  "
    }
    val time = timeFmt.format(Date(entry.timestampMs))
    return textRow("$time  $prefix${entry.message}", color)
}
```

**`buildErrorRow()`**:
```kotlin
private fun buildErrorRow(entry: ConsoleEntry.ErrorEntry): JTextArea {
    val name = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
    val area = textRow("✕  $name: ${entry.message}",
        JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x55, 0x55)))
    area.background = JBColor(Color(0xFF, 0xEE, 0xEE), Color(0x55, 0x22, 0x22))
    area.isOpaque = true
    return area
}
```

**`buildHttpRow()` summary** — replace the multi-JBLabel `FlowLayout` panel with a `JTextArea`. The expandable details section is unchanged (already uses JTextArea):
```kotlin
private fun buildHttpRow(entry: ConsoleEntry.HttpEntry): JPanel {
    val container = JPanel()
    container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
    container.isOpaque = false

    val statusColor = when {
        entry.status in 200..299 -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
        entry.status == 0        -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        entry.status in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x77, 0x33))
        else                     -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
    }
    val statusText = if (entry.status == 0) "ERROR" else "${entry.status}"
    val summaryText = "→  ${entry.method}  ${entry.url}  ·  $statusText  ·  ${entry.durationMs}ms"
    val summary = textRow(summaryText, statusColor)

    val details = buildHttpDetails(entry)
    details.isVisible = false

    summary.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    summary.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            details.isVisible = !details.isVisible
            container.revalidate()
            container.repaint()
        }
    })

    container.add(summary)
    container.add(details)
    return container
}
```

**`buildRow()`** — update return types to accept both `JTextArea` and `JPanel`:
```kotlin
private fun buildRow(entry: ConsoleEntry): java.awt.Component = when (entry) {
    is ConsoleEntry.ScriptBoundary -> buildBoundaryRow(entry)
    is ConsoleEntry.LogEntry       -> buildLogRow(entry)
    is ConsoleEntry.ErrorEntry     -> buildErrorRow(entry)
    is ConsoleEntry.HttpEntry      -> buildHttpRow(entry)
}
```

`showEntries()` already calls `contentPanel.add(buildRow(entry))` — no change needed there since `JTextArea` is a `Component`.

Remove imports no longer needed: `FlowLayout`, `MouseEvent` is still needed (keep), `JButton` is still needed (keep for Clear button).

---

## Files Changed

| File | Change |
|---|---|
| `settings/SonarwhaleConfigurable.kt` | Add `Configurable.NoPanel`, remove `createComponent()` |
| `toolwindow/RequestPanel.kt` | Wrap HTTP call, add `consoleOutput.http()` recording |
| `toolwindow/ConsolePanel.kt` | Replace JBLabel rows with JTextArea; update `buildRow()` return type |
