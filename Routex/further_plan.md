Plan: Request Panel UI Overhaul

Goals

- Params tab → JBTable with columns: ☑ · Key · Value · Description (like Postman)
- Headers tab → same table layout
- Body tab → radio buttons (none / form-data / raw / binary) + full-height editor per mode; raw mode has JSON syntax highlighting via EditorTextField

  ---                                                                                                                                                                                                                               
New files

ParamsTablePanel.kt

Reusable component used for both Params and Headers tabs.

ParamsTablePanel                                                                                                                                                                                                                  
├── JBTable (DefaultTableModel)                           
│   ├── col 0: ☑ (Boolean, ~24px, enables/disables the row)                                                                                                                                                                       
│   ├── col 1: Key   (String, 30%)                                                                                                                                                                                                
│   ├── col 2: Value (String, 40%)                                                                                                                                                                                                
│   └── col 3: Description (String, remaining)                                                                                                                                                                                    
├── Toolbar below table: [+] [-] buttons                                                                                                                                                                                          
└── Auto-appends a blank row when last row's key field is edited

API:                                                                                                                                                                                                                              
fun getRows(): List<NameValueRow>          // enabled rows only, empty key/value filtered                                                                                                                                         
fun setRows(rows: List<NameValueRow>)      // replaces content, always appends one blank row                                                                                                                                      
fun addChangeListener(l: () -> Unit)       // fires on any cell edit (for URL recomputation)                                                                                                                                      
data class NameValueRow(val enabled: Boolean, val key: String, val value: String, val description: String)

BodyPanel.kt

Replaces the current buildBodyTab() inline panel.

BodyPanel                                                                                                                                                                                                                         
├── NORTH: radio strip — ◉ none  ○ form-data  ○ raw [▼ JSON ▾]  ○ binary
└── CENTER: CardLayout                                                                                                                                                                                                            
├── "none"      → JBLabel("No body")                                                                                                                                                                                          
├── "form-data" → ParamsTablePanel                                                                                                                                                                                            
├── "raw"       → EditorTextField (language switches with dropdown)                                                                                                                                                           
└── "binary"    → JButton("Choose file…") + path label

Content type dropdown for raw (affects Content-Type header sent + editor syntax):                                                                                                                                                 
JSON · XML · HTML · Plain Text

EditorTextField approach:
- One instance per language (JSON, XML, text) pre-created in a CardLayout
- Switch inner card when dropdown changes, copy text across
- Language resolved at runtime: Language.findLanguageByID("JSON")?.associatedFileType ?: PlainTextFileType.INSTANCE

API:                                                                                                                                                                                                                              
sealed class BodyContent {                                                                                                                                                                                                        
object None : BodyContent()                                                                                                                                                                                                   
data class FormData(val rows: List<NameValueRow>) : BodyContent()                                                                                                                                                             
data class Raw(val text: String, val contentType: String) : BodyContent()                                                                                                                                                     
data class Binary(val filePath: String) : BodyContent()                                                                                                                                                                       
}

fun getContent(): BodyContent
fun setContent(content: BodyContent)                                                                                                                                                                                              
fun addChangeListener(l: () -> Unit)                      
val isVisible: Boolean  // hide whole panel for GET/HEAD
                                                                                                                                                                                                                                    
---                                                                                                                                                                                                                               
Changed files

RequestPanel.kt

┌─────────────────────────────────────────────────────────────────┬────────────────────────────────┐                                                                                                                              
│                             Current                             │          Replaced by           │
├─────────────────────────────────────────────────────────────────┼────────────────────────────────┤                                                                                                                              
│ paramsPanel: JPanel(GridBagLayout) + paramFields: LinkedHashMap │ paramsTable: ParamsTablePanel  │
├─────────────────────────────────────────────────────────────────┼────────────────────────────────┤                                                                                                                              
│ headersArea: JTextArea + buildHeadersTab()                      │ headersTable: ParamsTablePanel │                                                                                                                              
├─────────────────────────────────────────────────────────────────┼────────────────────────────────┤
│ bodyArea: JTextArea + buildBodyTab()                            │ bodyPanel: BodyPanel           │                                                                                                                              
└─────────────────────────────────────────────────────────────────┴────────────────────────────────┘

- rebuildParamsTab() → paramsTable.setRows(...) seeded from endpoint.parameters
- buildHeadersTemplate() → headersTable.setRows(...) with pre-filled Authorization row if auth required
- buildBodyTemplate() → bodyPanel.setContent(BodyContent.Raw(json, "application/json"))
- sendRequest():
    - Headers: read from headersTable.getRows() instead of raw text parse
    - Body: switch on bodyPanel.getContent():
        - Raw → BodyPublishers.ofString(text) + auto-add Content-Type header
        - FormData → encode as application/x-www-form-urlencoded
        - Binary → BodyPublishers.ofFile(path)
        - None → BodyPublishers.noBody()
- saveRequest() / showEndpoint(): serialize/restore all three panels' state

RouteXStateService.kt

Add fields to SavedRequest:                                                                                                                                                                                                       
data class SavedRequest(
var headers: String = "",           // JSON array of NameValueRow (migrate old "Name: Value" format)                                                                                                                          
var body: String = "",                                                                              
var bodyMode: String = "raw",       // "none" | "form-data" | "raw" | "binary"                                                                                                                                                
var bodyContentType: String = "application/json",                             
var paramValues: Map<String, String> = emptyMap()                                                                                                                                                                             
)

Migration in getSavedRequest: if headers starts with { or is empty, parse as JSON array; otherwise detect old "Name: Value" format and convert.
                                                                                                                                                                                                                                    
---                                                       
Key decisions / constraints

1. EditorTextField for body raw — already resolved in ResponsePanel: use Language.findLanguageByID("JSON")?.associatedFileType ?: PlainTextFileType.INSTANCE, no compile-time com.intellij.json import. Same pattern here, but
   editor is read-write (isViewer = false).
2. JBTable with boolean column — use DefaultTableModel with getColumnClass(0) = Boolean::class.java so the IDE renders a native checkbox without custom renderer.
3. Params tab auto-populate — table rows seeded from endpoint.parameters (PATH + QUERY only, source is the backend). Rows are pre-keyed but editable. User can add extra query params manually (blank rows).
4. Headers param params — completely user-defined; seeded from the endpoint's [FromHeader] params and auth info, rest is up to the user.
5. Tab visibility — Params tab only shown if endpoint has PATH/QUERY params (unchanged). Body tab always shown but defaults to none for GET/HEAD.
6. No form-data persistence for binary — file path is not saved between sessions (security/portability).

  ---                                                                                                                                                                                                                               
Implementation order

1. NameValueRow data class (shared between both panels) — add to new ParamsTablePanel.kt
2. ParamsTablePanel.kt — standalone, no dependencies on the rest
3. BodyPanel.kt — depends on ParamsTablePanel (for form-data card)
4. RouteXStateService.kt — add fields, add migration
5. RequestPanel.kt — wire everything together, update send/save logic
