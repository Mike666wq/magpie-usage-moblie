package com.magpie.usage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBookSerializerTest {
    @Test
    fun `roundtrip preserves sanitized rows and ordering`() {
        val book = PanelBook(
            listOf(
                PanelEntry("https://one.example", "家里", 123L),
                PanelEntry("http://192.168.31.60:3430", "办公室", 0L),
            ),
        )

        val raw = PanelBookSerializer.serialize(book)
        val parsed = PanelBookSerializer.parse(raw)

        assertEquals(book, parsed)
        assertEquals(1, JSONObject(raw).getInt("version"))
        assertFalse(raw.contains("k="))
        assertFalse(raw.contains("view="))
    }

    @Test
    fun `corrupt or structurally invalid JSON fails closed`() {
        listOf(
            null,
            "",
            "not json",
            "[]",
            "{}",
            """{"version":1,"entries":{}}""",
            """{"version":1,"entries":[null,1,"text"]}""",
        ).forEach { raw ->
            assertNull(PanelBookSerializer.parse(raw))
        }
        assertEquals(
            PanelBook(emptyList()),
            PanelBookSerializer.parse("""{"version":1,"entries":[]}"""),
        )
    }

    @Test
    fun `duplicate normalized origins keep the first valid row`() {
        val raw = """
            {
              "version": 1,
              "entries": [
                {"origin":"HTTPS://EXAMPLE.COM:443/?k=secret","label":"first","lastUsedAt":11},
                {"origin":"https://example.com/","label":"second","lastUsedAt":22},
                {"origin":"javascript:alert(1)","label":"bad","lastUsedAt":33},
                {"origin":"https://valid.example","label":"valid","lastUsedAt":-9}
              ]
            }
        """.trimIndent()

        val parsed = PanelBookSerializer.parse(raw)!!

        assertEquals(2, parsed.entries.size)
        assertEquals(PanelEntry("https://example.com", "first", 11L), parsed.entries[0])
        assertEquals(PanelEntry("https://valid.example", "valid", 0L), parsed.entries[1])
        assertFalse(PanelBookSerializer.serialize(parsed).contains("secret"))
    }

    @Test
    fun `origin-only legacy rows receive a default label`() {
        val parsed = PanelBookSerializer.parse(
            """{"version":1,"entries":[{"origin":"http://192.168.31.60:3430"}]}""",
        )!!

        assertEquals(
            PanelEntry("http://192.168.31.60:3430", "面板 192.168.31.60", 0L),
            parsed.entries.single(),
        )
    }

    @Test
    fun `serializer output is capped at fifty entries`() {
        val book = PanelBook(
            (1..PanelBookState.MAX_ENTRIES + 5).map { index ->
                PanelEntry("https://panel-$index.example", "panel $index", index.toLong())
            },
        )

        val raw = PanelBookSerializer.serialize(book)
        val parsed = PanelBookSerializer.parse(raw)!!

        assertEquals(PanelBookState.MAX_ENTRIES, JSONArray(JSONObject(raw).getJSONArray("entries")).length())
        assertEquals(PanelBookState.MAX_ENTRIES, parsed.entries.size)
        assertTrue(parsed.entries.all { it.origin.startsWith("https://panel-") })
    }
}
