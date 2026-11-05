package com.github.caiheyu.farview.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class Packet(val type: Int, val bytes: ByteArray) {
    fun text(): String = bytes.toString(Charsets.UTF_8)
    companion object {
        const val HELLO = 1
        const val STATE = 2
        const val COMMAND = 3
        const val FORMAT = 4
        const val FRAME = 5
        const val PING = 6
        const val PONG = 7
        const val END = 8
        const val ERROR = 9
        const val MAX_BYTES = 4 * 1024 * 1024
        fun text(type: Int, text: String) = Packet(type, text.toByteArray(Charsets.UTF_8))
        fun read(input: DataInputStream): Packet {
            val type = input.readUnsignedByte()
            val count = input.readInt()
            require(type in HELLO..ERROR && count in 0..MAX_BYTES) { "无效的视频协议数据" }
            return Packet(type, ByteArray(count).also { input.readFully(it) })
        }
        fun write(output: DataOutputStream, packet: Packet) {
            require(packet.bytes.size <= MAX_BYTES)
            output.writeByte(packet.type)
            output.writeInt(packet.bytes.size)
            output.write(packet.bytes)
        }
    }
}

data class EncodedFrame(val generation: Int, val ptsUs: Long, val flags: Int, val data: ByteArray) {
    fun packet(): Packet {
        val bytes = ByteArrayOutputStream(data.size + 16)
        DataOutputStream(bytes).use { it.writeInt(generation); it.writeLong(ptsUs); it.writeInt(flags); it.write(data) }
        return Packet(Packet.FRAME, bytes.toByteArray())
    }
    companion object {
        fun parse(packet: Packet): EncodedFrame {
            require(packet.bytes.size >= 16)
            val input = DataInputStream(ByteArrayInputStream(packet.bytes))
            return EncodedFrame(input.readInt(), input.readLong(), input.readInt(), input.readBytes())
        }
    }
}

/** Control messages have priority. A congested stream resumes only on a complete keyframe. */
class Peer(
    private val socket: Socket,
    private val onPacket: (Packet) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
    private val requestKeyframe: () -> Unit = {},
    private val onCongestion: () -> Unit = {}
) : AutoCloseable {
    private val open = AtomicBoolean(true)
    private val messages = ArrayBlockingQueue<Packet>(64)
    private val frames = ArrayBlockingQueue<Packet>(4)
    @Volatile private var needsKeyframe = true
    @Volatile var lastReceived = System.currentTimeMillis()
        private set
    @Volatile var lastSent = System.currentTimeMillis()
        private set

    fun start() {
        socket.tcpNoDelay = true
        socket.soTimeout = 12000
        thread(name = "farview-read") {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (open.get()) {
                    val packet = Packet.read(input)
                    lastReceived = System.currentTimeMillis()
                    onPacket(packet)
                }
            } catch (error: Exception) { finish(error) }
        }
        thread(name = "farview-write") {
            try {
                val output = DataOutputStream(socket.getOutputStream().buffered(65536))
                while (open.get()) {
                    val packet = messages.poll() ?: frames.poll(40, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val start = System.nanoTime()
                    Packet.write(output, packet)
                    output.flush()
                    lastSent = System.currentTimeMillis()
                    if (packet.type == Packet.FRAME && System.nanoTime() - start > 250_000_000L) onCongestion()
                }
            } catch (error: Exception) { finish(error) }
        }
    }

    fun send(packet: Packet) {
        if (open.get() && !messages.offer(packet)) finish(IllegalStateException("控制通道拥塞"))
    }

    @Synchronized fun video(frame: EncodedFrame) {
        if (!open.get()) return
        val key = frame.flags and 1 != 0
        if (needsKeyframe && !key) return
        if (key) needsKeyframe = false
        if (!frames.offer(frame.packet())) {
            frames.clear()
            needsKeyframe = true
            onCongestion()
            requestKeyframe()
        }
    }

    @Synchronized fun resetVideo() { frames.clear(); needsKeyframe = true }

    private fun finish(error: Throwable?) {
        if (open.compareAndSet(true, false)) {
            runCatching { socket.close() }
            messages.clear(); frames.clear()
            onClosed(error)
        }
    }
    override fun close() = finish(null)
}
