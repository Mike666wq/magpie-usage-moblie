import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelNavigationTest {
    @Test
    fun `new navigation advances generation and uses a unique token`() {
        val state = NavigationState()
        val first = state.begin("https://first.example")
        val second = state.begin("https://second.example")

        assertTrue(second.generation > first.generation)
        assertEquals(second.generation, state.generation)
        assertNotEquals(first.token, second.token)
        assertTrue(first.token.isNotBlank())
        assertTrue(state.isCurrent(second))
        assertFalse(state.isCurrent(first))
    }

    @Test
    fun `invalidate rejects old callbacks until a new navigation begins`() {
        val state = NavigationState()
        val old = state.begin("https://first.example")
        state.invalidate()

        assertFalse(state.isCurrent(old))
        val current = state.begin("https://second.example")
        assertTrue(current.generation > old.generation)
        assertTrue(state.isCurrent(current))
        assertNotEquals(old.token, current.token)
    }

    @Test
    fun `a capture from the previous address cannot pass current token validation`() {
        val state = NavigationState()
        val old = state.begin("https://first.example")
        val current = state.begin("https://second.example")
        val staleCapture = LoginKeyCapture.Capture(
            token = old.token,
            path = "/",
            key = "secret",
            back = "/?view=usage",
        )

        assertFalse(state.isCurrent(old))
        assertNull(
            LoginKeyCapture.accept(
                staleCapture,
                expectedOrigin = current.origin,
                expectedToken = current.token,
            ),
        )
    }
}
