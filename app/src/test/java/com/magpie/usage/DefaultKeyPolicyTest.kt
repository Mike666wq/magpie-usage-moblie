package com.magpie.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultKeyPolicyTest {
    private val panel = PanelEntry(
        origin = "http://192.168.31.60:3430",
        label = "家里",
        lastUsedAt = 1L,
    )

    @Test
    fun `reuse disabled never appends`() {
        assertNull(
            DefaultKeyPolicy.appendForSavedPanel(
                panel = panel,
                cleanUrl = "http://192.168.31.60:3430/?view=usage",
                key = "secret",
                reuseEnabled = false,
            ),
        )
    }

    @Test
    fun `null or blank key never appends`() {
        listOf(null, "", " \n\t").forEach { key ->
            assertNull(
                DefaultKeyPolicy.appendForSavedPanel(
                    panel = panel,
                    cleanUrl = "http://192.168.31.60:3430/?view=usage",
                    key = key,
                    reuseEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `different origin never receives the default key`() {
        listOf(
            "https://192.168.31.60:3430/?view=usage",
            "http://192.168.31.60:3431/?view=usage",
            "http://other.example:3430/?view=usage",
            "http://192.168.31.60:3430.evil.example/?view=usage",
        ).forEach { cleanUrl ->
            assertNull(
                DefaultKeyPolicy.appendForSavedPanel(
                    panel = panel,
                    cleanUrl = cleanUrl,
                    key = "secret",
                    reuseEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `an existing k prevents a second key from being appended`() {
        listOf(
            "http://192.168.31.60:3430/?view=usage&k=old",
            "http://192.168.31.60:3430/?%6B=old&view=usage",
        ).forEach { cleanUrl ->
            assertNull(
                DefaultKeyPolicy.appendForSavedPanel(
                    panel = panel,
                    cleanUrl = cleanUrl,
                    key = "new",
                    reuseEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `clean saved panel URLs receive exactly one encoded key`() {
        assertEquals(
            "http://192.168.31.60:3430/?view=usage&k=a%2Fb%2Bc",
            DefaultKeyPolicy.appendForSavedPanel(
                panel = panel,
                cleanUrl = "http://192.168.31.60:3430/?view=usage",
                key = "a/b+c",
                reuseEnabled = true,
            ),
        )
        assertEquals(
            "http://192.168.31.60:3430/path?view=routing&k=secret",
            DefaultKeyPolicy.appendForSavedPanel(
                panel = panel,
                cleanUrl = "http://192.168.31.60:3430/path?view=routing",
                key = "secret",
                reuseEnabled = true,
            ),
        )
    }

    @Test
    fun `invalid and unsupported URLs fail closed`() {
        listOf(
            "javascript:alert(1)",
            "http://",
            "http://192.168.31.60:3430/?view=usage#fragment",
        ).forEach { cleanUrl ->
            assertNull(
                DefaultKeyPolicy.appendForSavedPanel(
                    panel = panel,
                    cleanUrl = cleanUrl,
                    key = "secret",
                    reuseEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `key availability follows independent reuse and capture switches`() {
        assertEquals("secret", DefaultKeyPolicy.reusableKey("secret", true, false))
        assertEquals("secret", DefaultKeyPolicy.reusableKey("secret", false, true))
        assertNull(DefaultKeyPolicy.reusableKey("secret", false, false))
        assertNull(DefaultKeyPolicy.reusableKey(" ", true, true))
    }
}
