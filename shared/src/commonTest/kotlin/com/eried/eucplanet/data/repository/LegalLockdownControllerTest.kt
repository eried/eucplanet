package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.store.LockdownState
import com.eried.eucplanet.data.store.LockdownStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Legal Mode Lockdown's rules, on both phones: the code hash, arming, the
 * latch when legal mode comes on, and the only two ways back out.
 */
class LegalLockdownControllerTest {

    private class MemoryStore(start: LockdownState = LockdownState()) : LockdownStore {
        val flow = MutableStateFlow(start)
        override val state = flow
        override suspend fun get() = flow.value
        override suspend fun set(armed: Boolean, engaged: Boolean, codeHash: String) {
            flow.value = LockdownState(armed, engaged, codeHash)
        }
        override suspend fun setEngaged(engaged: Boolean) {
            flow.value = flow.value.copy(engaged = engaged)
        }
    }

    /** The controller mirrors the store from a collector, so wait for it. */
    private suspend fun StateFlow<Boolean>.await(value: Boolean) {
        withTimeout(2_000) { while (this@await.value != value) delay(5) }
    }

    @Test
    fun hashIsAndroidsMessageDigestMd5() {
        // MessageDigest.getInstance("MD5") of "1234", lower-case hex.
        assertEquals("81dc9bdb52d04dc20036dbd8313ed055", LegalLockdownCode.hash("1234"))
        assertEquals(32, LegalLockdownCode.hash("12345678").length)
    }

    @Test
    fun anInvalidCodeArmsNothing() = runBlocking {
        val store = MemoryStore()
        val c = LegalLockdownController(store)
        assertFalse(c.arm("123", engageNow = false))
        assertFalse(c.arm("12a4", engageNow = true))
        assertEquals(LockdownState(), store.get())
    }

    @Test
    fun armedWaitsUntilLegalModeComesOn() = runBlocking {
        val store = MemoryStore()
        val c = LegalLockdownController(store)
        assertTrue(c.arm("4821", engageNow = false))
        c.armed.await(true)
        assertFalse(c.isEngaged())
        c.onLegalModeActivated()
        c.engaged.await(true)
        assertTrue(store.get().engaged)
    }

    @Test
    fun legalModeAloneNeverEngages() = runBlocking {
        val store = MemoryStore()
        val c = LegalLockdownController(store)
        c.onLegalModeActivated()
        delay(50)
        assertFalse(c.isEngaged())
        assertFalse(store.get().engaged)
    }

    @Test
    fun armingWithLegalModeOnEngagesAtOnce() = runBlocking {
        val c = LegalLockdownController(MemoryStore())
        assertTrue(c.arm("4821", engageNow = true))
        c.engaged.await(true)
    }

    @Test
    fun theSwitchTurnsOffOnlyBeforeItEngages() = runBlocking {
        val store = MemoryStore()
        val c = LegalLockdownController(store)
        c.arm("4821", engageNow = false)
        c.disarmIfNotEngaged()
        assertFalse(store.get().armed)

        c.arm("4821", engageNow = true)
        c.disarmIfNotEngaged()
        assertTrue(store.get().armed)
        assertTrue(store.get().engaged)
    }

    @Test
    fun onlyTheRightCodeUnlocks() = runBlocking {
        val store = MemoryStore()
        val c = LegalLockdownController(store)
        c.arm("135790", engageNow = true)
        c.engaged.await(true)
        assertFalse(c.tryDisarm("135791"))
        assertTrue(c.isEngaged())
        assertTrue(c.tryDisarm("135790"))
        c.engaged.await(false)
        c.armed.await(false)
        // The hash stays, so arming again can reuse the same code.
        assertEquals(LegalLockdownCode.hash("135790"), store.get().codeHash)
    }

    @Test
    fun aBlankHashNeverUnlocks() = runBlocking {
        val store = MemoryStore(LockdownState(armed = true, engaged = true, codeHash = ""))
        val c = LegalLockdownController(store)
        assertFalse(c.tryDisarm(""))
        assertFalse(c.tryDisarm("0000"))
    }

    @Test
    fun aSeededStateHoldsFromTheFirstRead() {
        val s = LockdownState(armed = true, engaged = true, codeHash = LegalLockdownCode.hash("1111"))
        val c = LegalLockdownController(MemoryStore(s), initial = s)
        assertTrue(c.isEngaged())
        assertTrue(c.isArmed())
    }
}
