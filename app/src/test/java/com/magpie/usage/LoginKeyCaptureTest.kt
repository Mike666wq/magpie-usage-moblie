import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginKeyCaptureTest {
    private val origin = "https://magpie.example.com"
    private val token = "current-navigation-token"

    private fun capture(
        path: String = "/",
        key: String = "secret",
        back: String = "/?view=usage",
        captureToken: String = token,
    ) = LoginKeyCapture.Capture(captureToken, path, key, back)

    @Test
    fun `accept requires canonical root path and nonblank key`() {
        assertEquals(
            "secret",
            LoginKeyCapture.accept(capture(), origin, token),
        )
        assertEquals(
            "secret",
            LoginKeyCapture.accept(capture(path = ""), origin, token),
        )
        assertNull(LoginKeyCapture.accept(capture(key = " "), origin, token))
        assertNull(LoginKeyCapture.accept(capture(path = "/other"), origin, token))
        assertNull(LoginKeyCapture.accept(capture(path = "/_magpie/sign-in"), origin, token))
        assertNull(LoginKeyCapture.accept(capture(), null, token))
    }

    @Test
    fun `accept rejects a stale or mismatched capture token`() {
        assertEquals("secret", LoginKeyCapture.accept(capture(), origin, token))
        assertNull(LoginKeyCapture.accept(capture(captureToken = "old-token"), origin, token))
        assertNull(LoginKeyCapture.accept(capture(captureToken = ""), origin, token))
        assertNull(LoginKeyCapture.accept(capture(), origin, ""))
        assertNull(LoginKeyCapture.accept(capture(), origin, "different-token"))
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
                LoginKeyCapture.accept(capture(back = back), origin, token),
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
                LoginKeyCapture.accept(capture(back = back), origin, token),
            )
        }
    }

    @Test
    fun `accept allows clean same-origin panel return paths`() {
        listOf("/", "/?view=usage", "/?view=routing&k=old", "/nested/path?view=usage").forEach { back ->
            assertEquals(
                "secret",
                LoginKeyCapture.accept(capture(back = back), origin, token),
            )
        }
    }
}
