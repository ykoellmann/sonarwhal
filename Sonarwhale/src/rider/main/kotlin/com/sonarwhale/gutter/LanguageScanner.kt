package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint

/** One match: the endpoint this source line corresponds to, and which line the gutter icon goes on. */
data class ScanMatch(val endpoint: ApiEndpoint, val line: Int)

/**
 * Language-specific scanner that maps source-file lines to [ApiEndpoint] matches.
 *
 * Implement this interface to add gutter-icon and source-navigation support for a new language
 * (e.g. Python/FastAPI, Java/Spring Boot). Register the implementation in
 * [SonarwhaleGutterService.scanners].
 *
 * Implementations must be stateless and pure — no I/O, no side effects.
 */
interface LanguageScanner {

    /**
     * File extensions this scanner handles, lowercase, without the leading dot.
     * Examples: `setOf("cs")`, `setOf("py")`, `setOf("java", "kt")`.
     */
    val fileExtensions: Set<String>

    /**
     * Scan [lines] from a single source file and return all (endpoint, icon-line) pairs.
     *
     * @param lines     raw text lines of the source file (no trailing newlines)
     * @param endpoints current OpenAPI endpoint list to match against
     */
    fun scanLines(lines: List<String>, endpoints: List<ApiEndpoint>): List<ScanMatch>
}
