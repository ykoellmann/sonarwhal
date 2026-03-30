# Routex — IntelliJ Plugin: Planung & Architektur

## Projektziel

Routex ist ein JetBrains IDE-Plugin, das API-Endpoints automatisch aus dem Source Code erkennt und direkt in der IDE testbar macht. Es soll die Lücke zwischen Code-Entwicklung und API-Testing schließen — kein manuelles Pflegen von Postman-Collections mehr, kein Wechsel zwischen Tools.

Der zentrale USP gegenüber bestehenden Tools wie Postman oder dem eingebauten JetBrains HTTP Client:
- **Automatische Erkennung** von Endpoints durch Source-Code-Analyse (PSI), nicht durch manuelles Anlegen
- **Live-Update** bei Dateiänderungen — kein Build, kein Server nötig
- **Diff-View** wenn sich ein Endpoint ändert (Route, Parameter, Body-Schema)
- **Vollständige IDE-Integration** inkl. Jump-to-Source

Swagger/OpenAPI wird bewusst **nicht** als primäre Datenquelle verwendet, da es einen laufenden Build voraussetzt und damit Live-Update, Source-Navigation und feingranulares Diff unmöglich macht. Swagger kann optional als Enrichment-Layer ergänzt werden.

---

## Ziel-IDEs & Sprachen

**MVP:** JetBrains Rider — C# / ASP.NET Core

**Langfristig (Provider-Architektur):**
- IntelliJ IDEA — Java (Spring Boot)
- PyCharm — Python (Flask, FastAPI)
- WebStorm — TypeScript (Express, NestJS)
- GoLand — Go (Gin, Echo)

---

## Architektur-Überblick

### Rider ist ein Sonderfall

Rider ist intern zweigeteilt:
- **Frontend** (JVM / Kotlin) — IDE-Oberfläche, Tool Windows, UI
- **Backend** (C# / .NET, ReSharper) — C#-Code-Analyse, PSI für C#

Die beiden Prozesse kommunizieren über ein generiertes **Rider Protocol** (Kotlin ↔ C#-Modell). Für C#-Endpoint-Erkennung ist zwingend ein C#-Backend-Teil nötig. Alle anderen Sprachen (Python, Java, TypeScript) laufen direkt im Kotlin-Frontend via IntelliJ PSI.

### Schichtenarchitektur

```
┌─────────────────────────────────────────────────────┐
│              Tool Window UI (Kotlin)                 │
├─────────────────────────────────────────────────────┤
│            EndpointProviderRegistry                  │
├────────────┬───────────┬────────────┬───────────────┤
│  C#        │  Python   │   Java     │  TypeScript   │
│  Provider  │  Provider │  Provider  │  Provider     │
├────────────┴───────────┴────────────┴───────────────┤
│  ReSharper Backend  │  IntelliJ PSI (direkt Kotlin)  │
│  (nur C#)           │  (alle anderen Sprachen)       │
└─────────────────────┴──────────────────────────────-┘
```

### Provider-Abstraktion

Jede Sprache implementiert dasselbe Interface:

```kotlin
interface EndpointProvider {
    fun canHandle(file: PsiFile): Boolean
    fun extractEndpoints(file: PsiFile): List<ApiEndpoint>
    val language: SupportedLanguage
}
```

Provider werden per **IntelliJ Extension Point** registriert — in `plugin.xml` bzw. in sprachspezifischen optionalen Configs. Das ermöglicht Community-Contributions ohne den Core anzufassen.

---

## Datenmodell

### Enums

```kotlin
enum class SupportedLanguage {
    CSHARP, PYTHON, JAVA, TYPESCRIPT, GO
}

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
}

enum class ParameterSource {
    PATH, QUERY, BODY, HEADER, FORM
}

enum class AuthType {
    BEARER, API_KEY, BASIC, NONE
}
```

### ApiEndpoint

```kotlin
data class ApiEndpoint(
    // Identität
    val id: String,                         // Stabiler Hash für Diff-Tracking
    val httpMethod: HttpMethod,
    val route: String,                      // Vollständig zusammengesetzt: "/api/users/{id}"
    val rawRouteSegments: List<String>,     // Für Nachvollziehbarkeit der Zusammensetzung

    // Source-Location (für Jump-to-Source)
    val filePath: String,
    val lineNumber: Int,
    val controllerName: String?,            // null bei Minimal APIs
    val methodName: String,
    val language: SupportedLanguage,

    // Parameter
    val parameters: List<ApiParameter>,

    // Auth
    val auth: AuthInfo?,

    // Responses
    val responses: List<ApiResponse>,

    // Tracking-Metadata
    val meta: EndpointMeta
)

data class ApiParameter(
    val name: String,
    val type: String,
    val source: ParameterSource,
    val required: Boolean,
    val defaultValue: String? = null,
    val schema: ApiSchema? = null           // Nur wenn source == BODY
)

data class ApiSchema(
    val typeName: String,
    val properties: List<ApiSchemaProperty>,
    val isArray: Boolean = false,
    val isNullable: Boolean = false
)

data class ApiSchemaProperty(
    val name: String,
    val type: String,
    val required: Boolean,
    val nestedSchema: ApiSchema? = null,    // Max. Tiefe 3 — Schutz vor zirkulären Referenzen
    val validationHints: List<String> = emptyList()  // z.B. ["maxLength:100", "email"]
)

data class AuthInfo(
    val required: Boolean,
    val type: AuthType?,
    val policy: String? = null,
    val roles: List<String> = emptyList(),
    val inherited: Boolean = false          // true wenn vom Controller geerbt
)

data class ApiResponse(
    val statusCode: Int?,                   // null wenn nicht ableitbar
    val schema: ApiSchema? = null,
    val description: String? = null
)

data class EndpointMeta(
    val contentHash: String,                // Hash für Änderungs-Erkennung
    val detectedAt: Long,
    val lastModifiedAt: Long,
    val analysisConfidence: Float,          // 0.0 - 1.0
    val analysisWarnings: List<String>      // z.B. "Dynamische Route, evtl. unvollständig"
)
```

---

## C# Endpoint-Erkennung (ReSharper Backend)

### Was erkannt wird

| Was | Erkennbarkeit | Wie |
|---|---|---|
| HTTP-Methode per Attribut | ~95% | `[HttpGet]`, `[HttpPost]` etc. |
| Statische Route | ~90% | `[Route]` + Action-Attribut zusammensetzen |
| Pfad-Parameter | ~90% | Aus Route-Template `{id}` |
| Query-Parameter | ~85% | `[FromQuery]`-Attribute |
| Body-Typ (Name) | ~90% | `[FromBody] CreateUserRequest` |
| Body-Schema (Properties) | ~80% | Rekursive Typ-Auflösung |
| Auth-Attribute | ~85% | `[Authorize]`, `[AllowAnonymous]` |
| Minimal API (einfach) | ~80% | `app.MapGet(...)` |
| Convention-based Routing | ~40% | Partial Support |
| Dynamische Routen | <30% | Out of Scope MVP |
| Reflection-basiert | <10% | Out of Scope |

### Wichtige ASP.NET Core Patterns

**Controller-basiert:**
```csharp
[ApiController]
[Route("api/[controller]")]
public class UsersController : ControllerBase
{
    [HttpGet("{id}")]
    [Authorize]
    public ActionResult<UserDto> GetUser(int id) { }

    [HttpPost]
    public ActionResult<UserDto> CreateUser([FromBody] CreateUserRequest request) { }
}
```

**Minimal API:**
```csharp
app.MapGet("/api/users/{id}", (int id) => { });
app.MapPost("/api/users", (CreateUserRequest req) => { });
```

### Was Routex bewusst NICHT löst (MVP)

- Reflection-generierte Routen
- Dynamische String-Interpolation in Pfaden (`$"/api/{version}/users"`)
- Globale Middleware-Auth (zu vage zuordbar)
- Route-Versioning aus Konfiguration

Diese Einschränkungen werden im UI transparent kommuniziert (`analysisWarnings`).

---

## Frontend — Tool Window UI

### Platzierung

Laut JetBrains UI Guidelines: **horizontales Tool Window** (unten oder rechts), da Routex ein Master-Detail-Layout hat — keine vertikale Tree-only-Ansicht.

### Layout

```
┌─────────────────────────────────────────────────────────┐
│  Toolbar: [▶ Run] [↺ Refresh] [🔍 Filter] [⚙ Env]      │
├───────────────────┬─────────────────────────────────────┤
│  Endpoint-Tree    │  Detail-Panel                        │
│                   │                                      │
│  ▼ UsersController│  POST  /api/users                    │
│    GET  /users    │  ─────────────────────────────────── │
│  ► POST /users    │  [Request] [Response] [History] [Diff│
│    GET  /users/id │                                      │
│                   │  Headers                             │
│  ▼ ProductsCtrl   │  Authorization: Bearer ...           │
│    GET  /products │                                      │
│    POST /products │  Body                                │
│                   │  { "email": "", "name": "" }         │
└───────────────────┴─────────────────────────────────────┘
```

### Komponenten-Auswahl

| Element | JetBrains Komponente |
|---|---|
| Endpoint-Baum | `com.intellij.ui.treeStructure.Tree` |
| Splitter Links/Rechts | `com.intellij.ui.OnePixelSplitter` |
| Tabs Rechts | `com.intellij.ui.components.JBTabbedPane` |
| Body-Editor (JSON) | `EditorTextField` mit JSON-Language |
| Toolbar | `ActionToolbar` via `ActionManager` |
| Scrollbare Bereiche | `com.intellij.ui.components.JBScrollPane` |
| Farben & Borders | `JBUI.Borders`, `JBColor` — niemals hardcoded |

### UI-Regeln aus den Guidelines

- **Empty State**: Wenn keine Endpoints erkannt wurden, Hinweis zeigen statt leer lassen
- **Tool Window Button**: Standardmäßig versteckt, nur einblenden wenn Endpoints erkannt wurden
- **Badge am Icon**: Farbiges Badge wenn Routex Änderungen erkennt (Diff verfügbar)
- **HTTP-Methode als Badge**: Farbe je Methode — GET grün, POST blau, PUT orange, DELETE rot

### Detail-Panel Tabs

- **Request**: Headers, Path/Query-Parameter-Felder, Body-Editor mit automatisch generiertem Template aus `ApiSchema`
- **Response**: Status-Code, Body (formatiert), Headers, Dauer
- **History**: Liste vergangener Requests mit Timestamps
- **Diff**: Erscheint nur wenn Änderung erkannt — Vorher/Nachher der Endpoint-Signatur

---

## Projekt-Setup

Das offizielle JetBrains Template verwenden:

```bash
dotnet new install JetBrains.ReSharper.SamplePlugin.*.nupkg
dotnet new resharper-rider-plugin --name RouteX
```

### Projektstruktur

```
routex/
├── src/main/kotlin/com/routex/     ← Frontend (Kotlin)
│   ├── toolwindow/
│   │   ├── RouteXToolWindowFactory.kt
│   │   ├── EndpointTree.kt
│   │   └── DetailPanel.kt
│   ├── providers/
│   │   ├── EndpointProvider.kt         (Interface)
│   │   ├── EndpointProviderRegistry.kt
│   │   └── csharp/
│   │       └── CSharpEndpointProvider.kt
│   ├── model/
│   │   ├── ApiEndpoint.kt
│   │   ├── ApiParameter.kt
│   │   ├── ApiSchema.kt
│   │   ├── AuthInfo.kt
│   │   └── Enums.kt
│   └── protocol/
│       └── RouteXProtocol.kt
├── protocol/
│   └── model.kt                    ← Rider Protocol Definition
├── src/rider/RouteX.Rider/         ← Backend (C#)
│   ├── EndpointDetector.cs
│   ├── ControllerVisitor.cs
│   └── MinimalApiVisitor.cs
├── src/main/resources/
│   └── META-INF/plugin.xml
└── build.gradle.kts
```

### plugin.xml Grundstruktur

```xml
<idea-plugin>
    <id>com.routex.plugin</id>
    <name>Routex</name>
    <vendor>Yannik Köllmann</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends optional="true" config-file="routex-rider.xml">
        com.intellij.modules.rider
    </depends>

    <extensionPoints>
        <extensionPoint name="endpointProvider"
                        interface="com.routex.providers.EndpointProvider"/>
    </extensionPoints>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="RouteX"
                    anchor="bottom"
                    factoryClass="com.routex.toolwindow.RouteXToolWindowFactory"
                    icon="/icons/routex.svg"
                    conditionClass="com.routex.RouteXCondition"/>
    </extensions>
</idea-plugin>
```

---

## Implementierungs-Roadmap

### Phase 1 — MVP (Ziel: lauffähiges Plugin in Rider)

1. **Projekt-Setup** — Template, Gradle, Plugin.xml
2. **Datenmodell** — alle Kotlin-Datenklassen und Enums
3. **Provider-Interface + Registry** — Extension Point Mechanismus
4. **C# Backend** — Erkennung von Controller-basierten Endpoints (ReSharper PSI)
5. **Rider Protocol** — Datenaustausch Backend → Frontend
6. **Tool Window** — Basis-Layout mit Splitter, Tree und Tabs
7. **Endpoint-Tree** — Gruppiert nach Controller, mit HTTP-Method-Badges
8. **Request-Builder** — Automatisches Template aus ApiSchema
9. **HTTP Client** — Request senden, Response anzeigen
10. **File Watcher** — Neu-Scan bei .cs-Dateiänderungen

### Phase 2 — Diff & Polish

- Signatur-Diff-Engine (contentHash-Vergleich)
- Diff-Tab im Detail-Panel
- Badge am Tool Window Icon bei Änderungen
- Minimal API Support verbessern
- Performance: Incremental Parsing statt Full Scan

### Phase 3 — Erweiterungen

- Auth-Scripts (Bearer Token, OAuth)
- Environment Variables (dev/staging/prod URLs)
- Python Provider (Flask, FastAPI)
- Java Provider (Spring Boot)
- Swagger als optionaler Enrichment-Layer

---

## Wichtige Referenzen

- **Plugin Template**: https://github.com/JetBrains/resharper-rider-plugin
- **IntelliJ SDK Docs**: https://plugins.jetbrains.com/docs/intellij/
- **ReSharper SDK Docs**: https://www.jetbrains.com/help/resharper/sdk/
- **Rider Plugin Guide**: https://plugins.jetbrains.com/docs/intellij/rider.html
- **UI Guidelines**: https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html
- **Referenzprojekt (Architektur)**: https://github.com/JetBrains/resharper-unity
