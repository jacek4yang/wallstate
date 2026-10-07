package io.github.jacek4yang.wallstate

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HashingTest {
    @Test
    fun `sha256 of known vector`() {
        val digest = Hashing.sha256(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
    }

    @Test
    fun `sha256 matches across byte array and stream paths`() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val fromStream = Hashing.sha256(data.inputStream())
        val fromArray = Hashing.sha256(data)
        assertEquals(fromArray, fromStream)
    }

    @Test
    fun `empty input digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.sha256(ByteArray(0).inputStream()),
        )
    }

    @Test
    fun `isSha256Hex validation`() {
        assertTrue(Hashing.isSha256Hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertTrue(Hashing.isSha256Hex("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
        assertFalse(Hashing.isSha256Hex("tooshort"))
        assertFalse(Hashing.isSha256Hex("z" + "a".repeat(63)))
        assertFalse(Hashing.isSha256Hex(""))
    }
}
