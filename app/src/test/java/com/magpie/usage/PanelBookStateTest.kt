package com.magpie.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBookStateTest {
    @Test
    fun `upsert normalizes origin and appends one row`() {
        val first = PanelBookState.upsert(
            PanelBook(emptyList()),
            "HTTPS://BÜCHER.Example.:443/?view=routing&k=secret",
            label = "  家里\n面板  ",
            lastUsedAt = 10L,
        )!!

        assertEquals(1, first.entries.size)
        assertEquals("https://xn--bcher-kva.example", first.entries.single().origin)
        assertEquals("家里 面板", first.entries.single().label)
        assertEquals(10L, first.entries.single().lastUsedAt)
        assertFalse(PanelBookSerializer.serialize(first).contains("secret"))
    }

    @Test
    fun `upsert updates the matching row and preserves its label when blank`() {
        val original = PanelBookState.upsert(
            PanelBook(emptyList()),
            "https://example.com",
            label = "家里",
            lastUsedAt = 10L,
        )!!
        val other = PanelBookState.upsert(
            original,
            "https://other.example.com",
            label = "公司",
            lastUsedAt = 20L,
        )!!
        val updated = PanelBookState.upsert(
            other,
            "https://EXAMPLE.com:443/",
            label = " \n ",
            lastUsedAt = 30L,
        )!!

        assertEquals(2, updated.entries.size)
        assertEquals("家里", updated.find("https://example.com")?.label)
        assertEquals(30L, updated.find("https://example.com")?.lastUsedAt)
        assertEquals("公司", updated.find("https://other.example.com")?.label)
        assertEquals(other, original)
    }

    @Test
    fun `labels are one line length limited and default to a host label`() {
        val origin = "http://[2001:db8::1]:3430"
        val label = PanelBookState.normalizeLabel("A\nB\u2028C\u0000" + "x".repeat(80), origin)

        assertFalse(label.contains('\n'))
        assertFalse(label.contains('\u2028'))
        assertFalse(label.contains('\u0000'))
        assertEquals(PanelBookState.MAX_LABEL_LENGTH, label.length)
        assertEquals("面板 2001:db8::1", PanelBookState.normalizeLabel(null, origin))
        assertEquals("控制面板", PanelBookState.normalizeLabel(null, "not an origin"))
    }

    @Test
    fun `remove deletes only the requested origin`() {
        val book = PanelBook(
            listOf(
                PanelEntry("https://one.example", "one", 1L),
                PanelEntry("https://two.example", "two", 2L),
            ),
        )

        val removed = PanelBookState.remove(book, "https://one.example")

        assertEquals(listOf("https://two.example"), removed.entries.map { it.origin })
        assertEquals(2, book.entries.size)
        assertEquals(book, PanelBookState.remove(book, "https://missing.example"))
    }

    @Test
    fun `invalid origins fail and a full book rejects only new entries`() {
        assertNull(PanelBookState.normalizeOrigin("javascript:alert(1)"))
        assertNull(PanelBookState.upsert(PanelBook(emptyList()), "file:///tmp/panel"))

        val full = PanelBook(
            (1..PanelBookState.MAX_ENTRIES).map { index ->
                PanelEntry("https://panel-$index.example", "panel $index", index.toLong())
            },
        )
        assertNull(
            PanelBookState.upsert(
                full,
                "https://new.example",
                label = "new",
                lastUsedAt = 100L,
            ),
        )
        assertEquals(
            100L,
            PanelBookState.upsert(
                full,
                "https://PANEL-1.example:443/",
                label = "renamed",
                lastUsedAt = 100L,
            )?.find("https://panel-1.example")?.lastUsedAt,
        )
    }

    @Test
    fun `most recent and display ordering are deterministic`() {
        val book = PanelBook(
            listOf(
                PanelEntry("https://b.example", "Beta", 10L),
                PanelEntry("https://a.example", "alpha", 10L),
                PanelEntry("https://c.example", "Gamma", 20L),
            ),
        )

        assertEquals("https://c.example", book.mostRecent?.origin)
        assertEquals(
            listOf("https://c.example", "https://a.example", "https://b.example"),
            book.sortedForDisplay().map { it.origin },
        )
        assertNotEquals(book, book.copy(entries = book.entries.dropLast(1)))
    }
}
