package com.github.caiheyu.farview

import com.github.caiheyu.farview.network.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class WireTest {
    @Test fun binaryVideoFramesPreserveTimestampsAndPayload() {
        val frame = EncodedFrame(7, 9_123_456_789L, 1, byteArrayOf(0, 0, 0, 1, 103, -1))
        val bytes = ByteArrayOutputStream()
        Packet.write(DataOutputStream(bytes), frame.packet())
        val decoded = EncodedFrame.parse(Packet.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        assertEquals(frame.generation, decoded.generation); assertEquals(frame.ptsUs, decoded.ptsUs)
        assertEquals(frame.flags, decoded.flags); assertArrayEquals(frame.data, decoded.data)
    }
    @Test fun multiplePacketsDoNotMergeOrLoseBoundaries() {
        val bytes = ByteArrayOutputStream(); val output = DataOutputStream(bytes)
        listOf("中文控制", "", "next").forEach { Packet.write(output, Packet.text(Packet.COMMAND, it)) }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("中文控制", Packet.read(input).text()); assertEquals("", Packet.read(input).text()); assertEquals("next", Packet.read(input).text())
        assertEquals(0, input.available())
    }
    @Test fun unboundedAllocationAndUnknownPacketsAreRejected() {
        for ((type, length) in listOf(Packet.FRAME to -1, Packet.FRAME to Int.MAX_VALUE, 255 to 10)) {
            val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).apply { writeByte(type); writeInt(length) }
            assertThrows(IllegalArgumentException::class.java) { Packet.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
        }
    }
    @Test fun truncatedPacketsDoNotBecomeValidFrames() {
        val bytes = byteArrayOf(Packet.FRAME.toByte(), 0, 0, 0, 20, 1, 2)
        assertThrows(EOFException::class.java) { Packet.read(DataInputStream(ByteArrayInputStream(bytes))) }
        assertThrows(IllegalArgumentException::class.java) { EncodedFrame.parse(Packet(Packet.FRAME, ByteArray(15))) }
    }
}
