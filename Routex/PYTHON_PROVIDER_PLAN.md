# Python Endpoint Provider — Implementation Plan

## Architecture Decision

Python analysis runs **entirely in Kotlin** via IntelliJ's Python PSI API.
No additional .NET/ReSharper backend is needed.
Support is **optional**: every Python component is registered in `routex-python.xml`,
which is only loaded when the JetBrains Python plugin (`com.intellij.modules.python`) is installed.

---

## Data Flow

```
.py file changed / project opened
        │
        ▼
PythonScanService (project service, registered in routex-python.xml)
        │  scans all .py files in project scope via PythonAnalyzer
        │  watches VFS for .py changes
        ▼
RouteXService.mergePythonEndpoints(List<ApiEndpoint>)
        │  merges with C# endpoints in `endpoints` property
        ▼
Tool window + gutter icons + find usages (same as C# flow from here)
```

Python endpoints never touch the ReSharper backend or Rider Protocol.

---

## Files

### Create
| File | Purpose |
|------|---------|
| `src/rider/main/resources/META-INF/routex-python.xml` | Optional plugin descriptor — loaded only when Python plugin present |
| `src/rider/main/kotlin/com/routex/providers/python/PythonAnalyzer.kt` | Core PSI analysis: Flask + FastAPI decorator detection, route/param extraction |
| `src/rider/main/kotlin/com/routex/providers/python/PythonScanService.kt` | Project service: full project scan on startup, VFS watcher for .py changes |
| `src/rider/main/kotlin/com/routex/providers/python/PythonEndpointProvider.kt` | `EndpointProvider` impl — returns cached Python endpoints from `RouteXService` per file |
| `src/rider/main/kotlin/com/routex/providers/python/PythonEndpointLineMarkerProvider.kt` | Gutter play-icons on Python endpoint functions via `LineMarkerProviderDescriptor` |
| `src/rider/main/kotlin/com/routex/providers/python/PythonEndpointFindUsagesHandlerFactory.kt` | Intercepts Find Usages on endpoint functions; injects RouteX synthetic occurrence |
| `src/rider/main/kotlin/com/routex/providers/python/RouteXPythonUsageInfo.kt` | `UsageInfo` subclass carrying endpoint metadata for the synthetic find-usages entry |

### Modify
| File | Change |
|------|--------|
| `gradle.properties` | Add `PythonPluginVersion` |
| `build.gradle.kts` | Add `plugin("PythonCore:...")` to `intellijPlatform` dependencies |
| `src/rider/main/resources/META-INF/plugin.xml` | Add `<depends optional="true" config-file="routex-python.xml">` |
| `src/rider/main/kotlin/com/routex/RouteXService.kt` | Add `pythonEndpoints` bucket + `mergePythonEndpoints()` |

---

## Implementation Steps

- [x] **Step 1** — `gradle.properties` + `build.gradle.kts`: add Python plugin dependency
- [x] **Step 2** — `plugin.xml`: declare optional dependency on `routex-python.xml`
- [x] **Step 3** — `routex-python.xml`: register all Python extensions
- [x] **Step 4** — `RouteXService.kt`: add `pythonEndpoints` + `mergePythonEndpoints()`
- [x] **Step 5** — `PythonAnalyzer.kt`: PSI-based Flask + FastAPI endpoint extraction
- [x] **Step 6** — `PythonScanService.kt` + `PythonStartupActivity.kt`: project-wide scan + VFS watcher
- [x] **Step 7** — `PythonEndpointProvider.kt`: `EndpointProvider` returning cached endpoints
- [x] **Step 8** — `PythonEndpointLineMarkerProvider.kt`: gutter icons
- [x] **Step 9** — `RouteXPythonUsageInfo.kt` + `PythonEndpointFindUsagesHandlerFactory.kt`: find usages

---

## Framework Coverage

### Flask (classic)
```python
@app.route("/users", methods=["GET", "POST"])
@bp.route("/users/<int:id>")
```
- Any receiver name (`app`, `bp`, custom Blueprint)
- `methods` keyword arg → multiple HTTP methods → one `ApiEndpoint` per method
- Route param syntax: `<name>`, `<type:name>` → normalised to `{name}`

### Flask 2.0+ / FastAPI shorthand
```python
@app.get("/users")
@router.post("/users/{id}")
```
- Decorator name maps directly to HTTP method
- Route param syntax: `{name}`, `{name:type}` → normalised to `{name}`

### Parameter extraction
- **Path params**: parsed from normalised route template
- **Query params**: function parameters not present in path, with primitive / `Optional[T]` type hint
- **Body params**: parameters annotated with a class name (likely a Pydantic model) → `ParameterSource.BODY`

---

## ID & Hash Strategy (mirrors C#)
- **Endpoint ID**: `SHA256(filePath + ":" + functionTextOffset)` → first 8 chars base64
- **Content hash**: `SHA256(httpMethod + ":" + route)` → first 8 chars base64

---

## Analysis Confidence
| Case | Confidence | Warning |
|------|-----------|---------|
| Static route string literal | `0.8f` | — |
| Route from variable / f-string | `0.5f` | "Dynamic route — path may be incomplete" |
| `methods` not a literal list | `0.6f` | "HTTP methods could not be statically resolved" |

---

## Open Questions (decided)
- Django: **out of scope** for now (structurally different — routes in `urls.py` separate from views)
- Pydantic schema depth: **shallow only** — detect body param by type annotation name, no deep introspection
- Python plugin version: needs manual verification against `ProductVersion=2025.3` in marketplace
