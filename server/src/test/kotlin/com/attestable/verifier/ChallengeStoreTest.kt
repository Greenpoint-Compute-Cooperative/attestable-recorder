package com.attestable.verifier

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChallengeStoreTest {
    private val store = ChallengeStore(Files.createTempDirectory("challenges").toFile())

    @Test
    fun `issued challenge is 32 random bytes and can be looked up`() {
        val a = store.issue(Duration.ofMinutes(5))
        val b = store.issue(Duration.ofMinutes(5))
        assertEquals(32, a.challenge.size)
        assertFalse(a.challenge.contentEquals(b.challenge))
        val found = assertNotNull(store.lookup(a.challenge))
        assertTrue(found.challenge.contentEquals(a.challenge))
        assertFalse(found.isUsed)
        assertFalse(found.isExpired())
    }

    @Test
    fun `unknown challenge is not found`() {
        assertNull(store.lookup(ByteArray(32) { 7 }))
    }

    @Test
    fun `challenge expires after its ttl`() {
        val t0 = Instant.parse("2026-09-12T12:00:00Z")
        val r = store.issue(Duration.ofMinutes(10), now = t0)
        assertFalse(r.isExpired(t0.plusSeconds(599)))
        assertTrue(r.isExpired(t0.plusSeconds(601)))
    }

    @Test
    fun `challenge is single use`() {
        val r = store.issue(Duration.ofMinutes(5))
        store.markUsed(r, "recording-1")
        val again = assertNotNull(store.lookup(r.challenge))
        assertTrue(again.isUsed)
        assertEquals("recording-1", again.usedBy)
    }
}
