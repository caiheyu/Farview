package com.github.caiheyu.farview.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.github.caiheyu.farview.network.EncodedFrame
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class Lens(val id: String, val name: String, val equivalent: Float, val zoomMin: Float,
    val zoomMax: Float, val torch: Boolean, val qualities: List<Int>) {
    fun json() = JSONObject().put("id", id).put("name", name).put("equivalent", equivalent)
        .put("min", zoomMin.toDouble()).put("max", zoomMax.toDouble()).put("torch", torch).put("qualities", JSONArray(qualities))
    companion object {
        fun parse(j: JSONObject) = Lens(j.getString("id"), j.getString("name"), j.getDouble("equivalent").toFloat(),
            j.getDouble("min").toFloat(), j.getDouble("max").toFloat(), j.getBoolean("torch"),
            j.getJSONArray("qualities").let { a -> (0 until a.length()).map { a.getInt(it) } })
    }
}

class CameraEngine(context: Context, private val onFormat: (VideoFormat) -> Unit,
    private val onFrame: (EncodedFrame) -> Unit, private val onState: () -> Unit,
    private val onError: (String) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val worker = HandlerThread("farview-camera").apply { start() }
    private val handler = Handler(worker.looper)
    // EGL can wait for an encoder input buffer. Drain encoded output on another
    // thread so that waiting cannot also block the callback that frees a buffer.
    private val codecWorker = HandlerThread("farview-encoder").apply { start() }
    private val codecHandler = Handler(codecWorker.looper)
    val lenses: List<Lens> = manager.cameraIdList.mapNotNull { id ->
        val c = manager.getCameraCharacteristics(id)
        if (c[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
        val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: 4f
        val physical = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.width ?: 5.7f
        val equivalent = focal * 36f / physical
        val ratio = if (Build.VERSION.SDK_INT >= 30) c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] else null
        val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        val qualities = listOf(720, 1080).filter { h -> sizes.any { it.width == h * 16 / 9 && it.height == h } }
        if (qualities.isEmpty()) return@mapNotNull null
        Lens(id, if (equivalent < 20) "超广角" else if (equivalent > 40) "长焦" else "主摄", equivalent,
            ratio?.lower ?: 1f, ratio?.upper ?: c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f,
            c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true, qualities)
    }.sortedBy { it.equivalent }

    @Volatile var lens: Lens = lenses.minByOrNull { abs(it.equivalent - 26) } ?: error("没有可用于实时视频的后置相机")
        private set
    @Volatile var zoom = 1f
        private set
    @Volatile var torch = false
        private set
    @Volatile var quality = if (720 in lens.qualities) 720 else lens.qualities.first()
        private set
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var bridge: EncoderBridge? = null
    private var previewSurface: Surface? = null
    private var previewTexture: SurfaceTexture? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var captureRevision = 0

    fun start() = handler.post { restart() }

    fun preview(texture: SurfaceTexture?) = handler.post {
        previewTexture = texture
        // Preview attachment changes the session surfaces, never the encoded stream format.
        previewSurface?.release(); previewSurface = null
        if (texture != null) {
            texture.setDefaultBufferSize(quality * 16 / 9, quality)
            previewSurface = Surface(texture)
        }
        createCapture(generation)
    }

    fun changeLens(id: String, done: (String?) -> Unit) = handler.post {
        val next = lenses.find { it.id == id }
        if (next == null) { done("这个镜头不可用"); return@post }
        lens = next; zoom = 1f.coerceIn(next.zoomMin, next.zoomMax)
        if (!next.torch) torch = false
        if (quality !in next.qualities) quality = next.qualities.first()
        restart { done(null) }
    }

    fun changeQuality(value: Int, done: (String?) -> Unit = {}) = handler.post {
        if (value !in lens.qualities) { done("当前镜头不支持 ${value}p"); return@post }
        if (value == quality) { done(null); return@post }
        quality = value
        restart { done(null) }
    }

    fun controls(newZoom: Float? = null, newTorch: Boolean? = null, done: (String?) -> Unit) = handler.post {
        if (newZoom != null && (!newZoom.isFinite() || newZoom !in lens.zoomMin..lens.zoomMax)) { done("变焦倍率超出设备范围"); return@post }
        if (newTorch == true && !lens.torch) { done("当前镜头没有补光灯"); return@post }
        val previousZoom = zoom; val previousTorch = torch
        if (newZoom != null) zoom = newZoom
        if (newTorch != null) torch = newTorch
        try { applyControls { error ->
            if (error == null) onState() else { zoom = previousZoom; torch = previousTorch }
            done(error)
        } }
        catch (e: Exception) { zoom = previousZoom; torch = previousTorch; done("相机未能执行操作：${e.message}") }
    }

    fun keyframe() = handler.post { runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }

    @SuppressLint("MissingPermission")
    private fun restart(ready: () -> Unit = {}) {
        if (closed) return
        val gen = ++generation
        releaseCapture()
        try {
            val rotation = manager.getCameraCharacteristics(lens.id)[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
            val codec = MediaCodec.createEncoderByType("video/avc")
            encoder = codec
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        if (!closed && gen == generation && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = codec.getOutputBuffer(index) ?: return
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            onFrame(EncodedFrame(gen, info.presentationTimeUs, info.flags, bytes))
                        }
                    } catch (e: Exception) { if (!closed && gen == generation) onError("视频编码失败：${e.message}") }
                    finally { runCatching { codec.releaseOutputBuffer(index, false) } }
                }
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { if (!closed && gen == generation) onError("视频编码器异常：${error.diagnosticInfo}") }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (!closed && gen == generation) onFormat(VideoFormat.from(gen, format, rotation))
                }
            }, codecHandler)
            codec.configure(MediaFormat.createVideoFormat("video/avc", quality * 16 / 9, quality).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (quality == 1080) 5_000_000 else 2_800_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
            bridge = EncoderBridge(quality * 16 / 9, quality, encoderSurface!!, handler, onError)
            previewTexture?.setDefaultBufferSize(quality * 16 / 9, quality)
            manager.openCamera(lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed || gen != generation) { device.close(); return }
                    camera = device; createCapture(gen) { onState(); ready() }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (!closed && gen == generation) onError("相机已断开，请重新启动拍摄") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (!closed && gen == generation) onError("相机不可用（$error），请确认未被其他应用占用") }
            }, handler)
        } catch (e: Exception) { onError("无法启动相机：${e.message}") }
    }

    private fun createCapture(gen: Int, ready: () -> Unit = {}) {
        val device = camera ?: return
        val output = bridge?.surface ?: return
        if (closed) return
        val revision = ++captureRevision
        runCatching { capture?.close() }; capture = null
        val surfaces = listOfNotNull(output, previewSurface)
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closed || gen != generation || revision != captureRevision) { session.close(); return }
                    capture = session
                    try {
                        request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            surfaces.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            val c = manager.getCameraCharacteristics(lens.id)
                            val af = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
                            if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in af) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            val fps = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
                            fps.filter { it.upper == 30 }.maxByOrNull { it.lower }?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }
                        applyControls { error -> if (error == null) ready() else onError(error) }
                    } catch (e: Exception) { onError("无法配置拍摄：${e.message}") }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { if (!closed && gen == generation && revision == captureRevision) onError("当前镜头无法建立视频会话，请重试或换用其他镜头") }
            }, handler)
        } catch (e: Exception) { if (!closed) onError("相机会话失败：${e.message}") }
    }

    private fun applyControls(applied: (String?) -> Unit) {
        val builder = request ?: error("相机正在准备，请稍后重试")
        val c = manager.getCameraCharacteristics(lens.id)
        if (Build.VERSION.SDK_INT >= 30 && c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val sensor = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: error("无法读取变焦范围")
            val w = (sensor.width() / zoom).toInt(); val h = (sensor.height() / zoom).toInt()
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(sensor.centerX() - w / 2, sensor.centerY() - h / 2, sensor.centerX() + w / 2, sensor.centerY() + h / 2))
        }
        builder.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        var reported = false
        capture?.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (!reported) { reported = true; applied(null) }
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (!reported) { reported = true; applied("相机未能完成操作，请重试") }
            }
        }, handler) ?: error("相机尚未就绪")
    }

    private fun releaseCapture() {
        runCatching { capture?.stopRepeating() }; runCatching { capture?.close() }; capture = null; request = null
        runCatching { camera?.close() }; camera = null
        runCatching { bridge?.close() }; bridge = null
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
        encoderSurface?.release(); encoderSurface = null
    }
    override fun close() { closed = true; handler.post {
        generation++; releaseCapture(); previewSurface?.release(); previewSurface = null
        codecWorker.quitSafely(); worker.quitSafely()
    } }
}
