package com.sonarwhale.openapi

/**
 * Bekannte OpenAPI-Endpunkt-Pfade, geordnet nach Häufigkeit.
 * Werden der Reihe nach ausprobiert, erster 200-OK-Treffer gewinnt.
 */
object OpenApiDiscovery {

    val knownPaths: List<String> = listOf(
        "/swagger/v1/swagger.json",       // ASP.NET Core (Swashbuckle)
        "/openapi/v1.json",               // ASP.NET Core (Microsoft.OpenApi)
        "/openapi.json",                  // FastAPI (Python)
        "/v3/api-docs",                   // Spring Boot (springdoc)
        "/v3/api-docs.yaml",              // Spring Boot YAML
        "/api-docs",                      // Express + swagger-jsdoc
        "/swagger.json",                  // Generisch
        "/swagger/v2/swagger.json",       // Ältere Swashbuckle-Versionen
    )
}
