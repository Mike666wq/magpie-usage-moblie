package com.magpie.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests exercise the shared navigation policy without constructing a WebView.
 */
class NavigationPolicyTest {
    @Test
    fun `HTTP and HTTPS destinations stay in the current WebView`() {
        listOf(
            "https://other.example.com/",
            "http://192.168.31.60:3430/?view=usage",
            "HTTPS://magpie.example.com/path",
            "https:opaque-but-web-scheme",
        ).forEach { url ->
            assertEquals(NavigationAction.KEEP_IN_WEB_VIEW, NavigationPolicy.forUrl(url))
        }
    }

    @Test
    fun `internal blank pages are not handed to Android`() {
        assertEquals(NavigationAction.KEEP_IN_WEB_VIEW, NavigationPolicy.forUrl("about:blank"))
        assertEquals(NavigationAction.KEEP_IN_WEB_VIEW, NavigationPolicy.forUrl("ABOUT:blank"))
        assertTrue(NavigationPolicy.isInternalBlank("about:"))
        assertTrue(NavigationPolicy.isInternalBlank("ABOUT:blank"))
        assertFalse(NavigationPolicy.isInternalBlank("about:blankevil"))
        assertFalse(NavigationPolicy.isInternalBlank("about:blank?next=https://example.com"))
    }

    @Test
    fun `non-web schemes are explicitly handed to Android`() {
        listOf(
            "mailto:ops@example.com",
            "tel:+8613800000000",
            "intent://scan/#Intent;scheme=zxing;package=com.example.scanner;end",
            "geo:31.2,121.5",
            "javascript:alert(1)",
            "data:text/plain,test",
        ).forEach { url ->
            assertEquals(NavigationAction.OPEN_EXTERNALLY, NavigationPolicy.forUrl(url))
        }
    }

    @Test
    fun `same-origin redirects are allowed but other origins are blocked`() {
        val origin = "https://magpie.example.com"

        assertTrue(NavigationPolicy.isPanelTopLevelUrl("$origin/?view=routing", origin))
        assertTrue(NavigationPolicy.isPanelTopLevelUrl("$origin/api/gateway/trace", origin))
        assertFalse(NavigationPolicy.isPanelTopLevelUrl("https://other.example.com/", origin))
        assertFalse(NavigationPolicy.isPanelTopLevelUrl("http://magpie.example.com/", origin))
        assertFalse(NavigationPolicy.isPanelTopLevelUrl("mailto:ops@example.com", origin))
        assertFalse(NavigationPolicy.isPanelTopLevelUrl("javascript:alert(1)", origin))
    }
}
