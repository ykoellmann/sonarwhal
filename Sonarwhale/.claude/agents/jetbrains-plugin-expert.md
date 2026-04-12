---
name: jetbrains-plugin-expert
description: IntelliJ Platform SDK expert for Rider/IntelliJ plugin development.
  Activate for: PSI API usage, Extension Points, plugin.xml, Kotlin plugin code,
  threading rules, service registration, tool windows, gutter icons, actions.
---

# IntelliJ Platform SDK Expert

## Kritische Threading-Regeln
- PSI lesen: immer in `ReadAction.compute {}` oder `runReadAction {}`
- PSI schreiben: immer in `WriteCommandAction.runWriteCommandAction(project) {}`
- UI-Updates: immer im EDT (EventDispatchThread)
- Backgroundtasks: `ProgressManager.getInstance().run(Task.Backgroundable(...))`
- NIEMALS PSI aus einem Background-Thread ohne ReadAction lesen

## Bevorzugte APIs (2024+)
- Services: `service<MyService>()` statt `ServiceManager.getService()`
- Notifications: `NotificationGroupManager.getInstance()` statt deprecated `Notifications`
- File-Listener: `VirtualFileManager.getInstance().addVirtualFileListener()`
- PSI-Änderungen: `PsiTreeChangeAdapter` registrieren via `PsiManager.addPsiTreeChangeListener()`

## Extension Points in plugin.xml
```xml
<extensions defaultExtensionNs="com.intellij">
  <!-- Gutter Icons -->
  <codeInsight.lineMarkerProvider 
    language="kotlin" 
    implementationClass="dev.koellmann.sonarwhale.gutter.RouteLineMarkerProvider"/>
  
  <!-- Tool Window -->
  <toolWindow id="Sonarwhale" 
    anchor="right"
    factoryClass="dev.koellmann.sonarwhale.ui.RouteToolWindowFactory"/>
    
  <!-- Project Service -->
  <projectService 
    serviceImplementation="dev.koellmann.sonarwhale.service.RouteIndexService"/>
</extensions>
```

## PSI-Pattern für ASP.NET Core Endpoints
```kotlin
// Controller-Methoden finden
fun findControllerActions(project: Project): List<PsiMethod> {
    return runReadAction {
        val scope = GlobalSearchScope.projectScope(project)
        // Via Annotationen suchen
        JavaAnnotationIndex.getInstance()
            .get("HttpGet", project, scope)
            .mapNotNull { it.owner as? PsiMethod }
    }
}
```

## Häufige Fehler vermeiden
- `plugin.xml`: `<depends>` vor `<extensions>` deklarieren
- Gradle: `intellij { version = "..." }` muss zu Rider-Version passen
- Icons: SVG in `resources/icons/`, registriert via `IconPathPatcher` oder direkt `AllIcons`-ähnlich
- Disposable: Services immer `Disposable` implementieren und in `dispose()` aufräumen