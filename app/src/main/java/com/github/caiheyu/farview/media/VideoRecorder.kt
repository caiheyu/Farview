package com.github.caiheyu.farview.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.github.caiheyu.farview.camera.VideoFormat
import com.github.caiheyu.farview.network.EncodedFrame
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** All writes are serialized with format changes and stop, including disconnects. */
class VideoRecorder(private val context: Context, private val onStatus: (Boolean, Boolean) -> Unit,
    private val onMessage: (String) -> Unit, private val onSaved: (Uri) -> Unit,
    private val onSaving: (Boolean) -> Unit = {}) {
    private val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    private var requested = false
    private var format: VideoFormat? = null
    private var muxer: MediaMuxer? = null
    private var output: File? = null
    private var track = -1
    private var firstPts = -1L
    private var lastPts = -1L
    private var samples = 0
    private var segment = 0
    private val savingFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    private val publisher = java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work, "farview-album") }

    @Synchronized fun format(value: VideoFormat) {
        if (format?.generation == value.generation) return
        if (requested && muxer != null) {
            finishSegment()
            onStatus(true, true)
            onMessage("镜头或画质已变化，录像将自动保存为下一段")
        }
        format = value
    }

    @Synchronized fun start() {
        check(!requested) { "已在录像" }
        check(format != null) { "视频尚未就绪，请稍后重试" }
        check(directory.usableSpace >= 32L * 1024 * 1024) { "本机剩余空间不足，请先清理存储" }
        requested = true; segment = 0
        onStatus(true, true)
    }

    @Synchronized fun frame(frame: EncodedFrame) {
        val f = format ?: return
        if (!requested || frame.generation != f.generation || frame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        try {
            if (muxer == null) {
                if (frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
                check(directory.usableSpace >= 32L * 1024 * 1024) { "剩余空间不足" }
                segment++
                val name = "Farview_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}_$segment"
                output = File(directory, "$name.partial")
                muxer = MediaMuxer(output!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                    setOrientationHint(f.rotation)
                    track = addTrack(f.mediaFormat()); start()
                }
                firstPts = frame.ptsUs; lastPts = -1; samples = 0
                onStatus(true, false)
            }
            val pts = frame.ptsUs - firstPts
            if (pts <= lastPts) return
            muxer!!.writeSampleData(track, ByteBuffer.wrap(frame.data), MediaCodec.BufferInfo().apply {
                set(0, frame.data.size, pts, frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
            })
            lastPts = pts; samples++
        } catch (e: Exception) {
            requested = false
            finishSegment()
            onStatus(false, false)
            onMessage("录像已停止：${e.message}。未保存文件会保留在本机，可稍后重试。")
        }
    }

    @Synchronized fun stop(reason: String? = null) {
        if (!requested && muxer == null) return
        val hadSamples = samples > 0
        requested = false
        finishSegment()
        onStatus(false, false)
        if (!hadSamples && segment == 0) onMessage("尚未收到完整关键帧，没有生成录像")
        if (reason != null) onMessage(reason)
    }

    private fun finishSegment() {
        val active = muxer ?: return
        val file = output
        muxer = null; output = null
        var valid = false
        try { active.stop(); valid = samples > 0 }
        catch (_: Exception) { onMessage("录像片段未完成，已保留临时文件供恢复检查") }
        finally { runCatching { active.release() } }
        if (valid && file != null) {
            val ready = File(directory, file.nameWithoutExtension + ".mp4")
            if (file.renameTo(ready)) enqueueSave(ready)
            else onMessage("录像已结束，但文件整理失败，已保留原文件")
        } else if (samples == 0) file?.delete()
        samples = 0; firstPts = -1; lastPts = -1
    }

    private fun save(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Farview")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册文件")
                try {
                    (resolver.openOutputStream(uri) ?: error("相册不可写入")).use { stream -> file.inputStream().use { it.copyTo(stream) } }
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0) { "相册发布失败" }
                    file.delete(); onSaved(uri); onMessage("录像已保存到本机相册 · DCIM/Farview")
                } catch (e: Exception) { runCatching { resolver.delete(uri, null, null) }; throw e }
            } else {
                @Suppress("DEPRECATION") val album = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Farview").apply { mkdirs() }
                check(album.isDirectory) { "无法创建相册目录" }
                val target = File(album, file.name)
                // A previous scan/copy may have failed after creating this target.
                // Keep the private original until the media index confirms success.
                file.copyTo(target, overwrite = true)
                val scanned = CountDownLatch(1)
                val result = AtomicReference<Uri?>()
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4")) { _, uri ->
                    result.set(uri); scanned.countDown()
                }
                check(scanned.await(15, TimeUnit.SECONDS)) { "相册索引超时，原文件保留供重试" }
                val uri = result.get() ?: error("相册索引失败，原文件保留供重试")
                file.delete(); onSaved(uri); onMessage("录像已保存到本机相册 · DCIM/Farview")
            }
        } catch (e: Exception) { onMessage("保存到相册失败：${e.message}。录像已保留，可在关于页重试保存。") }
    }

    private fun enqueueSave(file: File) {
        if (!savingFiles.add(file)) return
        onSaving(true)
        publisher.execute {
            try { save(file) }
            finally { savingFiles.remove(file); onSaving(savingFiles.isNotEmpty()) }
        }
    }
    fun awaitSaves() { publisher.submit {}.get() }
    @Synchronized fun clearFormat() { format = null }

    @Synchronized fun recover(): Int {
        var incomplete = 0
        directory.listFiles().orEmpty().filter { it.extension == "partial" && it != output }.forEach { file ->
            val extractor = MediaExtractor()
            val playable = runCatching {
                extractor.setDataSource(file.absolutePath)
                if (extractor.trackCount > 0) { extractor.selectTrack(0); extractor.sampleTime >= 0 } else false
            }.getOrDefault(false)
            extractor.release()
            if (playable) file.renameTo(File(directory, file.nameWithoutExtension + ".mp4")) else incomplete++
        }
        directory.listFiles().orEmpty().filter { it.extension == "mp4" }.forEach(::enqueueSave)
        return incomplete
    }
    @Synchronized fun pendingCount() = directory.listFiles().orEmpty().count { it != output && it !in savingFiles }
}
