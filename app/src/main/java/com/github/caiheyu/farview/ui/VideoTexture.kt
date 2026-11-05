package com.github.caiheyu.farview.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import kotlin.math.min

/** Preserve the whole image when rotating. */
class VideoTexture(context: Context, private val available: (SurfaceTexture) -> Unit,
    private val destroyed: () -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    var imageWidth = 1280
    var imageHeight = 720
    var imageRotation = 90
    init { surfaceTextureListener = this; isOpaque = true }
    fun updateTransform() {
        if (width == 0 || height == 0) return
        val swap = imageRotation % 180 != 0
        val iw = if (swap) imageHeight.toFloat() else imageWidth.toFloat()
        val ih = if (swap) imageWidth.toFloat() else imageHeight.toFloat()
        val scale = min(width / iw, height / ih)
        val matrix = Matrix()
        matrix.setScale(imageWidth * scale / width, imageHeight * scale / height, width / 2f, height / 2f)
        matrix.postRotate(imageRotation.toFloat(), width / 2f, height / 2f)
        setTransform(matrix)
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { updateTransform(); available(texture) }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateTransform()
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { destroyed(); return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}
