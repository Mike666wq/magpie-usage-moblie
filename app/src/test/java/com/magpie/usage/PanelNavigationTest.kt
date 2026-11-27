package com.magpie.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `restored navigation cannot resume the old document or capture token`() {
        val state = NavigationState()
        val old = state.begin("https://first.example")

        val restored = state.restore(old.generation)

        assertEquals(old.generation, state.generation)
        assertEquals(old.generation, restored.generation)
        assertTrue(restored.isStale)
        assertFalse(restored.canResume)
        assertTrue(restored.token.isEmpty())
        assertFalse(state.isCurrent(restored))
        assertFalse(state.isCurrent(old))
        // A restored navigation has no live token. Reject both the old token
        // and any attempt to accept against the empty restored token.
        assertNull(
            LoginKeyCapture.accept(
                LoginKeyCapture.Capture(
                    token = old.token,
                    path = "/",
                    key = "secret",
                    back = "/?view=usage",
                ),
                expectedOrigin = old.origin,
                expectedToken = restored.token,
            ),
        )
    }

    @Test
    fun `begin after background restore creates a resumable newer navigation`() {
        val state = NavigationState()
        val old = state.begin("https://first.example")
        state.bump()

        val restored = state.restore(state.generation)
        assertFalse(restored.canResume)

        val current = state.begin("https://first.example")

        assertTrue(current.generation > restored.generation)
        assertTrue(current.generation > old.generation)
        assertTrue(current.canResume)
        assertFalse(current.isStale)
        assertNotEquals(old.token, current.token)
        assertTrue(state.isCurrent(current))
        assertFalse(state.isCurrent(restored))
        assertFalse(state.isCurrent(old))
    }

    @Test
    fun `restore clamps malformed generations before begin advances`() {
        val state = NavigationState()

        val restored = state.restore(-1L)

        assertEquals(0L, restored.generation)
        assertTrue(restored.isStale)
        val current = state.begin("https://first.example")
        assertEquals(1L, current.generation)
        assertTrue(current.canResume)
        assertTrue(state.isCurrent(current))
    }

    @Test
    fun `bump invalidates a live navigation exactly once`() {
        val state = NavigationState()
        val current = state.begin("https://first.example")
        val generationBeforeBump = state.generation

        state.bump()

        assertEquals(generationBeforeBump + 1L, state.generation)
        assertFalse(state.isCurrent(current))
        assertTrue(state.restore(state.generation).isStale)
    }
}
