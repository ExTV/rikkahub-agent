package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [sanitizeForGeminiSchema], the allowlist replacement for the old
 * `removeElements` blacklist call on tool `parameters` in
 * [me.rerere.ai.provider.providers.GoogleProvider.buildCompletionRequestBody]. MCP server
 * schemas routinely carry JSON-Schema-only keywords Google's Schema proto 400s on
 * ("Unknown name ...") - these tests pin exactly which keywords survive and how the
 * schema-composition keywords ($ref, oneOf, allOf, array type) get normalized away.
 */
class SchemaSanitizerTest {

    private fun sanitize(json: String) = Json.parseToJsonElement(json).sanitizeForGeminiSchema()

    @Test
    fun `unknown vendor and json-schema-only keys are stripped`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "${'$'}schema": "http://json-schema.org/draft-07/schema#",
              "deprecated": true,
              "x-google-identifier": "foo",
              "x-google-enum-descriptions": ["a", "b"],
              "properties": {}
            }
            """.trimIndent()
        ).jsonObject

        assertEquals("object", result["type"]?.jsonPrimitive?.content)
        assertNull(result["\$schema"])
        assertNull(result["deprecated"])
        assertNull(result["x-google-identifier"])
        assertNull(result["x-google-enum-descriptions"])
    }

    @Test
    fun `enum and format are still stripped`() {
        val result = sanitize(
            """{"type": "object", "properties": {"field": {"type": "string", "format": "email", "enum": ["a", "b"]}}}"""
        ).jsonObject
        val field = result["properties"]!!.jsonObject["field"]!!.jsonObject

        assertEquals("string", field["type"]?.jsonPrimitive?.content)
        assertNull(field["format"])
        assertNull(field["enum"])
    }

    @Test
    fun `local ref to defs is inlined`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "properties": {
                "child": { "${'$'}ref": "#/${'$'}defs/Child" }
              },
              "${'$'}defs": {
                "Child": { "type": "string", "description": "a child" }
              }
            }
            """.trimIndent()
        ).jsonObject

        // $defs itself is not in the allowlist, so it disappears from the output.
        assertNull(result["\$defs"])

        val child = result["properties"]!!.jsonObject["child"]!!.jsonObject
        assertEquals("string", child["type"]?.jsonPrimitive?.content)
        assertEquals("a child", child["description"]?.jsonPrimitive?.content)
        assertNull(child["\$ref"])
    }

    @Test
    fun `cyclic ref falls back to an empty object schema`() {
        val result = sanitize(
            """
            {
              "${'$'}ref": "#/${'$'}defs/Self",
              "${'$'}defs": {
                "Self": { "type": "object", "properties": { "self": { "${'$'}ref": "#/${'$'}defs/Self" } } }
              }
            }
            """.trimIndent()
        ).jsonObject

        val self = result["properties"]!!.jsonObject["self"]!!.jsonObject
        assertEquals("object", self["type"]?.jsonPrimitive?.content)
        assertEquals(1, self.size)
    }

    @Test
    fun `unresolvable non-local ref falls back to an empty object schema`() {
        val result = sanitize("""{"${'$'}ref": "https://example.com/schema.json#/Foo"}""").jsonObject

        assertEquals("object", result["type"]?.jsonPrimitive?.content)
        assertEquals(1, result.size)
    }

    @Test
    fun `type array without null takes the first entry`() {
        val result = sanitize(
            """{"type": "object", "properties": {"field": {"type": ["string", "number"]}}}"""
        ).jsonObject
        val field = result["properties"]!!.jsonObject["field"]!!.jsonObject

        assertEquals("string", field["type"]?.jsonPrimitive?.content)
        assertNull(field["nullable"])
    }

    @Test
    fun `type array with null sets nullable and drops null from type`() {
        val result = sanitize(
            """{"type": "object", "properties": {"field": {"type": ["string", "null"]}}}"""
        ).jsonObject
        val field = result["properties"]!!.jsonObject["field"]!!.jsonObject

        assertEquals("string", field["type"]?.jsonPrimitive?.content)
        assertTrue(field["nullable"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `oneOf is renamed to anyOf and merged with an existing anyOf`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "properties": {
                "field": {
                  "anyOf": [{"type": "string"}],
                  "oneOf": [{"type": "number"}]
                }
              }
            }
            """.trimIndent()
        ).jsonObject
        val field = result["properties"]!!.jsonObject["field"]!!.jsonObject

        assertNull(field["oneOf"])
        val anyOf = field["anyOf"]!!.jsonArray
        assertEquals(2, anyOf.size)
        assertEquals("string", anyOf[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("number", anyOf[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `single-element allOf merges into the parent, parent keys winning`() {
        val result = sanitize(
            """
            {
              "description": "parent wins",
              "allOf": [{"type": "object", "description": "child loses"}]
            }
            """.trimIndent()
        ).jsonObject

        assertEquals("object", result["type"]?.jsonPrimitive?.content)
        assertEquals("parent wins", result["description"]?.jsonPrimitive?.content)
        assertNull(result["allOf"])
    }

    @Test
    fun `single-element allOf wrapping a ref inlines the ref's contents`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "properties": {
                "field": {
                  "description": "a widget id",
                  "allOf": [{ "${'$'}ref": "#/${'$'}defs/WidgetId" }]
                }
              },
              "${'$'}defs": {
                "WidgetId": { "type": "string", "pattern": "^[a-z]+${'$'}", "minLength": 1 }
              }
            }
            """.trimIndent()
        ).jsonObject
        val field = result["properties"]!!.jsonObject["field"]!!.jsonObject

        assertEquals("a widget id", field["description"]?.jsonPrimitive?.content)
        assertEquals("string", field["type"]?.jsonPrimitive?.content)
        assertEquals("^[a-z]+$", field["pattern"]?.jsonPrimitive?.content)
        assertEquals(1L, field["minLength"]?.jsonPrimitive?.content?.toLong())
        assertNull(field["allOf"])
        assertNull(field["\$ref"])
    }

    @Test
    fun `multi-element allOf drops the keyword and keeps the rest of the schema`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "allOf": [{"type": "string"}, {"type": "number"}]
            }
            """.trimIndent()
        ).jsonObject

        assertEquals("object", result["type"]?.jsonPrimitive?.content)
        assertNull(result["allOf"])
    }

    @Test
    fun `nested properties, items and anyOf all recurse`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "properties": {
                "list": {
                  "type": "array",
                  "items": { "type": "string", "format": "email" }
                },
                "either": {
                  "anyOf": [
                    { "type": "string", "deprecated": true },
                    { "type": "number" }
                  ]
                }
              }
            }
            """.trimIndent()
        ).jsonObject

        val properties = result["properties"]!!.jsonObject
        val items = properties["list"]!!.jsonObject["items"]!!.jsonObject
        assertEquals("string", items["type"]?.jsonPrimitive?.content)
        assertNull(items["format"])

        val anyOf = properties["either"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals("string", anyOf[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertNull(anyOf[0].jsonObject["deprecated"])
        assertEquals("number", anyOf[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tuple-form items array is reduced to a single schema from its first element`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "properties": {
                "list": {
                  "type": "array",
                  "items": [
                    { "type": "string", "format": "email" },
                    { "type": "number" }
                  ]
                }
              }
            }
            """.trimIndent()
        ).jsonObject
        val list = result["properties"]!!.jsonObject["list"]!!.jsonObject

        val items = list["items"]!!.jsonObject
        assertEquals("string", items["type"]?.jsonPrimitive?.content)
        assertNull(items["format"])
        assertFalse(list["items"]!!.toString().contains("number"))
    }

    @Test
    fun `empty tuple-form items array is dropped`() {
        val result = sanitize(
            """{"type": "object", "properties": {"list": {"type": "array", "items": []}}}"""
        ).jsonObject
        val list = result["properties"]!!.jsonObject["list"]!!.jsonObject

        assertNull(list["items"])
        assertEquals("array", list["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a properties-bearing node with no type gains type object`() {
        val result = sanitize(
            """{"type": "object", "properties": {"child": {"properties": {"a": {"type": "string"}}}}}"""
        ).jsonObject
        val child = result["properties"]!!.jsonObject["child"]!!.jsonObject

        assertEquals("object", child["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a node with items and no type gains type array`() {
        val result = sanitize(
            """{"type": "object", "properties": {"list": {"items": {"type": "string"}}}}"""
        ).jsonObject
        val list = result["properties"]!!.jsonObject["list"]!!.jsonObject

        assertEquals("array", list["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a typeless empty root schema becomes an empty object schema`() {
        val result = sanitize("{}").jsonObject

        assertEquals("object", result["type"]?.jsonPrimitive?.content)
        assertEquals(0, result["properties"]!!.jsonObject.size)
        assertEquals(2, result.size)
    }

    @Test
    fun `a non-object root schema falls back to an empty object schema`() {
        val arrayRoot = sanitize("""["not", "a", "schema"]""").jsonObject
        assertEquals("object", arrayRoot["type"]?.jsonPrimitive?.content)
        assertEquals(0, arrayRoot["properties"]!!.jsonObject.size)

        val primitiveRoot = sanitize(""""just a string"""").jsonObject
        assertEquals("object", primitiveRoot["type"]?.jsonPrimitive?.content)
        assertEquals(0, primitiveRoot["properties"]!!.jsonObject.size)
    }

    @Test
    fun `a nested typeless anyOf wrapper does not gain an invented type`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "properties": {
                "either": {
                  "anyOf": [{"type": "string"}, {"type": "number"}]
                }
              }
            }
            """.trimIndent()
        ).jsonObject

        val either = result["properties"]!!.jsonObject["either"]!!.jsonObject
        assertNull(either["type"])
    }

    @Test
    fun `a bare anyOf wrapper root falls back to an object schema at the root boundary`() {
        val result = sanitize(
            """
            {
              "anyOf": [{"type": "string"}, {"type": "number"}]
            }
            """.trimIndent()
        ).jsonObject

        // Node-level type inference leaves this typeless (a bare anyOf wrapper stays
        // typeless per node), but the tool root must be an object schema, so it falls back.
        assertEquals("object", result["type"]?.jsonPrimitive?.content)
        assertEquals(0, result["properties"]!!.jsonObject.size)
    }

    @Test
    fun `required, default and enum values are copied without recursing into them`() {
        val result = sanitize(
            """
            {
              "type": "object",
              "required": ["a", "b"],
              "default": { "a": 1, "x-google-identifier": "should survive, not recursed into" }
            }
            """.trimIndent()
        ).jsonObject

        val required = result["required"]!!.jsonArray
        assertEquals(listOf("a", "b"), required.map { it.jsonPrimitive.content })

        val default = result["default"]!!.jsonObject
        assertEquals(1L, default["a"]?.jsonPrimitive?.content?.toLong())
        assertFalse(default["x-google-identifier"] == null)
    }
}
