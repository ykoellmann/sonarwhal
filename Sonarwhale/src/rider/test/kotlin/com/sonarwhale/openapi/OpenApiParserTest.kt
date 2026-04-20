package com.sonarwhale.openapi

import com.sonarwhale.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenApiParserTest {

    private val source = EndpointSource.OPENAPI_SERVER

    // -------------------------------------------------------------------------
    // Basic endpoint discovery
    // -------------------------------------------------------------------------

    @Test
    fun `parses single GET endpoint`() {
        val json = """
            {
              "paths": {
                "/api/users": {
                  "get": {
                    "summary": "List users",
                    "tags": ["Users"]
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)

        assertEquals(1, endpoints.size)
        val ep = endpoints[0]
        assertEquals(HttpMethod.GET, ep.method)
        assertEquals("/api/users", ep.path)
        assertEquals("List users", ep.summary)
        assertEquals(listOf("Users"), ep.tags)
    }

    @Test
    fun `parses multiple methods on same path`() {
        val json = """
            {
              "paths": {
                "/api/users": {
                  "get": { "summary": "List" },
                  "post": { "summary": "Create" }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)

        assertEquals(2, endpoints.size)
        val methods = endpoints.map { it.method }.toSet()
        assertTrue(methods.contains(HttpMethod.GET))
        assertTrue(methods.contains(HttpMethod.POST))
    }

    @Test
    fun `id field is METHOD space path`() {
        val json = """
            {
              "paths": {
                "/api/users/{id}": {
                  "get": {}
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)

        assertEquals("GET /api/users/{id}", endpoints[0].id)
    }

    @Test
    fun `trailing slash on path is trimmed`() {
        val json = """
            {
              "paths": {
                "/api/users/": {
                  "get": {}
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)

        assertEquals("/api/users", endpoints[0].path)
        assertEquals("GET /api/users", endpoints[0].id)
    }

    @Test
    fun `uses description when summary is absent`() {
        val json = """
            {
              "paths": {
                "/api/items": {
                  "get": {
                    "description": "Get all items"
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)

        assertEquals("Get all items", endpoints[0].summary)
    }

    @Test
    fun `source is propagated to each endpoint`() {
        val json = """
            {
              "paths": {
                "/api/test": { "get": {} }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)

        assertEquals(EndpointSource.OPENAPI_SERVER, endpoints[0].source)
    }

    // -------------------------------------------------------------------------
    // Parameters
    // -------------------------------------------------------------------------

    @Test
    fun `parses path and query parameters with correct location and required defaults`() {
        val json = """
            {
              "paths": {
                "/api/users/{id}": {
                  "get": {
                    "parameters": [
                      { "name": "id", "in": "path", "schema": { "type": "string" } },
                      { "name": "page", "in": "query", "schema": { "type": "integer" } }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val params = endpoints[0].parameters

        val idParam = params.first { it.name == "id" }
        assertEquals(ParameterLocation.PATH, idParam.location)
        assertTrue(idParam.required, "PATH params default to required=true")

        val pageParam = params.first { it.name == "page" }
        assertEquals(ParameterLocation.QUERY, pageParam.location)
        assertFalse(pageParam.required, "QUERY params default to required=false")
    }

    @Test
    fun `parses header parameter with correct location`() {
        val json = """
            {
              "paths": {
                "/api/data": {
                  "get": {
                    "parameters": [
                      { "name": "X-Api-Version", "in": "header", "required": true, "schema": { "type": "string" } }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val param = endpoints[0].parameters[0]

        assertEquals("X-Api-Version", param.name)
        assertEquals(ParameterLocation.HEADER, param.location)
        assertTrue(param.required)
    }

    @Test
    fun `path-level parameters merged with operation-level, operation wins on conflict`() {
        val json = """
            {
              "paths": {
                "/api/items/{id}": {
                  "parameters": [
                    { "name": "id", "in": "path", "schema": { "type": "string" } },
                    { "name": "locale", "in": "query", "schema": { "type": "string" } }
                  ],
                  "get": {
                    "parameters": [
                      { "name": "id", "in": "path", "schema": { "type": "integer" } }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val params = endpoints[0].parameters

        // Both path and locale should be present
        assertEquals(2, params.size)
        val idParam = params.first { it.name == "id" }
        // Operation-level id should override path-level (integer wins over string)
        assertEquals("integer", idParam.schema?.type)
        assertTrue(params.any { it.name == "locale" })
    }

    @Test
    fun `explicit required false on path parameter respected`() {
        val json = """
            {
              "paths": {
                "/api/test/{id}": {
                  "get": {
                    "parameters": [
                      { "name": "id", "in": "path", "required": false, "schema": { "type": "string" } }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val idParam = endpoints[0].parameters[0]

        assertFalse(idParam.required)
    }

    // -------------------------------------------------------------------------
    // Request body
    // -------------------------------------------------------------------------

    @Test
    fun `parses request body from application json content`() {
        val json = """
            {
              "paths": {
                "/api/users": {
                  "post": {
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": {
                            "type": "object",
                            "properties": {
                              "name": { "type": "string" },
                              "age":  { "type": "integer" }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val body = endpoints[0].requestBody

        assertNotNull(body)
        assertEquals("object", body!!.type)
        assertNotNull(body.properties)
        assertEquals("string", body.properties!!["name"]?.type)
        assertEquals("integer", body.properties!!["age"]?.type)
    }

    // -------------------------------------------------------------------------
    // Schema: $ref resolution
    // -------------------------------------------------------------------------

    @Test
    fun `resolves dollar-ref in request body schema from components`() {
        val json = """
            {
              "paths": {
                "/api/orders": {
                  "post": {
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": { "${'$'}ref": "#/components/schemas/Order" }
                        }
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "Order": {
                    "type": "object",
                    "properties": {
                      "orderId": { "type": "string" },
                      "total":   { "type": "number" }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val body = endpoints[0].requestBody

        assertNotNull(body)
        assertEquals("object", body!!.type)
        assertTrue(body.properties!!.containsKey("orderId"))
        assertTrue(body.properties!!.containsKey("total"))
    }

    // -------------------------------------------------------------------------
    // Schema types: array, object
    // -------------------------------------------------------------------------

    @Test
    fun `parses array schema with items`() {
        val json = """
            {
              "paths": {
                "/api/tags": {
                  "get": {
                    "responses": {
                      "200": {
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "array",
                              "items": { "type": "string" }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val schema = endpoints[0].responses[200]

        assertNotNull(schema)
        assertEquals("array", schema!!.type)
        assertEquals("string", schema.items?.type)
    }

    @Test
    fun `parses object schema with nested properties`() {
        val json = """
            {
              "paths": {
                "/api/profile": {
                  "get": {
                    "responses": {
                      "200": {
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "id":    { "type": "integer" },
                                "email": { "type": "string" }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val schema = endpoints[0].responses[200]

        assertNotNull(schema)
        assertEquals("object", schema!!.type)
        assertEquals("integer", schema.properties!!["id"]?.type)
        assertEquals("string", schema.properties!!["email"]?.type)
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    @Test
    fun `parses bearer auth from global security and components`() {
        val json = """
            {
              "security": [{ "BearerAuth": [] }],
              "paths": {
                "/api/secure": { "get": {} }
              },
              "components": {
                "securitySchemes": {
                  "BearerAuth": { "type": "http", "scheme": "bearer" }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val auth = endpoints[0].auth

        assertNotNull(auth)
        assertEquals(AuthType.BEARER, auth!!.type)
        assertEquals("bearer", auth.scheme)
    }

    @Test
    fun `parses apiKey auth`() {
        val json = """
            {
              "security": [{ "ApiKeyAuth": [] }],
              "paths": {
                "/api/data": { "get": {} }
              },
              "components": {
                "securitySchemes": {
                  "ApiKeyAuth": { "type": "apiKey", "in": "header", "name": "X-API-KEY" }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val auth = endpoints[0].auth

        assertNotNull(auth)
        assertEquals(AuthType.API_KEY, auth!!.type)
    }

    @Test
    fun `parses oauth2 auth`() {
        val json = """
            {
              "security": [{ "OAuth2": [] }],
              "paths": {
                "/api/resource": { "get": {} }
              },
              "components": {
                "securitySchemes": {
                  "OAuth2": {
                    "type": "oauth2",
                    "flows": {
                      "authorizationCode": {
                        "authorizationUrl": "https://example.com/oauth/authorize",
                        "tokenUrl": "https://example.com/oauth/token",
                        "scopes": {}
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val auth = endpoints[0].auth

        assertNotNull(auth)
        assertEquals(AuthType.OAUTH2, auth!!.type)
    }

    @Test
    fun `operation-level security overrides global security`() {
        val json = """
            {
              "security": [{ "BearerAuth": [] }],
              "paths": {
                "/api/public": {
                  "get": {
                    "security": [{ "ApiKeyAuth": [] }]
                  }
                }
              },
              "components": {
                "securitySchemes": {
                  "BearerAuth": { "type": "http", "scheme": "bearer" },
                  "ApiKeyAuth": { "type": "apiKey", "in": "header", "name": "X-API-KEY" }
                }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)
        val auth = endpoints[0].auth

        assertNotNull(auth)
        assertEquals(AuthType.API_KEY, auth!!.type)
    }

    @Test
    fun `no auth when no security defined`() {
        val json = """
            {
              "paths": {
                "/api/open": { "get": {} }
              }
            }
        """.trimIndent()

        val endpoints = OpenApiParser.parse(json, source)

        assertNull(endpoints[0].auth)
    }

    // -------------------------------------------------------------------------
    // Error / edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `invalid JSON returns empty list`() {
        val result = OpenApiParser.parse("not valid json {{{", source)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `missing paths key returns empty list`() {
        val json = """{ "info": { "title": "My API", "version": "1.0" } }"""
        val result = OpenApiParser.parse(json, source)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty paths object returns empty list`() {
        val json = """{ "paths": {} }"""
        val result = OpenApiParser.parse(json, source)
        assertTrue(result.isEmpty())
    }
}
