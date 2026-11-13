package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.readUpTo
import org.junit.Assert.*
import org.junit.Test

class StreamsTest {
    @Test fun readsEverythingUnderTheLimit() = assertArrayEquals(ByteArray(10) { it.toByte() }, ByteArray(10) { it.toByte() }.inputStream().readUpTo(100))
    @Test fun stopsAtTheLimit() = assertEquals(5, ByteArray(1000).inputStream().readUpTo(5).size)
    @Test fun oneOverTheLimitShowsItWasTooBig() = assertEquals(11, ByteArray(500).inputStream().readUpTo(11).size) // callers pass max + 1
    @Test fun emptyAndLargeInputs() {
        assertEquals(0, ByteArray(0).inputStream().readUpTo(10).size)
        val big = ByteArray(300_000) { (it % 251).toByte() }
        assertArrayEquals(big, big.inputStream().readUpTo(1_000_000))
    }
}
