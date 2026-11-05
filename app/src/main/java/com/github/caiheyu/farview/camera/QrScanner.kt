package com.github.caiheyu.farview.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

class QrScanner(context: Context, private val onResult: (String) -> Unit, private val onError: (String) -> Unit,
    private val onPreview: (PreviewInfo) -> Unit) : AutoCloseable {
    data class PreviewInfo(val width: Int, val height: Int, val sensorOrientation: Int)
    private val manager = context.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(context.mainLooper)
    private val worker = HandlerThread("farview-qr").apply { start() }
    private val handler = Handler(worker.looper)
    private val closed = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    private var lastScan = 0L
    @SuppressLint("MissingPermission")
    fun start(texture: SurfaceTexture) = handler.post {
        if (closed.get()) return@post
        try {
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
                ?: error("找不到后置相机")
            val characteristics = manager.getCameraCharacteristics(id)
            val sizes = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val size = sizes.filter { it.width in 640..1280 }.minByOrNull { kotlin.math.abs(it.width - 1280) + kotlin.math.abs(it.height - 720) }
                ?: sizes.minByOrNull { it.width * it.height } ?: error("相机不支持扫码")
            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = imageReader
            val decoder = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (closed.get() || System.currentTimeMillis() - lastScan < 300) return@setOnImageAvailableListener
                    lastScan = System.currentTimeMillis()
                    val plane = image.planes[0]; val buffer = plane.buffer
                    val raw = ByteArray(buffer.remaining()); buffer.get(raw)
                    val y = ByteArray(image.width * image.height)
                    for (row in 0 until image.height) for (col in 0 until image.width) {
                        y[row * image.width + col] = raw[row * plane.rowStride + col * plane.pixelStride]
                    }
                    val luminance = PlanarYUVLuminanceSource(y, image.width, image.height, 0, 0, image.width, image.height, false)
                    val result = runCatching { decoder.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))) }.getOrNull()
                    decoder.reset()
                    if (result != null) onResult(result.text)
                } catch (_: Exception) { /* No code in this frame; the camera keeps scanning. */ }
                finally { image.close() }
            }, handler)
            texture.setDefaultBufferSize(size.width, size.height)
            val info = PreviewInfo(size.width, size.height, characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0)
            mainHandler.post { if (!closed.get()) onPreview(info) }
            val surface = Surface(texture); preview = surface
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed.get()) { device.close(); return }
                    camera = device
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (closed.get()) { value.close(); return }
                            session = value
                            try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface); addTarget(imageReader.surface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }
                                value.setRepeatingRequest(request.build(), null, handler)
                            } catch (e: Exception) { onError("扫码相机不可用：${e.message}") }
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) { if (!closed.get()) onError("无法配置扫码相机") }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (!closed.get()) onError("扫码相机已断开") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (!closed.get()) onError("无法使用扫码相机（$error）") }
            }, handler)
        } catch (e: Exception) { if (!closed.get()) onError("无法打开扫码相机：${e.message}") }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            runCatching { session?.close() }; runCatching { camera?.close() }
            reader?.close(); preview?.release(); worker.quitSafely()
        }
    }
}
