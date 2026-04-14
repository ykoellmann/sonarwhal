# Sonarwhale (Arbeitstitel: Blip) — JetBrains Rider Plugin

## Was ist das?

Ein JetBrains Rider Plugin, das API-Endpoints aus OpenAPI-Quellen einliest und einen integrierten HTTP-Client dafür bereitstellt. Ziel: Endpoints direkt aus der IDE heraus testen, ohne Postman oder Browser. Primär für ASP.NET Core, langfristig framework-agnostisch (FastAPI, Spring Boot, Express, ...).

Mascot: Roux (ein Narwal). Name in Diskussion: Blip.

---

## Architektur-Übersicht

```
┌──────────────────────────────────────────────────────────┐
│  DATENQUELLE — pro Environment eine, OpenAPI ist Pflicht │
│                                                          │
│  Option A: Server-URL + Port                             │
│    → HTTP-Fetch gegen bekannte OpenAPI-Pfade             │
│    → Auto-Discovery (probiert Pfade durch, s.u.)        │
│    → Optional: manueller Pfad-Override                  │
│                                                          │
│  Option B: Dateipfad im Filesystem                       │
│    → z.B. ./bin/Debug/net8.0/swagger.json               │
│    → wird bei Build-Event neu gelesen                    │
│    → kein Server nötig                                   │
│                                                          │
│  Option C: Statischer Import (einmalig)                  │
│    → OpenAPI JSON/YAML Datei einmalig hochladen          │
│    → kein Auto-Refresh, manueller Re-Import möglich      │
└──────────────────────────────────────────────────────────┘
         ↓ (aktives Environment liefert OpenAPI-Daten)
┌──────────────────────────────────────────────────────────┐
│  OpenApiFetcher (async, Background)                      │
│  - führt Fetch/Lesen durch                              │
│  - bei Fehler: letzten Cache verwenden                  │
│  - Status-Icon im Tool Window (ok / cached / error)     │
└──────────────────────────────────────────────────────────┘
         ↓
┌──────────────────────────────┐
│  RouteIndexService           │
│  - Endpoint-Liste aufbauen  │
│  - Schemas speichern        │
│  - Auth-Infos halten        │
│  - Cache persistieren       │
└──────────────────────────────┘
         ↓ (zwei unabhängige Pfade)
┌──────────────────┐    ┌───────────────────────────┐
│  PSI-Bridge      │    │  Diff/Snapshot-Engine     │
│  (nur Navigation)│    │                           │
│  - Route-String  │    │  - Snapshot nach Refresh  │
│    → PsiElement  │    │  - Delta: added/modified/ │
│  - Jump to Def.  │    │    removed Endpoints      │
│  - Gutter Icons  │    │  - Breaking-Change-Badge  │
└──────────────────┘    └───────────────────────────┘
         ↓
┌──────────────────────────────────────────────────────┐
│  Tool Window UI                                      │
│                                                      │
│  ┌─────────────────┐  ┌──────────────────────────┐  │
│  │ Endpoint-Tree   │  │ Detail-Panel             │  │
│  │                 │  │ - Tabs: Request/Response │  │
│  │ ▼ GET           │  │ - Auth-Konfiguration     │  │
│  │   /api/users    │  │ - Headers/Body-Editor    │  │
│  │ ▼ POST          │  │ - Diff-Tab               │  │
│  │   /api/users    │  │ - Schema-Vorschau        │  │
│  └─────────────────┘  └──────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

---

## Environments & Konfiguration

Pro Projekt können mehrere Environments angelegt werden. Start-Default ist `dev`. Eines ist immer aktiv.

```kotlin
data class SonarwhaleEnvironment(
    val name: String,                  // z.B. "dev", "staging", "prod"
    val source: EnvironmentSource,
    val isActive: Boolean
)

sealed class EnvironmentSource {
    // Option A: Server-URL, Port manuell
    data class ServerUrl(
        val host: String,              // z.B. "http://localhost"
        val port: Int,                 // z.B. 5000
        val openApiPath: String?,      // null → Auto-Discovery
        // TODO (später): auth für den Swagger-Endpunkt selbst (Header/Token)
    ) : EnvironmentSource()

    // Option B: Dateipfad im Filesystem
    data class FilePath(
        val path: String               // z.B. "./bin/Debug/net8.0/swagger.json"
    ) : EnvironmentSource()

    // Option C: Statisch importierte Datei (kein Auto-Refresh)
    data class StaticImport(
        val cachedContent: String      // gespeicherter JSON-Inhalt
    ) : EnvironmentSource()
}
```

### OpenAPI Auto-Discovery (Option A)

Bekannte Pfade werden der Reihe nach probiert, erster Treffer gewinnt. Manueller Override hat Vorrang.

```
ASP.NET Core (Swashbuckle):      /swagger/v1/swagger.json
ASP.NET Core (Microsoft.OpenApi): /openapi/v1.json
FastAPI (Python):                 /openapi.json
Spring Boot (Java, springdoc):   /v3/api-docs
Express + swagger-jsdoc:         /api-docs (häufig, aber konfigurierbar)
```

Das Format (OpenAPI JSON/YAML) ist bei allen gleich — nur der Pfad variiert.

---

## Fetch-Trigger & Refresh-Strategie

Alle Fetch-Operationen laufen **asynchron im Background**, niemals im EDT.

| Trigger | Gilt für |
|---|---|
| Jede Minute (Intervall) | Option A + B |
| Build-Event | Option A + B |
| File-Save im Projekt | Option A + B (Schutz gegen veraltete Refs) |
| Manueller Refresh-Button | alle |

**Fallback-Verhalten:** Wenn Quelle nicht erreichbar oder Datei nicht lesbar → letzten erfolgreichen Fetch aus Cache verwenden. Im Tool Window wird ein unauffälliges Status-Icon angezeigt (z.B. kleines Warn-Icon neben dem Environment-Selektor). Kein Modal, kein aufdringlicher Hinweis.

**Cache-Persistenz:** Letzter erfolgreicher Stand wird in `.idea/sonarwhale/cache/{environment}.json` gespeichert, damit er auch nach IDE-Neustart verfügbar ist.

---

## PSI-Rolle (Navigation only)

PSI wird **ausschließlich für Navigation** genutzt, nie als Datenquelle:

- Gutter Icons neben Controller-Methoden/Minimal-API-Handlers (basieren auf OpenAPI-Daten)
- Jump to Definition: Route-String aus OpenAPI → PsiElement im Code finden
- Kein Endpoint-Discovery via PSI

---

## Kern-Datenmodell

```kotlin
data class ApiEndpoint(
    val id: String,                    // stabile ID: "METHOD /normalized/path"
    val method: HttpMethod,            // GET, POST, PUT, DELETE, PATCH, ...
    val path: String,                  // "/api/users/{id}"
    val summary: String?,              // aus OpenAPI description
    val tags: List<String>,            // Controller-Name oder OpenAPI-Tags
    val parameters: List<ApiParameter>,
    val requestBody: ApiSchema?,
    val responses: Map<Int, ApiSchema>,
    val auth: AuthInfo?,
    val source: EndpointSource,        // OPENAPI_SERVER, OPENAPI_FILE, OPENAPI_STATIC
    val psiNavigationTarget: String?,  // für Jump-to-Definition
    val status: EndpointStatus         // ACTIVE, ADDED, MODIFIED, REMOVED
)

data class ApiParameter(
    val name: String,
    val location: ParameterLocation,   // PATH, QUERY, HEADER, COOKIE
    val required: Boolean,
    val schema: ApiSchema?
)

data class ApiSchema(
    val type: String,
    val properties: Map<String, ApiSchema>?,
    val example: Any?
)

data class AuthInfo(
    val type: AuthType,                // NONE, BEARER, API_KEY, BASIC, OAUTH2
    val scheme: String?
)
```

---

## Persistenz & Import/Export

```
.idea/
└── sonarwhale/
    ├── environments.json  ← alle konfigurierten Environments
    ├── cache/
    │   └── {environment}.json  ← letzter erfolgreicher Fetch pro Environment
    ├── snapshot.json      ← letzter bekannter Stand (für Diff)
    └── collections/
        └── *.json         ← manuelle/importierte Collections (Postman v2.1)
```

---

## Manuelle vs. automatisch erkannte Endpoints

Alle Endpoints erscheinen in **einer gemeinsamen Liste**, visuell unterschieden:

- 🔍 Auto-erkannt (aus OpenAPI)
- 📌 Manuell angelegt oder importiert
- Filterbar via Toggle oben im Tool Window
- `REMOVED`-Endpoints bleiben sichtbar mit rotem Icon + durchgestrichen, bis der User sie bestätigt

---

## Technischer Stack

- **Sprache:** Kotlin
- **Build:** Gradle + `intellij {}` Plugin
- **Target:** Rider (primär), IntelliJ IDEA (optional)
- **Min. Platform-Version:** 2024.1
- **C# Backend (ReSharper SDK):** nur noch für PSI-Navigation in C#-Projekten, kein Endpoint-Discovery mehr

---

## Projektstruktur (Ziel nach Migration)

```
sonarwhale/
├── .claude/claude.md                  ← diese Datei
├── build.gradle.kts
├── plugin.xml
├── src/main/kotlin/dev/koellmann/sonarwhale/
│   ├── model/
│   │   ├── ApiEndpoint.kt             ← NEU: OpenAPI-Datenmodell (ersetzt altes PSI-Modell)
│   │   ├── ApiSchema.kt
│   │   ├── SonarwhaleEnvironment.kt       ← NEU: Environment + EnvironmentSource
│   │   └── Enums.kt                   ← UPDATE: EndpointSource, EndpointStatus, ParameterLocation
│   ├── openapi/
│   │   ├── OpenApiFetcher.kt          ← NEU: async Fetch/Read, alle drei Optionen
│   │   ├── OpenApiDiscovery.kt        ← NEU: Auto-Discovery bekannter Pfade
│   │   └── OpenApiParser.kt           ← NEU: JSON/YAML → List<ApiEndpoint>
│   ├── service/
│   │   ├── RouteIndexService.kt       ← NEU: Haupt-Service (ersetzt SonarwhaleService)
│   │   ├── EnvironmentService.kt      ← NEU: Environment-Verwaltung + Persistenz
│   │   ├── SnapshotService.kt         ← NEU (Phase 3): Diff + Cache-Persistenz
│   │   └── PsiNavigationBridge.kt     ← NEU: Route-String → PsiElement (Navigation only)
│   └── ui/
│       ├── RouteToolWindowFactory.kt  ← UPDATE: bestehend
│       ├── EndpointTree.kt            ← UPDATE: auf neues Datenmodell anpassen
│       ├── DetailPanel.kt             ← UPDATE: bestehend, kleiner Anpassungsbedarf
│       ├── RequestPanel.kt            ← UPDATE: bestehend, Headers/Body-UI bereits gut
│       ├── ResponsePanel.kt           ← KEEP: kaum Änderungen nötig
│       └── EnvironmentSelector.kt     ← NEU: Dropdown + Status-Icon
└── src/rider/Sonarwhale.Rider/            ← C# (nur PSI-Navigation für C#-Projekte)
    └── NavigationHelper.cs            ← REDUZIERT: kein Endpoint-Discovery mehr
```

### Bereits vorhandene Dateien (aktueller Stand):

```
src/rider/main/kotlin/com/sonarwhale/
├── model/
│   ├── ApiEndpoint.kt           ← altes PSI-Modell (muss ersetzt werden)
│   └── Enums.kt                 ← zum Teil wiederverwendbar
├── providers/
│   ├── EndpointProvider.kt      ← ENTFERNEN: PSI-Discovery-Interface
│   ├── EndpointProviderRegistry.kt ← ENTFERNEN
│   └── csharp/
│       └── CSharpEndpointProvider.kt ← ENTFERNEN
├── toolwindow/
│   ├── SonarwhaleToolWindowFactory.kt ← KEEP/UPDATE
│   ├── SonarwhalePanel.kt           ← KEEP/UPDATE
│   ├── EndpointTree.kt          ← UPDATE: auf neues Modell anpassen
│   ├── DetailPanel.kt           ← UPDATE: kleinere Anpassungen
│   ├── RequestPanel.kt          ← KEEP: gut ausgebaut (Params-Table, BodyPanel)
│   └── ResponsePanel.kt         ← KEEP: EditorTextField, "Open in Editor"
├── SonarwhaleService.kt             ← ERSETZEN durch RouteIndexService
├── SonarwhaleStateService.kt        ← KEEP: speichert Saved Requests
├── SonarwhaleStartupActivity.kt     ← UPDATE: startet OpenApiFetcher statt PSI-Scan
├── actions/
│   └── OpenInSonarwhaleAction.kt    ← KEEP: funktioniert über cached endpoints
└── collection/
    └── CollectionFormat.kt      ← KEEP: Postman-Export bleibt

src/dotnet/ReSharperPlugin.Sonarwhale/
├── ControllerVisitor.cs         ← ENTFERNEN: kein PSI-Discovery mehr
├── MinimalApiVisitor.cs         ← ENTFERNEN
├── EndpointDetector.cs          ← ENTFERNEN
├── SonarwhaleHost.cs                ← ENTFERNEN: Rider Protocol für Discovery
├── SonarwhaleModel.Generated.cs     ← ENTFERNEN: generiertes Protocol-Modell
├── SonarwhaleDtos.cs                ← ENTFERNEN: PSI-DTOs nicht mehr nötig
└── ISonarwhaleZone.cs               ← bleibt (falls C# PSI-Navigation noch nötig)
```

---

## Threading-Regeln (kritisch für IntelliJ Platform)

- PSI **lesen**: immer in `runReadAction {}` oder `ReadAction.compute {}`
- PSI **schreiben**: immer in `WriteCommandAction.runWriteCommandAction(project) {}`
- UI-Updates: immer im EDT — `ApplicationManager.getApplication().invokeLater {}`
- Background-Tasks: `ProgressManager.getInstance().run(Task.Backgroundable(project, "...") { ... })`
- **Niemals** PSI aus einem Background-Thread ohne ReadAction lesen — crasht die IDE

---

## Bevorzugte APIs (IntelliJ Platform 2024+)

```kotlin
// Services
val myService = project.service<MyService>()       // statt ServiceManager.getService()

// Notifications
NotificationGroupManager.getInstance()
    .getNotificationGroup("Sonarwhale")
    .createNotification("...", NotificationType.INFORMATION)
    .notify(project)

// File-Änderungen beobachten
VirtualFileManager.getInstance().addAsyncFileListener(...)

// PSI-Änderungen beobachten
PsiManager.getInstance(project).addPsiTreeChangeListener(listener, disposable)
```

---

## Coding-Prinzipien

- Clean Architecture: Service-Layer komplett von UI getrennt
- `RouteIndexService` ist der einzige Zustand — UI nur lesend
- Alle Parser/Analyzer sind pure functions, keine Seiteneffekte
- Kotlin idiomatisch: Extensions, Data Classes, Sealed Classes für Status/Enums
- **Async-Prinzip:** Alles was blockieren könnte (Netzwerk, Datei-I/O, Parsing) läuft im Background via Coroutines. Niemals BlockingIO im EDT. UI zeigt Ladezustand statt zu blockieren.
- Coroutines für async (kein `Thread.sleep`, kein BlockingIO im EDT)
- TDD: Tests vor Implementierung, besonders für Parser, Fetcher und Diff-Engine

---

## Roadmap

### Phase 1 — Migration auf OpenAPI-Basis ✅ ABGESCHLOSSEN

**PSI-Discovery-Infrastruktur entfernt:**
- [x] `ControllerVisitor.cs`, `MinimalApiVisitor.cs`, `EndpointDetector.cs` gelöscht
- [x] `SonarwhaleHost.cs`, `SonarwhaleModel.Generated.cs`, `SonarwhaleDtos.cs` gelöscht
- [x] `SonarwhaleModel.Generated.kt` (generiertes Kotlin-Protokoll-Modell) gelöscht — inkl. `SonarwhaleModel.Generated.cs` C#-Gegenstück
- [x] `EndpointProvider.kt`, `EndpointProviderRegistry.kt`, `CSharpEndpointProvider.kt` gelöscht
- [x] `providers/python/` — alle 7 Python PSI-Provider gelöscht
- [x] `SonarwhaleService.kt` gelöscht (ersetzt durch `RouteIndexService`)
- [x] `plugin.xml` bereinigt: kein EP für endpointProvider, keine Rd-Abhängigkeit

**Neues OpenAPI-Datenmodell: ✅**
- [x] `ApiEndpoint.kt` — OpenAPI-Modell: path, method, summary, tags, parameters, requestBody, responses, auth, source, psiNavigationTarget, status
- [x] `ApiSchema.toJsonTemplate()` — generiert JSON-Template aus Schema (für Body-Vorbefüllung)
- [x] `SonarwhaleEnvironment.kt` + `EnvironmentSource` (ServerUrl / FilePath / StaticImport)
- [x] `Enums.kt` — HttpMethod, ParameterLocation, AuthType, EndpointSource, EndpointStatus
- [x] `SavedRequest.kt` — mehrere benannte Requests pro Endpoint (isDefault-Flag)
- [x] `Environment.kt` — Umgebungsvariablen-Map ({{varName}}-Syntax)

**OpenAPI-Infrastruktur: ✅**
- [x] `OpenApiParser.kt` — vollständiger OpenAPI 3.x Parser (Pfade, Parameter, Schemas, Auth, $ref-Auflösung bis Tiefe 5)
- [x] `OpenApiDiscovery.kt` — Auto-Discovery bekannter Pfade (ASP.NET Core, FastAPI, Spring Boot, Express)
- [x] `OpenApiFetcher.kt` — async Fetch für alle drei Quell-Optionen; Fallback auf Cache bei Fehler

**Services: ✅**
- [x] `RouteIndexService.kt` — Haupt-Service mit VFS-FileListener (debounced 500ms), Listener-Pattern, FetchStatus-Enum
- [x] `EnvironmentService.kt` — Environment-CRUD, Persistenz in `.idea/sonarwhale/environments.json`, Cache in `.idea/sonarwhale/cache/{id}.json`
- [x] `SonarwhaleStateService.kt` — SavedRequests + Environments (Variablen) + baseUrl + resolveVariables()

**UI: ✅**
- [x] `EndpointTree.kt` — tags-Gruppierung, path+method-Rendering, REMOVED-Strikethrough, Context-Menü
- [x] `DetailPanel.kt` — Header mit method/path/tags/summary/auth-Badge, showEndpoint + showController
- [x] `RequestPanel.kt` — ParamsTable, HeadersTable, BodyPanel, computed URL mit Env-Variablen-Auflösung, Saved Requests
- [x] `SonarwhalePanel.kt` — Toolbar (Refresh/Re-Scan), Suche, Env-Selector, RouteIndexService-Integration
- [x] `ParamsTablePanel.kt` — JBTable mit enabled/key/value/description
- [x] `BodyPanel.kt` — none/form-data/raw/binary mit CardLayout und EditorTextField
- [x] `EnvironmentSettingsPanel.kt` + `SonarwhaleSettingsDialog.kt` — Environment + Variablen-Verwaltung
- [x] `CollectionFormat.kt` — Postman v2.1 Export (tags statt controllerName, path statt route)

**Bereits vorher vorhanden und behalten:**
- [x] HTTP-Client / Request senden (java.net.http, `SwingWorker`)
- [x] Response-Anzeige mit JSON-Highlighting (`ResponsePanel` mit `EditorTextField`)
- [x] Response "Open in Editor" (Scratch-File)
- [x] `OpenInSonarwhaleAction.kt` — "Open in Sonarwhale" Kontext-Menü (läuft über psiNavigationTarget, bis Phase 4 no-op)
- [x] `SonarwhaleGutterService.kt` — Gutter-Icons (laufen über psiNavigationTarget, bis Phase 4 no-op)

### Phase 2 — Fetch-Trigger & Refresh

- [x] File-Save Trigger via `BulkFileListener` (JSON/YAML), debounced 500ms — bereits in RouteIndexService
- [x] Manueller Refresh-Button und Re-Scan im Tool Window
- [x] Build-Event Trigger (MessageBus / CompileContext)
- [x] 1-Minuten-Intervall (Alarm/Coroutine-Loop)

### Phase 3 — Diff & Snapshots

- [ ] `SnapshotService.kt` — Diff nach jedem Refresh
- [ ] Delta-Erkennung: added / modified / removed (ID-basiert: `"METHOD /path"`)
- [ ] REMOVED-Endpoints im Tree anzeigen (Rendering bereits vorbereitet in EndpointTree)
- [ ] Diff-Tab im Detail-Panel
- [ ] Breaking-Change-Badge am Tool Window Icon

### Phase 4 — Gutter Icons & Jump-to-Source (Regex-based, kein PSI) ✅ ABGESCHLOSSEN

- [x] `LanguageScanner` Interface + `ScanMatch` data class (`LanguageScanner.kt`)
- [x] `CSharpScanner` — vollständiger ASP.NET Core Scanner (Attribute-Routing, Minimal API, AcceptVerbs, tilde-override, named-param fix)
- [x] `SonarwhaleGutterService` — thin dispatcher, delegiert an registrierte `LanguageScanner`-Implementierungen
- [x] `SourceLocationService` — on-demand Scan nutzt `isSupported(ext)` + `scanLines(lines, ext)` statt hardcoded `"cs"`
- [x] Gutter Icons aktiv für alle registrierten Scanner-Sprachen
- [x] Jump-to-Source: Endpoint → Controller-Methode (register + navigate)
- [x] Klick-Aktion: öffnet Tool Window, führt Default-Request aus (oder selektiert Endpoint)

### Phase 5 — Weitere Sprachen & Erweiterungen

**Language Scanner Infrastructure (Grundgerüst) ✅**
- [x] `LanguageScanner` Interface — erweiterbar für beliebige Sprachen ohne Änderung am Core
- [x] `CSharpScanner` — Referenz-Implementierung (ASP.NET Core)

**Weitere Scanner (noch nicht implementiert):**
- [ ] `PythonScanner` — FastAPI / Flask (`@app.get(...)`, `@router.post(...)`)
- [ ] `JavaScanner` — Spring Boot (`@GetMapping`, `@RequestMapping`, `@RestController`)

**Sonstiges:**
- [ ] Auth für den Swagger-Endpunkt selbst (Header/Token beim Fetch)
- [ ] Import/Export: Postman Collection Import

---

## Was noch nicht entschieden ist

- Finaler Name (Kandidaten: Blip, Sonarwhale)
- Monetarisierungsmodell (Freemium / kostenlos / Marketplace-Paid)
- Standalone App als langfristiges Ziel (nach Plugin-MVP)
- Ob PSI-Navigation (Phase 4) überhaupt ins MVP kommt oder erst nach dem OpenAPI-Kern
