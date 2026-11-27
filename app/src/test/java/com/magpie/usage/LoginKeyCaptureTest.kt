package com.magpie.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginKeyCaptureTest {
    private val origin = "https://magpie.example.com"

    @Test
    fun `accept requires canonical root path and nonblank key`() {
        assertEquals(
            "secret",
            LoginKeyCapture.accept(LoginKeyCapture.Capture("/", "secret", "/?view=usage"), origin),
        )
        assertEquals(
            "secret",
            LoginKeyCapture.accept(LoginKeyCapture.Capture("", "secret", "/"), origin),
        )
        assertNull(LoginKeyCapture.accept(LoginKeyCapture.Capture("/", " ", "/"), origin))
        assertNull(LoginKeyCapture.accept(LoginKeyCapture.Capture("/other", "secret", "/"), origin))
        assertNull(LoginKeyCapture.accept(LoginKeyCapture.Capture("/_magpie/sign-in", "secret", "/"), origin))
        assertNull(LoginKeyCapture.accept(LoginKeyCapture.Capture("/", "secret", "/"), null))
    }

    @Test
    fun `accept rejects cross-origin authority scheme and fragment in back`() {
        listOf(
            "https://evil.example/",
            "//evil.example/",
            "/\\evil.example/",
            "javascript:alert(1)",
            "https:%2f%2fevil.example/",
            "/%2f%2fevil.example/",
            "/%5Cevil.example/",
            "/?view=usage#fragment",
            "/ evil.example/",
            "/\tevil.example/",
            "/ evil.example",
            "/\tevil.example",
        ).forEach { back ->
            assertNull(
                LoginKeyCapture.accept(LoginKeyCapture.Capture("/", "secret", back), origin),
            )
        }
    }

    @Test
    fun `accept rejects sign-in loop and encoded sign-in paths`() {
        listOf(
            "/_magpie/sign-in",
            "/_magpie/sign-in?view=usage",
            "/%5Fmagpie/sign-in",
            "/_magpie%2Fsign-in",
            "/%255Fmagpie/sign-in",
            "/_magpie/sign-in/",
            "/?back=/_magpie/sign-in",
        ).forEach { back ->
            assertNull(
                LoginKeyCapture.accept(LoginKeyCapture.Capture("/", "secret", back), origin),
            )
        }
    }

    @Test
    fun `accept allows clean same-origin panel return paths`() {
        listOf("/", "/?view=usage", "/?view=routing&k=old", "/nested/path?view=usage").forEach { back ->
            assertEquals(
                "secret",
                LoginKeyCapture.accept(LoginKeyCapture.Capture("/", "secret", back), origin),
            )
        }
    }
}
