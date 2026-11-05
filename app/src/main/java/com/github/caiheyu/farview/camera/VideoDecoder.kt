package com.github.caiheyu.farview.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.github.caiheyu.farview.network.EncodedFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VideoDecoder(private val onError: (String) -> Unit, private val onNeedKeyframe: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private var surface: Surface? = null
    private var format: VideoFormat? = null
    private var codec: MediaCodec? = null
    private val frames = ArrayBlockingQueue<EncodedFrame>(5)
    private val running = AtomicBoolean(true)
    private var needsKeyframe = true
    @Volatile var lastRenderedAt = 0L
        private set
    @Volatile var isAttached = false
        private set
    init {
        thread(name = "farview-decode") {
            while (running.get()) {
                val frame = frames.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                synchronized(lock) {
                    if (frame != null) feed(frame)
                    val decoder = codec
                    if (decoder != null) runCatching {
                        val info = MediaCodec.BufferInfo()
                        var index = decoder.dequeueOutputBuffer(info, 0)
                        while (index >= 0) {
                            decoder.releaseOutputBuffer(index, true)
                            lastRenderedAt = System.currentTimeMillis()
                            index = decoder.dequeueOutputBuffer(info, 0)
                        }
                    }.onFailure { reset(); onError("画面解码中断，正在恢复"); onNeedKeyframe() }
                }
            }
            synchronized(lock) { release() }
        }
    }
    fun attach(value: Surface?) = synchronized(lock) { surface = value; isAttached = value != null; reset(); onNeedKeyframe() }
    fun configure(value: VideoFormat) = synchronized(lock) { format = value; frames.clear(); reset(); onNeedKeyframe() }
    fun frame(value: EncodedFrame) {
        if (!frames.offer(value)) {
            synchronized(lock) { frames.clear(); needsKeyframe = true }
            onNeedKeyframe()
        }
    }
    private fun feed(frame: EncodedFrame) {
        if (frame.generation != format?.generation) return
        if (needsKeyframe && frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
        val decoder = codec ?: return
        try {
            val index = decoder.dequeueInputBuffer(1000)
            if (index < 0) { needsKeyframe = true; onNeedKeyframe(); return }
            val buffer = decoder.getInputBuffer(index) ?: return
            buffer.clear(); buffer.put(frame.data)
            decoder.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
            needsKeyframe = false
        } catch (e: Exception) { reset(); onError("无法解码视频：${e.message}"); onNeedKeyframe() }
    }
    private fun reset() {
        release(); needsKeyframe = true; lastRenderedAt = 0
        val output = surface ?: return
        val f = format ?: return
        if (!running.get()) return
        try {
            codec = MediaCodec.createDecoderByType("video/avc").apply {
                val mf = f.mediaFormat()
                if (Build.VERSION.SDK_INT >= 30) mf.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                configure(mf, output, null, 0); start()
            }
        } catch (e: Exception) { release(); onError("此设备无法解码视频：${e.message}") }
    }
    private fun release() { runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null }
    fun clear() = synchronized(lock) { format = null; frames.clear(); release(); needsKeyframe = true; lastRenderedAt = 0 }
    override fun close() { running.set(false) }
}
