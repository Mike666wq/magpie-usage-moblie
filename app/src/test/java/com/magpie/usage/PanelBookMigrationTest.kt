package com.magpie.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBookMigrationTest {
    @Test
    fun `valid v1 origin is migrated once with default label`() {
        val result = PanelBookMigration.migrate(
            "HTTPS://MAGPIE.Example.com:443/?view=routing&k=secret",
            existingJson = null,
            now = 42L,
        )

        assertTrue(result.changed)
        assertEquals(
            PanelEntry("https://magpie.example.com", PanelBookMigration.DEFAULT_LABEL, 42L),
            result.book.entries.single(),
        )
        assertFalse(PanelBookSerializer.serialize(result.book).contains("secret"))

        val repeated = PanelBookMigration.migrate(
            "https://magpie.example.com",
            PanelBookSerializer.serialize(result.book),
            now = 99L,
        )
        assertFalse(repeated.changed)
        assertEquals(result.book, repeated.book)
    }

    @Test
    fun `existing normalized legacy origin is not duplicated or touched`() {
        val existing = PanelBook(
            listOf(PanelEntry("https://magpie.example.com", "自定义", 10L)),
        )

        val result = PanelBookMigration.migrate(
            "https://MAGPIE.example.com:443/",
            PanelBookSerializer.serialize(existing),
            now = 99L,
        )

        assertFalse(result.changed)
        assertEquals(existing, result.book)
    }

    @Test
    fun `legacy migration preserves a valid address book`() {
        val existing = PanelBook(
            listOf(
                PanelEntry("https://one.example", "one", 1L),
                PanelEntry("https://two.example", "two", 2L),
            ),
        )

        val result = PanelBookMigration.migrate(
            "http://192.168.31.60:3430",
            PanelBookSerializer.serialize(existing),
            now = 50L,
        )

        assertTrue(result.changed)
        assertEquals(existing.entries, result.book.entries.take(2))
        assertEquals(
            PanelEntry("http://192.168.31.60:3430", PanelBookMigration.DEFAULT_LABEL, 50L),
            result.book.entries.last(),
        )
    }

    @Test
    fun `missing or malformed legacy storage yields an empty book`() {
        val empty = PanelBookMigration.MigrationResult(PanelBook(emptyList()), changed = true)

        assertEquals(empty, PanelBookMigration.migrate(null, null, now = 1L))
        assertEquals(empty, PanelBookMigration.migrate("", "", now = 1L))
        assertEquals(empty, PanelBookMigration.migrate("", "not json", now = 1L))
        assertEquals(empty, PanelBookMigration.migrate("file:///tmp/panel", null, now = 1L))
        assertNull(PanelBookState.normalizeOrigin("javascript:alert(1)"))
    }

    @Test
    fun `malformed new storage does not discard a valid legacy origin`() {
        val result = PanelBookMigration.migrate("http://legacy.example:3430", "{broken", now = 7L)

        assertTrue(result.changed)
        assertEquals(
            PanelEntry("http://legacy.example:3430", PanelBookMigration.DEFAULT_LABEL, 7L),
            result.book.entries.single(),
        )
    }

    @Test
    fun `a full book is preserved without adding the legacy origin`() {
        val full = PanelBook(
            (1..PanelBookState.MAX_ENTRIES).map { index ->
                PanelEntry("https://panel-$index.example", "panel $index", index.toLong())
            },
        )

        val result = PanelBookMigration.migrate(
            "https://new.example",
            PanelBookSerializer.serialize(full),
            now = 100L,
        )

        assertFalse(result.changed)
        assertEquals(full, result.book)
    }
}
