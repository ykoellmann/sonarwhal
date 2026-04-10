# Routex — IntelliJ Plugin: Planung & Architektur

## Projektziel

Routex ist ein JetBrains IDE-Plugin, das API-Endpoints automatisch aus dem Source Code erkennt und direkt in der IDE testbar macht. Es soll die Lücke zwischen Code-Entwicklung und API-Testing schließen — kein manuelles Pflegen von Postman-Collections mehr, kein Wechsel zwischen Tools.

Der zentrale USP gegenüber bestehenden Tools wie Postman oder dem eingebauten JetBrains HTTP Client:
- **Automatische Erkennung** von Endpoints durch Source-Code-Analyse (PSI), nicht durch manuelles Anlegen
- **Live-Update** bei Dateiänderungen — kein Build, kein Server nötig
- **Diff-View** wenn sich ein Endpoint ändert (Route, Parameter, Body-Schema)
- **Vollständige IDE-Integration** inkl. Jump-to-Source und Endpoint-Usages

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
    val rawRouteSegments: List<String>,

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
    val schema: ApiSchema? = null
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
    val nestedSchema: ApiSchema? = null,    // Max. Tiefe 3
    val validationHints: List<String> = emptyList()
)

data class AuthInfo(
    val required: Boolean,
    val type: AuthType?,
    val policy: String? = null,
    val roles: List<String> = emptyList(),
    val inherited: Boolean = false
)

data class ApiResponse(
    val statusCode: Int?,
    val schema: ApiSchema? = null,
    val description: String? = null
)

data class EndpointMeta(
    val contentHash: String,
    val detectedAt: Long,
    val lastModifiedAt: Long,
    val analysisConfidence: Float,
    val analysisWarnings: List<String>
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

### Was Routex bewusst NICHT löst (MVP)

- Reflection-generierte Routen
- Dynamische String-Interpolation in Pfaden
- Globale Middleware-Auth
- Route-Versioning aus Konfiguration

Diese Einschränkungen werden transparent via `analysisWarnings` kommuniziert.

---

## Frontend — Tool Window UI

### Layout

```
┌─────────────────────────────────────────────────────────┐
│  Toolbar: [▶ Run] [↺ Refresh] [⟳ Re-Scan] [🔍] [⚙ Env] │
├───────────────────┬─────────────────────────────────────┤
│  Endpoint-Tree    │  Detail-Panel                        │
│                   │                                      │
│  ⭐ Favourites    │  POST  /api/users                    │
│  ▼ UsersCtrl      │  ────────────────────────────────── │
│    GET  /users    │  [Request] [Response] [History]      │
│  ► POST /users    │                                      │
│    GET  /users/id │  Headers                             │
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

### UI-Regeln

- **Empty State**: Kein leerer Baum, sondern Hinweis mit Aktion
- **Badge am Icon**: Bei erkannten Änderungen (Diff verfügbar)
- **HTTP-Methode als Badge**: GET grün, POST blau, PUT orange, DELETE rot
- **Favourites-Sektion**: Angepinnte Endpoints ganz oben im Tree, persistent

---

## Implementierungs-Roadmap

### Phase 1 — MVP ✅ (abgeschlossen)

- ✅ Projekt-Setup, Datenmodell, Provider-Interface + Registry
- ✅ C# Backend — Controller-basierte Endpoint-Erkennung (ReSharper PSI)
- ✅ Rider Protocol — Datenaustausch Backend ↔ Frontend
- ✅ Tool Window — Splitter, Tree, Tabs (inkl. Layout-Fixes)
- ✅ Endpoint-Tree — Gruppiert nach Controller, HTTP-Method-Badges, `EndpointNode`-Wrapper
- ✅ Route-Parameter — Constraint-Syntax (`{id:int}`) korrekt als Pfad-Segment
- ✅ Tabs konditionell — Params-Tab und Body-Tab nur bei Bedarf sichtbar
- ✅ Jump-to-Source — „→ source"-Link im Header + Rechtsklick „Go to Source"
- ✅ Request speichern — Headers + Body pro Endpoint
- ✅ HTTP Client — Request senden, Response anzeigen
- ✅ Response in neuem Editor-Tab öffnen
- ✅ Code → RouteX Navigation — Editor-Kontextmenü springt zum Endpoint im Tool Window

---

### Phase 2 — Stabilisierung & Kernfeatures ✅ (abgeschlossen)

#### 2.1 Re-Scan vs. Refresh — Zwei getrennte Aktionen ✅

Aktuell gibt es nur einen Button. Künftig zwei klar unterschiedliche Aktionen in der Toolbar:

| Aktion | Verhalten | Wann nutzen |
|---|---|---|
| **Refresh** `↺` | Nur geänderte Dateien neu analysieren (Hash-Cache) | Normaler Workflow |
| **Re-Scan** `⟳` | Komplette Neuanalyse aller Dateien, Cache leeren | Debugging, wenn etwas nicht stimmt |

Re-Scan ignoriert bewusst alle Caches und ist die Escape-Hatch für Fälle wo der inkrementelle Scan etwas verpasst hat.

#### 2.2 Persistenz ✅

Vollständig implementiert via `PersistentStateComponent` + `routex.xml`. Speichert pro Endpoint-ID eine JSON-Liste von `SavedRequest`-Objekten inkl. Headers, Body, Params, bodyMode, bodyContentType. Legacy-Format automatisch migriert.

#### 2.3 Endpoint-Usages — Navigation via „Using"

Analog zu „Find Usages" in Rider: Routex registriert einen **`FindUsagesProvider`** oder **`LineMarkerProvider`**, der Stellen im Code markiert, wo ein Endpoint aufgerufen wird (z.B. `HttpClient.GetAsync("/api/users/...")`, `_client.PostAsync(...)`, oder typisierte `IApiClient`-Aufrufe).

Von diesen Aufrufstellen kann man direkt in das Tool Window zum entsprechenden Endpoint springen — genau wie wenn eine Methode irgendwo verwendet wird und man per Gutter-Icon oder „Find Usages" dahin navigiert.

**Implementierungsansatz:**
- `LineMarkerProvider` für HttpClient-Aufrufe mit erkennbaren Route-Strings
- Matching: Route-String aus dem Aufruf wird gegen bekannte Endpoints gematcht
- Gutter-Icon erscheint neben dem Aufruf → Klick öffnet RouteX und selektiert den Endpoint
- Lazy: Usages werden nur berechnet wenn Tree-Node expandiert wird (Performance)

#### 2.4 File Watcher — performant, ein globaler Watcher ✅

**Wichtig**: Kein separater `VirtualFileListener` pro Datei. Stattdessen ein einziger globaler Listener:

```kotlin
VirtualFileManager.getInstance().addVirtualFileListener(object : VirtualFileListener {
    override fun contentsChanged(event: VirtualFileChangeEvent) {
        if (event.file.extension == "cs") {
            RouteXService.getInstance(project).scheduleRefresh(event.file.path)
        }
    }
}, disposable)
```

`scheduleRefresh` debounced (z.B. 500ms Delay via `Alarm`) — damit bei mehreren schnellen Saves nicht sofort N Scans angestoßen werden. Nur die geänderten Dateien werden in die Refresh-Queue eingetragen.

---

### Phase 2.5 — Hierarchische Endpoint-Architektur (Schema → Request) ✅

**Status: Implementiert**

Ein erkannter Endpoint (`ApiEndpoint`) ist ein Schema/Template — nicht direkt ausführbar. Darunter liegen konkrete `SavedRequest`-Objekte, die benannt, befüllt und ausgeführt werden.

#### Datenmodell

```kotlin
// model/SavedRequest.kt — benannter, ausführbarer Request unter einem Schema
data class SavedRequest(
    val id: String,
    val name: String,           // "Default", "Happy Path", ...
    val isDefault: Boolean,     // wird vom Gutter-Icon ausgeführt
    val headers: String,        // JSON array of NameValueRow
    val body: String,
    val bodyMode: String,
    val bodyContentType: String,
    val paramValues: Map<String, String>
)
```

#### Tree-Struktur

```
▼ UsersController
    ▼ POST /api/users          ← EndpointNode (Schema)
        📄 Default  ★          ← SavedRequestNode (default, hat ★-Badge)
        📄 Happy Path          ← SavedRequestNode
      [+ New Request]
    ▼ GET /api/users/{id}
        [+ New Request]        ← noch kein Request angelegt
```

#### Verhalten

| Aktion | Verhalten |
|---|---|
| Klick auf Endpoint-Node | Öffnet Default/ersten Request |
| Doppelklick auf Endpoint-Node | Öffnet Dialog zum Anlegen eines neuen Requests |
| Klick auf `+ New Request` | Öffnet Dialog (Name eingeben) |
| Rechtsklick → Set as Default | Setzt diesen Request als Default (Gutter-Icon führt ihn aus) |
| Rechtsklick → Remove | Entfernt den Request |
| Gutter-Icon Klick (default request vorhanden) | Öffnet Tool Window + führt Default-Request direkt aus |
| Gutter-Icon Klick (kein Request) | Öffnet Tool Window + navigiert zum Endpoint |

#### Persistenz

`RouteXStateService` speichert pro Endpoint-ID eine JSON-Liste von `SavedRequest`-Objekten. Legacy-Format (einzelnes JSON-Objekt) wird automatisch migriert → `SavedRequest("Default", isDefault=true)`.

#### Gutter-Icon Zeilen-Fix

`ControllerVisitor.cs`: Zeile wird jetzt auf das `NameIdentifier` des Methods gesetzt (statt auf den ersten `[HttpGet]`-Attribut-Offset), damit das Gutter-Icon auf der Methodensignatur-Zeile erscheint.

---

### Phase 3 — IDE-Integration & UX

#### 3.1 Gutter Icons (Play-Button) ✅

Markup-basierte Gutter-Icons auf den Controller-Methodenzeilen. Klick führt Default-Request direkt aus oder öffnet das Tool Window wenn kein Request existiert. Tooltip zeigt Request-Namen. Zeilennummer-Erkennung via `File.ReadAllLines` Text-Scan (robust gegenüber C# 10 file-scoped namespaces).

---

### Phase 3A — Nächste Implementierungen (priorisiert)

#### 3A.1 Environment Variables ✅

**Was**: `{{variableName}}`-Platzhalter in URL, Headers und Body werden zur Laufzeit durch Werte aus dem aktiven Environment ersetzt.

**Warum zuerst**: Ohne Environments ist das Tool für echte Projekte kaum nutzbar — Token, Base-URLs, IDs müssen bei jedem Request manuell angepasst werden. Dies ist das Feature mit dem größten Mehrwert nach den Gutter-Icons.

**Datenmodell** (Erweiterung `RouteXStateService`):
```kotlin
data class Environment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,                    // "Local", "Staging", "Production"
    val variables: Map<String, String>   // "token" → "abc123", "baseUrl" → "http://localhost:5000"
)
// State bekommt: environments: List<Environment>, activeEnvironmentId: String?
```

**UI**:
- Toolbar-Dropdown rechts: `[⚙ Local ▼]` — zeigt aktives Environment, Klick öffnet Dropdown mit allen Envs + "Manage Environments..."
- "Manage Environments" öffnet Dialog: Links Environment-Liste, rechts Key-Value-Tabelle
- Beim Senden: `{{var}}` in URL, Headers, Body wird durch den Wert aus dem aktiven Env ersetzt. Nicht aufgelöste Variablen bleiben als `{{var}}` sichtbar (kein Fehler, sichtbarer Hinweis)
- Im URL-Feld: `{{var}}` wird farblich hervorgehoben (ähnlich wie in Postman — gelblicher Hintergrund wenn aufgelöst, rötlich wenn unbekannt)

**Persistenz**: Environments in `routex.xml`, aktives Environment als ID gespeichert.

**Scope**: Environments sind projekt-global (nicht per Endpoint).

---

#### 3A.2 Auth-Tab im Request Panel 🔴 Nächste Implementierung

**Was**: Dedizierter "Auth"-Tab neben Params/Headers/Body im Request-Panel mit häufigen Auth-Typen, die automatisch den korrekten Header setzen.

**Warum**: Aktuell muss Bearer-Token als roher Header `Authorization: Bearer xyz` eingegeben werden. Das ist umständlich und fehleranfällig.

**Auth-Typen**:
| Typ | Eingabe | Erzeugter Header |
|---|---|---|
| None | — | — |
| Bearer Token | Token-Feld | `Authorization: Bearer <token>` |
| Basic Auth | Username + Password | `Authorization: Basic <base64>` |
| API Key | Key-Name + Wert + Position (Header/Query) | Header oder Query-Param |

**UI**: Radio-Gruppe für Typ-Auswahl, darunter typ-spezifische Eingabefelder (CardLayout). Token-Felder unterstützen `{{variablen}}` aus Environment.

**Persistenz**: Auth-Config wird als Teil des `SavedRequest` gespeichert — neues Feld `authMode: String` + `authValues: Map<String, String>`.

**Kein Folder-Inheritance im MVP**: Die komplexe "Auth vererbt sich nach unten"-Logik aus Phase 3.3 kommt erst später. Zunächst nur per-Request Auth.

---

#### 3A.3 Favourites / Bookmarks 🟡 Mittlere Priorität

**Was**: Endpoints können als Favorit markiert werden. Favoriten erscheinen in einer eigenen Sektion `⭐ Favourites` ganz oben im Tree.

**Warum**: Bei größeren Projekten mit vielen Endpoints ist der Tree unübersichtlich. Favoriten ermöglichen schnellen Zugriff auf die am häufigsten genutzten Endpoints.

**UI**:
- Stern-Icon neben jedem Endpoint-Node im Tree (leer = kein Favorit, gefüllt = Favorit)
- Rechtsklick-Menü: "Add to Favourites" / "Remove from Favourites"
- Ganz oben im Tree: `⭐ Favourites`-Sektion (nur wenn mind. 1 Favorit vorhanden)
- Selektion eines Favoriten springt zu dessen normaler Position im Tree (oder direkt im Detail-Panel)

**Persistenz**: `Set<String>` von Endpoint-IDs in `RouteXStateService`.

**Implementierung**: `EndpointTree` bekommt `FavouritesNode` als ersten Child des Root-Nodes. `RouteXStateService` bekommt `getFavourites()`, `addFavourite(id)`, `removeFavourite(id)`.

---

#### 3A.4 Import (.http & Postman) 🟡 Mittlere Priorität

**Was**: Bestehende Request-Dateien importieren und den erkannten Endpoints zuordnen.

**Datenfluss**: Import liest Requests → matched auf `METHOD /route` gegen erkannte Endpoints → erstellt `SavedRequest`-Einträge im `RouteXStateService`.

**Formate**:

1. **JetBrains `.http`-Format**:
   ```
   ### GetAllUsers
   GET {{baseUrl}}/api/users
   Authorization: Bearer {{token}}
   ```
   Parser: Zeilenbasiert, `###` als Trennzeichen, erste Zeile = Method + URL, danach Headers, dann Body nach Leerzeile.

2. **Postman Collection v2.1 (JSON)**:
   ```json
   { "item": [{ "name": "...", "request": { "method": "GET", "url": {...}, "header": [...], "body": {...} } }] }
   ```

**Matching-Strategie**: `METHOD /route` (normalisiert, ohne Query-Params) gegen erkannte Endpoints. Bei Unklarheit: Dialog zeigt mögliche Matches zum manuellen Zuordnen.

**UI**: Toolbar-Aktion "Import" → Dateiauswahl → Import-Dialog mit Vorschau.

**Interfaces** bereits definiert in `collection/CollectionFormat.kt`.

---

### Phase 3B — IDE-Vertiefung (nach Phase 3A)

#### 3B.1 Body-Validierung via PSI + Annotator

PSI kennt den `[FromBody]`-Typ und dessen Properties (`[Required]`, Typen etc.). Ein `Annotator` prüft den JSON-Body im Request-Editor live dagegen und zeigt Fehler inline.

#### 3B.2 Endpoint Usages — Navigation via „Using" ✅

`RouteXUsageGutterService`: Scannt offene C#-Editoren nach HttpClient-Aufrufen (`GetAsync`, `PostAsync`, `PutAsync`, `DeleteAsync`, `PatchAsync`). Extrahiert den URL-String-Literal, normalisiert ihn zum Pfad und matcht ihn per Route-Regex gegen bekannte Endpoints. Gutter-Icon (🔍) erscheint → Klick öffnet RouteX und selektiert den Endpoint. Kein Protocol-Change nötig — reine Kotlin-Frontend-Implementierung.

#### 3B.3 Auth-Inheritance (Folder-Level)

Auth-Konfiguration auf Controller-Ebene im Tree — erbt sich nach unten auf alle Endpoints des Controllers. Überschreibbar pro Request (Auth-Tab bleibt wie in 3A.2). Analog zu Postman Collection-Level Auth.

#### 3B.4 Lazy Loading (Tree)

Tree lädt zunächst nur Controller-Ebene. Endpoints werden erst beim Expandieren geladen. Relevant ab ~50+ Endpoints.

#### 3B.5 Import

- JetBrains `.http`-Files importieren → Requests werden bestehenden erkannten Endpoints zugeordnet
- Postman Collection v2.1 JSON importieren
- **Import ist wichtiger als Export** — niedrige Einstiegshürde für neue User

---

### Phase 4 — Später / Niedrig priorisiert

- **Diff-View**: Aktuelle vs. letzte gespeicherte Response vergleichen. Braucht `lastResponse: SavedResponse?` in `SavedRequest`
- **Workflow / Request Chaining**: Response eines Requests als Variable in den nächsten einspeisen (z.B. Login-Token → Authorization-Header via `{{response.body.token}}`)
- **OpenAPI als Enrichment-Layer**: Optional als sekundäre Datenquelle — z.B. für Typen die PSI nicht auflösen kann
- **Export**: Postman Collection v2.1, JetBrains `.http`-Format
- **Response-History**: Letzte N Responses pro Request gespeichert, aufrufbar in History-Tab
- **Open in Editor Flag**: Response immer im selben Editor-Tab öffnen (kein neues Tab bei jedem Send)
- **Model-Generierung**: TypeScript/Kotlin/Swift-Interface aus Response-Body generieren
- **Mehr Tree-Filter**: Nach HTTP-Methode, Auth-Requirement, Controller, Tags filtern

---

### Phase 5 — Langfristig

- Python Provider (Flask, FastAPI)
- Java Provider (Spring Boot)
- TypeScript Provider (NestJS, Express)
- Standalone App + lokaler WebSocket-Server für IDE-Sync
- Cloud-Sync (optional, lokal bleibt Default — kein Account-Zwang als USP)
- Multi-IDE Sync via `~/.routex/`

---

## Projekt-Setup

```bash
dotnet new install JetBrains.ReSharper.SamplePlugin.*.nupkg
dotnet new resharper-rider-plugin --name RouteX
```

### Projektstruktur

```
routex/
├── src/main/kotlin/com/routex/
│   ├── toolwindow/
│   │   ├── RouteXToolWindowFactory.kt
│   │   ├── EndpointTree.kt
│   │   └── DetailPanel.kt
│   ├── providers/
│   │   ├── EndpointProvider.kt
│   │   ├── EndpointProviderRegistry.kt
│   │   └── csharp/
│   │       └── CSharpEndpointProvider.kt
│   ├── model/
│   │   ├── ApiEndpoint.kt
│   │   ├── ApiParameter.kt
│   │   ├── ApiSchema.kt
│   │   ├── AuthInfo.kt
│   │   └── Enums.kt
│   ├── services/
│   │   ├── RouteXService.kt
│   │   └── RouteXStateService.kt
│   ├── actions/
│   │   └── OpenInRouteXAction.kt
│   └── protocol/
│       └── RouteXProtocol.kt
├── protocol/
│   └── model.kt
├── src/rider/RouteX.Rider/
│   ├── EndpointDetector.cs
│   ├── ControllerVisitor.cs
│   └── MinimalApiVisitor.cs
├── src/main/resources/META-INF/plugin.xml
└── build.gradle.kts
```

---

## Wichtige Referenzen

- **Plugin Template**: https://github.com/JetBrains/resharper-rider-plugin
- **IntelliJ SDK Docs**: https://plugins.jetbrains.com/docs/intellij/
- **ReSharper SDK Docs**: https://www.jetbrains.com/help/resharper/sdk/
- **Rider Plugin Guide**: https://plugins.jetbrains.com/docs/intellij/rider.html
- **UI Guidelines**: https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html
- **Referenzprojekt (Architektur)**: https://github.com/JetBrains/resharper-unity


