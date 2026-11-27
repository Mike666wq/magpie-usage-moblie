package com.magpie.usage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * JVM-friendly address-book state. Labels are limited to one line so a saved
 * address cannot visually or structurally impersonate another row.
 */
internal object PanelBookState {
    const val MAX_ENTRIES = 50
    const val MAX_LABEL_LENGTH = 40

    fun normalizeLabel(rawLabel: String?, origin: String): String {
        val normalized = rawLabel.orEmpty()
            .replace(Regex("[\\p{Cntrl}\\u2028\\u2029]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized.take(MAX_LABEL_LENGTH).ifBlank {
            try {
                val authority = PanelUrl.parse(origin).origin.substringAfter("://")
                val host = if (authority.startsWith('[')) {
                    authority.substringAfter('[').substringBefore(']')
                } else {
                    authority.substringBefore(':')
                }
                "面板 $host"
            } catch (_: IllegalArgumentException) {
                "控制面板"
            }
        }
    }

    fun normalizeOrigin(rawOrigin: String): String? = try {
        PanelUrl.parse(rawOrigin).origin
    } catch (_: IllegalArgumentException) {
        null
    }

    fun upsert(
        book: PanelBook,
        rawOrigin: String,
        label: String? = null,
        lastUsedAt: Long = System.currentTimeMillis(),
    ): PanelBook? {
        val origin = normalizeOrigin(rawOrigin) ?: return null
        val existing = book.find(origin)
        if (existing == null && book.entries.size >= MAX_ENTRIES) return null
        val entry = PanelEntry(
            origin = origin,
            label = normalizeLabel(label ?: existing?.label, origin),
            lastUsedAt = lastUsedAt,
        )
        return PanelBook(
            book.entries.filterNot { it.origin == origin } + entry,
        )
    }

    fun remove(book: PanelBook, origin: String): PanelBook =
        PanelBook(book.entries.filterNot { it.origin == origin })
}

internal object PanelBookSerializer {
    private const val VERSION = 1
    private const val ENTRIES = "entries"

    fun serialize(book: PanelBook): String {
        val entries = JSONArray()
        book.entries.take(PanelBookState.MAX_ENTRIES).forEach { entry ->
            entries.put(
                JSONObject()
                    .put("origin", entry.origin)
                    .put("label", entry.label)
                    .put("lastUsedAt", entry.lastUsedAt),
            )
        }
        return JSONObject().put("version", VERSION).put(ENTRIES, entries).toString()
    }

    fun parse(rawJson: String?): PanelBook? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val root = JSONObject(rawJson)
            val entriesJson = root.getJSONArray(ENTRIES)
            val entries = mutableListOf<PanelEntry>()
            val seenOrigins = mutableSetOf<String>()
            for (index in 0 until entriesJson.length()) {
                val item = entriesJson.optJSONObject(index) ?: continue
                val origin = PanelBookState.normalizeOrigin(item.optString("origin")) ?: continue
                if (!seenOrigins.add(origin)) continue
                val lastUsedAt = try {
                    item.getLong("lastUsedAt").coerceAtLeast(0L)
                } catch (_: JSONException) {
                    // Origin-only rows predate timestamps; other missing or
                    // malformed timestamp fields safely mean "never used".
                    0L
                }
                entries += PanelEntry(
                    origin = origin,
                    label = PanelBookState.normalizeLabel(item.optString("label"), origin),
                    lastUsedAt = lastUsedAt,
                )
                if (entries.size == PanelBookState.MAX_ENTRIES) break
            }
            PanelBook(entries)
        } catch (_: JSONException) {
            null
        }
    }
}

internal object PanelBookMigration {
    const val DEFAULT_LABEL = "默认面板"

    /**
     * Pure migration used by both the store and JVM tests. A malformed legacy
     * value is dropped rather than turned into an unexpected navigation target.
     */
    fun migrate(
        legacyOrigin: String?,
        existingJson: String?,
        now: Long = System.currentTimeMillis(),
    ): MigrationResult {
        val existing = PanelBookSerializer.parse(existingJson)
        val legacy = PanelBookState.normalizeOrigin(legacyOrigin)
        if (legacy == null) {
            return MigrationResult(existing ?: PanelBook(emptyList()), changed = existing == null)
        }

        val current = existing ?: PanelBook(emptyList())
        val entry = current.find(legacy)
        if (entry != null) {
            return MigrationResult(current, changed = false)
        }
        if (current.entries.size >= PanelBookState.MAX_ENTRIES) {
            return MigrationResult(current, changed = false)
        }
        val migrated = PanelBookState.upsert(
            current,
            legacy,
            label = DEFAULT_LABEL,
            lastUsedAt = now,
        ) ?: return MigrationResult(current, changed = false)
        return MigrationResult(migrated, changed = true)
    }

    internal data class MigrationResult(
        val book: PanelBook,
        val changed: Boolean,
    )
}

internal class PanelBookStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val book: PanelBook
        get() = PanelBookMigration.migrate(
            legacyOrigin = preferences.getString(LEGACY_ORIGIN, null),
            existingJson = preferences.getString(BOOK, null),
        ).book

    fun save(book: PanelBook) {
        // Commit synchronously so an address the user just selected cannot be
        // lost if the process exits immediately. No address ever contains k.
        val editor = preferences.edit().putString(BOOK, PanelBookSerializer.serialize(book))
        if (preferences.contains(LEGACY_ORIGIN)) editor.remove(LEGACY_ORIGIN)
        check(editor.commit()) { "Unable to save the panel address book." }
    }

    fun upsert(rawOrigin: String, label: String? = null, now: Long = System.currentTimeMillis()) =
        PanelBookState.upsert(book, rawOrigin, label, now)

    fun remove(origin: String) = save(PanelBookState.remove(book, origin))

    /** Returns the book with [origin] marked as most recently used. */
    fun touch(origin: String, now: Long = System.currentTimeMillis()): PanelBook {
        val current = book
        if (current.find(origin) == null) return current
        val updated = PanelBook(
            current.entries.map { if (it.origin == origin) it.copy(lastUsedAt = now) else it },
        )
        save(updated)
        return updated
    }

    private companion object {
        const val PREFERENCES_NAME = "magpie_usage"
        const val LEGACY_ORIGIN = "panel_origin"
        const val BOOK = "panel_book"
    }
}

internal fun PanelBook.sortedForDisplay(): List<PanelEntry> = entries.sortedWith(
    compareByDescending<PanelEntry> { it.lastUsedAt }.thenBy { it.label.lowercase(Locale.ROOT) }
        .thenBy { it.origin },
)
