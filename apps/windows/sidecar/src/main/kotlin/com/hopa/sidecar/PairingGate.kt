package com.hopa.sidecar

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards the `backend.pair` command so only one admission runs at a time. Two
 * simultaneous commands would each run a full admission and both write
 * `backend-session.json`; the last writer wins and the loser's pending session
 * stays live until its TTL. The first [tryStart] wins, a second while the first
 * is unresolved is refused immediately, and [finish] frees the gate even when
 * admission fails.
 */
class PairingGate {
    private val inFlight = AtomicBoolean(false)

    /** Claims the gate when no admission is running; false when one is. */
    fun tryStart(): Boolean = inFlight.compareAndSet(false, true)

    /** Releases the gate so the next `backend.pair` may start. */
    fun finish() {
        inFlight.set(false)
    }
}
