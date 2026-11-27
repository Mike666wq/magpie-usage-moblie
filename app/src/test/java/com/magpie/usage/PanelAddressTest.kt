package com.magpie.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAddressTest {
    @Test
    fun `bare LAN address gets HTTP and explicit port`() {
        val target = PanelUrl.parse("  192.168.31.60:3430/path?view=routing&k=test-key  ")

        assertEquals("http://192.168.31.60:3430", target.origin)
        assertEquals("http://192.168.31.60:3430/path?view=routing&k=test-key", target.initialUrl)
        assertTrue(target.redirectAfterLoad)
        assertFalse(target.origin.contains("k="))
        assertEquals("http://192.168.31.60:3430/path?view=routing", target.cleanUrl)
        assertFalse(target.cleanUrl.contains("k="))
    }

    @Test
    fun `HTTPS default port is normalized away`() {
        val target = PanelUrl.parse("https://MAGPIE.Example.com.:443/?k=test-key")

        assertEquals("https://magpie.example.com", target.origin)
        assertEquals("https://magpie.example.com/?view=usage&k=test-key", target.initialUrl)
        assertEquals("https://magpie.example.com/?view=usage", target.cleanUrl)
        assertTrue(target.redirectAfterLoad)
    }

    @Test
    fun `HTTP default port is normalized away`() {
        val target = PanelUrl.parse("http://example.com:80")

        assertEquals("http://example.com", target.origin)
        assertEquals("http://example.com/?view=usage", target.initialUrl)
        assertFalse(target.redirectAfterLoad)
    }

    @Test
    fun `IPv6 origin keeps brackets`() {
        val target = PanelUrl.parse("http://[2001:db8::1]:3430")

        assertEquals("http://[2001:db8::1]:3430", target.origin)
        assertEquals("http://[2001:db8::1]:3430/?view=usage", target.initialUrl)
    }

    @Test
    fun `unsupported view value falls back to usage`() {
        val target = PanelUrl.parse("https://example.com/?view=../../admin&k=test-key")

        assertEquals("https://example.com/?view=usage&k=test-key", target.initialUrl)
        assertEquals("https://example.com/?view=usage", target.cleanUrl)
        assertTrue(target.redirectAfterLoad)
    }

    @Test
    fun `view parameter is allowlisted and normalized`() {
        val routing = PanelUrl.parse("https://example.com/?view=ROUTING&k=test-key")
        val unsupported = PanelUrl.parse("https://example.com/?view=custom&k=test-key")

        assertEquals("https://example.com/?view=routing&k=test-key", routing.initialUrl)
        assertEquals("https://example.com/?view=routing", routing.cleanUrl)
        assertEquals("https://example.com/?view=usage&k=test-key", unsupported.initialUrl)
        assertEquals("https://example.com/?view=usage", unsupported.cleanUrl)
    }

    @Test
    fun `IPv6 with scheme and brackets stays valid`() {
        val target = PanelUrl.parse("http://[2001:db8::1]:3430/?view=routing")

        assertEquals("http://[2001:db8::1]:3430", target.origin)
        assertEquals("http://[2001:db8::1]:3430/?view=routing", target.initialUrl)
    }

    @Test
    fun `key is URL encoded and never becomes part of the clean target`() {
        val target = PanelUrl.parse("http://example.com/?k=a%2Fb%2Bc")

        assertEquals("http://example.com/?view=usage&k=a%2Fb%2Bc", target.initialUrl)
        assertEquals("http://example.com/?view=usage", target.cleanUrl)
        assertFalse(target.cleanUrl.contains("k="))
    }

    @Test
    fun `invalid addresses are rejected without echoing input`() {
        val invalid = listOf(
            "",
            "   ",
            "javascript:alert(1)",
            "data:text/html,test",
            "file:///tmp/test",
            "http://",
            "https:///path",
            "http://user:password@example.com",
            "http://example.com:0",
            "http://example.com:65536",
            "http://example.com:not-a-port",
            "http://bad_host.example.com",
            "http://-bad.example.com",
            "http://2001:db8::1",
            "http://[not-an-ipv6-address]",
            "http://example.com:",
            "http://example.com:abc",
        )

        invalid.forEach { input ->
            try {
                PanelUrl.parse(input)
                throw AssertionError("Expected invalid address to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `usage URL only appends the clean view`() {
        assertEquals(
            "http://192.168.31.60:3430/?view=usage",
            PanelUrl.usageUrl("http://192.168.31.60:3430"),
        )
    }

    @Test
    fun `current parser keeps all legacy URL expectations`() {
        val target = PanelUrl.parse("https://bücher.example:443/path?view=routing&k=a%2Fb")

        assertEquals("https://xn--bcher-kva.example", target.origin)
        assertEquals("https://xn--bcher-kva.example/path?view=routing&k=a%2Fb", target.initialUrl)
        assertEquals("https://xn--bcher-kva.example/path?view=routing", target.cleanUrl)
        assertTrue(target.redirectAfterLoad)
    }

    @Test
    fun `default key append rejects existing encoded or blank key names`() {
        assertEquals(
            "http://example.com/?view=usage&k=a%2Bb",
            PanelUrl.withDefaultKey("http://example.com/?view=usage", "a+b"),
        )
        assertEquals(
            "http://example.com/?view=routing&k=a%2Fb%2Bc",
            PanelUrl.withDefaultKey("http://example.com/?view=routing", "a/b+c"),
        )
        assertNull(PanelUrl.withDefaultKey("http://example.com/?view=usage", " \n"))
        assertNull(PanelUrl.withDefaultKey("http://example.com/?view=usage&k=old", "new"))
        assertNull(PanelUrl.withDefaultKey("http://example.com/?%6B=old", "new"))
        assertNull(PanelUrl.withDefaultKey("http://example.com/?k=", "new"))
        assertNull(PanelUrl.withDefaultKey("http://example.com/?K=old", "new"))
        assertNull(PanelUrl.withDefaultKey("http://example.com/?%E0%A4%A=old", "new"))
    }
}
