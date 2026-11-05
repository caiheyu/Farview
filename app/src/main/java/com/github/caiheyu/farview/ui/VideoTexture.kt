package com.github.caiheyu.farview.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import kotlin.math.min

/** Preserve the whole image when rotating. */
class VideoTexture(context: Context, private val available: (SurfaceTexture) -> Unit,
    private val destroyed: () -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    var imageWidth = 1280
    var imageHeight = 720
    var imageRotation = 90
    var cameraSensorOrientation: Int? = null
    init { surfaceTextureListener = this; isOpaque = true }
    fun updateTransform() {
        if (width == 0 || height == 0) return
        val sensorOrientation = cameraSensorOrientation
        // Camera2 applies sensor orientation to TextureView's buffer already.
        // Decoded video buffers still need their explicit stream rotation.
        val cameraSwapsAxes = sensorOrientation != null && sensorOrientation % 180 != 0
        val sourceWidth = if (cameraSwapsAxes) imageHeight else imageWidth
        val sourceHeight = if (cameraSwapsAxes) imageWidth else imageHeight
        val rotation = if (sensorOrientation == null) imageRotation else when (display?.rotation) {
            Surface.ROTATION_90 -> -90
            Surface.ROTATION_180 -> -180
            Surface.ROTATION_270 -> -270
            else -> 0
        }
        val swap = rotation % 180 != 0
        val iw = if (swap) sourceHeight.toFloat() else sourceWidth.toFloat()
        val ih = if (swap) sourceWidth.toFloat() else sourceHeight.toFloat()
        val scale = min(width / iw, height / ih)
        val matrix = Matrix()
        matrix.setScale(sourceWidth * scale / width, sourceHeight * scale / height, width / 2f, height / 2f)
        matrix.postRotate(rotation.toFloat(), width / 2f, height / 2f)
        setTransform(matrix)
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { updateTransform(); available(texture) }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateTransform()
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { destroyed(); return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}
