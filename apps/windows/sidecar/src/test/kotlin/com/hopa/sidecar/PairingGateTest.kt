package com.hopa.sidecar

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingGateTest {
    @Test
    fun `a second start is refused while the first is in flight`() {
        val gate = PairingGate()

        // The first command claims the gate.
        assertTrue(gate.tryStart())
        // A second command while the first is unresolved is refused.
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun `finish frees the gate so a later start is allowed`() {
        val gate = PairingGate()

        assertTrue(gate.tryStart())
        // The first admission completes (or fails) and releases the gate.
        gate.finish()
        assertTrue(gate.tryStart())

        // A failed admission frees the gate too, so it is never stuck.
        gate.finish()
        assertTrue(gate.tryStart())
    }
}
