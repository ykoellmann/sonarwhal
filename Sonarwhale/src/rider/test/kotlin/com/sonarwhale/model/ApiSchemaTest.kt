package com.sonarwhale.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiSchemaTest {

    @Test
    fun `string with no example returns empty quoted string`() {
        val schema = ApiSchema(type = "string")
        assertEquals("\"\"", schema.toJsonTemplate())
    }

    @Test
    fun `string with example returns quoted example`() {
        val schema = ApiSchema(type = "string", example = "hello")
        assertEquals("\"hello\"", schema.toJsonTemplate())
    }

    @Test
    fun `integer with no example returns zero`() {
        val schema = ApiSchema(type = "integer")
        assertEquals("0", schema.toJsonTemplate())
    }

    @Test
    fun `integer with example returns example value`() {
        val schema = ApiSchema(type = "integer", example = 42)
        assertEquals("42", schema.toJsonTemplate())
    }

    @Test
    fun `boolean with no example returns false`() {
        val schema = ApiSchema(type = "boolean")
        assertEquals("false", schema.toJsonTemplate())
    }

    @Test
    fun `object with empty properties returns empty braces`() {
        val schema = ApiSchema(type = "object", properties = emptyMap())
        assertEquals("{}", schema.toJsonTemplate())
    }

    @Test
    fun `object with null properties returns empty braces`() {
        val schema = ApiSchema(type = "object", properties = null)
        assertEquals("{}", schema.toJsonTemplate())
    }

    @Test
    fun `object with string property returns valid JSON object structure`() {
        val schema = ApiSchema(
            type = "object",
            properties = mapOf("name" to ApiSchema(type = "string"))
        )
        val result = schema.toJsonTemplate()
        assertEquals("{\n  \"name\": \"\"\n}", result)
    }

    @Test
    fun `array with string items returns bracketed empty string`() {
        val schema = ApiSchema(type = "array", items = ApiSchema(type = "string"))
        assertEquals("[\"\"]", schema.toJsonTemplate())
    }

    @Test
    fun `array with no items returns bracket-null`() {
        val schema = ApiSchema(type = "array", items = null)
        assertEquals("[null]", schema.toJsonTemplate())
    }

    @Test
    fun `depth greater than 4 returns ellipsis`() {
        val schema = ApiSchema(type = "string", example = "should not appear")
        assertEquals("\"...\"", schema.toJsonTemplate(depth = 5))
    }

    @Test
    fun `depth exactly 4 still renders normally`() {
        val schema = ApiSchema(type = "string")
        assertEquals("\"\"", schema.toJsonTemplate(depth = 4))
    }

    @Test
    fun `nested object within object produces correct indentation`() {
        val inner = ApiSchema(
            type = "object",
            properties = mapOf("id" to ApiSchema(type = "integer"))
        )
        val outer = ApiSchema(
            type = "object",
            properties = mapOf("nested" to inner)
        )
        val result = outer.toJsonTemplate()
        val expected = "{\n  \"nested\": {\n    \"id\": 0\n  }\n}"
        assertEquals(expected, result)
    }

    @Test
    fun `unknown type with no example returns null`() {
        val schema = ApiSchema(type = "unknown")
        assertEquals("null", schema.toJsonTemplate())
    }

    @Test
    fun `unknown type with example returns quoted example`() {
        val schema = ApiSchema(type = "unknown", example = "someValue")
        assertEquals("\"someValue\"", schema.toJsonTemplate())
    }

    @Test
    fun `number type with no example returns zero`() {
        val schema = ApiSchema(type = "number")
        assertEquals("0", schema.toJsonTemplate())
    }

    @Test
    fun `boolean with example true returns true`() {
        val schema = ApiSchema(type = "boolean", example = true)
        assertEquals("true", schema.toJsonTemplate())
    }
}
